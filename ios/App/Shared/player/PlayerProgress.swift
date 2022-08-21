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
    
    private init() {}
    
    public static func syncFromPlayer() {
        updateLocalMediaProgressFromLocalSession()
    }
    
    public static func syncToServer() async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncToServer")
        updateAllServerSessionFromLocalSession()
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    public static func syncFromServer() async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:syncFromServer")
        await updateLocalSessionFromServerMediaProgress()
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    private static func updateLocalMediaProgressFromLocalSession() {
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
    
    private static func updateAllServerSessionFromLocalSession() {
        let sessions = try! Realm().objects(PlaybackSession.self).where({ $0.serverConnectionConfigId == Store.serverConfig?.id })
        for session in sessions {
            let session = session.freeze()
            Task { await updateServerSessionFromLocalSession(session) }
        }
    }
    
    private static func updateServerSessionFromLocalSession(_ session: PlaybackSession) async {
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
            NSLog("Deleting sessionId(\(session.id)) as is no longer active")
            session.thaw()?.delete()
        }
    }
    
    private static func updateLocalSessionFromServerMediaProgress() async {
        NSLog("checkCurrentSessionProgress: Checking if local media progress was updated on server")
        guard let session = PlayerHandler.getPlaybackSession()?.freeze() else { return }
        
        // Fetch the current progress
        let progress = await ApiClient.getMediaProgress(libraryItemId: session.libraryItemId!, episodeId: session.episodeId)
        guard let progress = progress else { return }
        
        // Determine which session is newer
        let serverLastUpdate = progress.lastUpdate
        guard let localLastUpdate = session.updatedAt else { return }
        let serverCurrentTime = progress.currentTime
        let localCurrentTime = session.currentTime
        
        let serverIsNewerThanLocal = serverLastUpdate > localLastUpdate
        let currentTimeIsDifferent = serverCurrentTime != localCurrentTime
        
        // Update the session, if needed
        if serverIsNewerThanLocal && currentTimeIsDifferent {
            guard let session = session.thaw() else { return }
            session.update {
                session.currentTime = serverCurrentTime
                session.updatedAt = serverLastUpdate
            }
            PlayerHandler.seek(amount: session.currentTime)
        }
    }
    
}
