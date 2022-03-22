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
    
    private var audioPlayer: AVPlayer
    public var audiobook: Audiobook
    
    init(audiobook: Audiobook, playWhenReady: Bool = false) {
        self.playWhenReady = playWhenReady
        self.audiobook = audiobook
        self.audioPlayer = AVPlayer()
        self.status = -1
        self.rate = 0.0
        
        super.init()
        
        initAudioSession()
        setupRemoteTransportControls()
        NowPlayingInfo.setAudiobook(audiobook: audiobook)
        
        // Listen to player events
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.rate), options: .new, context: &playerContext)
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem), options: .new, context: &playerContext)
        
        let playerItem = AVPlayerItem(asset: createAsset())
        playerItem.addObserver(self, forKeyPath: #keyPath(AVPlayerItem.status), options: .new, context: &playerItemContext)
        
        self.audioPlayer.replaceCurrentItem(with: playerItem)
        seek(self.audiobook.startTime)
        
        NSLog("Audioplayer ready")
    }
    deinit {
        destroy()
    }
    func destroy() {
        pause()
        audioPlayer.replaceCurrentItem(with: nil)
        
        do {
            try AVAudioSession.sharedInstance().setActive(false)
        } catch {
            NSLog("Failed to set AVAudioSession inactive")
            print(error)
        }
        
        DispatchQueue.main.sync {
            UIApplication.shared.endReceivingRemoteControlEvents()
        }
    }
    
    // MARK: - Methods
    public func play(allowSeekBack: Bool = false) {
        if allowSeekBack {
            if lastPlayTime + 5 < Date.timeIntervalSinceReferenceDate {
                seek(getCurrentTime() - 1.5)
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
            "Authorization": "Bearer \(audiobook.token)"
        ]
        
        return AVURLAsset(url: URL(string: audiobook.playlistUrl)!, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
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
    func setupRemoteTransportControls() {
        DispatchQueue.main.sync {
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
    
    func updateNowPlaying() {
        NowPlayingInfo.update(duration: getDuration(), currentTime: getCurrentTime(), rate: rate)
    }
    
    // MARK: - Observer
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if context == &playerItemContext {
            if keyPath == #keyPath(AVPlayer.status) {
                guard let playerStatus = AVPlayerItem.Status(rawValue: (change?[.newKey] as? Int ?? -1)) else { return }
                
                if playerStatus == .readyToPlay {
                    updateNowPlaying()
                    
                    self.status = 0
                    if self.playWhenReady {
                        self.playWhenReady = false
                        self.play()
                    }
                }
            }
        } else if context == &playerContext {
            if keyPath == #keyPath(AVPlayer.rate) {
                setPlaybackRate(change?[.newKey] as? Float ?? 1.0, observed: true)
            } else if keyPath == #keyPath(AVPlayer.currentItem) {
                NSLog("WARNING: Item ended")
            }
        } else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }
    }
}
