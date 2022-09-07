//
//  PlaybackMetadata.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation

class PlaybackMetadata: Codable {
    var duration: Double = 0
    var currentTime: Double = 0
    var playerState: PlayerState = PlayerState.IDLE
}
