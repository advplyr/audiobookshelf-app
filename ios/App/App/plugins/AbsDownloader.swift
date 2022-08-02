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
    let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    
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
                self.startLibraryItemDownload(item: libraryItem!)
                call.resolve()
            }
        }
    }
    
    private func startLibraryItemDownload(item: LibraryItem) {
        let length = item.media.tracks?.count ?? 0
        if length > 0 {
            let downloadDispatch = DispatchGroup()
            let files = item.media.tracks!.enumerated().map {
                position, track -> LocalFile in startLibraryItemTrackDownload(item: item, position: position, track: track, dispatch: downloadDispatch)
            }
            downloadDispatch.notify(queue: .main) {
                let localLibraryItem = LocalLibraryItem(item, localUrl: self.documentsDirectory, server: Store.serverConfig!, files: files)
                Database.shared.saveLocalLibraryItem(localLibraryItem: localLibraryItem)
            }
        } else {
            NSLog("No audio tracks for the supplied library item")
        }
    }
    
    private func startLibraryItemTrackDownload(item: LibraryItem, position: Int, track: AudioTrack, dispatch: DispatchGroup) -> LocalFile {
        NSLog("TRACK \(track.contentUrl!)")
        
        // If we don't name metadata, then we can't proceed
        guard let filename = track.metadata?.filename else {
            NSLog("No metadata for track, unable to download")
            return LocalFile()
        }
        
        let serverUrl = urlForTrack(item: item, track: track)
        let itemDirectory = createLibraryItemFileDirectory(item: item)
        let localUrl = itemDirectory.appendingPathComponent("\(filename)")
        
        downloadTrack(serverUrl: serverUrl, localUrl: localUrl, dispatch: dispatch)
        return LocalFile(item.id, filename, track.mimeType, localUrl)
    }
    
    private func urlForTrack(item: LibraryItem, track: AudioTrack) -> URL {
        // filename needs to be encoded otherwise would just use contentUrl
        let filenameEncoded = track.metadata?.filename.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
        let urlstr = "\(Store.serverConfig!.address)/s/item/\(item.id)/\(filenameEncoded ?? "")?token=\(Store.serverConfig!.token)"
        return URL(string: urlstr)!
    }
    
    private func createLibraryItemFileDirectory(item: LibraryItem) -> URL {
        let itemDirectory = documentsDirectory.appendingPathComponent("\(item.id)")
        
        NSLog("ITEM DIR \(itemDirectory)")
        
        // Create library item directory
        do {
            try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: true)
        } catch {
            NSLog("Failed to CREATE LI DIRECTORY \(error)")
        }
        
        return itemDirectory
    }
    
    private func downloadTrack(serverUrl: URL, localUrl: URL, dispatch: DispatchGroup) {
        dispatch.enter()
        
        let downloadTask = URLSession.shared.downloadTask(with: serverUrl) { urlOrNil, responseOrNil, errorOrNil in
            defer { dispatch.leave() }
            
            guard let fileURL = urlOrNil else {
                return
            }
            
            do {
                NSLog("Download TMP file URL \(fileURL)")
                let audioData = try Data(contentsOf:fileURL)
                try audioData.write(to: localUrl)
                NSLog("Download written to \(localUrl)")
            } catch {
                NSLog("FILE ERROR: \(error)")
            }
        }
        
        // Start the download
        downloadTask.resume()
    }
}

struct DownloadItem: Codable {
    var isDownloading = false
    var progress: Float = 0
    var resumeData: Data?
//    var task: URLSessionDownloadTask?
}
