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
                
                self.startLibraryItemDownload(libraryItem: libraryItem!)
                call.resolve()
            }
        }
    }
    
    func startLibraryItemDownload(libraryItem: LibraryItem) {
        let length = libraryItem.media.tracks?.count ?? 0
        if length > 0 {
            libraryItem.media.tracks?.enumerated().forEach { position, track in
                NSLog("TRACK \(track.contentUrl!)")
                // filename needs to be encoded otherwise would just use contentUrl
                let filename = track.metadata?.filename ?? ""
                let filenameEncoded = filename.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
                let urlstr = "\(Store.serverConfig!.address)/s/item/\(libraryItem.id)/\(filenameEncoded ?? "")?token=\(Store.serverConfig!.token)"
                let url = URL(string: urlstr)!
                
                let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let itemDirectory = documentsDirectory.appendingPathComponent("\(libraryItem.id)")
                NSLog("ITEM DIR \(itemDirectory)")
                
                // Create library item directory
                do {
                    try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: false)
                } catch {
                    NSLog("Failed to CREATE LI DIRECTORY \(error)")
                }
           
                // Output filename
                let trackFilename = itemDirectory.appendingPathComponent("\(filename)")
                
                let downloadTask = URLSession.shared.downloadTask(with: url) { urlOrNil, responseOrNil, errorOrNil in

                    guard let fileURL = urlOrNil else { return }
                    
                    do {
                        NSLog("Download TMP file URL \(fileURL)")
                        let audioData = try Data(contentsOf:fileURL)
                        try audioData.write(to: trackFilename)
                        NSLog("Download written to \(trackFilename)")
                    } catch {
                        NSLog("FILE ERROR: \(error)")
                    }
                }
                downloadTask.resume()
            }
        } else {
            NSLog("No audio tracks for the supplied library item")
        }
//        let encoder = JSONEncoder()
//        let jsobj = try encoder.encode(Download)
//        notifyListeners("onItemDownloadComplete", data: jsobj)
    }
}
struct DownloadItem: Codable {
    var isDownloading = false
    var progress: Float = 0
    var resumeData: Data?
//    var task: URLSessionDownloadTask?
}
