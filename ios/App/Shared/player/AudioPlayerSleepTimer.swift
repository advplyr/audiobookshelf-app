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
    
    public func getSleepTimeRemaining() -> Double? {
        guard let currentTime = self.getCurrentTime() else { return nil }
        
        // Return the player time until sleep
        var timeUntilSleep: Double? = nil
        if let chapterStopAt = self.sleepTimeChapterStopAt {
            timeUntilSleep = chapterStopAt - currentTime
        } else if let stopAt = self.sleepTimeStopAt {
            timeUntilSleep = stopAt - currentTime
        }
        
        // Scale the time until sleep based on the playback rate
        if let timeUntilSleep = timeUntilSleep {
            let timeUntilSleepScaled = timeUntilSleep / self.getPlaybackRate()
            guard timeUntilSleepScaled.isNaN == false else { return nil }
            
            return timeUntilSleepScaled.rounded()
        } else {
            return nil
        }
    }
    
    // Let iOS handle the sleep timer logic by letting us know when it's time to stop
    public func setSleepTime(stopAt: Double, scaleBasedOnSpeed: Bool = false) {
        NSLog("SLEEP TIMER: Scheduling for \(stopAt)")
        
        // Reset any previous sleep timer
        let isChapterSleepTimer = !scaleBasedOnSpeed
        self.removeSleepTimer(resetStopAt: !isChapterSleepTimer)
        
        guard let currentTime = getCurrentTime() else {
            NSLog("SLEEP TIMER: Failed to get currenTime")
            return
        }
        
        // Mark the time to stop playing
        let scaledStopAt = self.calculateScaledStopAt(stopAt, currentTime: currentTime, scaleBasedOnSpeed: scaleBasedOnSpeed)
        self.sleepTimeStopAt = scaledStopAt
        let sleepTime = CMTime(seconds: scaledStopAt, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        
        // Schedule the observation time
        var times = [NSValue]()
        times.append(NSValue(time: sleepTime))
        
        self.sleepTimeToken = self.audioPlayer.addBoundaryTimeObserver(forTimes: times, queue: self.queue) { [weak self] in
            NSLog("SLEEP TIMER: Pausing audio")
            self?.pause()
            self?.removeSleepTimer()
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func increaseSleepTime(extraTimeInSeconds: Double) {
        if let sleepTime = self.getSleepTimeRemaining(), let currentTime = getCurrentTime() {
            let newSleepTimerPosition = currentTime + sleepTime + extraTimeInSeconds
            if newSleepTimerPosition > currentTime {
                self.setSleepTime(stopAt: newSleepTimerPosition, scaleBasedOnSpeed: true)
            }
        }
    }
    
    public func decreaseSleepTime(removeTimeInSeconds: Double) {
        if let sleepTime = self.getSleepTimeRemaining(), let currentTime = getCurrentTime() {
            let newSleepTimerPosition = currentTime + sleepTime - removeTimeInSeconds
            if newSleepTimerPosition > currentTime {
                self.setSleepTime(stopAt: newSleepTimerPosition, scaleBasedOnSpeed: true)
            }
        }

    }
    
    public func removeSleepTimer(resetStopAt: Bool = true) {
        if resetStopAt {
            self.sleepTimeStopAt = nil
            self.sleepTimeChapterStopAt = nil
        }
        
        if let token = self.sleepTimeToken {
            self.audioPlayer.removeTimeObserver(token)
            self.sleepTimeToken = nil
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepEnded.rawValue), object: self)
    }
    
    
    // MARK: - Internal helpers
    
    internal func rescheduleSleepTimerAtTime(time: Double, secondsRemaining: Double?) {
        guard self.isSleepTimerSet() else { return }
        
        // Cancel a chapter sleep timer that is no longer valid
        if isChapterSleepTimerBeforeTime(time) {
            return self.removeSleepTimer()
        }
        
        // Update the sleep timer
        if !isChapterSleepTimer() {
            guard let secondsRemaining = secondsRemaining else { return }
            let newSleepTimerPosition = time + secondsRemaining
            self.setSleepTime(stopAt: newSleepTimerPosition, scaleBasedOnSpeed: true)
        }
    }
    
    private func isChapterSleepTimerBeforeTime(_ time: Double) -> Bool {
        if let chapterStopAt = self.sleepTimeChapterStopAt {
            return chapterStopAt <= time
        }
        
        return false
    }
    
    private func isSleepTimerSet() -> Bool {
        return self.sleepTimeStopAt != nil
    }
    
    private func isChapterSleepTimer() -> Bool {
        return self.sleepTimeChapterStopAt != nil
    }
    
    private func getPlaybackRate() -> Double {
        // Consider paused as playing at 1x
        return Double(self.rate > 0 ? self.rate : 1)
    }
    
    private func calculateScaledStopAt(_ stopAt: Double, currentTime: Double, scaleBasedOnSpeed: Bool) -> Double {
        if scaleBasedOnSpeed {
            // Calculate the scaled time to stop at
            let secondsUntilStopAt1x = stopAt - currentTime
            let secondsUntilSleep = secondsUntilStopAt1x * self.getPlaybackRate()
            NSLog("SLEEP TIMER: Adjusted based on playback speed of \(self.getPlaybackRate()) to \(secondsUntilSleep)")
            return currentTime + secondsUntilSleep
        } else {
            return stopAt
        }
    }
    
}
