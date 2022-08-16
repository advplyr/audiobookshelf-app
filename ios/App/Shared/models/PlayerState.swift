//
//  PlayerState.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation

enum PlayerState: Codable {
    case IDLE
    case BUFFERING
    case READY
    case ENDED
}
