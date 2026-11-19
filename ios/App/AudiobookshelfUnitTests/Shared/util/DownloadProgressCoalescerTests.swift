//
//  DownloadProgressCoalescerTests.swift
//  AudiobookshelfUnitTests
//
//  Created by advplyr on 10/8/26.
//

import XCTest
@testable import Audiobookshelf

final class DownloadProgressCoalescerTests: XCTestCase {

    func testRecordKeepsLatestProgressPerPart() {
        var coalescer = DownloadProgressCoalescer()

        coalescer.record(partId: "part-1", totalBytesWritten: 100, totalBytesExpectedToWrite: 1000)
        coalescer.record(partId: "part-1", totalBytesWritten: 500, totalBytesExpectedToWrite: 1000)

        XCTAssertEqual(coalescer.pending.count, 1)
        XCTAssertEqual(coalescer.pending["part-1"], DownloadProgressCoalescer.Progress(bytesWritten: 500, expectedBytes: 1000, percent: 50))
    }

    func testRecordRejectsInvalidPercent() {
        var coalescer = DownloadProgressCoalescer()

        // Unknown expected size yields NaN percent
        coalescer.record(partId: "part-1", totalBytesWritten: 10, totalBytesExpectedToWrite: 0)
        // More bytes written than expected yields percent > 100
        coalescer.record(partId: "part-2", totalBytesWritten: 150, totalBytesExpectedToWrite: 100)

        XCTAssertTrue(coalescer.pending.isEmpty)
    }

    func testDrainReturnsAllPendingAndClears() {
        var coalescer = DownloadProgressCoalescer()
        coalescer.record(partId: "part-1", totalBytesWritten: 100, totalBytesExpectedToWrite: 1000)

        let drained = coalescer.drain()

        XCTAssertEqual(drained.count, 1)
        XCTAssertEqual(drained["part-1"]?.bytesWritten, 100)
        XCTAssertEqual(drained["part-1"]?.expectedBytes, 1000)
        XCTAssertEqual(drained["part-1"]?.percent, 10)
        XCTAssertTrue(coalescer.pending.isEmpty)
        XCTAssertFalse(coalescer.hasPendingUpdates)
    }

    func testPartsAccumulateIndependently() {
        var coalescer = DownloadProgressCoalescer()

        coalescer.record(partId: "part-1", totalBytesWritten: 100, totalBytesExpectedToWrite: 1000)
        coalescer.record(partId: "part-2", totalBytesWritten: 250, totalBytesExpectedToWrite: 500)

        let drained = coalescer.drain()

        XCTAssertEqual(drained.count, 2)
        XCTAssertEqual(drained["part-1"]?.percent, 10)
        XCTAssertEqual(drained["part-2"]?.percent, 50)
    }
}
