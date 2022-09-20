//
//  PlayerTimeUtils.swift
//  Audiobookshelf
//
//  Created by Ron Heft on 9/20/22.
//

import Foundation

class PlayerTimeUtils {
    
    private init() {}
    
    static func calcSeekBackTime(currentTime: TimeInterval, lastPlayedMs: Double?) -> TimeInterval {
        let sinceLastPlayed = timeSinceLastPlayed(lastPlayedMs)
        let timeToSeekBack = timeToSeekBackForSinceLastPlayed(sinceLastPlayed)
        return currentTime.advanced(by: -timeToSeekBack)
    }
    
    static internal func timeSinceLastPlayed(_ lastPlayedMs: Double?) -> TimeInterval? {
        guard let lastPlayedMs = lastPlayedMs else { return nil }
        let lastPlayed = Date(timeIntervalSince1970: lastPlayedMs / 1000)
        return lastPlayed.timeIntervalSinceNow
    }
    
    static internal func timeToSeekBackForSinceLastPlayed(_ sinceLastPlayed: TimeInterval?) -> TimeInterval {
        if let sinceLastPlayed = sinceLastPlayed {
            if sinceLastPlayed < 6 {
                return 2
            } else if sinceLastPlayed < 12 {
                return 10
            } else if sinceLastPlayed < 30 {
                return 15
            } else if sinceLastPlayed < 180 {
                return 20
            } else if sinceLastPlayed < 3600 {
                return 25
            } else {
                return 29
            }
        } else {
            return 5
        }
    }
    
}
