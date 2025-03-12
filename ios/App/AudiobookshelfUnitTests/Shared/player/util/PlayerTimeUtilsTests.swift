//
//  PlayerTimeUtilsTests.swift
//  AudiobookshelfUnitTests
//
//  Created by Ron Heft on 9/20/22.
//

import XCTest
@testable import Audiobookshelf

final class PlayerTimeUtilsTests: XCTestCase {
    
    func testCalcSeekBackTime() {
        let currentTime: Double = 1000
        
        // 1. Nil lastPlayedMs → should seek back 5s
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: nil), 1000)
        
        // 2. Played ~2s ago (<6s) → should seek back 2s
        let played2sAgo = Date(timeIntervalSinceNow: -2).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played2sAgo), 1000)
        
        // 3. Played ~12s ago (6-12s range) → should seek back 10s
        let played12sAgo = Date(timeIntervalSinceNow: -12).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played12sAgo), 997)
        
        // 4. Played ~62s ago (12-30s range) → should seek back 15s
        let played62sAgo = Date(timeIntervalSinceNow: -62).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played62sAgo), 990)
        
        // 5. Played ~302s ago (30-180s range) → should seek back 20s
        let played302sAgo = Date(timeIntervalSinceNow: -302).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played302sAgo), 980)
        
        // 6. Played ~1802s ago (180-3600s range) → should seek back 25s
        let played1802sAgo = Date(timeIntervalSinceNow: -1802).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played1802sAgo), 970)
        
        // 8. Edge case where currentTime is small and would go negative
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: 1, lastPlayedMs: played12sAgo), 0)
        
        // 9. Edge case: negative lastPlayedMs (should be treated as an old timestamp)
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: -5000), 970)
      
    }
    
    func testCalcSeekBackTimeWithZeroCurrentTime() {
        let currentTime: Double = 0
        let threeHundredSecondsAgo = Date(timeIntervalSinceNow: -300)
        let lastPlayedMs = threeHundredSecondsAgo.timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: lastPlayedMs), 0)
    }

    func testTimeSinceLastPlayed() throws {
        let fiveSecondsAgo = Date(timeIntervalSinceNow: -5)
        let lastPlayedMs = fiveSecondsAgo.timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.timeSinceLastPlayed(lastPlayedMs)!, 5, accuracy: 1.0)
        XCTAssertNil(PlayerTimeUtils.timeSinceLastPlayed(nil))
    }
    
    func testTimeToSeekBackForSinceLastPlayed() throws {
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(nil), 0, "Seeks back 0 seconds for nil")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(5), 0, "Seeks back 0 seconds for less than 10 seconds")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(11), 3, "Seeks back 3 seconds for less than 1 minute")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(298), 10, "Seeks back 10 seconds for less than 5 minutes")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(1798), 20, "Seeks back 20 seconds for less than 30 minutes")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(3599), 30, "Seeks back 30 seconds for greater than 30 minutes")

    }

}
