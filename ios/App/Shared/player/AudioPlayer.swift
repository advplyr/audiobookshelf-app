//
//  AudioPlayer.swift
//  App
//
//  Created by Rasmus Krämer on 07.03.22.
//

import Foundation
import AVFoundation
import UIKit
import MediaPlayer

enum PlayMethod:Int {
    case directplay = 0
    case directstream = 1
    case transcode = 2
    case local = 3
}

class AudioPlayer: NSObject {
    private let queue = DispatchQueue(label: "ABSAudioPlayerQueue")
    
    // enums and @objc are not compatible
    @objc dynamic var status: Int
    @objc dynamic var rate: Float
    
    private var tmpRate: Float = 1.0
    
    private var playerContext = 0
    private var playerItemContext = 0
    
    private var playWhenReady: Bool
    private var initialPlaybackRate: Float
    
    private var audioPlayer: AVQueuePlayer
    private var sessionId: String

    private var timeObserverToken: Any?
    private var queueObserver:NSKeyValueObservation?
    private var queueItemStatusObserver:NSKeyValueObservation?
    
    private var sleepTimeStopAt: Double?
    private var sleepTimeToken: Any?
    
    private var currentTrackIndex = 0
    private var allPlayerItems:[AVPlayerItem] = []
    
    private var pausedTimer: Timer?
    
    // MARK: - Constructor
    init(sessionId: String, playWhenReady: Bool = false, playbackRate: Float = 1) {
        self.playWhenReady = playWhenReady
        self.initialPlaybackRate = playbackRate
        self.audioPlayer = AVQueuePlayer()
        self.audioPlayer.automaticallyWaitsToMinimizeStalling = false
        self.sessionId = sessionId
        self.status = -1
        self.rate = 0.0
        self.tmpRate = playbackRate
        
        super.init()
        
        initAudioSession()
        setupRemoteTransportControls()
        
        let playbackSession = self.getPlaybackSession()
        guard let playbackSession = playbackSession else {
            NSLog("Failed to fetch playback session. Player will not initialize")
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
            return
        }
        
        // Listen to player events
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.rate), options: .new, context: &playerContext)
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), options: .new, context: &playerContext)
        
        for track in playbackSession.audioTracks {
            if let playerAsset = createAsset(itemId: playbackSession.libraryItemId!, track: track) {
                let playerItem = AVPlayerItem(asset: playerAsset)
                self.allPlayerItems.append(playerItem)
            }
        }
        
        self.currentTrackIndex = getItemIndexForTime(time: playbackSession.currentTime)
        NSLog("Starting track index \(self.currentTrackIndex) for start time \(playbackSession.currentTime)")
        
        let playerItems = self.allPlayerItems[self.currentTrackIndex..<self.allPlayerItems.count]
        NSLog("Setting player items \(playerItems.count)")
        
        for item in Array(playerItems) {
            self.audioPlayer.insert(item, after:self.audioPlayer.items().last)
        }

        setupTimeObserver()
        setupQueueObserver()
        setupQueueItemStatusObserver()

        NSLog("Audioplayer ready")
    }
    deinit {
        self.stopPausedTimer()
        self.removeSleepTimer()
        self.removeTimeObserver()
        self.queueObserver?.invalidate()
        self.queueItemStatusObserver?.invalidate()
        destroy()
    }
    public func destroy() {
        // Pause is not synchronous causing this error on below lines:
        // AVAudioSession_iOS.mm:1206  Deactivating an audio session that has running I/O. All I/O should be stopped or paused prior to deactivating the audio session
        // It is related to L79 `AVAudioSession.sharedInstance().setActive(false)`
        pause()
        audioPlayer.replaceCurrentItem(with: nil)
        
        do {
            try AVAudioSession.sharedInstance().setActive(false)
        } catch {
            NSLog("Failed to set AVAudioSession inactive")
            print(error)
        }
        
        DispatchQueue.runOnMainQueue {
            UIApplication.shared.endReceivingRemoteControlEvents()
        }
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.closed.rawValue), object: nil)
    }
    
    public func isInitialized() -> Bool {
        return self.status != -1
    }
    
    public func getPlaybackSession() -> PlaybackSession? {
        return Database.shared.getPlaybackSession(id: self.sessionId)
    }
    
    private func getItemIndexForTime(time:Double) -> Int {
        guard let playbackSession = self.getPlaybackSession() else { return 0 }
        for index in 0..<self.allPlayerItems.count {
            let startOffset = playbackSession.audioTracks[index].startOffset ?? 0.0
            let duration = playbackSession.audioTracks[index].duration
            let trackEnd = startOffset + duration
            if (time < trackEnd.rounded(.down)) {
                return index
            }
        }
        return 0
    }
    
    private func setupTimeObserver() {
        // Time observer should be configured on the main queue
        DispatchQueue.runOnMainQueue {
            self.removeTimeObserver()
            
            let timeScale = CMTimeScale(NSEC_PER_SEC)
            // Rate will be different depending on playback speed, aim for 2 observations/sec
            let seconds = 0.5 * (self.rate > 0 ? self.rate : 1.0)
            let time = CMTime(seconds: Double(seconds), preferredTimescale: timeScale)
            self.timeObserverToken = self.audioPlayer.addPeriodicTimeObserver(forInterval: time, queue: self.queue) { [weak self] time in
                guard let self = self else { return }
                
                guard let currentTime = self.getCurrentTime() else { return }
                let isPlaying = self.isPlaying()
                
                Task {
                    // Let the player update the current playback positions
                    await PlayerProgress.shared.syncFromPlayer(currentTime: currentTime, includesPlayProgress: isPlaying, isStopping: false)
                }
                
                // Update the sleep time, if set
                if self.sleepTimeStopAt != nil {
                    NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
                }
            }
        }
    }
    
    private func removeTimeObserver() {
        if let timeObserverToken = timeObserverToken {
            self.audioPlayer.removeTimeObserver(timeObserverToken)
            self.timeObserverToken = nil
        }
    }
    
    private func setupQueueObserver() {
        self.queueObserver = self.audioPlayer.observe(\.currentItem, options: [.new]) {_,_ in
            let prevTrackIndex = self.currentTrackIndex
            self.audioPlayer.currentItem.map { item in
                self.currentTrackIndex = self.allPlayerItems.firstIndex(of:item) ?? 0
                if (self.currentTrackIndex != prevTrackIndex) {
                    NSLog("New Current track index \(self.currentTrackIndex)")
                }
            }
        }
    }
    
    private func setupQueueItemStatusObserver() {
        NSLog("queueStatusObserver: Setting up")

        // Listen for player item updates
        self.queueItemStatusObserver?.invalidate()
        self.queueItemStatusObserver = self.audioPlayer.currentItem?.observe(\.status, options: [.new, .old], changeHandler: { playerItem, change in
            self.handleQueueItemStatus(playerItem: playerItem)
        })
        
        // Ensure we didn't miss a player item update during initialization
        if let playerItem = self.audioPlayer.currentItem {
            self.handleQueueItemStatus(playerItem: playerItem)
        }
    }
    
    private func handleQueueItemStatus(playerItem: AVPlayerItem) {
        NSLog("queueStatusObserver: Current item status changed")
        guard let playbackSession = self.getPlaybackSession() else {
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
            return
        }
        if (playerItem.status == .readyToPlay) {
            NSLog("queueStatusObserver: Current Item Ready to play. PlayWhenReady: \(self.playWhenReady)")
            
            // Seek the player before initializing, so a currentTime of 0 does not appear in MediaProgress / session
            let firstReady = self.status < 0
            if firstReady || !self.playWhenReady {
                // Seek is async, and if we call this when also pressing play, we will get weird jumps in the scrub bar depending on timing
                // Seeking to the correct position happens during play()
                self.seek(playbackSession.currentTime, from: "queueItemStatusObserver")
            }
            
            // Mark the player as ready
            self.status = 0
            
            // Start the player, if requested
            if self.playWhenReady {
                self.playWhenReady = false
                self.play()
            }
        } else if (playerItem.status == .failed) {
            NSLog("queueStatusObserver: FAILED \(playerItem.error?.localizedDescription ?? "")")
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
        }
    }
    
    private func startPausedTimer() {
        guard self.pausedTimer == nil else { return }
        self.queue.async {
            self.pausedTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { timer in
                NSLog("PAUSE TIMER: Syncing from server")
                Task { await PlayerProgress.shared.syncFromServer() }
            }
        }
    }
    
    private func stopPausedTimer() {
        self.pausedTimer?.invalidate()
        self.pausedTimer = nil
    }
    
    // MARK: - Methods
    public func play(allowSeekBack: Bool = false) {
        guard self.isInitialized() else { return }
        guard let session = self.getPlaybackSession() else {
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
            return
        }
        
        // Capture remaining sleep time before changing the track position
        let sleepSecondsRemaining = PlayerHandler.remainingSleepTime
        
        // Stop the paused timer
        self.stopPausedTimer()
        
        // Determine where we are starting playback
        let lastPlayed = (session.updatedAt ?? 0)/1000
        let currentTime = allowSeekBack ? calculateSeekBackTimeAtCurrentTime(session.currentTime, lastPlayed: lastPlayed) : session.currentTime
        
        // Sync our new playback position
        Task { await PlayerProgress.shared.syncFromPlayer(currentTime: currentTime, includesPlayProgress: self.isPlaying(), isStopping: false) }

        // Start playback, with a seek, for as smooth a scrub bar start as possible
        let currentTrackStartOffset = session.audioTracks[self.currentTrackIndex].startOffset ?? 0.0
        let seekTime = currentTime - currentTrackStartOffset
        self.audioPlayer.seek(to: CMTime(seconds: seekTime, preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] completed in
            if completed, let self = self {
                // Start playback
                self.audioPlayer.play()
                self.rate = self.tmpRate
                self.audioPlayer.rate = self.tmpRate
                self.status = 1
                
                // If we have an active sleep timer, reschedule based on rate
                self.rescheduleSleepTimerAtTime(time: currentTime, secondsRemaining: sleepSecondsRemaining)
                
                // Update the progress
                self.updateNowPlaying()
            }
        }
    }
    
    private func calculateSeekBackTimeAtCurrentTime(_ currentTime: Double, lastPlayed: Double) -> Double {
        let difference = Date.timeIntervalSinceReferenceDate - lastPlayed
        var time: Double = 0
        
        // Scale seek back time based on how long since last play
        if lastPlayed == 0 {
            time = 5
        } else if difference < 6 {
            time = 2
        } else if difference < 12 {
            time = 10
        } else if difference < 30 {
            time = 15
        } else if difference < 180 {
            time = 20
        } else if difference < 3600 {
            time = 25
        } else {
            time = 29
        }
        
        // Wind the clock back
        return currentTime - time
    }
    
    public func pause() {
        guard self.isInitialized() else { return }
        
        self.audioPlayer.pause()
        
        Task {
            if let currentTime = self.getCurrentTime() {
                await PlayerProgress.shared.syncFromPlayer(currentTime: currentTime, includesPlayProgress: self.isPlaying(), isStopping: true)
            }
        }
        
        self.status = 0
        self.rate = 0.0
        
        updateNowPlaying()
        
        self.startPausedTimer()
    }
    
    public func seek(_ to: Double, from: String) {
        let continuePlaying = rate > 0.0
        
        pause()
        
        NSLog("Seek to \(to) from \(from)")
        
        guard let playbackSession = self.getPlaybackSession() else { return }
        
        let currentTrack = playbackSession.audioTracks[self.currentTrackIndex]
        let ctso = currentTrack.startOffset ?? 0.0
        let trackEnd = ctso + currentTrack.duration
        NSLog("Seek current track END = \(trackEnd)")
        
        // Capture remaining sleep time before changing the track position
        let sleepSecondsRemaining = PlayerHandler.remainingSleepTime
        
        let indexOfSeek = getItemIndexForTime(time: to)
        NSLog("Seek to index \(indexOfSeek) | Current index \(self.currentTrackIndex)")
        
        // Reconstruct queue if seeking to a different track
        if (self.currentTrackIndex != indexOfSeek) {
            self.currentTrackIndex = indexOfSeek
            
            try? playbackSession.update {
                playbackSession.currentTime = to
            }
            
            self.playWhenReady = continuePlaying // Only playWhenReady if already playing
            self.status = -1
            let playerItems = self.allPlayerItems[indexOfSeek..<self.allPlayerItems.count]
            
            self.audioPlayer.removeAllItems()
            for item in Array(playerItems) {
                self.audioPlayer.insert(item, after:self.audioPlayer.items().last)
            }
            
            setupQueueItemStatusObserver()
        } else {
            NSLog("Seeking in current item \(to)")
            let currentTrackStartOffset = playbackSession.audioTracks[self.currentTrackIndex].startOffset ?? 0.0
            let seekTime = to - currentTrackStartOffset
            
            self.audioPlayer.seek(to: CMTime(seconds: seekTime, preferredTimescale: 1000)) { [weak self] completed in
                if !completed {
                    NSLog("WARNING: seeking not completed (to \(seekTime)")
                }
                
                if continuePlaying {
                    self?.play()
                }
                self?.updateNowPlaying()
                
                // If we have an active sleep timer, reschedule based on seek, since seek is fuzzy
                // This needs to occur after play() to capture the correct playback rate
                if let currentTime = self?.getCurrentTime() {
                    self?.rescheduleSleepTimerAtTime(time: currentTime, secondsRemaining: sleepSecondsRemaining)
                }
            }
        }
    }
    
    public func setPlaybackRate(_ rate: Float, observed: Bool = false) {
        // Capture remaining sleep time before changing the rate
        let sleepSecondsRemaining = PlayerHandler.remainingSleepTime
        let playbackSpeedChanged = rate > 0.0 && rate != self.tmpRate && !(observed && rate == 1)
        
        if self.audioPlayer.rate != rate {
            NSLog("setPlaybakRate rate changed from \(self.audioPlayer.rate) to \(rate)")
            self.audioPlayer.rate = rate
        }
        
        self.rate = rate
        self.updateNowPlaying()
        
        if playbackSpeedChanged {
            self.tmpRate = rate
            
            // If we have an active sleep timer, reschedule based on rate
            if let currentTime = self.getCurrentTime() {
                self.rescheduleSleepTimerAtTime(time: currentTime, secondsRemaining: sleepSecondsRemaining)
            }
            
            // Setup the time observer again at the new rate
            self.setupTimeObserver()
        }
    }
    
    public func getSleepStopAt() -> Double? {
        return self.sleepTimeStopAt
    }
    
    // Let iOS handle the sleep timer logic by letting us know when it's time to stop
    public func setSleepTime(stopAt: Double, scaleBasedOnSpeed: Bool = false) {
        NSLog("SLEEP TIMER: Scheduling for \(stopAt)")
        
        // Reset any previous sleep timer
        self.removeSleepTimer()
        
        guard let currentTime = getCurrentTime() else {
            NSLog("Failed to get currenTime")
            return
        }
        
        // Mark the time to stop playing
        if scaleBasedOnSpeed {
            // Consider paused as playing at 1x
            let rate = Double(self.rate > 0 ? self.rate : 1)
            
            // Calculate the scaled time to stop at
            let timeUntilSleep = (stopAt - currentTime) * rate
            self.sleepTimeStopAt = currentTime + timeUntilSleep
            
            NSLog("SLEEP TIMER: Adjusted based on playback speed of \(rate) to \(self.sleepTimeStopAt!)")
        } else {
            self.sleepTimeStopAt = stopAt
        }
        
        guard let sleepTimeStopAt = self.sleepTimeStopAt else { return }
        let sleepTime = CMTime(seconds: sleepTimeStopAt, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        
        // Schedule the observation time
        var times = [NSValue]()
        times.append(NSValue(time: sleepTime))
        
        sleepTimeToken = self.audioPlayer.addBoundaryTimeObserver(forTimes: times, queue: queue) { [weak self] in
            NSLog("SLEEP TIMER: Pausing audio")
            self?.pause()
            PlayerHandler.sleepTimerChapterStopTime = nil
            self?.removeSleepTimer()
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    private func rescheduleSleepTimerAtTime(time: Double, secondsRemaining: Int?) {
        // Not a chapter sleep timer
        let hadToCancelChapterSleepTimer = decideIfChapterSleepTimerNeedsToBeCanceled(time: time)
        guard !hadToCancelChapterSleepTimer else { return }
        guard PlayerHandler.sleepTimerChapterStopTime == nil else { return }
        
        // Verify sleep timer is set
        guard self.sleepTimeToken != nil else { return }
        
        // Update the sleep timer
        if let secondsRemaining = secondsRemaining {
            let newSleepTimerPosition = time + Double(secondsRemaining)
            self.setSleepTime(stopAt: newSleepTimerPosition, scaleBasedOnSpeed: true)
        }
    }
    
    private func decideIfChapterSleepTimerNeedsToBeCanceled(time: Double) -> Bool {
        if let chapterSleepTime = PlayerHandler.sleepTimerChapterStopTime {
            let sleepIsBeforeCurrentTime = Double(chapterSleepTime) <= time
            if sleepIsBeforeCurrentTime {
                PlayerHandler.sleepTimerChapterStopTime = nil
                self.removeSleepTimer()
                return true
            }
        }
        
        return false
    }
    
    public func increaseSleepTime(extraTimeInSeconds: Double) {
        if let sleepTime = PlayerHandler.remainingSleepTime, let currentTime = getCurrentTime() {
            let newSleepTimerPosition = currentTime + Double(sleepTime) + extraTimeInSeconds
            if newSleepTimerPosition > currentTime {
                self.setSleepTime(stopAt: newSleepTimerPosition, scaleBasedOnSpeed: true)
            }
        }
    }
    
    public func decreaseSleepTime(removeTimeInSeconds: Double) {
        if let sleepTime = PlayerHandler.remainingSleepTime, let currentTime = getCurrentTime() {
            let newSleepTimerPosition = currentTime + Double(sleepTime) - removeTimeInSeconds
            guard newSleepTimerPosition > currentTime else { return }
            if newSleepTimerPosition > currentTime {
                self.setSleepTime(stopAt: newSleepTimerPosition, scaleBasedOnSpeed: true)
            }
        }

    }
    
    public func removeSleepTimer() {
        self.sleepTimeStopAt = nil
        if let token = sleepTimeToken {
            self.audioPlayer.removeTimeObserver(token)
            sleepTimeToken = nil
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepEnded.rawValue), object: self)
    }
    
    public func getCurrentTime() -> Double? {
        guard let playbackSession = self.getPlaybackSession() else { return nil }
        let currentTrackTime = self.audioPlayer.currentTime().seconds
        let audioTrack = playbackSession.audioTracks[currentTrackIndex]
        let startOffset = audioTrack.startOffset ?? 0.0
        return startOffset + currentTrackTime
    }

    public func getPlayMethod() -> Int? {
        guard let playbackSession = self.getPlaybackSession() else { return nil }
        return playbackSession.playMethod
    }
    
    public func getPlaybackSessionId() -> String {
        return self.sessionId
    }
    
    public func getDuration() -> Double? {
        guard let playbackSession = self.getPlaybackSession() else { return nil }
        return playbackSession.duration
    }
    
    public func isPlaying() -> Bool {
        return self.status > 0
    }
    
    // MARK: - Private
    private func createAsset(itemId:String, track:AudioTrack) -> AVAsset? {
        guard let playbackSession = self.getPlaybackSession() else { return nil }
        
        if (playbackSession.playMethod == PlayMethod.directplay.rawValue) {
            // The only reason this is separate is because the filename needs to be encoded
            let filename = track.metadata?.filename ?? ""
            let filenameEncoded = filename.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
            let urlstr = "\(Store.serverConfig!.address)/s/item/\(itemId)/\(filenameEncoded ?? "")?token=\(Store.serverConfig!.token)"
            let url = URL(string: urlstr)!
            return AVURLAsset(url: url)
        } else if (playbackSession.playMethod == PlayMethod.local.rawValue) {
            guard let localFile = track.getLocalFile() else {
                // Worst case we can stream the file
                NSLog("Unable to play local file. Resulting to streaming \(track.localFileId ?? "Unknown")")
                let filename = track.metadata?.filename ?? ""
                let filenameEncoded = filename.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
                let urlstr = "\(Store.serverConfig!.address)/s/item/\(itemId)/\(filenameEncoded ?? "")?token=\(Store.serverConfig!.token)"
                let url = URL(string: urlstr)!
                return AVURLAsset(url: url)
            }
            return AVURLAsset(url: localFile.contentPath)
        } else { // HLS Transcode
            let headers: [String: String] = [
                "Authorization": "Bearer \(Store.serverConfig!.token)"
            ]
            return AVURLAsset(url: URL(string: "\(Store.serverConfig!.address)\(track.contentUrl ?? "")")!, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        }
    }
    
    private func initAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            NSLog("Failed to set AVAudioSession category")
            print(error)
        }
    }
    
    // MARK: - Now playing
    private func setupRemoteTransportControls() {
        DispatchQueue.runOnMainQueue {
            UIApplication.shared.beginReceivingRemoteControlEvents()
        }
        let commandCenter = MPRemoteCommandCenter.shared()
        let deviceSettings = Database.shared.getDeviceSettings()
        
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [unowned self] event in
            play(allowSeekBack: true)
            return .success
        }
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [unowned self] event in
            pause()
            return .success
        }
        
        commandCenter.skipForwardCommand.isEnabled = true
        commandCenter.skipForwardCommand.preferredIntervals = [NSNumber(value: deviceSettings.jumpForwardTime)]
        commandCenter.skipForwardCommand.addTarget { [unowned self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            guard let currentTime = self.getCurrentTime() else {
                return .commandFailed
            }
            seek(currentTime + command.preferredIntervals[0].doubleValue, from: "remote")
            return .success
        }
        commandCenter.skipBackwardCommand.isEnabled = true
        commandCenter.skipBackwardCommand.preferredIntervals = [NSNumber(value: deviceSettings.jumpBackwardsTime)]
        commandCenter.skipBackwardCommand.addTarget { [unowned self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            guard let currentTime = self.getCurrentTime() else {
                return .commandFailed
            }
            seek(currentTime - command.preferredIntervals[0].doubleValue, from: "remote")
            return .success
        }
        
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .noSuchContent
            }
            
            self.seek(event.positionTime, from: "remote")
            return .success
        }
        
        commandCenter.changePlaybackRateCommand.isEnabled = true
        commandCenter.changePlaybackRateCommand.supportedPlaybackRates = [0.5, 0.75, 1.0, 1.25, 1.5, 2]
        commandCenter.changePlaybackRateCommand.addTarget { event in
            guard let event = event as? MPChangePlaybackRateCommandEvent else {
                return .noSuchContent
            }
            
            self.setPlaybackRate(event.playbackRate)
            return .success
        }
    }
    private func updateNowPlaying() {
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.update.rawValue), object: nil)
        if let duration = self.getDuration(), let currentTime = self.getCurrentTime() {
            NowPlayingInfo.shared.update(duration: duration, currentTime: currentTime, rate: rate)
        }
    }
    
    // MARK: - Observer
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if context == &playerContext {
            if keyPath == #keyPath(AVPlayer.rate) {
                NSLog("playerContext observer player rate")
                self.setPlaybackRate(change?[.newKey] as? Float ?? 1.0, observed: true)
            } else if keyPath == #keyPath(AVPlayer.currentItem) {
                NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.update.rawValue), object: nil)
                NSLog("WARNING: Item ended")
            }
        } else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }
    }
    
    public static var instance: AudioPlayer?
}
