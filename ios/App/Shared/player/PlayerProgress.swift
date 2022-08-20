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
    
    public static func syncLocalFromPlayer() async {
        
    }
    
    public static func syncServerFromLocal() async {
        
    }
    
    public static func syncLocalFromServer() async {
        let backgroundToken = await UIApplication.shared.beginBackgroundTask(withName: "ABS:updateLocalSessionFromServerMediaProgress")
        await updateLocalSessionFromServerMediaProgress()
        await UIApplication.shared.endBackgroundTask(backgroundToken)
    }
    
    private static func updateLocalSessionFromActivePlayer() {
        
    }
    
    private static func updateLocalMediaProgressFromLocalSession() {
        
    }
    
    private static func updateServerSessionFromLocalSession() {
        
    }
    
    private static func updateLocalSessionFromServerMediaProgress() async {
        NSLog("checkCurrentSessionProgress: Checking if local media progress was updated on server")
        guard let session = PlayerHandler.getPlaybackSession() else { return }
        
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
            session.update {
                session.currentTime = serverCurrentTime
                session.updatedAt = serverLastUpdate
            }
            PlayerHandler.seek(amount: session.currentTime)
        }
    }
    
}
