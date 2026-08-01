//
//  ABSApi.swift
//  Audiobookshelf
//
//  Facade for endpoints served by the generated ABSApiClient. Each method resolves the active
//  server config, calls a package operation wrapper, and maps the returned DTO/Data to the Realm
//  model the app expects. The app never holds a generated `Client` (that would link OpenAPIRuntime);
//  all client interaction stays in the package, which hands back plain DTOs.
//
//  The two read endpoints (getCurrentUser, getMediaProgress) fetch the raw JSON and decode it into
//  the Realm `User`/`MediaProgress` models with their own lenient `Codable` (which tolerates numeric
//  fields the server has historically returned as strings). The decode happens ON THE MAIN THREAD,
//  because the result is a Realm object graph — matching how the player/downloader consume the
//  other endpoints' objects.
//

import Foundation
import UIKit
import ABSApiClient

enum ABSApi {
    /// GET /api/me → Realm `User` (decoded on the main thread), or nil on failure.
    static func getCurrentUser() async -> User? {
        guard let config = ABSClientProvider.config else {
            AbsLogger.error(message: "ABSApi.getCurrentUser: no server configured")
            return nil
        }
        guard let data = await ABSApiClient.fetchCurrentUserData(config: config) else {
            AbsLogger.error(message: "ABSApi.getCurrentUser: request failed")
            return nil
        }
        return await MainActor.run {
            do { return try JSONDecoder().decode(User.self, from: data) }
            catch { AbsLogger.error(message: "ABSApi.getCurrentUser: decode failed: \(error)"); return nil }
        }
    }

    /// GET /api/me/progress/{id}[/{episodeId}] → Realm `MediaProgress` (decoded on the main thread),
    /// or nil when there is no progress (404) or on failure.
    static func getMediaProgress(libraryItemId: String, episodeId: String?) async -> MediaProgress? {
        guard let config = ABSClientProvider.config else { return nil }
        guard let data = await ABSApiClient.fetchMediaProgressData(config: config, libraryItemId: libraryItemId, episodeId: episodeId) else {
            return nil
        }
        return await MainActor.run {
            do { return try JSONDecoder().decode(MediaProgress.self, from: data) }
            catch { AbsLogger.error(message: "ABSApi.getMediaProgress: decode failed: \(error)"); return nil }
        }
    }

    /// GET /api/items/{id}?expanded=1&include=progress → raw JSON body, which the caller decodes into
    /// a Realm `LibraryItem` on the main thread using the model's own lenient decoder. Nil on failure.
    static func getLibraryItemData(libraryItemId: String, episodeId: String?) async -> Data? {
        guard let config = ABSClientProvider.config else { return nil }
        return await ABSApiClient.fetchLibraryItemData(config: config, libraryItemId: libraryItemId, episodeId: episodeId)
    }

    // MARK: - Writes

    /// PATCH /api/me/progress/{id}[/{ep}]. The generic payload (e.g. ["isFinished": true]) is
    /// re-encoded into the typed mediaProgressUpdate DTO. Returns true on success.
    static func updateMediaProgress<T: Encodable>(libraryItemId: String, episodeId: String?, payload: T) async -> Bool {
        guard let config = ABSClientProvider.config else { return false }
        let update: Components.Schemas.mediaProgressUpdate
        do {
            update = try JSONDecoder().decode(Components.Schemas.mediaProgressUpdate.self, from: try JSONEncoder().encode(payload))
        } catch {
            AbsLogger.error(message: "ABSApi.updateMediaProgress: failed to build update DTO: \(error)")
            return false
        }
        return await ABSApiClient.updateMediaProgress(config: config, libraryItemId: libraryItemId, episodeId: episodeId, update: update)
    }

    /// POST /api/session/{sessionId}/sync — progress heartbeat for an open server session.
    static func reportPlaybackProgress(report: PlaybackReport, sessionId: String) async -> Bool {
        guard let config = ABSClientProvider.config else { return false }
        let dto = Components.Schemas.playbackReport(currentTime: report.currentTime, duration: report.duration, timeListened: report.timeListened)
        return await ABSApiClient.syncPlaybackSession(config: config, sessionId: sessionId, report: dto)
    }

    /// POST /api/session/local — sync a single locally-recorded session. `session` must be frozen.
    static func reportLocalPlaybackProgress(_ session: PlaybackSession) async -> Bool {
        guard let config = ABSClientProvider.config else { return false }
        return await ABSApiClient.syncLocalPlaybackSession(config: config, session: session.toDTO())
    }

    /// POST /api/session/local-all — bulk-sync offline sessions. Sessions must be frozen.
    static func reportAllLocalPlaybackSessions(_ sessions: [PlaybackSession]) async -> Bool {
        guard let config = ABSClientProvider.config else { return false }
        let body = Components.Schemas.localPlaybackSessionSyncAll(
            sessions: sessions.map { $0.toDTO() },
            deviceInfo: PlaybackSession.deviceInfoDTO(from: sessions.first?.deviceInfo)
        )
        return await ABSApiClient.syncAllLocalPlaybackSessions(config: config, body: body)
    }

    /// POST /api/items/{id}/play[/{episodeId}] → the raw playback session DTO, or nil on failure.
    ///
    /// Returns the DTO (not a Realm object) on purpose: the caller maps it to a Realm
    /// `PlaybackSession` on the MAIN thread, because that object is immediately saved to Realm and
    /// consumed by the player there — Realm object graphs must be built and used on one thread.
    ///
    /// The generated play response models `libraryItem` as a freeform object (unused; mapped to
    /// nil). On servers ≥ 2.22.0 directplay uses `/public/session/{id}/track/{index}` and transcode
    /// uses the track `contentUrl`, both covered by the mapped audioTracks; the `libraryItem` `ino`
    /// (only needed for < 2.22.0 directplay + local fallback) is intentionally not carried.
    static func startPlaybackSessionDTO(libraryItemId: String, episodeId: String?, forceTranscode: Bool) async -> Components.Schemas.playbackSession? {
        guard let config = ABSClientProvider.config else {
            AbsLogger.error(message: "ABSApi.startPlaybackSession: no server configured")
            return nil
        }
        let request = Components.Schemas.playbackSessionRequest(
            forceDirectPlay: forceTranscode ? ._empty : ._1,
            forceTranscode: forceTranscode ? ._1 : ._empty,
            mediaPlayer: "AVPlayer",
            deviceInfo: Self.deviceInfoRequest()
        )
        guard let dto = await ABSApiClient.startPlaybackSession(config: config, libraryItemId: libraryItemId, episodeId: episodeId, request: request) else {
            AbsLogger.error(message: "ABSApi.startPlaybackSession: request failed")
            return nil
        }
        return dto
    }

    /// Build the deviceInfoRequest the client sends when starting a session (the server enriches
    /// the rest). Mirrors the device info the legacy ApiClient.startPlaybackSession sent.
    private static func deviceInfoRequest() -> Components.Schemas.deviceInfoRequest {
        var systemInfo = utsname()
        uname(&systemInfo)
        let modelCode = withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(validatingUTF8: $0) }
        }
        return Components.Schemas.deviceInfoRequest(
            deviceId: UIDevice.current.identifierForVendor?.uuidString,
            clientVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String,
            manufacturer: "Apple",
            model: modelCode
        )
    }
}
