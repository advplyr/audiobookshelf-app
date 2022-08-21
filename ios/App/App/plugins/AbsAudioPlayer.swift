//
//  AbsAudioPlayer.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

import Foundation
import Capacitor
import RealmSwift

@objc(AbsAudioPlayer)
public class AbsAudioPlayer: CAPPlugin {
    private var initialPlayWhenReady = false
    private var isUIReady = false
    
    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: NSNotification.Name(PlayerEvents.update.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendPlaybackClosedEvent), name: NSNotification.Name(PlayerEvents.closed.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendSleepTimerSet), name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendSleepTimerEnded), name: NSNotification.Name(PlayerEvents.sleepEnded.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(onPlaybackFailed), name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(onLocalMediaProgressUpdate), name: NSNotification.Name(PlayerEvents.localProgress.rawValue), object: nil)
        
        self.bridge?.webView?.allowsBackForwardNavigationGestures = true;
        
    }
    
    @objc func onReady(_ call: CAPPluginCall) {
        Task { await self.restorePlaybackSession() }
    }
    
    func restorePlaybackSession() async {
        // We don't need to restore if we have an active session
        guard PlayerHandler.getPlaybackSession() == nil else { return }
        
        do {
            // Fetch the most recent active session
            let activeSession = try await Realm().objects(PlaybackSession.self).where({ $0.isActiveSession == true }).last
            if let activeSession = activeSession {
                await PlayerProgress.syncFromServer()
                try self.startPlaybackSession(activeSession, playWhenReady: false, playbackRate: PlayerSettings.main().playbackRate)
            }
        } catch {
            NSLog("Failed to restore playback session")
            debugPrint(error)
        }
    }
    
    @objc func startPlaybackSession(_ session: PlaybackSession, playWhenReady: Bool, playbackRate: Float) throws {
        guard let libraryItemId = session.libraryItemId else { throw PlayerError.libraryItemIdNotSpecified }
        
        self.sendPrepareMetadataEvent(itemId: libraryItemId, playWhenReady: playWhenReady)
        self.sendPlaybackSession(session: try session.asDictionary())
        PlayerHandler.startPlayback(sessionId: session.id, playWhenReady: playWhenReady, playbackRate: playbackRate)
        self.sendMetadata()
    }

    @objc func prepareLibraryItem(_ call: CAPPluginCall) {
        let libraryItemId = call.getString("libraryItemId")
        let episodeId = call.getString("episodeId")
        let playWhenReady = call.getBool("playWhenReady", true)
        let playbackRate = call.getFloat("playbackRate", 1)
        
        if libraryItemId == nil {
            NSLog("provide library item id")
            return call.resolve()
        }
        
        PlayerHandler.stopPlayback()
        
        let isLocalItem = libraryItemId?.starts(with: "local_") ?? false
        if (isLocalItem) {
            let item = Database.shared.getLocalLibraryItem(localLibraryItemId: libraryItemId!)
            let episode = item?.getPodcastEpisode(episodeId: episodeId)
            guard let playbackSession = item?.getPlaybackSession(episode: episode) else {
                NSLog("Failed to get local playback session")
                return call.resolve([:])
            }
            playbackSession.save()
            
            do {
                try self.startPlaybackSession(playbackSession, playWhenReady: playWhenReady, playbackRate: playbackRate)
                call.resolve(try playbackSession.asDictionary())
            } catch(let exception) {
                NSLog("Failed to start session")
                debugPrint(exception)
                call.resolve([:])
            }
        } else { // Playing from the server
            ApiClient.startPlaybackSession(libraryItemId: libraryItemId!, episodeId: episodeId, forceTranscode: false) { session in
                session.save()
                do {
                    try self.startPlaybackSession(session, playWhenReady: playWhenReady, playbackRate: playbackRate)
                    call.resolve(try session.asDictionary())
                } catch(let exception) {
                    NSLog("Failed to start session")
                    debugPrint(exception)
                    call.resolve([:])
                }
            }
        }
    }
    
    @objc func closePlayback(_ call: CAPPluginCall) {
        NSLog("Close playback")
        
        PlayerHandler.stopPlayback()
        call.resolve()
    }
    
    @objc func getCurrentTime(_ call: CAPPluginCall) {
        call.resolve([
            "value": PlayerHandler.getCurrentTime() ?? 0,
            "bufferedTime": PlayerHandler.getCurrentTime() ?? 0,
        ])
    }
    @objc func setPlaybackSpeed(_ call: CAPPluginCall) {
        let playbackRate = call.getFloat("value", 1.0)
        let settings = PlayerSettings.main()
        settings.update {
            settings.playbackRate = playbackRate
        }
        PlayerHandler.setPlaybackSpeed(speed: settings.playbackRate)
        call.resolve()
    }
    
    @objc func playPlayer(_ call: CAPPluginCall) {
        PlayerHandler.paused = false
        call.resolve()
    }
    @objc func pausePlayer(_ call: CAPPluginCall) {
        PlayerHandler.paused = true
        call.resolve()
    }
    // I have no clue why but after i moved this block of code from above "playPlayer" to here the app stopped crashing. Move it back up if you want to
    @objc func playPause(_ call: CAPPluginCall) {
        PlayerHandler.paused = !PlayerHandler.paused
        call.resolve([ "playing": !PlayerHandler.paused ])
    }
    
    @objc func seek(_ call: CAPPluginCall) {
        PlayerHandler.seek(amount: call.getDouble("value", 0.0))
        call.resolve()
    }
    @objc func seekForward(_ call: CAPPluginCall) {
        PlayerHandler.seekForward(amount: call.getDouble("value", 0.0))
        call.resolve()
    }
    @objc func seekBackward(_ call: CAPPluginCall) {
        PlayerHandler.seekBackward(amount: call.getDouble("value", 0.0))
        call.resolve()
    }
    
    @objc func sendMetadata() {
        self.notifyListeners("onPlayingUpdate", data: [ "value": !PlayerHandler.paused ])
        if let metadata = PlayerHandler.getMetdata() {
            self.notifyListeners("onMetadata", data: metadata)
        }
    }
    @objc func sendPlaybackClosedEvent() {
        self.notifyListeners("onPlaybackClosed", data: [ "value": true ])
    }
    
    @objc func decreaseSleepTime(_ call: CAPPluginCall) {
        guard let timeString = call.getString("time") else { return call.resolve([ "success": false ]) }
        guard let time = Int(timeString) else { return call.resolve([ "success": false ]) }
        guard let currentSleepTime = PlayerHandler.remainingSleepTime else { return call.resolve([ "success": false ]) }
        
        PlayerHandler.remainingSleepTime = currentSleepTime - (time / 1000)
        call.resolve()
    }
    @objc func increaseSleepTime(_ call: CAPPluginCall) {
        guard let timeString = call.getString("time") else { return call.resolve([ "success": false ]) }
        guard let time = Int(timeString) else { return call.resolve([ "success": false ]) }
        guard let currentSleepTime = PlayerHandler.remainingSleepTime else { return call.resolve([ "success": false ]) }
        
        PlayerHandler.remainingSleepTime = currentSleepTime + (time / 1000)
        call.resolve()
    }
    @objc func setSleepTimer(_ call: CAPPluginCall) {
        guard let timeString = call.getString("time") else { return call.resolve([ "success": false ]) }
        guard let time = Int(timeString) else { return call.resolve([ "success": false ]) }
        let timeSeconds = time / 1000
        
        NSLog("chapter time: \(call.getBool("isChapterTime", false))")
        
        if call.getBool("isChapterTime", false) {
            let timeToPause = timeSeconds - Int(PlayerHandler.getCurrentTime() ?? 0)
            if timeToPause < 0 { return call.resolve([ "success": false ]) }
            
            PlayerHandler.sleepTimerChapterStopTime = timeSeconds
            PlayerHandler.remainingSleepTime = timeToPause
            return call.resolve([ "success": true ])
        }
        
        PlayerHandler.sleepTimerChapterStopTime = nil
        PlayerHandler.remainingSleepTime = timeSeconds
        call.resolve([ "success": true ])
    }
    @objc func cancelSleepTimer(_ call: CAPPluginCall) {
        PlayerHandler.remainingSleepTime = nil
        PlayerHandler.sleepTimerChapterStopTime = nil
        call.resolve()
    }
    @objc func getSleepTimerTime(_ call: CAPPluginCall) {
        call.resolve([
            "value": PlayerHandler.remainingSleepTime
        ])
    }
    
    @objc func sendSleepTimerEnded() {
        self.notifyListeners("onSleepTimerEnded", data: [
            "value": PlayerHandler.getCurrentTime()
        ])
    }
    
    @objc func sendSleepTimerSet() {
        self.notifyListeners("onSleepTimerSet", data: [
            "value": PlayerHandler.remainingSleepTime
        ])
    }
    
    @objc func onLocalMediaProgressUpdate() {
        guard let localMediaProgressId = PlayerHandler.getPlaybackSession()?.localMediaProgressId else { return }
        guard let localMediaProgress = Database.shared.getLocalMediaProgress(localMediaProgressId: localMediaProgressId) else { return }
        guard let progressUpdate = try? localMediaProgress.asDictionary() else { return }
        NSLog("Sending local progress back to the UI")
        self.notifyListeners("onLocalMediaProgressUpdate", data: progressUpdate)
    }
    
    @objc func onPlaybackFailed() {
        if (PlayerHandler.getPlayMethod() == PlayMethod.directplay.rawValue) {
            let session = PlayerHandler.getPlaybackSession()
            let libraryItemId = session?.libraryItemId ?? ""
            let episodeId = session?.episodeId ?? nil
            NSLog("Forcing Transcode")
            
            // If direct playing then fallback to transcode
            ApiClient.startPlaybackSession(libraryItemId: libraryItemId, episodeId: episodeId, forceTranscode: true) { session in
                session.save()
                PlayerHandler.startPlayback(sessionId: session.id, playWhenReady: self.initialPlayWhenReady, playbackRate: PlayerSettings.main().playbackRate)
                
                do {
                    self.sendPlaybackSession(session: try session.asDictionary())
                } catch(let exception) {
                    NSLog("failed to convert session to json")
                    debugPrint(exception)
                }
                
                self.sendMetadata()
            }
        } else {
            self.notifyListeners("onPlaybackFailed", data: [
                "value": "Playback Error"
            ])
        }
        
    }
    
    @objc func sendPrepareMetadataEvent(itemId: String, playWhenReady: Bool) {
        self.notifyListeners("onPrepareMedia", data: [
            "audiobookId": itemId,
            "playWhenReady": playWhenReady,
        ])
    }
    
    @objc func sendPlaybackSession(session: [String: Any]) {
        self.notifyListeners("onPlaybackSession", data: session)
    }
}

enum PlayerError: String, Error {
    case libraryItemIdNotSpecified = "No libraryItemId provided on session"
}
