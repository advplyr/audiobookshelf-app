//
//  AbsFileSystem.swift
//  App
//
//  Created by advplyr on 5/13/22.
//

import Foundation
import Capacitor

@objc(AbsFileSystem)
public class AbsFileSystem: CAPPlugin, CAPBridgedPlugin {
    public var identifier = "AbsFileSystemPlugin"
    public var jsName = "AbsFileSystem"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "selectFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkFolderPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "scanFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeLocalLibraryItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "scanLocalLibraryItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteTrackFromItem", returnType: CAPPluginReturnPromise)
    ]
    
    @objc func selectFolder(_ call: CAPPluginCall) {
        let mediaType = call.getString("mediaType")

        AbsLogger.info(message: "Select Folder for media type \(mediaType ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func checkFolderPermission(_ call: CAPPluginCall) {
        let folderUrl = call.getString("folderUrl")

        AbsLogger.info(message: "checkFolderPermission for folder \(folderUrl ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func scanFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        AbsLogger.info(message: "scanFolder \(folderId ?? "UNSET") | Force Probe = \(forceAudioProbe)")
        
        call.unavailable("Not available on iOS")
    }

    @objc func removeFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")

        AbsLogger.info(message: "removeFolder \(folderId ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func removeLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")

        AbsLogger.info(message: "removeLocalLibraryItem \(localLibraryItemId ?? "UNSET")")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func scanLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        AbsLogger.info(message: "scanLocalLibraryItem \(localLibraryItemId ?? "UNSET") | Force Probe = \(forceAudioProbe)")
        
        call.unavailable("Not available on iOS")
    }
    
    @objc func deleteItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("id")
        let contentUrl = call.getString("contentUrl")
        
        AbsLogger.info(message: "deleteItem \(localLibraryItemId ?? "UNSET") url \(contentUrl ?? "UNSET")")
        
        var success = false
        do {
            if let localLibraryItemId = localLibraryItemId, let item = Database.shared.getLocalLibraryItem(localLibraryItemId: localLibraryItemId) {
                try FileManager.default.removeItem(at: item.contentDirectory!)
                try item.delete()
                success = true
            }
        } catch {
            AbsLogger.error(message: "Failed to delete \(error)")
            success = false
        }
        
        call.resolve(["success": success])
    }
    
    @objc func deleteTrackFromItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("id")
        let trackLocalFileId = call.getString("trackLocalFileId")

        AbsLogger.info(message: "deleteTrackFromItem \(localLibraryItemId ?? "UNSET") track file \(trackLocalFileId ?? "UNSET")")
        
        var success = false
        if let localLibraryItemId = localLibraryItemId, let trackLocalFileId = trackLocalFileId, let item = Database.shared.getLocalLibraryItem(localLibraryItemId: localLibraryItemId) {
            do {
                try item.update {
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
                        AbsLogger.error(message: "Failed to delete \(error)")
                        success = false
                    }
                }
            } catch {
                AbsLogger.error(message: "Failed to delete \(error)")
                success = false
            }
        }
        
        if !success {
            call.resolve(["success": success])
        }
    }
}
