//
//  PlayerHandler.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation

class PlayerHandler {
    private static var player: AudioPlayer?
    private static var session: PlaybackSession?
    private static var timer: Timer?
    
    private static var listeningTimePassedSinceLastSync = 0.0
    private static var lastSyncReport:PlaybackReport?
    
    public static func startPlayback(session: PlaybackSession, playWhenReady: Bool, playbackRate: Float) {
        if player != nil {
            player?.destroy()
            player = nil
        }
        
        NowPlayingInfo.setSessionMetadata(metadata: NowPlayingMetadata(id: session.id, itemId: session.libraryItemId!, artworkUrl: session.coverPath, title: session.displayTitle ?? "Unknown title", author: session.displayAuthor, series: nil))
        
        self.session = session
        player = AudioPlayer(playbackSession: session, playWhenReady: playWhenReady, playbackRate: playbackRate)
        
        // DispatchQueue.main.sync {
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            self.tick()
        }
        // }
    }
    public static func stopPlayback() {
        player?.destroy()
        player = nil
        
        timer?.invalidate()
        timer = nil
        
        NowPlayingInfo.reset()
    }
    
    public static func getCurrentTime() -> Double? {
        self.player?.getCurrentTime()
    }
    public static func setPlaybackSpeed(speed: Float) {
        self.player?.setPlaybackRate(speed)
    }
    
    public static func play() {
        self.player?.play()
    }
    public static func pause() {
        self.player?.play()
    }
    public static func playPause() {
        if paused() {
            self.player?.play()
        } else {
            self.player?.pause()
        }
    }
    
    public static func seekForward(amount: Double) {
        if player == nil {
            return
        }
        
        let destinationTime = player!.getCurrentTime() + amount
        player!.seek(destinationTime, from: "handler")
    }
    public static func seekBackward(amount: Double) {
        if player == nil {
            return
        }
        
        let destinationTime = player!.getCurrentTime() - amount
        player!.seek(destinationTime, from: "handler")
    }
    public static func seek(amount: Double) {
        player?.seek(amount, from: "handler")
    }
    
    public static func paused() -> Bool {
        player?.rate == 0.0
    }
    
    public static func getMetdata() -> [String: Any] {
        DispatchQueue.main.async {
            syncProgress()
        }
        
        return [
            "duration": player?.getDuration() ?? 0,
            "currentTime": player?.getCurrentTime() ?? 0,
            "playerState": !paused(),
            "currentRate": player?.rate ?? 0,
        ]
    }
    
    private static func tick() {
        if !paused() {
            listeningTimePassedSinceLastSync += 1
        }
        
        if listeningTimePassedSinceLastSync > 3 {
            syncProgress()
        }
    }
    public static func syncProgress() {
        if player == nil || session == nil {
            return
        }
        
        let playerCurrentTime = player!.getCurrentTime()
        if (lastSyncReport != nil && lastSyncReport?.currentTime == playerCurrentTime) {
            // No need to syncProgress
            return
        }
        
        let report = PlaybackReport(currentTime: playerCurrentTime, duration: player!.getDuration(), timeListened: listeningTimePassedSinceLastSync)
        
        session!.currentTime = playerCurrentTime
        listeningTimePassedSinceLastSync = 0
        lastSyncReport = report
        
        // TODO: check if online
        NSLog("sending playback report")
        ApiClient.reportPlaybackProgress(report: report, sessionId: session!.id)
    }
}
