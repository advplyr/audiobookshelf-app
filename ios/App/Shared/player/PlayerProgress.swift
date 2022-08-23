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
    
    private init() {}
    
    
    // MARK: - SYNC HOOKS
    
    public func syncFromPlayer(currentTime: Double, includesPlayProgress: Bool, isStopping: Bool) async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncFromPlayer")
        let session = await updateLocalSessionFromPlayer(currentTime: currentTime, includesPlayProgress: includesPlayProgress)
        updateLocalMediaProgressFromLocalSession()
        if let session = session {
            await updateServerSessionFromLocalSession(session, rateLimitSync: !isStopping)
        }
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    public func syncToServer() async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncToServer")
        await updateAllServerSessionFromLocalSession()
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    public func syncFromServer() async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncFromServer")
        await updateLocalSessionFromServerMediaProgress()
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    
    // MARK: - SYNC LOGIC
    
    private func updateLocalSessionFromPlayer(currentTime: Double, includesPlayProgress: Bool) async -> PlaybackSession? {
        guard let session = PlayerHandler.getPlaybackSession() else { return nil }
        guard !currentTime.isNaN else { return nil } // Prevent bad data on player stop
        
        session.update {
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
    
    private func updateLocalMediaProgressFromLocalSession() {
        guard let session = PlayerHandler.getPlaybackSession() else { return }
        guard session.isLocal else { return }
        
        let localMediaProgress = LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: session.localMediaProgressId, localLibraryItemId: session.localLibraryItem?.id, localEpisodeId: session.episodeId)
        guard let localMediaProgress = localMediaProgress else {
            // Local media progress should have been created
            // If we're here, it means a library id is invalid
            return
        }

        localMediaProgress.updateFromPlaybackSession(session)
        Database.shared.saveLocalMediaProgress(localMediaProgress)
        
        NSLog("Local progress saved to the database")
        
        // Send the local progress back to front-end
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.localProgress.rawValue), object: nil)
    }
    
    private func updateAllServerSessionFromLocalSession() async {
        await withTaskGroup(of: Void.self) { [self] group in
            for session in try! await Realm().objects(PlaybackSession.self).where({ $0.serverConnectionConfigId == Store.serverConfig?.id }) {
                let session = session.freeze()
                group.addTask {
                    await self.updateServerSessionFromLocalSession(session)
                }
            }
            await group.waitForAll()
        }
    }
    
    private func updateServerSessionFromLocalSession(_ session: PlaybackSession, rateLimitSync: Bool = false) async {
        guard var session = session.thaw() else { return }
        var safeToSync = true
        
        // We need to update and check the server time in a transaction for thread-safety
        session.update {
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
        
        NSLog("Sending sessionId(\(session.id)) to server")
        
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
                session.delete()
            }
        }
    }
    
    private func updateLocalSessionFromServerMediaProgress() async {
        NSLog("updateLocalSessionFromServerMediaProgress: Checking if local media progress was updated on server")
        guard let session = try! await Realm().objects(PlaybackSession.self).last(where: { $0.isActiveSession == true })?.freeze() else {
            NSLog("updateLocalSessionFromServerMediaProgress: Failed to get session")
            return
        }
        
        // Fetch the current progress
        let progress = await ApiClient.getMediaProgress(libraryItemId: session.libraryItemId!, episodeId: session.episodeId)
        guard let progress = progress else {
            NSLog("updateLocalSessionFromServerMediaProgress: No progress object")
            return
        }
        
        // Determine which session is newer
        let serverLastUpdate = progress.lastUpdate
        guard let localLastUpdate = session.updatedAt else {
            NSLog("updateLocalSessionFromServerMediaProgress: No local session updatedAt")
            return
        }
        let serverCurrentTime = progress.currentTime
        let localCurrentTime = session.currentTime
        
        let serverIsNewerThanLocal = serverLastUpdate > localLastUpdate
        let currentTimeIsDifferent = serverCurrentTime != localCurrentTime
        
        // Update the session, if needed
        if serverIsNewerThanLocal && currentTimeIsDifferent {
            NSLog("updateLocalSessionFromServerMediaProgress: Server has newer time than local serverLastUpdate=\(serverLastUpdate) localLastUpdate=\(localLastUpdate)")
            guard let session = session.thaw() else { return }
            session.update {
                session.currentTime = serverCurrentTime
                session.updatedAt = serverLastUpdate
            }
            NSLog("updateLocalSessionFromServerMediaProgress: Updated session currentTime newCurrentTime=\(serverCurrentTime) previousCurrentTime=\(localCurrentTime)")
            PlayerHandler.seek(amount: session.currentTime)
        } else {
            NSLog("updateLocalSessionFromServerMediaProgress: Local session does not need updating; local has latest progress")
        }
    }
    
}
