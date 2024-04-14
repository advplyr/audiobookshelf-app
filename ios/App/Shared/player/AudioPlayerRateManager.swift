//
//  AudioPlayerRateManager.swift
//  Audiobookshelf
//
//  Created by Marke Hallowell on 4/14/24.
//

import Foundation
import AVFoundation

protocol AudioPlayerRateManager {
    var rate: Float { get }
    var defaultRate: Float { get }
    var rateChangedCompletion: () -> Void { get set }
    var defaultRateChangedCompletion: () -> Void { get set }
    
    init(audioPlayer: AVPlayer, defaultRate: Float)
    
    func setPlaybackRate(_ rate: Float)

    // Callback for play events (e.g. LegacyAudioPlayerRateManager uses this set rate immediately after playback resumes)
    func handlePlayEvent() -> Void
}
