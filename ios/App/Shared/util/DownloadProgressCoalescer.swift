//
//  DownloadProgressCoalescer.swift
//  App
//
//  Created by advplyr on 10/8/26.
//

import Foundation

/// Accumulates per-chunk download progress in memory so the URLSession delegate
/// callback does no Realm writes or bridge events per chunk. A timer drains it.
struct DownloadProgressCoalescer {
    struct Progress: Equatable {
        var bytesWritten: Double
        var expectedBytes: Double
        var percent: Double
    }
    private(set) var pending: [String: Progress] = [:]

    mutating func record(partId: String, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let percent = (Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) * 100
        guard percent >= 0.0 && percent <= 100.0 else { return }
        pending[partId] = Progress(bytesWritten: Double(totalBytesWritten), expectedBytes: Double(totalBytesExpectedToWrite), percent: percent)
    }

    var hasPendingUpdates: Bool { !pending.isEmpty }

    mutating func drain() -> [String: Progress] {
        let drained = pending
        pending.removeAll()
        return drained
    }
}
