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

        // TODO: Implement
        NSLog("Select Folder for media type \(mediaType ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func checkFolderPermission(_ call: CAPPluginCall) {
        let folderUrl = call.getString("folderUrl")

        // TODO: Is this even necessary on iOS?
        NSLog("checkFolderPermission for folder \(folderUrl ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func scanFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        // TODO: Implement
        NSLog("scanFolder \(folderId ?? "UNSET") | Force Probe = \(forceAudioProbe)")
        
        call.unavailable("Not available on iOS")
    }

    @objc func removeFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")

        // TODO: Implement
        NSLog("removeFolder \(folderId ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func removeLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")

        // TODO: Implement
        NSLog("removeLocalLibraryItem \(localLibraryItemId ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func scanLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        // TODO: Implement
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
                Database.shared.removeLocalLibraryItem(localLibraryItemId: localLibraryItemId)
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
        do {
            if let localLibraryItemId = localLibraryItemId, let trackLocalFileId = trackLocalFileId, var item = Database.shared.getLocalLibraryItem(localLibraryItemId: localLibraryItemId) {
                if let fileIndex = item.localFiles.firstIndex(where: { $0.id == trackLocalFileId }) {
                    try FileManager.default.removeItem(at: item.localFiles[fileIndex].contentPath)
                    item.localFiles.remove(at: fileIndex)
                    if item.isPodcast, var media = item.media {
                        media.episodes = media.episodes?.filter { $0.audioTrack?.localFileId != trackLocalFileId }
                        item.media = media
                    }
                    Database.shared.saveLocalLibraryItem(localLibraryItem: item)
                    call.resolve(try item.asDictionary())
                    success = true
                }
            }
        } catch {
            NSLog("Failed to delete \(error)")
            success = false
        }
        
        if !success {
            call.resolve(["success": success])
        }
    }
}
