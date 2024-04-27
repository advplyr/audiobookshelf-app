//
//  AudioPlayerSleepTimer.swift
//  App
//
//  Created by Ron Heft on 9/2/22.
//

import Foundation
import AVFoundation

extension AudioPlayer {
    
    // MARK: - Public API
    
    public func isSleepTimerSet() -> Bool {
        return self.isCountdownSleepTimerSet() || self.isChapterSleepTimerSet()
    }
    
    public func getSleepTimeRemaining() -> Double? {
        guard let currentTime = self.getCurrentTime() else { return nil }
        
        // Return the player time until sleep
        var sleepTimeRemaining: Double? = nil
        if let chapterStopAt = self.sleepTimeChapterStopAt {
            sleepTimeRemaining = (chapterStopAt - currentTime) / Double(self.rateManager.rate > 0 ? self.rateManager.rate : 1.0)
        } else if self.isCountdownSleepTimerSet() {
            sleepTimeRemaining = self.sleepTimeRemaining
        }
        
        return sleepTimeRemaining
    }
    
    public func setSleepTimer(secondsUntilSleep: Double) {
        logger.log("SLEEP TIMER: Sleeping in \(secondsUntilSleep) seconds")
        self.removeSleepTimer()
        self.sleepTimeRemaining = secondsUntilSleep
        
        DispatchQueue.runOnMainQueue {
            self.sleepTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                if self?.isPlaying() ?? false {
                    self?.decrementSleepTimerIfRunning()
                }
            }
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func setChapterSleepTimer(stopAt: Double?) {
        self.removeSleepTimer()
        guard let stopAt = stopAt else { return }
        guard let currentTime = self.getCurrentTime() else { return }
        guard stopAt >= currentTime else { return }
        
        logger.log("SLEEP TIMER: Scheduling for chapter end \(stopAt)")
        
        // Schedule the observation time
        self.sleepTimeChapterStopAt = stopAt
        
        // Get the current track
        guard let playbackSession = self.getPlaybackSession() else { return }
        let currentTrack = playbackSession.audioTracks[currentTrackIndex]

        // Set values
        guard let trackStartTime = currentTrack.startOffset else { return }
        guard let trackEndTime = currentTrack.endOffset else { return }

        // Verify the stop is during the current audio track
        guard trackEndTime >= stopAt else { return }

        // Schedule the observation time
        let trackBasedStopTime = stopAt - trackStartTime
        
        let sleepTime = CMTime(seconds: trackBasedStopTime, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        var times = [NSValue]()
        times.append(NSValue(time: sleepTime))
        
        self.sleepTimeChapterToken = self.audioPlayer.addBoundaryTimeObserver(forTimes: times, queue: self.queue) { [weak self] in
            self?.handleSleepEnd()
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func increaseSleepTime(extraTimeInSeconds: Double) {
        self.removeChapterSleepTimer()
        guard let sleepTimeRemaining = self.sleepTimeRemaining else { return }
        self.sleepTimeRemaining = sleepTimeRemaining + extraTimeInSeconds
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func decreaseSleepTime(removeTimeInSeconds: Double) {
        self.removeChapterSleepTimer()
        guard let sleepTimeRemaining = self.sleepTimeRemaining else { return }
        self.sleepTimeRemaining = sleepTimeRemaining - removeTimeInSeconds
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func removeSleepTimer() {
        self.sleepTimer?.invalidate()
        self.sleepTimer = nil
        self.removeChapterSleepTimer()
        self.sleepTimeRemaining = nil
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepEnded.rawValue), object: self)
    }
    
    
    // MARK: - Internal helpers
    
    internal func handleTrackChangeForChapterSleepTimer() {
        if self.isChapterSleepTimerSet() {
            self.setChapterSleepTimer(stopAt: self.sleepTimeChapterStopAt)
        }
    }
    
    private func decrementSleepTimerIfRunning() {
        if var sleepTimeRemaining = self.sleepTimeRemaining {
            sleepTimeRemaining -= 1
            self.sleepTimeRemaining = sleepTimeRemaining
            
            // Handle the sleep if the timer has expired
            if sleepTimeRemaining <= 0 {
                self.handleSleepEnd()
            }
        }
    }
    
    private func handleSleepEnd() {
        logger.log("SLEEP TIMER: Pausing audio")
        self.pause()
        self.removeSleepTimer()
    }
    
    private func removeChapterSleepTimer() {
        if let token = self.sleepTimeChapterToken {
            self.audioPlayer.removeTimeObserver(token)
        }
        self.sleepTimeChapterToken = nil
        self.sleepTimeChapterStopAt = nil
    }
    
    private func isCountdownSleepTimerSet() -> Bool {
        return self.sleepTimeRemaining != nil
    }
    
    private func isChapterSleepTimerSet() -> Bool {
        return self.sleepTimeChapterStopAt != nil
    }
    
}
