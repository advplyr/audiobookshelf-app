//
//  AbsAudioPlayer.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

import Foundation
import Capacitor

@objc(AbsAudioPlayer)
public class AbsAudioPlayer: CAPPlugin {
    private var initialPlayWhenReady = false
    private var initialPlaybackRate:Float = 1
    
    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: NSNotification.Name(PlayerEvents.update.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendPlaybackClosedEvent), name: NSNotification.Name(PlayerEvents.closed.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendSleepTimerSet), name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendSleepTimerEnded), name: NSNotification.Name(PlayerEvents.sleepEnded.rawValue), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(onPlaybackFailed), name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
        
        self.bridge?.webView?.allowsBackForwardNavigationGestures = true;
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
        if libraryItemId!.starts(with: "local") {
            NSLog("local items are not implemnted")
            return call.resolve()
        }
        
        initialPlayWhenReady = playWhenReady
        initialPlaybackRate = playbackRate
        
        sendPrepareMetadataEvent(itemId: libraryItemId!, playWhenReady: playWhenReady)
        ApiClient.startPlaybackSession(libraryItemId: libraryItemId!, episodeId: episodeId, forceTranscode: false) { session in
            PlayerHandler.startPlayback(session: session, playWhenReady: playWhenReady, playbackRate: playbackRate)
            
            do {
                self.sendPlaybackSession(session: try session.asDictionary())
                call.resolve(try session.asDictionary())
            } catch(let exception) {
                NSLog("failed to convert session to json")
                debugPrint(exception)
                call.resolve([:])
            }
            
            self.sendMetadata()
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
        PlayerHandler.setPlaybackSpeed(speed: call.getFloat("value", 1.0))
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
        self.notifyListeners("onMetadata", data: PlayerHandler.getMetdata())
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
        
        NSLog("chapter time: \(call.getBool("isChapterTime", false))")
        
        if call.getBool("isChapterTime", false) {
            let timeToPause = time / 1000 - Int(PlayerHandler.getCurrentTime() ?? 0)
            if timeToPause < 0 { return call.resolve([ "success": false ]) }
            
            NSLog("oof \(timeToPause)")
            
            PlayerHandler.remainingSleepTime = timeToPause
            return call.resolve([ "success": true ])
        }
        
        PlayerHandler.remainingSleepTime = time / 1000
        call.resolve([ "success": true ])
    }
    @objc func cancelSleepTimer(_ call: CAPPluginCall) {
        PlayerHandler.remainingSleepTime = nil
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
    
    @objc func onPlaybackFailed() {
        if (PlayerHandler.getPlayMethod() == PlayMethod.directplay.rawValue) {
            let playbackSession = PlayerHandler.getPlaybackSession()
            let libraryItemId = playbackSession?.libraryItemId ?? ""
            let episodeId = playbackSession?.episodeId ?? nil
            NSLog("TEST: Forcing Transcode")
            
            // If direct playing then fallback to transcode
            ApiClient.startPlaybackSession(libraryItemId: libraryItemId, episodeId: episodeId, forceTranscode: true) { session in
                PlayerHandler.startPlayback(session: session, playWhenReady: self.initialPlayWhenReady, playbackRate: self.initialPlaybackRate)
                
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
