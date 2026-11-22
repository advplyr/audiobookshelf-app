//
//  MediaProgressConflictResolverTests.swift
//  AudiobookshelfUnitTests
//

import XCTest
@testable import Audiobookshelf

final class MediaProgressConflictResolverTests: XCTestCase {
    func testKeepsFurtherLocalProgressWhenServerTimestampIsNewer() {
        let result = MediaProgressConflictResolver.resolveAudioProgress(
            localCurrentTime: 900,
            localIsFinished: false,
            localLastUpdate: 100,
            serverCurrentTime: 300,
            serverIsFinished: false,
            serverLastUpdate: 200
        )

        XCTAssertEqual(result, .localToServer)
    }

    func testAcceptsFurtherServerProgressWhenLocalTimestampIsNewer() {
        let result = MediaProgressConflictResolver.resolveAudioProgress(
            localCurrentTime: 300,
            localIsFinished: false,
            localLastUpdate: 200,
            serverCurrentTime: 900,
            serverIsFinished: false,
            serverLastUpdate: 100
        )

        XCTAssertEqual(result, .serverToLocal)
    }

    func testKeepsRecentLocalFinishedStateWhenCurrentTimeWasReset() {
        let result = MediaProgressConflictResolver.resolveAudioProgress(
            localCurrentTime: 0,
            localIsFinished: true,
            localLastUpdate: 200,
            serverCurrentTime: 900,
            serverIsFinished: false,
            serverLastUpdate: 100
        )

        XCTAssertEqual(result, .localToServer)
    }

    func testKeepsRecentLocalRestartOfFinishedMedia() {
        let result = MediaProgressConflictResolver.resolveAudioProgress(
            localCurrentTime: 0,
            localIsFinished: false,
            localLastUpdate: 200,
            serverCurrentTime: 0,
            serverIsFinished: true,
            serverLastUpdate: 100
        )

        XCTAssertEqual(result, .localToServer)
    }

    func testSkipsEqualProgress() {
        let result = MediaProgressConflictResolver.resolveAudioProgress(
            localCurrentTime: 900,
            localIsFinished: false,
            localLastUpdate: 100,
            serverCurrentTime: 900,
            serverIsFinished: false,
            serverLastUpdate: 200
        )

        XCTAssertEqual(result, .none)
    }
}
