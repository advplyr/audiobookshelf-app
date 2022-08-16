//
//  AbsFileSystem.swift
//  App
//
//  Created by advplyr on 5/13/22.
//

import Foundation
import Capacitor

@objc(AbsFileSystem)
public class AbsFileSystem: CAPPlugin {
    @objc func selectFolder(_ call: CAPPluginCall) {
        let mediaType = call.getString("mediaType")

        NSLog("Select Folder for media type \(mediaType ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func checkFolderPermission(_ call: CAPPluginCall) {
        let folderUrl = call.getString("folderUrl")

        NSLog("checkFolderPermission for folder \(folderUrl ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func scanFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        NSLog("scanFolder \(folderId ?? "UNSET") | Force Probe = \(forceAudioProbe)")
        
        call.unavailable("Not available on iOS")
    }

    @objc func removeFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")

        NSLog("removeFolder \(folderId ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func removeLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")

        NSLog("removeLocalLibraryItem \(localLibraryItemId ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func scanLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        NSLog("scanLocalLibraryItem \(localLibraryItemId ?? "UNSET") | Force Probe = \(forceAudioProbe)")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func deleteItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("id")
        let contentUrl = call.getString("contentUrl")
        
        NSLog("deleteItem \(localLibraryItemId ?? "UNSET") url \(contentUrl ?? "UNSET")")
        
        var success = false
        do {
            if let localLibraryItemId = localLibraryItemId, let item = Database.shared.getLocalLibraryItem(localLibraryItemId: localLibraryItemId) {
                try FileManager.default.removeItem(at: item.contentDirectory!)
                item.delete()
                success = true
            }
        } catch {
            NSLog("Failed to delete \(error)")
            success = false
        }
        
        call.resolve(["success": success])
    }
    
    @objc func deleteTrackFromItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("id")
        let trackLocalFileId = call.getString("trackLocalFileId")

        NSLog("deleteTrackFromItem \(localLibraryItemId ?? "UNSET") track file \(trackLocalFileId ?? "UNSET")")
        
        var success = false
        if let localLibraryItemId = localLibraryItemId, let trackLocalFileId = trackLocalFileId, let item = Database.shared.getLocalLibraryItem(localLibraryItemId: localLibraryItemId) {
            item.update {
                do {
                    if let fileIndex = item.localFiles.firstIndex(where: { $0.id == trackLocalFileId }) {
                        try FileManager.default.removeItem(at: item.localFiles[fileIndex].contentPath)
                        item.realm?.delete(item.localFiles[fileIndex])
                        if item.isPodcast, let media = item.media {
                            if let episodeIndex = media.episodes.firstIndex(where: { $0.audioTrack?.localFileId == trackLocalFileId }) {
                                media.episodes.remove(at: episodeIndex)
                            }
                            item.media = media
                        }
                        call.resolve(try item.asDictionary())
                        success = true
                    }
                } catch {
                    NSLog("Failed to delete \(error)")
                    success = false
                }
            }
        }
        
        if !success {
            call.resolve(["success": success])
        }
    }
}
