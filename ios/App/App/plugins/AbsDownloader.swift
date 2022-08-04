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
        
        NSLog("Download library item \(libraryItemId ?? "N/A") / episode \(episodeId ?? "")")
        
        ApiClient.getLibraryItemWithProgress(libraryItemId: libraryItemId!, episodeId: episodeId) { libraryItem in
            if (libraryItem == nil) {
                NSLog("Library item not found")
                call.resolve()
            } else {
                NSLog("Got library item from server \(libraryItem!.id)")
                Task {
                    do {
                        let downloadSession = LibraryItemDownloadSession(libraryItem!)
                        let localLibraryItem = try await downloadSession.startDownload()
                        Database.shared.saveLocalLibraryItem(localLibraryItem: localLibraryItem)
                    } catch {
                        NSLog("Failed to download \(error)")
                    }
                    call.resolve()
                }
            }
        }
    }
}

struct DownloadItem: Codable {
    var isDownloading = false
    var progress: Float = 0
    var resumeData: Data?
//    var task: URLSessionDownloadTask?
}
