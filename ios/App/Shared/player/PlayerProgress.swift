//
//  PlayerProgressSync.swift
//  App
//
//  Created by Ron Heft on 8/19/22.
//

import Foundation
import UIKit
import RealmSwift

class PlayerProgress {
    public static let shared = PlayerProgress()
    
    private static let TIME_BETWEEN_SESSION_SYNC_IN_SECONDS = 10.0
    
    private let logger = AppLogger(category: "PlayerProgress")
    
    private init() {}
    
    
    // MARK: - SYNC HOOKS
    
    public func syncFromPlayer(currentTime: Double, includesPlayProgress: Bool, isStopping: Bool) async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncFromPlayer")
        do {
            let session = try updateLocalSessionFromPlayer(currentTime: currentTime, includesPlayProgress: includesPlayProgress)
            try updateLocalMediaProgressFromLocalSession()
            if let session = session {
                try await updateServerSessionFromLocalSession(session, rateLimitSync: !isStopping)
            }
        } catch {
            logger.error("Failed to syncFromPlayer")
            logger.error(error)
        }
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    public func syncToServer() async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncToServer")
        do {
            try await updateAllServerSessionFromLocalSession()
        } catch {
            logger.error("Failed to syncToServer")
            logger.error(error)
        }
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    public func syncFromServer() async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncFromServer")
        do {
            try await updateLocalSessionFromServerMediaProgress()
        } catch {
            logger.error("Failed to syncFromServer")
            logger.error(error)
        }
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    
    // MARK: - SYNC LOGIC
    
    private func updateLocalSessionFromPlayer(currentTime: Double, includesPlayProgress: Bool) throws -> PlaybackSession? {
        guard let session = PlayerHandler.getPlaybackSession() else { return nil }
        guard !currentTime.isNaN else { return nil } // Prevent bad data on player stop
        
        try session.update {
            session.realm?.refresh()
            
            let nowInSeconds = Date().timeIntervalSince1970
            let nowInMilliseconds = nowInSeconds * 1000
            let lastUpdateInMilliseconds = session.updatedAt ?? nowInMilliseconds
            let lastUpdateInSeconds = lastUpdateInMilliseconds / 1000
            let secondsSinceLastUpdate = nowInSeconds - lastUpdateInSeconds
            
            session.currentTime = currentTime
            session.updatedAt = nowInMilliseconds
            
            if includesPlayProgress {
                session.timeListening += secondsSinceLastUpdate
            }
        }
        
        return session.freeze()
    }
    
    private func updateLocalMediaProgressFromLocalSession() throws {
        guard let session = PlayerHandler.getPlaybackSession() else { return }
        guard session.isLocal else { return }
        
        let localMediaProgress = try LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: session.localMediaProgressId, localLibraryItemId: session.localLibraryItem?.id, localEpisodeId: session.episodeId)
        guard let localMediaProgress = localMediaProgress else {
            // Local media progress should have been created
            // If we're here, it means a library id is invalid
            return
        }

        try localMediaProgress.updateFromPlaybackSession(session)
        
        logger.debug("Local progress saved to the database")
        
        // Send the local progress back to front-end
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.localProgress.rawValue), object: nil)
    }
    
    private func updateAllServerSessionFromLocalSession() async throws {
        try await withThrowingTaskGroup(of: Void.self) { [self] group in
            for session in try await Realm().objects(PlaybackSession.self).where({ $0.serverConnectionConfigId == Store.serverConfig?.id }) {
                let session = session.freeze()
                group.addTask {
                    try await self.updateServerSessionFromLocalSession(session)
                }
            }
            try await group.waitForAll()
        }
    }
    
    private func updateServerSessionFromLocalSession(_ session: PlaybackSession, rateLimitSync: Bool = false) async throws {
        var safeToSync = true
        
        guard var session = session.thaw() else { return }
        
        // We need to update and check the server time in a transaction for thread-safety
        try session.update {
            session.realm?.refresh()
            
            let nowInMilliseconds = Date().timeIntervalSince1970 * 1000
            let lastUpdateInMilliseconds = session.serverUpdatedAt
            
            // If required, rate limit requests based on session last update
            if rateLimitSync {
                let timeSinceLastSync = nowInMilliseconds - lastUpdateInMilliseconds
                let timeBetweenSessionSync = PlayerProgress.TIME_BETWEEN_SESSION_SYNC_IN_SECONDS * 1000
                safeToSync = timeSinceLastSync > timeBetweenSessionSync
                if !safeToSync {
                    return // This only exits the update block
                }
            }
            
            session.serverUpdatedAt = nowInMilliseconds
        }
        session = session.freeze()
        
        guard safeToSync else { return }
        logger.debug("Sending sessionId(\(session.id)) to server with currentTime(\(session.currentTime))")
        
        var success = false
        if session.isLocal {
            success = await ApiClient.reportLocalPlaybackProgress(session)
        } else {
            let playbackReport = PlaybackReport(currentTime: session.currentTime, duration: session.duration, timeListened: session.timeListening)
            success = await ApiClient.reportPlaybackProgress(report: playbackReport, sessionId: session.id)
        }
        
        
        // Remove old sessions after they synced with the server
        if success && !session.isActiveSession {
            if let session = session.thaw() {
                try session.delete()
            }
        }
    }
    
    private func updateLocalSessionFromServerMediaProgress() async throws {
        logger.debug("updateLocalSessionFromServerMediaProgress: Checking if local media progress was updated on server")
        guard let session = try await Realm().objects(PlaybackSession.self).last(where: {
            $0.isActiveSession == true && $0.serverConnectionConfigId == Store.serverConfig?.id
        })?.freeze() else {
            logger.debug("updateLocalSessionFromServerMediaProgress: Failed to get session")
            return
        }
        
        // Fetch the current progress
        let progress = await ApiClient.getMediaProgress(libraryItemId: session.libraryItemId!, episodeId: session.episodeId)
        guard let progress = progress else {
            logger.debug("updateLocalSessionFromServerMediaProgress: No progress object")
            return
        }
        
        // Determine which session is newer
        let serverLastUpdate = progress.lastUpdate
        guard let localLastUpdate = session.updatedAt else {
            logger.debug("updateLocalSessionFromServerMediaProgress: No local session updatedAt")
            return
        }
        let serverCurrentTime = progress.currentTime
        let localCurrentTime = session.currentTime
        
        let serverIsNewerThanLocal = serverLastUpdate > localLastUpdate
        let currentTimeIsDifferent = serverCurrentTime != localCurrentTime
        
        // Update the session, if needed
        if serverIsNewerThanLocal && currentTimeIsDifferent {
            logger.debug("updateLocalSessionFromServerMediaProgress: Server has newer time than local serverLastUpdate=\(serverLastUpdate) localLastUpdate=\(localLastUpdate)")
            guard let session = session.thaw() else { return }
            try session.update {
                session.currentTime = serverCurrentTime
                session.updatedAt = serverLastUpdate
            }
            logger.debug("updateLocalSessionFromServerMediaProgress: Updated session currentTime newCurrentTime=\(serverCurrentTime) previousCurrentTime=\(localCurrentTime)")
            PlayerHandler.seek(amount: session.currentTime)
        } else {
            logger.debug("updateLocalSessionFromServerMediaProgress: Local session does not need updating; local has latest progress")
        }
    }
    
}
