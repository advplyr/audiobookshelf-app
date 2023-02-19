//
//  PlayerState.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation

enum PlayerState: String, Codable {
    case idle = "IDLE"
    case buffering = "BUFFERING"
    case ready = "READY"
    case ended = "ENDED"
}
