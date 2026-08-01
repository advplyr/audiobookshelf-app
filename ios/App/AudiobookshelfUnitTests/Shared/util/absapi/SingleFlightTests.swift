//
//  SingleFlightTests.swift
//  AudiobookshelfUnitTests
//
//  Verifies SingleFlight coalesces concurrent calls into one operation run, while sequential
//  calls each run.
//

import XCTest
@testable import Audiobookshelf

private actor InvocationCounter {
    private(set) var count = 0
    /// Increments, then yields a few times so concurrent callers have a chance to coalesce onto
    /// the same in-flight run before this returns.
    func tick() async -> Int {
        count += 1
        let c = count
        await Task.yield()
        await Task.yield()
        return c
    }
}

final class SingleFlightTests: XCTestCase {

    func testConcurrentCallersShareOneRun() async {
        let sf = SingleFlight<Int>()
        let counter = InvocationCounter()

        async let a = sf.run { await counter.tick() }
        async let b = sf.run { await counter.tick() }
        async let c = sf.run { await counter.tick() }
        let results = await [a, b, c]

        let count = await counter.count
        XCTAssertEqual(count, 1, "operation should run exactly once for concurrent callers")
        XCTAssertEqual(results, [1, 1, 1], "all concurrent callers get the shared result")
    }

    func testSequentialCallersEachRun() async {
        let sf = SingleFlight<Int>()
        let counter = InvocationCounter()

        let r1 = await sf.run { await counter.tick() }
        let r2 = await sf.run { await counter.tick() }

        XCTAssertEqual(r1, 1)
        XCTAssertEqual(r2, 2, "a call after the previous run finished starts a fresh run")
        let count = await counter.count
        XCTAssertEqual(count, 2)
    }
}
