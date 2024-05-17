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
import RealmSwift

enum PlayMethod: Int {
    case directplay = 0
    case directstream = 1
    case transcode = 2
    case local = 3
}

enum PlayerStatus: Int {
    case uninitialized = -1
    case paused = 0
    case playing = 1
}

class AudioPlayer: NSObject {
    internal let queue = DispatchQueue(label: "ABSAudioPlayerQueue")
    internal let logger = AppLogger(category: "AudioPlayer")

    private var status: PlayerStatus
    internal var rateManager: AudioPlayerRateManager
    
    private var playerContext = 0
    private var playerItemContext = 0
    
    internal var playWhenReady: Bool
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
    
    // MARK: - Constructor
    init(sessionId: String, playWhenReady: Bool = false, playbackRate: Float = 1) {
        self.playWhenReady = playWhenReady
        self.initialPlaybackRate = playbackRate
        self.audioPlayer = AVQueuePlayer()
        self.audioPlayer.automaticallyWaitsToMinimizeStalling = true
        self.sessionId = sessionId
        self.status = .uninitialized
        
        if #available(iOS 16.0, *) {
            self.rateManager = DefaultedAudioPlayerRateManager(audioPlayer: self.audioPlayer, defaultRate: playbackRate)
        } else {
            self.rateManager = LegacyAudioPlayerRateManager(audioPlayer: self.audioPlayer, defaultRate: playbackRate)
        }
        
        super.init()
        
        self.rateManager.rateChangedCompletion = self.updateNowPlaying
        self.rateManager.defaultRateChangedCompletion = self.setupTimeObservers
        
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
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), options: .new, context: &playerContext)
        
        for track in playbackSession.audioTracks {
            
            // TODO: All of this to get the ino of the file on the server. Future server release will include the ino with the session tracks
            var audioFileIno = ""
            let trackPath = track.metadata?.path ?? ""
            if (!playbackSession.isLocal && playbackSession.episodeId != nil) {
                let episodes = playbackSession.libraryItem?.media?.episodes ?? List<PodcastEpisode>()
                let matchingEpisode:PodcastEpisode? = episodes.first(where: { $0.audioFile?.metadata?.path == trackPath })
                audioFileIno = matchingEpisode?.audioFile?.ino ?? ""
            } else if (!playbackSession.isLocal) {
                let audioFiles = playbackSession.libraryItem?.media?.audioFiles ?? List<AudioFile>()
                let matchingAudioFile = audioFiles.first(where: { $0.metadata?.path == trackPath })
                audioFileIno = matchingAudioFile?.ino ?? ""
            }
            
            if let playerAsset = createAsset(itemId: playbackSession.libraryItemId!, track: track, ino: audioFileIno) {
                let playerItem = AVPlayerItem(asset: playerAsset)
                if (playbackSession.playMethod == PlayMethod.transcode.rawValue) {
                    playerItem.preferredForwardBufferDuration = 50
                }
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
        self.audioPlayer.removeObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), context: &playerContext)
        self.removeTimeObservers()
        
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.closed.rawValue), object: nil)
        
        // Remove timers
        self.removeSleepTimer()
    }
    
    public func isInitialized() -> Bool {
        return self.status != .uninitialized
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
            } else if (index == self.allPlayerItems.count - 1)  {
                // Seeking past end of last item
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
                // Update the now playing and chapter info
                self.updateNowPlaying()
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
            let firstReady = self.status == .uninitialized
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
                self.status = .paused
            }
        } else if (playerItem.status == .failed) {
            logger.error("queueStatusObserver: FAILED \(playerItem.error?.localizedDescription ?? "")")
            NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
        }
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
        
        self.markAudioSessionAs(active: true)
        DispatchQueue.runOnMainQueue {
            self.audioPlayer.play()
            self.rateManager.handlePlayEvent()
        }
        self.status = .playing

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
        
        
        self.status = .paused
        updateNowPlaying()
    }
    
    public func seek(_ to: Double, from: String) {
        logger.log("SEEK: Seek to \(to) from \(from)")

        guard let playbackSession = self.getPlaybackSession() else { return }

        let indexOfSeek = getItemIndexForTime(time: to)
        logger.log("SEEK: Seek to index \(indexOfSeek) | Current index \(self.currentTrackIndex)")
        
        // Reconstruct queue if seeking to a different track
        if (self.currentTrackIndex != indexOfSeek) {
            // When we seek to a different track, we need to make sure to seek the old track to 0
            // or we will get jumps to the old position when fading over into a new track
            self.audioPlayer.seek(to: CMTime(seconds: 0, preferredTimescale: 1000))

            self.currentTrackIndex = indexOfSeek
            
            try? playbackSession.update {
                playbackSession.currentTime = to
            }
            
            let playerItems = self.allPlayerItems[indexOfSeek..<self.allPlayerItems.count]
            
            DispatchQueue.runOnMainQueue {
                self.audioPlayer.removeAllItems()
                for item in Array(playerItems) {
                    self.audioPlayer.insert(item, after:self.audioPlayer.items().last)
                }
            }

            seekInCurrentTrack(to: to, playbackSession: playbackSession)

            setupQueueItemStatusObserver()
        } else {
            seekInCurrentTrack(to: to, playbackSession: playbackSession)
        }

        // Only for use in here where we handle track selection
        func seekInCurrentTrack(to: Double, playbackSession: PlaybackSession) {
            let currentTrack = playbackSession.audioTracks[self.currentTrackIndex]
            let ctso = currentTrack.startOffset ?? 0.0
            let trackEnd = ctso + currentTrack.duration
            logger.log("SEEK: Seeking in current item \(to) (track START = \(ctso) END = \(trackEnd))")

            let boundedTime = min(max(to, ctso), trackEnd)
            let seekTime = boundedTime - ctso

            DispatchQueue.runOnMainQueue {
                self.audioPlayer.seek(to: CMTime(seconds: seekTime, preferredTimescale: 1000)) { [weak self] completed in
                    self?.logger.log("SEEK: Completion handler called")
                    guard completed else {
                        self?.logger.log("SEEK: WARNING: seeking not completed (to \(seekTime)")
                        return
                    }
                    guard let self = self else { return }
                    
                    self.updateNowPlaying()
                }
            }
        }
    }
    
    public func setPlaybackRate(_ rate: Float) {
        self.rateManager.setPlaybackRate(rate)
    }
    
    public func setChapterTrack() {
        self.updateNowPlaying()
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
        return self.status == .playing
    }
    
    public func getPlayerState() -> PlayerState {
        switch status {
        case .uninitialized:
            return PlayerState.buffering
        case .paused, .playing:
            return PlayerState.ready
        }
    }

    // MARK: - Private
    private func createAsset(itemId:String, track:AudioTrack, ino:String) -> AVAsset? {
        guard let playbackSession = self.getPlaybackSession() else { return nil }
        
        if (playbackSession.playMethod == PlayMethod.directplay.rawValue) {
            let urlstr = "\(Store.serverConfig!.address)/api/items/\(itemId)/file/\(ino)?token=\(Store.serverConfig!.token)"
            let url = URL(string: urlstr)!
            return AVURLAsset(url: url)
        } else if (playbackSession.playMethod == PlayMethod.local.rawValue) {
            guard let localFile = track.getLocalFile() else {
                // Worst case we can stream the file
                logger.log("Unable to play local file. Resulting to streaming \(track.localFileId ?? "Unknown")")
                let urlstr = "\(Store.serverConfig!.address)/api/items/\(itemId)/file/\(ino)?token=\(Store.serverConfig!.token)"
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
        
        // When interruption is from the app suspending then don't resume playback
        if #available(iOS 14.5, *) {
            let reasonValue = userInfo[AVAudioSessionInterruptionReasonKey] as? UInt ?? 0
            let reason = AVAudioSession.InterruptionReason(rawValue: reasonValue)
            if (reason == .appWasSuspended) {
                logger.log("AVAudioSession was suspended")
                return
            }
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
    func setupRemoteTransportControls() {
        DispatchQueue.runOnMainQueue {
            UIApplication.shared.beginReceivingRemoteControlEvents()
        }
        let commandCenter = MPRemoteCommandCenter.shared()
        let deviceSettings = Database.shared.getDeviceSettings()
        let jumpForwardTime = deviceSettings.jumpForwardTime
        let jumpBackwardsTime = deviceSettings.jumpBackwardsTime
        
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.removeTarget(nil)
        commandCenter.playCommand.addTarget { [weak self] event in
            guard let strongSelf = self else { return .commandFailed }
            if strongSelf.isPlaying() {
                strongSelf.pause()
            } else {
                strongSelf.play(allowSeekBack: true)
            }
            return .success
        }

        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.removeTarget(nil)
        commandCenter.pauseCommand.addTarget { [weak self] event in
            guard let strongSelf = self else { return .commandFailed }
            if strongSelf.isPlaying() {
                strongSelf.pause()
            } else {
                strongSelf.play(allowSeekBack: true)
            }
            return .success
        }

        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.removeTarget(nil)
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] event in
            guard let strongSelf = self else { return .commandFailed }
            if strongSelf.isPlaying() {
                strongSelf.pause()
            } else {
                strongSelf.play(allowSeekBack: true)
            }
            return .success
        }
        
        commandCenter.skipForwardCommand.isEnabled = true
        commandCenter.skipForwardCommand.removeTarget(nil)
        commandCenter.skipForwardCommand.preferredIntervals = [NSNumber(value: jumpForwardTime)]
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
        commandCenter.skipBackwardCommand.removeTarget(nil)
        commandCenter.skipBackwardCommand.preferredIntervals = [NSNumber(value: jumpBackwardsTime)]
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
        commandCenter.nextTrackCommand.removeTarget(nil)
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard let currentTime = self?.getCurrentTime() else {
                return .commandFailed
            }
            self?.seek(currentTime + Double(jumpForwardTime), from: "remote")
            return .success
        }
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.removeTarget(nil)
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard let currentTime = self?.getCurrentTime() else {
                return .commandFailed
            }
            self?.seek(currentTime - Double(jumpBackwardsTime), from: "remote")
            return .success
        }

        commandCenter.changePlaybackPositionCommand.isEnabled = deviceSettings.allowSeekingOnMediaControls
        commandCenter.changePlaybackPositionCommand.removeTarget(nil)
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .noSuchContent
            }
            
            // Adjust seek time if chapter track is being used
            var seekTime = event.positionTime
            if PlayerSettings.main().chapterTrack {
                if let session = self?.getPlaybackSession(), let currentChapter = session.getCurrentChapter() {
                    seekTime += currentChapter.start
                }
            }
            self?.seek(seekTime, from: "remote")
            return .success
        }
        
        commandCenter.changePlaybackRateCommand.isEnabled = true
        commandCenter.changePlaybackRateCommand.removeTarget(nil)
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
        if let session = self.getPlaybackSession(), let currentChapter = session.getCurrentChapter(), PlayerSettings.main().chapterTrack {
            NowPlayingInfo.shared.update(
                duration: currentChapter.getRelativeChapterEndTime(),
                currentTime: currentChapter.getRelativeChapterCurrentTime(sessionCurrentTime: session.currentTime),
                rate: self.rateManager.rate,
                defaultRate: self.rateManager.defaultRate,
                chapterName: currentChapter.title,
                chapterNumber: (session.chapters.firstIndex(of: currentChapter) ?? 0) + 1,
                chapterCount: session.chapters.count
            )
        } else if let duration = self.getDuration(), let currentTime = self.getCurrentTime() {
            NowPlayingInfo.shared.update(duration: duration, currentTime: currentTime, rate: self.rateManager.rate, defaultRate: self.rateManager.defaultRate)
        }
    }
    
    // MARK: - Observer
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if context == &playerContext {
            if keyPath == #keyPath(AVPlayer.currentItem) {
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
