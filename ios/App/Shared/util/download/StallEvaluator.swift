//
//  StallEvaluator.swift
//  Audiobookshelf
//
//  Decides which in-flight downloads have genuinely stalled — and, crucially, when the question
//  can't be answered because the app wasn't running.
//

import Foundation

struct StallCandidate {
    let partId: String
    let lastProgressAt: Date
    /// While set and in the future, this download is exempt from stall detection.
    let graceUntil: Date?
}

enum StallDecision: Equatable {
    /// Too much wall-clock time passed since the previous check for the timestamps to mean anything —
    /// the app was suspended. Re-baseline every download's progress clock and cancel nothing.
    case rebaseline
    /// Part ids that have genuinely produced no data for longer than the stall timeout.
    case cancel([String])
}

enum StallEvaluator {

    /// The stall watchdog is a main-run-loop `Timer`, so it stops dead while the app is suspended — but
    /// `lastProgressAt` is wall-clock. The moment the app resumed, every healthy in-flight download
    /// looked like it had produced no data for hours and was cancelled. On a real device this fired in
    /// bursts after every single launch/foreground, burning retry budget and (with no resume data)
    /// restarting multi-hundred-megabyte files from byte zero — which is why large books never finished.
    ///
    /// A tick that arrives long after the previous one means "we were suspended", not "everything stalled".
    static func evaluate(now: Date,
                         lastTick: Date?,
                         checkInterval: TimeInterval,
                         stallTimeout: TimeInterval,
                         candidates: [StallCandidate]) -> StallDecision {
        if let lastTick = lastTick, now.timeIntervalSince(lastTick) > checkInterval * 2 {
            return .rebaseline
        }

        let stalled = candidates.filter { candidate in
            // Adopted tasks and freshly re-baselined ones haven't had a chance to report yet.
            if let graceUntil = candidate.graceUntil, now < graceUntil { return false }
            return now.timeIntervalSince(candidate.lastProgressAt) > stallTimeout
        }

        return .cancel(stalled.map(\.partId))
    }
}
