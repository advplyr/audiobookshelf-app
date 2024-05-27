//
//  AbsAudioPlayer.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

import Foundation
import Capacitor
import RealmSwift
import Network

@objc(AbsAudioPlayer)
public class AbsAudioPlayer: CAPPlugin {
    private let logger = AppLogger(category: "AbsAudioPlayer")

    private var initialPlayWhenReady = false
    private var monitor: NWPathMonitor?
    private let queue = DispatchQueue.global(qos: .background)

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
        self.bridge?.webView?.scrollView.alwaysBounceVertical = false;

        setupNetworkMonitor()
    }

    deinit {
        monitor?.cancel()
    }

    @objc func onReady(_ call: CAPPluginCall) {
        // TODO: Was used to notify when Abs UI was ready so that last played media could be opened - this was buggy and removed
        call.resolve()
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
        let startTimeOverride = call.getDouble("startTime")

        if libraryItemId == nil {
            logger.error("provide library item id")
            return call.resolve()
        }

        PlayerHandler.stopPlayback()

        let isLocalItem = libraryItemId?.starts(with: "local_") ?? false
        if (isLocalItem) {
            let item = Database.shared.getLocalLibraryItem(localLibraryItemId: libraryItemId!)
            let episode = item?.getPodcastEpisode(episodeId: episodeId)
            guard let playbackSession = item?.getPlaybackSession(episode: episode) else {
                logger.error("Failed to get local playback session")
                return call.resolve([:])
            }

            do {
                if (startTimeOverride != nil) {
                    playbackSession.currentTime = startTimeOverride!
                }
                try playbackSession.save()
                try self.startPlaybackSession(playbackSession, playWhenReady: playWhenReady, playbackRate: playbackRate)
                call.resolve(try playbackSession.asDictionary())
            } catch(let exception) {
                logger.error("Failed to start session")
                debugPrint(exception)
                call.resolve([:])
            }
        } else { // Playing from the server
            ApiClient.startPlaybackSession(libraryItemId: libraryItemId!, episodeId: episodeId, forceTranscode: false) { [weak self] session in
                do {
                    if (startTimeOverride != nil) {
                        session.currentTime = startTimeOverride!
                    }
                    try session.save()
                    try self?.startPlaybackSession(session, playWhenReady: playWhenReady, playbackRate: playbackRate)
                    call.resolve(try session.asDictionary())
                } catch(let exception) {
                    self?.logger.error("Failed to start session")
                    debugPrint(exception)
                    call.resolve([:])
                }
            }
        }
    }

    @objc func closePlayback(_ call: CAPPluginCall) {
        logger.log("Close playback")

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
        try? settings.update {
            settings.playbackRate = playbackRate
        }
        PlayerHandler.setPlaybackSpeed(speed: settings.playbackRate)
        call.resolve()
    }

    @objc func setChapterTrack(_ call: CAPPluginCall) {
        let chapterTrack = call.getBool("enabled", true)
        logger.log(String(chapterTrack))
        let settings = PlayerSettings.main()
        try? settings.update {
            settings.chapterTrack = chapterTrack
        }
        PlayerHandler.setChapterTrack()
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
        if let metadata = try? PlayerHandler.getMetdata()?.asDictionary() {
            self.notifyListeners("onMetadata", data: metadata)
        }
    }
    @objc func sendPlaybackClosedEvent() {
        self.notifyListeners("onPlaybackClosed", data: [ "value": true ])
    }

    @objc func decreaseSleepTime(_ call: CAPPluginCall) {
        guard let timeString = call.getString("time") else { return call.resolve([ "success": false ]) }
        guard let time = Double(timeString) else { return call.resolve([ "success": false ]) }
        guard let _ = PlayerHandler.getSleepTimeRemaining() else { return call.resolve([ "success": false ]) }

        let seconds = time/1000
        PlayerHandler.decreaseSleepTime(decreaseSeconds: seconds)
        call.resolve()
    }

    @objc func increaseSleepTime(_ call: CAPPluginCall) {
        guard let timeString = call.getString("time") else { return call.resolve([ "success": false ]) }
        guard let time = Double(timeString) else { return call.resolve([ "success": false ]) }
        guard let _ = PlayerHandler.getSleepTimeRemaining() else { return call.resolve([ "success": false ]) }

        let seconds = time/1000
        PlayerHandler.increaseSleepTime(increaseSeconds: seconds)
        call.resolve()
    }

    @objc func setSleepTimer(_ call: CAPPluginCall) {
        guard let timeString = call.getString("time") else { return call.resolve([ "success": false ]) }
        guard let time = Double(timeString) else { return call.resolve([ "success": false ]) }
        let isChapterTime = call.getBool("isChapterTime", false)

        let seconds = time / 1000

        logger.log("chapter time: \(isChapterTime)")
        if isChapterTime {
            PlayerHandler.setChapterSleepTime(stopAt: seconds)
            return call.resolve([ "success": true ])
        } else {
            PlayerHandler.setSleepTime(secondsUntilSleep: seconds)
            call.resolve([ "success": true ])
        }
    }

    @objc func cancelSleepTimer(_ call: CAPPluginCall) {
        PlayerHandler.cancelSleepTime()
        call.resolve()
    }

    @objc func getSleepTimerTime(_ call: CAPPluginCall) {
        call.resolve([
            "value": PlayerHandler.getSleepTimeRemaining() ?? 0
        ])
    }

    @objc func sendSleepTimerEnded() {
        self.notifyListeners("onSleepTimerEnded", data: [
            "value": PlayerHandler.getCurrentTime() ?? 0
        ])
    }

    @objc func sendSleepTimerSet() {
        self.notifyListeners("onSleepTimerSet", data: [
            "value": PlayerHandler.getSleepTimeRemaining() ?? 0
        ])
    }

    @objc func onLocalMediaProgressUpdate() {
        guard let localMediaProgressId = PlayerHandler.getPlaybackSession()?.localMediaProgressId else { return }
        guard let localMediaProgress = Database.shared.getLocalMediaProgress(localMediaProgressId: localMediaProgressId) else { return }
        guard let progressUpdate = try? localMediaProgress.asDictionary() else { return }
        logger.log("Sending local progress back to the UI")
        self.notifyListeners("onLocalMediaProgressUpdate", data: progressUpdate)
    }

    @objc func onPlaybackFailed() {
        if (PlayerHandler.getPlayMethod() == PlayMethod.directplay.rawValue) {
            let session = PlayerHandler.getPlaybackSession()
            let playWhenReady = PlayerHandler.getPlayWhenReady()
            let libraryItemId = session?.libraryItemId ?? ""
            let episodeId = session?.episodeId ?? nil
            logger.log("Forcing Transcode")

            // If direct playing then fallback to transcode
            ApiClient.startPlaybackSession(libraryItemId: libraryItemId, episodeId: episodeId, forceTranscode: true) { [weak self] session in
                do {
                    guard let self = self else { return }
                    try session.save()
                    PlayerHandler.startPlayback(sessionId: session.id, playWhenReady: playWhenReady, playbackRate: PlayerSettings.main().playbackRate)
                    self.sendPlaybackSession(session: try session.asDictionary())
                    self.sendMetadata()
                } catch(let exception) {
                    self?.logger.error("Failed to start transcoded session")
                    debugPrint(exception)
                }
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

    private func setupNetworkMonitor() {
        monitor = NWPathMonitor()
        monitor?.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }

            let isUnmetered = !path.isExpensive && !path.isConstrained

            DispatchQueue.main.async {
                self.notifyNetworkMeteredChanged(isUnmetered: isUnmetered)
            }
        }
        monitor?.start(queue: queue)
    }

    private func notifyNetworkMeteredChanged(isUnmetered: Bool) {
        let data: [String: Any] = ["value": isUnmetered]
        self.notifyListeners("onNetworkMeteredChanged", data: data)
    }
}

enum PlayerError: String, Error {
    case libraryItemIdNotSpecified = "No libraryItemId provided on session"
}
