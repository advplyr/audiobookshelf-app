//
//  PlaybackMetadata.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation

struct PlaybackMetadata: Codable {
    let duration: Double
    let currentTime: Double
    let playerState: PlayerState
}
