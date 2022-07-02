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
    // enums and @objc are not compatible
    @objc dynamic var status: Int
    @objc dynamic var rate: Float
    
    private var tmpRate: Float = 1.0
    private var lastPlayTime: Double = 0.0
    
    private var playerContext = 0
    private var playerItemContext = 0
    
    private var playWhenReady: Bool
    private var initialPlaybackRate: Float
    
    private var audioPlayer: AVQueuePlayer
    private var playbackSession: PlaybackSession

    private var queueObserver:NSKeyValueObservation?
    private var queueItemStatusObserver:NSKeyValueObservation?
    
    private var currentTrackIndex = 0
    private var allPlayerItems:[AVPlayerItem] = []
    
    // MARK: - Constructor
    init(playbackSession: PlaybackSession, playWhenReady: Bool = false, playbackRate: Float = 1) {
        self.playWhenReady = playWhenReady
        self.initialPlaybackRate = playbackRate
        self.audioPlayer = AVQueuePlayer()
        self.audioPlayer.automaticallyWaitsToMinimizeStalling = false
        self.playbackSession = playbackSession
        self.status = -1
        self.rate = 0.0
        self.tmpRate = playbackRate
        
        super.init()
        
        initAudioSession()
        setupRemoteTransportControls()
        
        // Listen to player events
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.rate), options: .new, context: &playerContext)
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), options: .new, context: &playerContext)
        
        for track in playbackSession.audioTracks {
            let playerItem = AVPlayerItem(asset: createAsset(itemId: playbackSession.libraryItemId!, track: track))
            self.allPlayerItems.append(playerItem)
        }
        
        self.currentTrackIndex = getItemIndexForTime(time: playbackSession.currentTime)
        NSLog("Starting track index \(self.currentTrackIndex) for start time \(playbackSession.currentTime)")
        
        let playerItems = self.allPlayerItems[self.currentTrackIndex..<self.allPlayerItems.count]
        NSLog("Setting player items \(playerItems.count)")
        
        for item in Array(playerItems) {
            self.audioPlayer.insert(item, after:self.audioPlayer.items().last)
        }

        setupQueueObserver()
        setupQueueItemStatusObserver()

        NSLog("Audioplayer ready")
    }
    deinit {
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
    
    func getItemIndexForTime(time:Double) -> Int {
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
    
    func setupQueueObserver() {
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
    
    func setupQueueItemStatusObserver() {
        self.queueItemStatusObserver?.invalidate()
        self.queueItemStatusObserver = self.audioPlayer.currentItem?.observe(\.status, options: [.new, .old], changeHandler: { (playerItem, change) in
            if (playerItem.status == .readyToPlay) {
                NSLog("queueStatusObserver: Current Item Ready to play. PlayWhenReady: \(self.playWhenReady)")
                self.updateNowPlaying()
                
                let firstReady = self.status < 0
                self.status = 0
                if self.playWhenReady {
                    self.seek(self.playbackSession.currentTime, from: "queueItemStatusObserver")
                    self.playWhenReady = false
                    self.play()
                } else if (firstReady) { // Only seek on first readyToPlay
                    self.seek(self.playbackSession.currentTime, from: "queueItemStatusObserver")
                }
            } else if (playerItem.status == .failed) {
                NSLog("queueStatusObserver: FAILED \(playerItem.error?.localizedDescription ?? "")")
                
                NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.failed.rawValue), object: nil)
            }
        })
    }
    
    // MARK: - Methods
    public func play(allowSeekBack: Bool = false) {
        if allowSeekBack {
            let diffrence = Date.timeIntervalSinceReferenceDate - lastPlayTime
            var time: Int?
            
            if lastPlayTime == 0 {
                time = 5
            } else if diffrence < 6 {
                time = 2
            } else if diffrence < 12 {
                time = 10
            } else if diffrence < 30 {
                time = 15
            } else if diffrence < 180 {
                time = 20
            } else if diffrence < 3600 {
                time = 25
            } else {
                time = 29
            }
            
            if time != nil {
                seek(getCurrentTime() - Double(time!), from: "play")
            }
        }
        lastPlayTime = Date.timeIntervalSinceReferenceDate

        self.audioPlayer.play()
        self.status = 1
        self.rate = self.tmpRate
        self.audioPlayer.rate = self.tmpRate
        
        updateNowPlaying()
    }
    
    public func pause() {
        self.audioPlayer.pause()
        self.status = 0
        self.rate = 0.0
        
        updateNowPlaying()
        lastPlayTime = Date.timeIntervalSinceReferenceDate
    }
    
    public func seek(_ to: Double, from: String) {
        let continuePlaying = rate > 0.0
        
        pause()
        
        NSLog("Seek to \(to) from \(from)")
        
        let currentTrack = self.playbackSession.audioTracks[self.currentTrackIndex]
        let ctso = currentTrack.startOffset ?? 0.0
        let trackEnd = ctso + currentTrack.duration
        NSLog("Seek current track END = \(trackEnd)")
        
        
        let indexOfSeek = getItemIndexForTime(time: to)
        NSLog("Seek to index \(indexOfSeek) | Current index \(self.currentTrackIndex)")
        
        // Reconstruct queue if seeking to a different track
        if (self.currentTrackIndex != indexOfSeek) {
            self.currentTrackIndex = indexOfSeek
            
            self.playbackSession.currentTime = to
            
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
            let currentTrackStartOffset = self.playbackSession.audioTracks[self.currentTrackIndex].startOffset ?? 0.0
            let seekTime = to - currentTrackStartOffset
            
            self.audioPlayer.seek(to: CMTime(seconds: seekTime, preferredTimescale: 1000)) { completed in
                if !completed {
                    NSLog("WARNING: seeking not completed (to \(seekTime)")
                }
                
                if continuePlaying {
                    self.play()
                }
                self.updateNowPlaying()
            }
        }
    }
    
    public func setPlaybackRate(_ rate: Float, observed: Bool = false) {
        if self.audioPlayer.rate != rate {
            NSLog("setPlaybakRate rate changed from \(self.audioPlayer.rate) to \(rate)")
            self.audioPlayer.rate = rate
        }
        if rate > 0.0 && !(observed && rate == 1) {
            self.tmpRate = rate
        }
        
        self.rate = rate
        self.updateNowPlaying()
    }
    
    public func getCurrentTime() -> Double {
        let currentTrackTime = self.audioPlayer.currentTime().seconds
        let audioTrack = playbackSession.audioTracks[currentTrackIndex]
        let startOffset = audioTrack.startOffset ?? 0.0
        return startOffset + currentTrackTime
    }
    public func getPlayMethod() -> Int {
        return self.playbackSession.playMethod
    }
    public func getPlaybackSession() -> PlaybackSession {
        return self.playbackSession
    }
    public func getDuration() -> Double {
        return playbackSession.duration
    }
    
    // MARK: - Private
    private func createAsset(itemId:String, track:AudioTrack) -> AVAsset {
        if (playbackSession.playMethod == PlayMethod.directplay.rawValue) {
            // The only reason this is separate is because the filename needs to be encoded
            let filename = track.metadata?.filename ?? ""
            let filenameEncoded = filename.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
            let urlstr = "\(Store.serverConfig!.address)/s/item/\(itemId)/\(filenameEncoded ?? "")?token=\(Store.serverConfig!.token)"
            let url = URL(string: urlstr)!
            return AVURLAsset(url: url)
        } else { // HLS Transcode
            let headers: [String: String] = [
                "Authorization": "Bearer \(Store.serverConfig!.token)"
            ]
            return AVURLAsset(url: URL(string: "\(Store.serverConfig!.address)\(track.contentUrl ?? "")")!, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        }
    }
    
    private func initAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.allowAirPlay])
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
        commandCenter.skipForwardCommand.preferredIntervals = [30]
        commandCenter.skipForwardCommand.addTarget { [unowned self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            
            seek(getCurrentTime() + command.preferredIntervals[0].doubleValue, from: "remote")
            return .success
        }
        commandCenter.skipBackwardCommand.isEnabled = true
        commandCenter.skipBackwardCommand.preferredIntervals = [30]
        commandCenter.skipBackwardCommand.addTarget { [unowned self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            
            seek(getCurrentTime() - command.preferredIntervals[0].doubleValue, from: "remote")
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
        NowPlayingInfo.shared.update(duration: getDuration(), currentTime: getCurrentTime(), rate: rate)
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
