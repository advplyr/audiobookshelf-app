//
//  AbsDownloader.swift
//  App
//
//  Created by advplyr on 5/13/22.
//

import Foundation
import Capacitor

@objc(AbsDownloader)
public class AbsDownloader: CAPPlugin {
    @objc func downloadLibraryItem(_ call: CAPPluginCall) {
        let libraryItemId = call.getString("libraryItemId")
        let episodeId = call.getString("episodeId")
        let localFolderId = call.getString("localFolderId")
        
        // TODO: Implement download
        NSLog("Download library item \(libraryItemId ?? "N/A") episode \(episodeId ?? "") to folder \(localFolderId ?? "N/A")")
        
        call.resolve()
    }
}
