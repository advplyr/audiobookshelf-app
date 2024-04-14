//
//  DefaultedAudioPlayerRateManager.swift
//  Audiobookshelf
//
//  Created by Marke Hallowell on 4/14/24.
//

import Foundation
import AVFoundation

@available(iOS 16.0, *)
class DefaultedAudioPlayerRateManager: NSObject, AudioPlayerRateManager {
    internal let logger = AppLogger(category: "DefaultedAudioPlayerRateManager")
    
    internal var audioPlayer: AVPlayer
    
    // MARK: - AudioPlayerRateManager
    public private(set) var defaultRate: Float
    public private(set) var rate: Float
    public var rateChangedCompletion: () -> Void
    public var defaultRateChangedCompletion: () -> Void

    required init(audioPlayer: AVPlayer, defaultRate: Float) {
        self.audioPlayer = audioPlayer
        self.rateChangedCompletion = {}
        self.defaultRateChangedCompletion = {}
        self.rate = self.audioPlayer.rate
        self.defaultRate = defaultRate
        self.audioPlayer.defaultRate = defaultRate

        super.init()
        
        NotificationCenter.default.addObserver(self, selector: #selector(handleObservedRateChange), name: AVPlayer.rateDidChangeNotification, object: self.audioPlayer)
    }
    
    public func setPlaybackRate(_ rate: Float) {
        self.handlePlaybackRateChange(rate, observed: false)
    }
    
    // No-op (player automatically resumes at last-known defaultRate)
    public func handlePlayEvent() { }
    
    // MARK: - Destructor
    public func destroy() {
        NotificationCenter.default.removeObserver(self, name: AVPlayer.rateDidChangeNotification, object: self.audioPlayer)
    }
    
    // MARK: - Internal
    internal func handlePlaybackRateChange(_ rate: Float, observed: Bool = false) {
        let playbackSpeedChanged = rate > 0.0 && rate != self.defaultRate
        
        if playbackSpeedChanged {
            self.defaultRate = rate
            self.audioPlayer.defaultRate = rate
            self.defaultRateChangedCompletion()

            // Check to see if we also need to make a temporary rate change to player
            if self.audioPlayer.rate > 0.0 {
                if self.audioPlayer.rate != rate {
                    self.rate = rate
                    self.audioPlayer.rate = rate
                    self.rateChangedCompletion()
                }
            }
        } else {
            self.rate = rate
            self.rateChangedCompletion()
        }
    }
    
    // MARK: - iOS rate change notification handler
    @objc internal func handleObservedRateChange(notification: Notification) {
        // TODO: Consider handling cases individually (e.g. overall session impact?)
        /*
        guard let reason = notification.userInfo?[AVPlayer.rateDidChangeReasonKey] as? AVPlayer.RateDidChangeReason else {
            return
        }
         
        switch reason {
        case .appBackgrounded:
            // App transitioned to the background.
        case .audioSessionInterrupted:
            // The system interrupts the app's audio session.
        case .setRateCalled:
            // The app set the player's rate.
        case .setRateFailed:
            // An attempt to change the player's rate failed.
        default:
            break
        }
        */
        self.handlePlaybackRateChange(self.audioPlayer.rate, observed: true)
    }
}
