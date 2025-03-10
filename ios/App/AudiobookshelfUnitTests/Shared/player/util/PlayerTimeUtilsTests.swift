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
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: nil), 995)
        
        // 2. Played ~3s ago (<6s) → should seek back 2s
        let played3sAgo = Date(timeIntervalSinceNow: -3).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played3sAgo), 998)
        
        // 3. Played ~8s ago (6-12s range) → should seek back 10s
        let played8sAgo = Date(timeIntervalSinceNow: -8).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played8sAgo), 990)
        
        // 4. Played ~20s ago (12-30s range) → should seek back 15s
        let played20sAgo = Date(timeIntervalSinceNow: -20).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played20sAgo), 985)
        
        // 5. Played ~60s ago (30-180s range) → should seek back 20s
        let played60sAgo = Date(timeIntervalSinceNow: -60).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played60sAgo), 980)
        
        // 6. Played ~300s ago (180-3600s range) → should seek back 25s
        let played300sAgo = Date(timeIntervalSinceNow: -300).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played300sAgo), 975)
        
        // 7. Played ~4000s ago (>3600s range) → should seek back 29s
        let played4000sAgo = Date(timeIntervalSinceNow: -4000).timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: played4000sAgo), 971)
        
        // 8. Edge case where currentTime is small and would go negative
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: 3, lastPlayedMs: played3sAgo), 1)
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: 1, lastPlayedMs: played3sAgo), 0)
        
        // 9. Edge case: negative lastPlayedMs (should be treated as an old timestamp)
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: -5000), 971)
      
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
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(nil), 5, "Seeks back 5 seconds for nil")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(5), 2, "Seeks back 2 seconds for less than 6 seconds")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(11), 10, "Seeks back 10 seconds for less than 12 seconds")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(29), 15, "Seeks back 15 seconds for less than 30 seconds")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(179), 20, "Seeks back 20 seconds for less than 2 minutes")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(3599), 25, "Seeks back 25 seconds for less than 59 minutes")
        XCTAssertEqual(PlayerTimeUtils.timeToSeekBackForSinceLastPlayed(60000), 29, "Seeks back 29 seconds for anything over 59 minuts")
    }

}
