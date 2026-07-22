//
//  StallEvaluatorTests.swift
//  AudiobookshelfUnitTests
//

import XCTest
@testable import Audiobookshelf

final class StallEvaluatorTests: XCTestCase {

    private let checkInterval: TimeInterval = 10
    private let stallTimeout: TimeInterval = 30
    private let now = Date(timeIntervalSince1970: 1_000_000)

    private func candidate(_ id: String, idleFor: TimeInterval, graceUntil: Date? = nil) -> StallCandidate {
        StallCandidate(partId: id, lastProgressAt: now.addingTimeInterval(-idleFor), graceUntil: graceUntil)
    }

    private func evaluate(lastTick: Date?, _ candidates: [StallCandidate]) -> StallDecision {
        StallEvaluator.evaluate(now: now, lastTick: lastTick, checkInterval: checkInterval,
                                stallTimeout: stallTimeout, candidates: candidates)
    }

    func testGenuinelyStalledDownloadIsCancelled() {
        let decision = evaluate(lastTick: now.addingTimeInterval(-checkInterval),
                                [candidate("stuck", idleFor: 45)])
        XCTAssertEqual(decision, .cancel(["stuck"]))
    }

    func testHealthyDownloadIsLeftAlone() {
        let decision = evaluate(lastTick: now.addingTimeInterval(-checkInterval),
                                [candidate("fine", idleFor: 5)])
        XCTAssertEqual(decision, .cancel([]))
    }

    // THE bug behind every stall burst in the device log: the watchdog is a main-run-loop Timer, so it
    // stops while the app is suspended, but lastProgressAt is wall-clock. On resume every healthy
    // in-flight task looked ">30s idle" and got cancelled — burning retries and restarting big files
    // from byte 0. A tick that arrives long after the previous one means "we were suspended", not
    // "everything stalled".
    func testSuspensionGapRebaselinesInsteadOfCancellingEverything() {
        let decision = evaluate(lastTick: now.addingTimeInterval(-3600),
                                [candidate("a", idleFor: 3600), candidate("b", idleFor: 3600)])
        XCTAssertEqual(decision, .rebaseline)
    }

    func testGapJustOverOneIntervalStillEvaluatesNormally() {
        // Normal timer jitter must NOT be mistaken for a suspension.
        let decision = evaluate(lastTick: now.addingTimeInterval(-(checkInterval + 1)),
                                [candidate("stuck", idleFor: 45)])
        XCTAssertEqual(decision, .cancel(["stuck"]))
    }

    // Tasks adopted from a previous launch haven't delivered a progress callback yet; the background
    // session may take a while to start reporting. Without a grace period they were cancelled 30s
    // after every launch (log: 13:07:13 launch -> 13:07:43 "Stall detected").
    func testAdoptedTaskInsideGracePeriodIsNotCancelled() {
        let decision = evaluate(lastTick: now.addingTimeInterval(-checkInterval),
                                [candidate("adopted", idleFor: 600,
                                           graceUntil: now.addingTimeInterval(60))])
        XCTAssertEqual(decision, .cancel([]))
    }

    func testAdoptedTaskIsCancelledOnceGraceExpires() {
        let decision = evaluate(lastTick: now.addingTimeInterval(-checkInterval),
                                [candidate("adopted", idleFor: 600,
                                           graceUntil: now.addingTimeInterval(-1))])
        XCTAssertEqual(decision, .cancel(["adopted"]))
    }

    func testFirstTickEvaluatesNormally() {
        XCTAssertEqual(evaluate(lastTick: nil, [candidate("stuck", idleFor: 45)]), .cancel(["stuck"]))
    }

    func testOnlyStalledPartsAreReported() {
        let decision = evaluate(lastTick: now.addingTimeInterval(-checkInterval),
                                [candidate("fine", idleFor: 1), candidate("stuck", idleFor: 31)])
        XCTAssertEqual(decision, .cancel(["stuck"]))
    }
}
