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
        let currentTimeAfterSeekBack = currentTime.advanced(by: -timeToSeekBack)
        return max(currentTimeAfterSeekBack, 0)
    }
    
    static internal func timeSinceLastPlayed(_ lastPlayedMs: Double?) -> TimeInterval? {
        guard let lastPlayedMs = lastPlayedMs else { return nil }
        let lastPlayed = Date(timeIntervalSince1970: lastPlayedMs / 1000)
        return Date().timeIntervalSince(lastPlayed)
    }
    
    static internal func timeToSeekBackForSinceLastPlayed(_ sinceLastPlayed: TimeInterval?) -> TimeInterval {
        if isAutoRewindDisabled(){
          return 0
        }
        if let sinceLastPlayed = sinceLastPlayed {
          if sinceLastPlayed < 10 { return 0 } // 10s or less = no seekback
          else if sinceLastPlayed < 60 { return 3 } // 10s to 1m = jump back 3s
          else if sinceLastPlayed < 300 { return 10 } // 1m to 5m = jump back 10s
          else if sinceLastPlayed < 1800 { return 20 } // 5m to 30m = jump back 20s
          else { return 30 } // 30m and up = jump back 30s
        } else {
            return 0
        }
    }
    
    static internal func isAutoRewindDisabled() -> Bool {
      let deviceSettings = Database.shared.getDeviceSettings()
      return deviceSettings.disableAutoRewind
    }
    
}
