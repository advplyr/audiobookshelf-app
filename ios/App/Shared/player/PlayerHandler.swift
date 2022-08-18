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
    
    public static var sleepTimerChapterStopTime: Int? = nil
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
    
    private static func cleanupOldSessions(currentSessionId: String?) {
        if let currentSessionId = currentSessionId {
            let realm = try! Realm()
            let oldSessions = realm.objects(PlaybackSession.self)
                .where({ $0.isActiveSession == true && $0.id != currentSessionId })
            try! realm.write {
                for s in oldSessions {
                    s.isActiveSession = false
                }
            }
        }
    }
    
    public static func startPlayback(sessionId: String, playWhenReady: Bool, playbackRate: Float) {
        guard let session = Database.shared.getPlaybackSession(id: sessionId) else { return }
        
        // Clean up the existing player
        if player != nil {
            player?.destroy()
            player = nil
        }
        
        // Cleanup old sessions
        cleanupOldSessions(currentSessionId: sessionId)
        
        // Set now playing info
        NowPlayingInfo.shared.setSessionMetadata(metadata: NowPlayingMetadata(id: session.id, itemId: session.libraryItemId!, artworkUrl: session.coverPath, title: session.displayTitle ?? "Unknown title", author: session.displayAuthor, series: nil))
        
        // Create the audio player
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
        
        cleanupOldSessions(currentSessionId: nil)
        
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
    
    public static func getMetdata() -> [String: Any]? {
        guard let player = player else { return nil }
        guard player.isInitialized() else { return nil }
        
        DispatchQueue.main.async {
            syncPlayerProgress()
        }
        
        return [
            "duration": player.getDuration(),
            "currentTime": player.getCurrentTime(),
            "playerState": !paused,
            "currentRate": player.rate,
        ]
    }
    
    private static func tick() {
        if !paused {
            listeningTimePassedSinceLastSync += 1
            
            if remainingSleepTime != nil {
                if sleepTimerChapterStopTime != nil {
                    let timeUntilChapterEnd = Double(sleepTimerChapterStopTime ?? 0) - (getCurrentTime() ?? 0)
                    if timeUntilChapterEnd <= 0 {
                        paused = true
                        remainingSleepTime = nil
                    } else {
                        remainingSleepTime = Int(timeUntilChapterEnd.rounded())
                    }
                } else {
                    if remainingSleepTime! <= 0 {
                        paused = true
                    }
                    remainingSleepTime! -= 1
                }
            }
        }
        
        if listeningTimePassedSinceLastSync >= 5 {
            syncPlayerProgress()
        }
    }
    
    public static func syncPlayerProgress() {
        guard let player = player else { return }
        guard player.isInitialized() else { return }
        guard let session = getPlaybackSession() else { return }
        
        // Get current time
        let playerCurrentTime = player.getCurrentTime()
        
        // Prevent multiple sync requests
        let timeSinceLastSync = Date().timeIntervalSince1970 - lastSyncTime
        if (lastSyncTime > 0 && timeSinceLastSync < 1) {
            NSLog("syncProgress last sync time was < 1 second so not syncing")
            return
        }
        
        // Prevent a sync if we got junk data from the player (occurs when exiting out of memory
        guard !playerCurrentTime.isNaN else { return }
        
        lastSyncTime = Date().timeIntervalSince1970 // seconds
        
        session.update {
            session.currentTime = playerCurrentTime
            session.timeListening += listeningTimePassedSinceLastSync
            session.updatedAt = Date().timeIntervalSince1970 * 1000
        }
        listeningTimePassedSinceLastSync = 0
        
        // Persist items in the database and sync to the server
        if session.isLocal { syncLocalProgress() }
        syncServerProgress()
    }
    
    private static func syncLocalProgress() {
        DispatchQueue.global(qos: .utility).async {
            guard let session = getPlaybackSession() else { return }
            guard session.isLocal else { return }
            
            let localMediaProgress = LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: session.localMediaProgressId, localLibraryItemId: session.localLibraryItem?.id, localEpisodeId: session.episodeId)
            guard let localMediaProgress = localMediaProgress else {
                // Local media progress should have been created
                // If we're here, it means a library id is invalid
                return
            }

            localMediaProgress.updateFromPlaybackSession(session)
            Database.shared.saveLocalMediaProgress(localMediaProgress)
            
            NSLog("Local progress saved to the database")
            
            // Send the local progress back to front-end
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.localProgress.rawValue), object: nil)
        }
    }
    
    public static func syncServerProgress() {
        guard Connectivity.isConnectedToInternet else { return }
        DispatchQueue.global(qos: .utility).async {
            let realm = try! Realm()
            for session in realm.objects(PlaybackSession.self).where({ $0.serverConnectionConfigId == Store.serverConfig?.id }) {
                NSLog("Sending sessionId(\(session.id)) to server")
                let sessionRef = ThreadSafeReference(to: session)
                
                func handleSyncSuccess(_ success: Bool) {
                    // Remove old sessions after they synced with the server
                    let session = try! Realm().resolve(sessionRef)
                    if success && !(session?.isActiveSession ?? false) {
                        NSLog("Deleting sessionId(\(session!.id)) as is no longer active")
                        session?.delete()
                    }
                }
                
                if session.isLocal {
                    ApiClient.reportLocalPlaybackProgress(session.freeze()) { success in
                        handleSyncSuccess(success)
                    }
                } else {
                    let playbackReport = PlaybackReport(currentTime: session.currentTime, duration: session.duration, timeListened: session.timeListening)
                    ApiClient.reportPlaybackProgress(report: playbackReport, sessionId: session.id) { success in
                        handleSyncSuccess(success)
                    }
                }
            }
        }
    }
}
