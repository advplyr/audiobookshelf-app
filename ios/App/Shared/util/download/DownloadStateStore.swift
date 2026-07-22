//
//  DownloadStateStore.swift
//  Audiobookshelf
//
//  Download state that has to survive the app process: URLSession resume data and retry attempt
//  counts, keyed by download part id.
//

import Foundation
import CryptoKit

/// Kept on disk rather than in Realm: resume-data blobs are megabytes (they don't belong in the
/// database), and a file sidecar needs no schema migration for existing installs.
///
/// Solves two halves of the same problem. Retry counts used to live only in memory, so every relaunch
/// reset them — a part could cycle "attempt 1/3" forever, never completing and never failing. And
/// resume data was dropped on the floor when a part gave up, so `reconcilePersistedDownloads` had
/// nothing to continue from and restarted the file from zero. A book too large to finish in one app
/// session could therefore never finish at all.
final class DownloadStateStore {

    private let directory: URL
    private let fileManager = FileManager.default

    init(directory: URL) {
        self.directory = directory
    }

    // MARK: - Resume data

    func saveResumeData(_ data: Data, forPartId partId: String) {
        ensureDirectory()
        try? data.write(to: fileURL(partId, "resume"), options: .atomic)
    }

    func resumeData(forPartId partId: String) -> Data? {
        try? Data(contentsOf: fileURL(partId, "resume"))
    }

    // MARK: - Attempt counts

    func attempts(forPartId partId: String) -> Int {
        guard let raw = try? String(contentsOf: fileURL(partId, "attempts"), encoding: .utf8) else { return 0 }
        return Int(raw.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
    }

    @discardableResult
    func recordAttempt(forPartId partId: String) -> Int {
        let next = attempts(forPartId: partId) + 1
        ensureDirectory()
        try? "\(next)".write(to: fileURL(partId, "attempts"), atomically: true, encoding: .utf8)
        return next
    }

    // MARK: - Lifecycle

    /// Called when a part finishes, or when it makes real forward progress again (a recovered
    /// connection shouldn't leave a part one blip away from permanent failure).
    func clear(forPartId partId: String) {
        try? fileManager.removeItem(at: fileURL(partId, "resume"))
        try? fileManager.removeItem(at: fileURL(partId, "attempts"))
    }

    // MARK: - Private

    /// Part ids are base64 of "<itemId>/<filename>", so they contain '/' and '+' and can't be used as
    /// filenames. Hashing also bounds the length, which raw encoding would not.
    private func fileURL(_ partId: String, _ ext: String) -> URL {
        let digest = SHA256.hash(data: Data(partId.utf8))
        let name = digest.map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("\(name).\(ext)")
    }

    private func ensureDirectory() {
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    }
}
