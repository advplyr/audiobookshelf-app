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
        
        call.resolve()
    }
    
    @objc func checkFolderPermission(_ call: CAPPluginCall) {
        let folderUrl = call.getString("folderUrl")

        // TODO: Is this even necessary on iOS?
        NSLog("checkFolderPermission for folder \(folderUrl ?? "UNSET")")
        
        call.resolve([
            "value": true
        ])
    }
    
    @objc func scanFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        // TODO: Implement
        NSLog("scanFolder \(folderId ?? "UNSET") | Force Probe = \(forceAudioProbe)")
        
        call.resolve()
    }

    @objc func removeFolder(_ call: CAPPluginCall) {
        let folderId = call.getString("folderId")

        // TODO: Implement
        NSLog("removeFolder \(folderId ?? "UNSET")")
        
        call.resolve()
    }
    
    @objc func removeLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")

        // TODO: Implement
        NSLog("removeLocalLibraryItem \(localLibraryItemId ?? "UNSET")")
        
        call.resolve()
    }
    
    @objc func scanLocalLibraryItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let forceAudioProbe = call.getBool("forceAudioProbe", false)

        // TODO: Implement
        NSLog("scanLocalLibraryItem \(localLibraryItemId ?? "UNSET") | Force Probe = \(forceAudioProbe)")
        
        call.resolve()
    }
    
    @objc func deleteItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let contentUrl = call.getString("contentUrl")

        // TODO: Implement
        NSLog("deleteItem \(localLibraryItemId ?? "UNSET") url \(contentUrl ?? "UNSET")")
        
        call.resolve()
    }
    
    @objc func deleteTrackFromItem(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let trackLocalFileId = call.getString("trackLocalFileId")
        let contentUrl = call.getString("contentUrl")

        // TODO: Implement
        NSLog("deleteTrackFromItem \(localLibraryItemId ?? "UNSET") track file \(trackLocalFileId ?? "UNSET") url \(contentUrl ?? "UNSET")")
        
        call.resolve()
    }
}
