//
//  ApiClient.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//
//  Phase 6 of the ABSApiClient migration: the hand-rolled Alamofire request plumbing has been
//  removed. Every JSON endpoint now goes through the generated ABSApiClient (via ABSApi); this
//  type is a thin façade that preserves the existing call sites/signatures and keeps the one
//  non-JSON helper (binary cover download via URLSession). See ios/ABSApiClient/README.md.
//

import Foundation
import UIKit

class ApiClient {
    /// Fetch a binary image (e.g. a cover) directly. Not a JSON endpoint — stays on URLSession.
    public static func getData(from url: URL, completion: @escaping (UIImage?) -> Void) {
        URLSession.shared.dataTask(with: url, completionHandler: {(data, response, error) in
            if let data = data {
                completion(UIImage(data:data))
            }
        }).resume()
    }

    // MARK: - API Functions (served by the generated ABSApiClient via ABSApi)

    public static func startPlaybackSession(libraryItemId: String, episodeId: String?, forceTranscode:Bool, callback: @escaping (_ param: PlaybackSession) -> Void) {
        // The DTO is fetched off-thread, but the Realm PlaybackSession is built AND consumed on the
        // main actor: it is saved to Realm and used by the player on the main thread immediately
        // after, and Realm object graphs must be constructed and used on the same thread.
        Task {
            let dto = await ABSApi.startPlaybackSessionDTO(libraryItemId: libraryItemId, episodeId: episodeId, forceTranscode: forceTranscode)
            await MainActor.run {
                guard let dto = dto else {
                    AbsLogger.error(message: "startPlaybackSession: Failed to create playback session")
                    callback(PlaybackSession()) // Empty session on failure, per the contract
                    return
                }
                let session = PlaybackSession.from(dto: dto)
                // Reject a decodable-but-degenerate session: an empty primary key or no audio tracks
                // would produce a bad Realm save / an unplayable player. Treat as failure.
                guard !session.id.isEmpty, session.libraryItemId?.isEmpty == false, !session.audioTracks.isEmpty else {
                    AbsLogger.error(message: "startPlaybackSession: mapped session missing id/libraryItemId/audioTracks")
                    callback(PlaybackSession())
                    return
                }
                let serverConfig = Store.serverConfig
                session.serverConnectionConfigId = serverConfig?.id
                session.serverAddress = serverConfig?.address
                callback(session)
            }
        }
    }

    public static func reportPlaybackProgress(report: PlaybackReport, sessionId: String) async -> Bool {
        return await ABSApi.reportPlaybackProgress(report: report, sessionId: sessionId)
    }

    public static func reportLocalPlaybackProgress(_ session: PlaybackSession) async -> Bool {
        return await ABSApi.reportLocalPlaybackProgress(session)
    }

    public static func reportAllLocalPlaybackSessions(_ sessions: [PlaybackSession]) async -> Bool {
        return await ABSApi.reportAllLocalPlaybackSessions(sessions)
    }

    public static func syncLocalSessionsWithServer(isFirstSync: Bool) async {
        do {
            // Read the active server id once — Store.serverConfig opens a Realm on each access.
            let serverId = Store.serverConfig?.id

            // Sync server progress with local media progress
            let localMediaProgressList = Database.shared.getAllLocalMediaProgress().filter {
                $0.serverConnectionConfigId == serverId
            }.map { $0.freeze() }
            AbsLogger.info(message: "syncLocalSessionsWithServer: Found \(localMediaProgressList.count) local media progress for server")

            if (localMediaProgressList.isEmpty) {
                AbsLogger.info(message: "syncLocalSessionsWithServer: No local progress to sync")
            } else {
                let currentUser = await ApiClient.getCurrentUser()
                guard let currentUser = currentUser else {
                    AbsLogger.info(message: "syncLocalSessionsWithServer: No User")
                    return
                }
                try currentUser.mediaProgress.forEach { mediaProgress in
                    let localMediaProgress = localMediaProgressList.first { lmp in
                        if (lmp.episodeId != nil) {
                            return lmp.episodeId == mediaProgress.episodeId
                        } else {
                            return lmp.libraryItemId == mediaProgress.libraryItemId
                        }
                    }
                    if (localMediaProgress != nil && mediaProgress.lastUpdate > localMediaProgress!.lastUpdate) {
                        AbsLogger.info(message: "syncLocalSessionsWithServer: Updating local media progress \(localMediaProgress!.id) with server media progress")
                        if let localMediaProgress = localMediaProgress?.thaw() {
                            try localMediaProgress.updateFromServerMediaProgress(mediaProgress)
                        }
                    } else if (localMediaProgress != nil) {
                        AbsLogger.info(message: "syncLocalSessionsWithServer: Local progress for \(localMediaProgress!.id) is more recent then server progress")
                    }
                }
            }

            // Send saved playback sessions to server and remove them from db
            let playbackSessions = Database.shared.getAllPlaybackSessions().filter {
                $0.serverConnectionConfigId == serverId
            }.map { $0.freeze() }
            AbsLogger.info(message: "syncLocalSessionsWithServer: Found \(playbackSessions.count) playback sessions for server (first sync: \(isFirstSync))")
            if (!playbackSessions.isEmpty) {
                let success = await ApiClient.reportAllLocalPlaybackSessions(playbackSessions)
                if (success) {
                    // Remove sessions from db
                    try playbackSessions.forEach { session in
                        AbsLogger.info(message: "syncLocalSessionsWithServer: Handling \(session.displayTitle ?? "") (\(session.id)) \(session.isActiveSession)")
                        // On first sync then remove all sessions
                        if (!session.isActiveSession || isFirstSync) {
                            if let session = session.thaw() {
                                try session.delete()
                            }
                        }
                    }
                }
            }
        } catch {
            debugPrint(error)
            return
        }
    }

    public static func updateMediaProgress<T:Encodable>(libraryItemId: String, episodeId: String?, payload: T, callback: @escaping () -> Void) {
        AbsLogger.info(message: "updateMediaProgress \(libraryItemId) \(episodeId ?? "NIL") \(payload)")
        // Preserves the fire-and-forget callback contract (invoked after the request completes).
        Task {
            _ = await ABSApi.updateMediaProgress(libraryItemId: libraryItemId, episodeId: episodeId, payload: payload)
            callback()
        }
    }

    public static func getMediaProgress(libraryItemId: String, episodeId: String?) async -> MediaProgress? {
        AbsLogger.info(message: "getMediaProgress \(libraryItemId) \(episodeId ?? "NIL")")
        return await ABSApi.getMediaProgress(libraryItemId: libraryItemId, episodeId: episodeId)
    }

    public static func getCurrentUser() async -> User? {
        AbsLogger.info(message: "getCurrentUser")
        return await ABSApi.getCurrentUser()
    }

    public static func getLibraryItemWithProgress(libraryItemId: String, episodeId: String?, callback: @escaping (_ param: LibraryItem?) -> Void) {
        // Fetched via the generated client as a freeform object, then decoded into the Realm
        // LibraryItem with its own lenient decoder ON THE MAIN THREAD (Realm object, immediately
        // persisted/used by the downloader on main).
        Task {
            let data = await ABSApi.getLibraryItemData(libraryItemId: libraryItemId, episodeId: episodeId)
            await MainActor.run {
                guard let data = data else {
                    callback(nil)
                    return
                }
                do {
                    callback(try JSONDecoder().decode(LibraryItem.self, from: data))
                } catch {
                    AbsLogger.error(message: "getLibraryItemWithProgress: decode failed: \(error)")
                    callback(nil)
                }
            }
        }
    }
}
