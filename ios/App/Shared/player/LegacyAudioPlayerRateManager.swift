//
//  LegacyAudioPlayerRateManager.swift
//  Audiobookshelf
//
//  Created by Marke Hallowell on 4/14/24.
//

import Foundation
import AVFoundation

class LegacyAudioPlayerRateManager: NSObject, AudioPlayerRateManager {
    internal let logger = AppLogger(category: "AudioPlayer")
    
    internal var audioPlayer: AVPlayer
    
    internal var managerContext = 0
    
    // MARK: - AudioPlayerRateManager
    public private(set) var defaultRate: Float
    public private(set) var rate: Float
    public var rateChangedCompletion: () -> Void
    public var defaultRateChangedCompletion: () -> Void

    required init(audioPlayer: AVPlayer, defaultRate: Float) {
        self.rate = 0.0
        self.defaultRate = defaultRate
        self.audioPlayer = audioPlayer
        self.rateChangedCompletion = {}
        self.defaultRateChangedCompletion = {}
        
        super.init()
        
        self.audioPlayer.addObserver(self, forKeyPath: #keyPath(AVPlayer.rate), options: .new, context: &managerContext)
    }
    
    public func setPlaybackRate(_ rate: Float) {
        self.handlePlaybackRateChange(rate, observed: false)
    }
    
    public func handlePlayEvent() {
        DispatchQueue.runOnMainQueue {
            self.audioPlayer.rate = self.defaultRate
        }
    }

    // MARK: - Destructor
    public func destroy() {
        // Remove Observer
        self.audioPlayer.removeObserver(self, forKeyPath: #keyPath(AVPlayer.rate), context: &managerContext)
    }
    
    // MARK: - Internal
    internal func handlePlaybackRateChange(_ rate: Float, observed: Bool = false) {
        let playbackSpeedChanged = rate > 0.0 && rate != self.defaultRate && !(observed && rate == 1)
        
        if self.audioPlayer.rate != rate {
            logger.log("setPlaybakRate rate changed from \(self.audioPlayer.rate) to \(rate)")
            DispatchQueue.runOnMainQueue {
                self.audioPlayer.rate = rate
            }
        }
        
        self.rate = rate
        self.rateChangedCompletion()
        
        if playbackSpeedChanged {
            self.defaultRate = rate
            self.defaultRateChangedCompletion()
        }
    }
    
    // MARK: - Observer
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if context == &managerContext {
            if keyPath == #keyPath(AVPlayer.rate) {
                logger.log("playerContext observer player rate")
                self.handlePlaybackRateChange(change?[.newKey] as? Float ?? 1.0, observed: true)
            }
        } else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }
    }
}
