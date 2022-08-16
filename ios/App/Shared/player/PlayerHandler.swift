//
//  PlayerHandler.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class PlayerHandler {
    private static var player: AudioPlayer?
    private static var timer: Timer?
    private static var lastSyncTime: Double = 0.0
    
    private static var _remainingSleepTime: Int? = nil
    public static var remainingSleepTime: Int? {
        get {
            return _remainingSleepTime
        }
        set(time) {
            if time != nil && time! < 0 {
                _remainingSleepTime = nil
            } else {
                _remainingSleepTime = time
            }
            
            if _remainingSleepTime == nil {
                NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepEnded.rawValue), object: _remainingSleepTime)
            } else {
                NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: _remainingSleepTime)
            }
        }
    }
    private static var listeningTimePassedSinceLastSync: Double = 0.0
    private static var lastSyncReport: PlaybackReport?
    
    public static var paused: Bool {
        get {
            guard let player = player else {
                return true
            }
            
            return player.rate == 0.0
        }
        set(paused) {
            if paused {
                self.player?.pause()
            } else {
                self.player?.play()
            }
        }
    }
    
    public static func startTickTimer() {
        DispatchQueue.runOnMainQueue {
            NSLog("Starting the tick timer")
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
                self.tick()
            }
        }
    }
    
    public static func stopTickTimer() {
        NSLog("Stopping the tick timer")
        timer?.invalidate()
        timer = nil
    }
    
    public static func startPlayback(sessionId: String, playWhenReady: Bool, playbackRate: Float) {
        guard let session = Database.shared.getPlaybackSession(id: sessionId) else { return }
        if player != nil {
            player?.destroy()
            player = nil
        }
        
        NowPlayingInfo.shared.setSessionMetadata(metadata: NowPlayingMetadata(id: session.id, itemId: session.libraryItemId!, artworkUrl: session.coverPath, title: session.displayTitle ?? "Unknown title", author: session.displayAuthor, series: nil))
        
        player = AudioPlayer(sessionId: sessionId, playWhenReady: playWhenReady, playbackRate: playbackRate)
        
        startTickTimer()
    }
    
    public static func stopPlayback() {
        // Pause playback first, so we can sync our current progress
        player?.pause()
        
        // Stop updating progress before we destory the player, so we don't receive bad data
        stopTickTimer()
        
        player?.destroy()
        player = nil
        
        NowPlayingInfo.shared.reset()
    }
    
    public static func getCurrentTime() -> Double? {
        self.player?.getCurrentTime()
    }
    
    public static func setPlaybackSpeed(speed: Float) {
        self.player?.setPlaybackRate(speed)
    }
    
    public static func getPlayMethod() -> Int? {
        self.player?.getPlayMethod()
    }
    
    public static func getPlaybackSession() -> PlaybackSession? {
        guard let player = player else { return nil }
        guard let session = Database.shared.getPlaybackSession(id: player.getPlaybackSessionId()) else { return nil }
        return session
    }
    
    public static func seekForward(amount: Double) {
        guard let player = player else {
            return
        }
        
        let destinationTime = player.getCurrentTime() + amount
        player.seek(destinationTime, from: "handler")
    }
    
    public static func seekBackward(amount: Double) {
        guard let player = player else {
            return
        }
        
        let destinationTime = player.getCurrentTime() - amount
        player.seek(destinationTime, from: "handler")
    }
    
    public static func seek(amount: Double) {
        player?.seek(amount, from: "handler")
    }
    
    public static func getMetdata() -> [String: Any] {
        DispatchQueue.main.async {
            syncProgress()
        }
        
        return [
            "duration": player?.getDuration() ?? 0,
            "currentTime": player?.getCurrentTime() ?? 0,
            "playerState": !paused,
            "currentRate": player?.rate ?? 0,
        ]
    }
    
    private static func tick() {
        if !paused {
            listeningTimePassedSinceLastSync += 1
        }
        
        if listeningTimePassedSinceLastSync >= 5 {
            syncProgress()
        }
        
        if remainingSleepTime != nil {
            if remainingSleepTime! == 0 {
                paused = true
            }
            remainingSleepTime! -= 1
        }
    }
    
    public static func syncProgress() {
        guard let player = player else { return }
        guard let session = getPlaybackSession() else { return }
        
        // Prevent a sync at the current time
        let playerCurrentTime = player.getCurrentTime()
        let hasSyncAtCurrentTime = lastSyncReport?.currentTime.isEqual(to: playerCurrentTime) ?? false
        if hasSyncAtCurrentTime { return }
        
        // Prevent multiple sync requests
        let timeSinceLastSync = Date().timeIntervalSince1970 - lastSyncTime
        if (lastSyncTime > 0 && timeSinceLastSync < 1) {
            NSLog("syncProgress last sync time was < 1 second so not syncing")
            return
        }
        
        // Prevent a sync if we got junk data from the player (occurs when exiting out of memory
        guard !playerCurrentTime.isNaN else { return }
        
        lastSyncTime = Date().timeIntervalSince1970 // seconds
        let report = PlaybackReport(currentTime: playerCurrentTime, duration: player.getDuration(), timeListened: listeningTimePassedSinceLastSync)
        
        session.update {
            session.currentTime = playerCurrentTime
        }
        listeningTimePassedSinceLastSync = 0
        lastSyncReport = report
        
        let sessionIsLocal = session.isLocal
        if !sessionIsLocal {
            if Connectivity.isConnectedToInternet {
                NSLog("sending playback report")
                ApiClient.reportPlaybackProgress(report: report, sessionId: session.id)
            }
        } else {
            if let localMediaProgress = syncLocalProgress() {
                if Connectivity.isConnectedToInternet {
                    ApiClient.reportLocalMediaProgress(localMediaProgress) { success in
                        NSLog("Synced local media progress: \(success)")
                    }
                }
            }
        }
    }
    
    private static func syncLocalProgress() -> LocalMediaProgress? {
        guard let session = getPlaybackSession() else { return nil }
        
        let localMediaProgress = LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: session.localMediaProgressId, localLibraryItemId: session.localLibraryItem?.id, localEpisodeId: session.episodeId)
        guard let localMediaProgress = localMediaProgress else {
            // Local media progress should have been created
            // If we're here, it means a library id is invalid
            return nil
        }

        localMediaProgress.updateFromPlaybackSession(session)
        Database.shared.saveLocalMediaProgress(localMediaProgress)
        
        NSLog("Local progress saved to the database")
        
        // Send the local progress back to front-end
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.localProgress.rawValue), object: nil)
        
        return localMediaProgress
    }
}
