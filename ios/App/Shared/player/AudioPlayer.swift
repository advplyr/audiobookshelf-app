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
    internal let queue = DispatchQueue(label: "ABSAudioPlayerQueue")
    internal let logger = AppLogger(category: "AudioPlayer")
    
    // enums and @objc are not compatible
    @objc dynamic var status: Int
    @objc dynamic var rate: Float
    
    private var tmpRate: Float = 1.0
    
    private var playerContext = 0
    private var playerItemContext = 0
    
    private var playWhenReady: Bool
    private var initialPlaybackRate: Float
    
    internal var audioPlayer: AVQueuePlayer
    private var sessionId: String

    private var timeObserverToken: Any?
    private var sleepTimerObserverToken: Any?
    private var queueObserver:NSKeyValueObservation?
    private var queueItemStatusObserver:NSKeyValueObservation?
    
    // Sleep timer values
    internal var sleepTimeChapterStopAt: Double?
    internal var sleepTimeChapterToken: Any?
    internal var sleepTimer: Timer?
    internal var sleepTimeRemaining: Double?
    
    internal var currentTrackIndex = 0
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
            logger.error("Failed to fetch playback session. Player will not initialize")
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
            return
        }
        
        // Listen to player events
        self.setupAudioSessionNotifications()
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.rate), options: .new, context: &playerContext)
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), options: .new, context: &playerContext)
        
        for track in playbackSession.audioTracks {
            if let playerAsset = createAsset(itemId: playbackSession.libraryItemId!, track: track) {
                let playerItem = AVPlayerItem(asset: playerAsset)
                self.allPlayerItems.append(playerItem)
            }
        }
        
        self.currentTrackIndex = getItemIndexForTime(time: playbackSession.currentTime)
        logger.log("Starting track index \(self.currentTrackIndex) for start time \(playbackSession.currentTime)")
        
        let playerItems = self.allPlayerItems[self.currentTrackIndex..<self.allPlayerItems.count]
        logger.log("Setting player items \(playerItems.count)")
        
        for item in Array(playerItems) {
            self.audioPlayer.insert(item, after:self.audioPlayer.items().last)
        }

        setupTimeObservers()
        setupQueueObserver()
        setupQueueItemStatusObserver()

        logger.log("Audioplayer ready")
    }
    
    deinit {
        self.queueObserver?.invalidate()
        self.queueItemStatusObserver?.invalidate()
    }
    
    public func destroy() {
        // Pause is not synchronous causing this error on below lines:
        // AVAudioSession_iOS.mm:1206  Deactivating an audio session that has running I/O. All I/O should be stopped or paused prior to deactivating the audio session
        // It is related to L79 `AVAudioSession.sharedInstance().setActive(false)`
        self.pause()
        self.audioPlayer.replaceCurrentItem(with: nil)
        
        do {
            try AVAudioSession.sharedInstance().setActive(false)
        } catch {
            logger.error("Failed to set AVAudioSession inactive")
            logger.error(error)
        }
        
        self.removeAudioSessionNotifications()
        DispatchQueue.runOnMainQueue {
            UIApplication.shared.endReceivingRemoteControlEvents()
        }
        
        // Remove observers
        self.audioPlayer.removeObserver(self, forKeyPath: #keyPath(AVPlayer.rate), context: &playerContext)
        self.audioPlayer.removeObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), context: &playerContext)
        self.removeTimeObservers()
        
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.closed.rawValue), object: nil)
        
        // Remove timers
        self.stopPausedTimer()
        self.removeSleepTimer()
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
    
    private func setupAudioSessionNotifications() {
        NotificationCenter.default.addObserver(self, selector: #selector(handleInteruption), name: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self, selector: #selector(handleRouteChange), name: AVAudioSession.routeChangeNotification, object: AVAudioSession.sharedInstance())
    }
    
    private func removeAudioSessionNotifications() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance())
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: AVAudioSession.sharedInstance())
    }
    
    private func setupTimeObservers() {
        // Time observer should be configured on the main queue
        DispatchQueue.runOnMainQueue {
            self.removeTimeObservers()
            
            let timeScale = CMTimeScale(NSEC_PER_SEC)
            // Save the current time every 15 seconds
            var seconds = 15.0
            var time = CMTime(seconds: Double(seconds), preferredTimescale: timeScale)
            self.timeObserverToken = self.audioPlayer.addPeriodicTimeObserver(forInterval: time, queue: self.queue) { [weak self] time in
                guard let self = self else { return }
                guard self.isInitialized() else { return }
                
                guard let currentTime = self.getCurrentTime() else { return }
                let isPlaying = self.isPlaying()
                
                Task {
                    // Let the player update the current playback positions
                    await PlayerProgress.shared.syncFromPlayer(currentTime: currentTime, includesPlayProgress: isPlaying, isStopping: false)
                }
            }
            // Update the sleep timer every second
            seconds = 1.0
            time = CMTime(seconds: Double(seconds), preferredTimescale: timeScale)
            self.sleepTimerObserverToken = self.audioPlayer.addPeriodicTimeObserver(forInterval: time, queue: self.queue) { [weak self] time in
                guard let self = self else { return }
                if self.isSleepTimerSet() {
                    // Update the UI
                    NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
                }
            }
        }
    }
    
    private func removeTimeObservers() {
        if let timeObserverToken = timeObserverToken {
            self.audioPlayer.removeTimeObserver(timeObserverToken)
            self.timeObserverToken = nil
        }
        if let sleepTimerObserverToken = sleepTimerObserverToken {
            self.audioPlayer.removeTimeObserver(sleepTimerObserverToken)
            self.sleepTimerObserverToken = nil
        }
    }
    
    private func setupQueueObserver() {
        self.queueObserver = self.audioPlayer.observe(\.currentItem, options: [.new]) { [weak self] _,_ in
            guard let self = self else { return }
            let prevTrackIndex = self.currentTrackIndex
            self.audioPlayer.currentItem.map { item in
                self.currentTrackIndex = self.allPlayerItems.firstIndex(of:item) ?? 0
                if (self.currentTrackIndex != prevTrackIndex) {
                    self.logger.log("New Current track index \(self.currentTrackIndex)")
                }
            }
        }
    }
    
    private func setupQueueItemStatusObserver() {
        logger.log("queueStatusObserver: Setting up")

        // Listen for player item updates
        self.queueItemStatusObserver?.invalidate()
        self.queueItemStatusObserver = self.audioPlayer.currentItem?.observe(\.status, options: [.new, .old], changeHandler: { [weak self] playerItem, change in
            self?.handleQueueItemStatus(playerItem: playerItem)
        })
        
        // Ensure we didn't miss a player item update during initialization
        if let playerItem = self.audioPlayer.currentItem {
            self.handleQueueItemStatus(playerItem: playerItem)
        }
    }
    
    private func handleQueueItemStatus(playerItem: AVPlayerItem) {
        logger.log("queueStatusObserver: Current item status changed")
        guard let playbackSession = self.getPlaybackSession() else {
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
            return
        }
        if (playerItem.status == .readyToPlay) {
            logger.log("queueStatusObserver: Current Item Ready to play. PlayWhenReady: \(self.playWhenReady)")
            
            // Seek the player before initializing, so a currentTime of 0 does not appear in MediaProgress / session
            let firstReady = self.status < 0
            if firstReady && !self.playWhenReady {
                // Seek is async, and if we call this when also pressing play, we will get weird jumps in the scrub bar depending on timing
                // Seeking to the correct position happens during play()
                self.seek(playbackSession.currentTime, from: "queueItemStatusObserver")
            }
            
            // Start the player, if requested
            if self.playWhenReady {
                self.playWhenReady = false
                self.play(allowSeekBack: false, isInitializing: true)
            } else {
                // Mark the player as ready
                self.status = 0
            }
        } else if (playerItem.status == .failed) {
            logger.error("queueStatusObserver: FAILED \(playerItem.error?.localizedDescription ?? "")")
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
        }
    }
    
    private func startPausedTimer() {
        guard self.pausedTimer == nil else { return }
        self.queue.async {
            self.pausedTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] timer in
                self?.logger.log("PAUSE TIMER: Syncing from server")
                Task { await PlayerProgress.shared.syncFromServer() }
            }
        }
    }
    
    private func stopPausedTimer() {
        self.pausedTimer?.invalidate()
        self.pausedTimer = nil
    }
    
    // MARK: - Methods
    public func play(allowSeekBack: Bool = false, isInitializing: Bool = false) {
        guard self.isInitialized() || isInitializing else { return }
        guard let session = self.getPlaybackSession() else {
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
            return
        }
        
        // Determine where we are starting playback
        let currentTime = allowSeekBack ? PlayerTimeUtils.calcSeekBackTime(currentTime: session.currentTime, lastPlayedMs: session.updatedAt) : session.currentTime
        
        // Sync our new playback position
        Task { await PlayerProgress.shared.syncFromPlayer(currentTime: currentTime, includesPlayProgress: self.isPlaying(), isStopping: false) }

        // Start playback, with a seek, for as smooth a scrub bar start as possible
        let currentTrackStartOffset = session.audioTracks[self.currentTrackIndex].startOffset ?? 0.0
        let seekTime = currentTime - currentTrackStartOffset
        
        DispatchQueue.runOnMainQueue {
            self.audioPlayer.seek(to: CMTime(seconds: seekTime, preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] completed in
                guard completed else { return }
                self?.resumePlayback()
            }
        }
    }
    
    private func resumePlayback() {
        logger.log("PLAY: Resuming playback")
        
        // Stop the paused timer
        self.stopPausedTimer()
        
        self.markAudioSessionAs(active: true)
        DispatchQueue.runOnMainQueue {
            self.audioPlayer.play()
            self.audioPlayer.rate = self.tmpRate
        }
        self.status = 1
        
        // Update the progress
        self.updateNowPlaying()
        
        // Handle a chapter sleep timer that may now be invalid
        self.handleTrackChangeForChapterSleepTimer()
    }
    
    public func pause() {
        guard self.isInitialized() else { return }
        
        logger.log("PAUSE: Pausing playback")
        DispatchQueue.runOnMainQueue {
            self.audioPlayer.pause()
        }
        self.markAudioSessionAs(active: false)
        
        Task {
            if let currentTime = self.getCurrentTime() {
                await PlayerProgress.shared.syncFromPlayer(currentTime: currentTime, includesPlayProgress: self.isPlaying(), isStopping: true)
            }
        }
        
        self.status = 0
        
        updateNowPlaying()
        
        self.startPausedTimer()
    }
    
    public func seek(_ to: Double, from: String) {
        let continuePlaying = rate > 0.0
        
        self.pause()
        
        logger.log("SEEK: Seek to \(to) from \(from) and continuePlaying(\(continuePlaying)")
        
        guard let playbackSession = self.getPlaybackSession() else { return }
        
        let currentTrack = playbackSession.audioTracks[self.currentTrackIndex]
        let ctso = currentTrack.startOffset ?? 0.0
        let trackEnd = ctso + currentTrack.duration
        logger.log("SEEK: Seek current track END = \(trackEnd)")
        
        let indexOfSeek = getItemIndexForTime(time: to)
        logger.log("SEEK: Seek to index \(indexOfSeek) | Current index \(self.currentTrackIndex)")
        
        // Reconstruct queue if seeking to a different track
        if (self.currentTrackIndex != indexOfSeek) {
            self.currentTrackIndex = indexOfSeek
            
            try? playbackSession.update {
                playbackSession.currentTime = to
            }
            
            self.playWhenReady = continuePlaying // Only playWhenReady if already playing
            self.status = -1
            let playerItems = self.allPlayerItems[indexOfSeek..<self.allPlayerItems.count]
            
            DispatchQueue.runOnMainQueue {
                self.audioPlayer.removeAllItems()
                for item in Array(playerItems) {
                    self.audioPlayer.insert(item, after:self.audioPlayer.items().last)
                }
            }
            
            setupQueueItemStatusObserver()
        } else {
            logger.log("SEEK: Seeking in current item \(to)")
            let currentTrackStartOffset = playbackSession.audioTracks[self.currentTrackIndex].startOffset ?? 0.0
            let seekTime = to - currentTrackStartOffset
            
            DispatchQueue.runOnMainQueue {
                self.audioPlayer.seek(to: CMTime(seconds: seekTime, preferredTimescale: 1000)) { [weak self] completed in
                    self?.logger.log("SEEK: Completion handler called and continuePlaying(\(continuePlaying)")
                    guard completed else {
                        self?.logger.log("SEEK: WARNING: seeking not completed (to \(seekTime)")
                        return
                    }
                    guard let self = self else { return }
                    
                    if continuePlaying {
                        self.resumePlayback()
                    }
                    
                    self.updateNowPlaying()
                }
            }
        }
    }
    
    public func setPlaybackRate(_ rate: Float, observed: Bool = false) {
        let playbackSpeedChanged = rate > 0.0 && rate != self.tmpRate && !(observed && rate == 1)
        
        if self.audioPlayer.rate != rate {
            logger.log("setPlaybakRate rate changed from \(self.audioPlayer.rate) to \(rate)")
            DispatchQueue.runOnMainQueue {
                self.audioPlayer.rate = rate
            }
        }
        
        self.rate = rate
        self.updateNowPlaying()
        
        if playbackSpeedChanged {
            self.tmpRate = rate
            
            // Setup the time observer again at the new rate
            self.setupTimeObservers()
        }
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
            let relPath = track.metadata?.relPath ?? ""
            let filepathEncoded = relPath.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
            let urlstr = "\(Store.serverConfig!.address)/s/item/\(itemId)/\(filepathEncoded ?? "")?token=\(Store.serverConfig!.token)"
            let url = URL(string: urlstr)!
            return AVURLAsset(url: url)
        } else if (playbackSession.playMethod == PlayMethod.local.rawValue) {
            guard let localFile = track.getLocalFile() else {
                // Worst case we can stream the file
                logger.log("Unable to play local file. Resulting to streaming \(track.localFileId ?? "Unknown")")
                let relPath = track.metadata?.relPath ?? ""
                let filepathEncoded = relPath.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
                let urlstr = "\(Store.serverConfig!.address)/s/item/\(itemId)/\(filepathEncoded ?? "")?token=\(Store.serverConfig!.token)"
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
        } catch {
            logger.error("Failed to set AVAudioSession category")
            logger.error(error)
        }
    }
    
    private func markAudioSessionAs(active: Bool) {
        do {
            try AVAudioSession.sharedInstance().setActive(active)
        } catch {
            logger.error("Failed to set audio session as active=\(active)")
        }
    }
    
    // MARK: - iOS audio session notifications
    @objc private func handleInteruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
            let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
                return
        }
        
        switch type {
        case .ended:
            guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            if options.contains(.shouldResume) {
                self.play(allowSeekBack: true)
            }
        default: ()
        }
    }
    
    @objc private func handleRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
            let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
                return
        }
        
        switch reason {
        case .newDeviceAvailable: // New device found.
            let session = AVAudioSession.sharedInstance()
            let headphonesConnected = hasHeadphones(in: session.currentRoute)
            if headphonesConnected {
                // We should just let things be, as it's okay to go from speaker to headphones
            }
        case .oldDeviceUnavailable: // Old device removed.
            if let previousRoute = userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription {
                let headphonesWereConnected = hasHeadphones(in: previousRoute)
                if headphonesWereConnected {
                    // Removing headphones we should pause instead of keeping on playing
                    self.pause()
                }
            }
        
        default: ()
        }
    }
    
    private func hasHeadphones(in routeDescription: AVAudioSessionRouteDescription) -> Bool {
        // Filter the outputs to only those with a port type of headphones.
        return !routeDescription.outputs.filter({$0.portType == .headphones}).isEmpty
    }
    
    // MARK: - Now playing
    private func setupRemoteTransportControls() {
        DispatchQueue.runOnMainQueue {
            UIApplication.shared.beginReceivingRemoteControlEvents()
        }
        let commandCenter = MPRemoteCommandCenter.shared()
        let deviceSettings = Database.shared.getDeviceSettings()
        
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [weak self] event in
            self?.play(allowSeekBack: true)
            return .success
        }
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [weak self] event in
            self?.pause()
            return .success
        }
        
        commandCenter.skipForwardCommand.isEnabled = true
        commandCenter.skipForwardCommand.preferredIntervals = [NSNumber(value: deviceSettings.jumpForwardTime)]
        commandCenter.skipForwardCommand.addTarget { [weak self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            guard let currentTime = self?.getCurrentTime() else {
                return .commandFailed
            }
            self?.seek(currentTime + command.preferredIntervals[0].doubleValue, from: "remote")
            return .success
        }
        commandCenter.skipBackwardCommand.isEnabled = true
        commandCenter.skipBackwardCommand.preferredIntervals = [NSNumber(value: deviceSettings.jumpBackwardsTime)]
        commandCenter.skipBackwardCommand.addTarget { [weak self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            guard let currentTime = self?.getCurrentTime() else {
                return .commandFailed
            }
            self?.seek(currentTime - command.preferredIntervals[0].doubleValue, from: "remote")
            return .success
        }
        
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard let currentTime = self?.getCurrentTime() else {
                return .commandFailed
            }
            self?.seek(currentTime + Double(deviceSettings.jumpForwardTime), from: "remote")
            return .success
        }
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard let currentTime = self?.getCurrentTime() else {
                return .commandFailed
            }
            self?.seek(currentTime - Double(deviceSettings.jumpBackwardsTime), from: "remote")
            return .success
        }

        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .noSuchContent
            }
            
            self?.seek(event.positionTime, from: "remote")
            return .success
        }
        
        commandCenter.changePlaybackRateCommand.isEnabled = true
        commandCenter.changePlaybackRateCommand.supportedPlaybackRates = [0.5, 0.75, 1.0, 1.25, 1.5, 2]
        commandCenter.changePlaybackRateCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackRateCommandEvent else {
                return .noSuchContent
            }
            
            self?.setPlaybackRate(event.playbackRate)
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
                logger.log("playerContext observer player rate")
                self.setPlaybackRate(change?[.newKey] as? Float ?? 1.0, observed: true)
            } else if keyPath == #keyPath(AVPlayer.currentItem) {
                NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.update.rawValue), object: nil)
                logger.log("WARNING: Item ended")
            }
        } else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }
    }
    
    public static var instance: AudioPlayer?
}
