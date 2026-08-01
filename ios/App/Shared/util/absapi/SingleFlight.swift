//
//  SingleFlight.swift
//  Audiobookshelf
//
//  Coalesces concurrent invocations of an async operation into a single in-flight run: while an
//  operation is running, additional callers await the same result instead of starting their own.
//  Used to serialize token refresh so a burst of concurrent 401s produces exactly one
//  /auth/refresh round-trip (avoiding a refresh-token rotation race).
//

import Foundation

actor SingleFlight<T: Sendable> {
    private var inFlight: Task<T, Never>?

    /// Run `operation`, or — if one is already in flight — await that existing run's result.
    func run(_ operation: @Sendable @escaping () async -> T) async -> T {
        if let inFlight {
            return await inFlight.value
        }
        let task = Task { await operation() }
        inFlight = task
        let result = await task.value
        inFlight = nil
        return result
    }
}
