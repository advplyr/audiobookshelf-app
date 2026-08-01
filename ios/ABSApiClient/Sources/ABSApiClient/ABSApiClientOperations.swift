//
//  ABSApiClientOperations.swift
//  ABSApiClient
//
//  High-level, app-friendly wrappers around the generated operations. They build a refresh-aware
//  client internally and return plain DTOs (Components.Schemas.*) or Data, so the app can call them
//  without ever holding a `Client` or otherwise linking OpenAPIRuntime symbols. Each wrapper returns
//  nil / false on any failure (network, non-2xx, or strict-decode).
//

import Foundation

extension ABSApiClient {
    /// Run one operation against a fresh refresh-aware client, mapping any thrown error to nil and
    /// reporting it to `config.diagnostics`. Every wrapper below is a one-liner over this helper.
    private static func perform<T>(
        _ config: ABSClientConfig,
        _ call: @Sendable (Client) async throws -> T?
    ) async -> T? {
        do { return try await call(makeRefreshAwareClient(config: config)) }
        catch {
            config.diagnostics?(String(describing: error))
            return nil
        }
    }

    // MARK: - Reads

    /// GET /api/me — raw JSON body (freeform), for the app to decode with its own lenient `User`
    /// model. Returns nil on failure.
    public static func fetchCurrentUserData(config: ABSClientConfig) async -> Data? {
        await perform(config) { client in
            guard case let .ok(ok) = try await client.getCurrentUser() else { return nil }
            return try JSONEncoder().encode(ok.body.json)
        }
    }

    /// GET /api/me/progress/{libraryItemId}[/{episodeId}] — raw JSON body (freeform), for the app to
    /// decode with its own lenient `MediaProgress` model. Nil when there is no progress (404) or on
    /// failure.
    public static func fetchMediaProgressData(config: ABSClientConfig, libraryItemId: String, episodeId: String?) async -> Data? {
        await perform(config) { client in
            if let episodeId, !episodeId.isEmpty {
                guard case let .ok(ok) = try await client.getPodcastEpisodeMediaProgress(.init(path: .init(libraryItemId: libraryItemId, episodeId: episodeId))) else { return nil }
                return try JSONEncoder().encode(ok.body.json)
            } else {
                guard case let .ok(ok) = try await client.getMediaProgress(.init(path: .init(libraryItemId: libraryItemId))) else { return nil }
                return try JSONEncoder().encode(ok.body.json)
            }
        }
    }

    /// GET /api/items/{id}?expanded=1&include=progress[&episodeId=…] — the raw JSON body of the
    /// (freeform) library item, for the app to decode with its own lenient model. The expanded item
    /// is a large tree; typing it here would be brittle, so this op only provides the authenticated,
    /// refresh-aware request. Returns nil on failure.
    public static func fetchLibraryItemData(config: ABSClientConfig, libraryItemId: String, episodeId: String?) async -> Data? {
        await perform(config) { client in
            guard case let .ok(ok) = try await client.getLibraryItem(.init(
                path: .init(id: libraryItemId),
                query: .init(expanded: "1", include: "progress", episodeId: episodeId)
            )) else { return nil }
            // Re-serialize the freeform object to JSON, preserving exact values (including
            // numbers-as-strings the lenient model still tolerates).
            return try JSONEncoder().encode(ok.body.json)
        }
    }

    // MARK: - Writes

    /// PATCH /api/me/progress/{libraryItemId}[/{episodeId}] with a partial progress update.
    public static func updateMediaProgress(config: ABSClientConfig, libraryItemId: String, episodeId: String?, update: Components.Schemas.mediaProgressUpdate) async -> Bool {
        await perform(config) { client in
            if let episodeId, !episodeId.isEmpty {
                if case .ok = try await client.updatePodcastEpisodeMediaProgress(.init(path: .init(libraryItemId: libraryItemId, episodeId: episodeId), body: .json(update))) { return true }
                return false
            } else {
                if case .ok = try await client.updateMediaProgress(.init(path: .init(libraryItemId: libraryItemId), body: .json(update))) { return true }
                return false
            }
        } ?? false
    }

    /// POST /api/session/{sessionId}/sync — heartbeat for an open server session.
    public static func syncPlaybackSession(config: ABSClientConfig, sessionId: String, report: Components.Schemas.playbackReport) async -> Bool {
        await perform(config) { client in
            if case .ok = try await client.syncPlaybackSession(.init(path: .init(id: sessionId), body: .json(report))) { return true }
            return false
        } ?? false
    }

    /// POST /api/session/local — sync a single locally-recorded session.
    public static func syncLocalPlaybackSession(config: ABSClientConfig, session: Components.Schemas.playbackSession) async -> Bool {
        await perform(config) { client in
            if case .ok = try await client.syncLocalPlaybackSession(.init(body: .json(session))) { return true }
            return false
        } ?? false
    }

    /// POST /api/session/local-all — bulk-sync offline sessions accumulated while disconnected.
    public static func syncAllLocalPlaybackSessions(config: ABSClientConfig, body: Components.Schemas.localPlaybackSessionSyncAll) async -> Bool {
        await perform(config) { client in
            if case .ok = try await client.syncAllLocalPlaybackSessions(.init(body: .json(body))) { return true }
            return false
        } ?? false
    }

    /// POST /api/items/{libraryItemId}/play[/{episodeId}] — start a server playback session.
    public static func startPlaybackSession(config: ABSClientConfig, libraryItemId: String, episodeId: String?, request: Components.Schemas.playbackSessionRequest) async -> Components.Schemas.playbackSession? {
        await perform(config) { client in
            if let episodeId, !episodeId.isEmpty {
                guard case let .ok(ok) = try await client.playPodcastEpisode(.init(path: .init(id: libraryItemId, episodeId: episodeId), body: .json(request))) else { return nil }
                return try ok.body.json
            } else {
                guard case let .ok(ok) = try await client.playLibraryItem(.init(path: .init(id: libraryItemId), body: .json(request))) else { return nil }
                return try ok.body.json
            }
        }
    }
}
