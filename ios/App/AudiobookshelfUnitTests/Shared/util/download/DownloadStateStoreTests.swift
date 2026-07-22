//
//  DownloadStateStoreTests.swift
//  AudiobookshelfUnitTests
//

import XCTest
@testable import Audiobookshelf

final class DownloadStateStoreTests: XCTestCase {

    private var directory: URL!
    private var store: DownloadStateStore!

    // Real part ids are base64 of "<itemId>/<filename>", so they contain '/' and '+' and cannot be
    // used as filenames as-is.
    private let partId = "MjAwNjJiNWYtY2JhOC00MDFjL2ZpbGUrbmFtZS5mbGFj/x+y="

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("DownloadStateStoreTests-\(UUID().uuidString)")
        store = DownloadStateStore(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testResumeDataRoundTripsForAPartIdThatIsNotFilenameSafe() {
        let data = Data("partial-bytes".utf8)
        store.saveResumeData(data, forPartId: partId)
        XCTAssertEqual(store.resumeData(forPartId: partId), data)
    }

    func testDistinctPartIdsDoNotCollide() {
        store.saveResumeData(Data("a".utf8), forPartId: "part/one")
        store.saveResumeData(Data("b".utf8), forPartId: "part+one")
        XCTAssertEqual(store.resumeData(forPartId: "part/one"), Data("a".utf8))
        XCTAssertEqual(store.resumeData(forPartId: "part+one"), Data("b".utf8))
    }

    func testMissingResumeDataIsNil() {
        XCTAssertNil(store.resumeData(forPartId: partId))
    }

    // Attempt counts were in-memory only, so every relaunch reset them (log: "attempt 2/3" at 23:55,
    // then "attempt 1/3" again at 11:55 the next morning). A part could loop forever, never completing
    // and never failing.
    func testAttemptsPersistAcrossStoreInstances() {
        XCTAssertEqual(store.attempts(forPartId: partId), 0)
        XCTAssertEqual(store.recordAttempt(forPartId: partId), 1)
        XCTAssertEqual(store.recordAttempt(forPartId: partId), 2)

        let reopened = DownloadStateStore(directory: directory)
        XCTAssertEqual(reopened.attempts(forPartId: partId), 2)
        XCTAssertEqual(reopened.recordAttempt(forPartId: partId), 3)
    }

    // Forward progress means the connection recovered; a part shouldn't die because of three unrelated
    // blips spread over a multi-hour download.
    func testClearingResetsAttemptsAndResumeData() {
        store.saveResumeData(Data("x".utf8), forPartId: partId)
        _ = store.recordAttempt(forPartId: partId)

        store.clear(forPartId: partId)

        XCTAssertEqual(store.attempts(forPartId: partId), 0)
        XCTAssertNil(store.resumeData(forPartId: partId))
    }

    func testClearingOnePartLeavesOthersIntact() {
        store.saveResumeData(Data("keep".utf8), forPartId: "other")
        _ = store.recordAttempt(forPartId: "other")

        store.clear(forPartId: partId)

        XCTAssertEqual(store.resumeData(forPartId: "other"), Data("keep".utf8))
        XCTAssertEqual(store.attempts(forPartId: "other"), 1)
    }

    func testSavingResumeDataTwiceOverwrites() {
        store.saveResumeData(Data("first".utf8), forPartId: partId)
        store.saveResumeData(Data("second".utf8), forPartId: partId)
        XCTAssertEqual(store.resumeData(forPartId: partId), Data("second".utf8))
    }
}
