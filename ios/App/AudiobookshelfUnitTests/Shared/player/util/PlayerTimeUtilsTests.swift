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
        let threeSecondsAgo = Date(timeIntervalSinceNow: -3)
        let lastPlayedMs = threeSecondsAgo.timeIntervalSince1970 * 1000
        XCTAssertEqual(PlayerTimeUtils.calcSeekBackTime(currentTime: currentTime, lastPlayedMs: lastPlayedMs), 998)
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
        XCTAssertEqual(PlayerTimeUtils.timeSinceLastPlayed(lastPlayedMs)!, -5, accuracy: 1.0)
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
