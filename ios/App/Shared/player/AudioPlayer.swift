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
    
    private var audioPlayer: AVPlayer
    private var playbackSession: PlaybackSession
    private var activeAudioTrack: AudioTrack
    
    // MARK: - Constructor
    init(playbackSession: PlaybackSession, playWhenReady: Bool = false, playbackRate: Float = 1) {
        self.playWhenReady = playWhenReady
        self.initialPlaybackRate = playbackRate
        self.audioPlayer = AVPlayer()
        self.playbackSession = playbackSession
        self.status = -1
        self.rate = 0.0
        self.tmpRate = playbackRate
        
        if playbackSession.audioTracks.count != 1 || playbackSession.audioTracks[0].mimeType != "application/vnd.apple.mpegurl" {
            NSLog("The player only support HLS streams right now")
            self.activeAudioTrack = AudioTrack(index: 0, startOffset: -1, duration: -1, title: "", contentUrl: nil, mimeType: "", metadata: nil, serverIndex: 0)
            
            super.init()
            return
        }
        self.activeAudioTrack = playbackSession.audioTracks[0]
        
        super.init()
        
        initAudioSession()
        setupRemoteTransportControls()
        
        // Listen to player events
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.rate), options: .new, context: &playerContext)
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), options: .new, context: &playerContext)
        
        let playerItem = AVPlayerItem(asset: createAsset())
        playerItem.addObserver(self, forKeyPath: #keyPath(AVPlayerItem.status), options: .new, context: &playerItemContext)
        
        self.audioPlayer.replaceCurrentItem(with: playerItem)
        
        NSLog("Audioplayer ready")
    }
    deinit {
        destroy()
    }
    public func destroy() {
        // Pause is not synchronous causing this error on below lines:
        // AVAudioSession_iOS.mm:1206  Deactivating an audio session that has running I/O. All I/O should be stopped or paused prior to deactivating the audio session
        pause()
        audioPlayer.replaceCurrentItem(with: nil)
        
        do {
            try AVAudioSession.sharedInstance().setActive(false)
        } catch {
            NSLog("Failed to set AVAudioSession inactive")
            print(error)
        }
        
        // Throws error Possibly related to the error above
//        DispatchQueue.main.sync {
//            UIApplication.shared.endReceivingRemoteControlEvents()
//        }
        
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.closed.rawValue), object: nil)
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
                seek(getCurrentTime() - Double(time!))
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
    public func seek(_ to: Double) {
        let continuePlaing = rate > 0.0
        
        pause()
        self.audioPlayer.seek(to: CMTime(seconds: to, preferredTimescale: 1000)) { completed in
            if !completed {
                NSLog("WARNING: seeking not completed (to \(to)")
            }
            
            if continuePlaing {
                self.play()
            }
            self.updateNowPlaying()
        }
    }
    
    public func setPlaybackRate(_ rate: Float, observed: Bool = false) {
        if self.audioPlayer.rate != rate {
            self.audioPlayer.rate = rate
        }
        if rate > 0.0 && !(observed && rate == 1) {
            self.tmpRate = rate
        }
        
        self.rate = rate
        
        self.updateNowPlaying()
    }
    
    public func getCurrentTime() -> Double {
        self.audioPlayer.currentTime().seconds
    }
    public func getDuration() -> Double {
        self.audioPlayer.currentItem?.duration.seconds ?? 0
    }
    
    // MARK: - Private
    private func createAsset() -> AVAsset {
        let headers: [String: String] = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        return AVURLAsset(url: URL(string: "\(Store.serverConfig!.address)\(activeAudioTrack.contentUrl ?? "")")!, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
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
        // DispatchQueue.main.sync {
            UIApplication.shared.beginReceivingRemoteControlEvents()
        // }
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
            
            seek(getCurrentTime() + command.preferredIntervals[0].doubleValue)
            return .success
        }
        commandCenter.skipBackwardCommand.isEnabled = true
        commandCenter.skipBackwardCommand.preferredIntervals = [30]
        commandCenter.skipBackwardCommand.addTarget { [unowned self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            
            seek(getCurrentTime() - command.preferredIntervals[0].doubleValue)
            return .success
        }
        
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .noSuchContent
            }
            
            self.seek(event.positionTime)
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
        NowPlayingInfo.update(duration: getDuration(), currentTime: getCurrentTime(), rate: rate)
    }
    
    // MARK: - Observer
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if context == &playerItemContext {
            if keyPath == #keyPath(AVPlayer.status) {
                guard let playerStatus = AVPlayerItem.Status(rawValue: (change?[.newKey] as? Int ?? -1)) else { return }
                
                if playerStatus == .readyToPlay {
                    self.updateNowPlaying()
                    
                    let firstReady = self.status < 0
                    self.status = 0
                    if self.playWhenReady {
                        seek(playbackSession.currentTime)
                        self.playWhenReady = false
                        self.play()
                    } else if (firstReady) { // Only seek on first readyToPlay
                        seek(playbackSession.currentTime)
                    }
                }
            }
        } else if context == &playerContext {
            if keyPath == #keyPath(AVPlayer.rate) {
                self.setPlaybackRate(change?[.newKey] as? Float ?? 1.0, observed: true)
            } else if keyPath == #keyPath(AVPlayer.currentItem) {
                NSLog("WARNING: Item ended")
            }
        } else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }
    }
    
    public static var instance: AudioPlayer?
}
