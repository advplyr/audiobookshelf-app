//
//  AbsDownloader.swift
//  App
//
//  Created by advplyr on 5/13/22.
//

import Foundation
import Capacitor

@objc(AbsDownloader)
public class AbsDownloader: CAPPlugin, URLSessionDownloadDelegate {
    
    typealias DownloadProgressHandler = (_ downloadItem: DownloadItem, _ downloadItemPart: inout DownloadItemPart) throws -> Void
    
    private let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue.main)
    
    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        handleDownloadTaskUpdate(downloadTask: downloadTask) { downloadItem, downloadItemPart in
            downloadItemPart.progress = 1
            downloadItemPart.completed = true
            
            do {
                // Move the downloaded file into place
                guard let destinationUrl = downloadItemPart.destinationURL() else {
                    throw LibraryItemDownloadError.downloadItemPartDestinationUrlNotDefined
                }
                try? FileManager.default.removeItem(at: destinationUrl)
                try FileManager.default.moveItem(at: location, to: destinationUrl)
                downloadItemPart.moved = true
            } catch {
                downloadItemPart.failed = true
                throw error
            }
        }
    }
    
    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        handleDownloadTaskUpdate(downloadTask: task) { downloadItem, downloadItemPart in
            if let error = error {
                downloadItemPart.completed = true
                downloadItemPart.failed = true
                throw error
            }
        }
    }
    
    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        handleDownloadTaskUpdate(downloadTask: downloadTask) { downloadItem, downloadItemPart in
            // Calculate the download percentage
            let percentDownloaded = (Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) * 100
            downloadItemPart.progress = percentDownloaded
        }
    }
    
    private func handleDownloadTaskUpdate(downloadTask: URLSessionTask, progressHandler: DownloadProgressHandler) {
        do {
            guard let downloadItemPartId = downloadTask.taskDescription else { throw LibraryItemDownloadError.noTaskDescription }
            NSLog("Received download update for \(downloadItemPartId)")
            
            // Find the download item
            let downloadItem = Database.shared.getDownloadItem(downloadItemPartId: downloadItemPartId)
            guard let downloadItem = downloadItem else { throw LibraryItemDownloadError.downloadItemNotFound }
        
            // Find the download item part
            let downloadItemPart = downloadItem.downloadItemParts.filter { $0.id == downloadItemPartId }.first
            guard var downloadItemPart = downloadItemPart else { throw LibraryItemDownloadError.downloadItemPartNotFound }
            
            // Call the progress handler
            do {
                try progressHandler(downloadItem, &downloadItemPart)
            } catch {
                NSLog("Error while processing progress")
                debugPrint(error)
            }
            
            // Update the progress
            Database.shared.updateDownloadItemPart(downloadItemPart)
            
            // Notify the UI
            try? notifyListeners("onItemDownloadUpdate", data: downloadItem.asDictionary())
            
            // Handle a completed download
            if ( downloadItem.isDoneDownloading() ) {
                // Delete the download item on exit even if handling complete errors
                defer { Database.shared.removeDownloadItem(downloadItem) }
                handleDownloadTaskCompleteFromDownloadItem(downloadItem)
            }
        } catch {
            NSLog("DownloadItemError")
            debugPrint(error)
        }
    }
    
    private func handleDownloadTaskCompleteFromDownloadItem(_ downloadItem: DownloadItem) {
        if ( downloadItem.didDownloadSuccessfully() ) {
            
            ApiClient.getLibraryItemWithProgress(libraryItemId: downloadItem.libraryItemId!, episodeId: downloadItem.episodeId) { libraryItem in
                var statusNotification = [String: Any]()
                statusNotification["libraryItemId"] = libraryItem?.id
                
                guard let libraryItem = libraryItem else { NSLog("LibraryItem not found"); return }
                let localDirectory = self.documentsDirectory.appendingPathComponent("\(libraryItem.id)")
                let files = downloadItem.downloadItemParts.map { part in LocalFile(libraryItem.id, part.filename!, part.mimeType()!, part.destinationURL()!) }
                let localLibraryItem = LocalLibraryItem(libraryItem, localUrl: localDirectory, server: Store.serverConfig!, files: files)
                
                Database.shared.saveLocalLibraryItem(localLibraryItem: localLibraryItem)
                statusNotification["localLibraryItem"] = try? localLibraryItem.asDictionary()
                
                if let progress = libraryItem.userMediaProgress {
                    // TODO: Handle podcast
                    let localMediaProgress = LocalMediaProgress(localLibraryItem: localLibraryItem, episode: nil, progress: progress)
                    Database.shared.saveLocalMediaProgress(localMediaProgress)
                    statusNotification["localMediaProgress"] = try? localMediaProgress.asDictionary()
                }
                
                self.notifyListeners("onItemDownloadComplete", data: statusNotification)
            }
            
        }
    }
    
    @objc func downloadLibraryItem(_ call: CAPPluginCall) {
        let libraryItemId = call.getString("libraryItemId")
        var episodeId = call.getString("episodeId")
        if ( episodeId == "null" ) { episodeId = nil }
        
        NSLog("Download library item \(libraryItemId ?? "N/A") / episode \(episodeId ?? "N/A")")
        guard let libraryItemId = libraryItemId else { return call.resolve(["error": "libraryItemId not specified"]) }
        
        // Verify the file isn't already downloading
        let downloadItemId = episodeId != nil ? "\(libraryItemId)-\(episodeId!)" : libraryItemId
        let downloadItem = Database.shared.getDownloadItem(downloadItemId: downloadItemId)
        if ( downloadItem != nil ) {
            return call.resolve(["error": "Download already started for this media entity"])
        }
        
        ApiClient.getLibraryItemWithProgress(libraryItemId: libraryItemId, episodeId: episodeId) { libraryItem in
            if let libraryItem = libraryItem {
                NSLog("Got library item from server \(libraryItem.id)")
                do {
                    if let episodeId = episodeId {
                        // Download a podcast episode
                        guard libraryItem.mediaType == "podcast" else { throw LibraryItemDownloadError.libraryItemNotPodcast }
                        let episode = libraryItem.media.episodes?.first(where: { $0.id == episodeId })
                        guard let episode = episode else { throw LibraryItemDownloadError.podcastEpisodeNotFound }
                        try self.startLibraryItemDownload(libraryItem, episode: episode)
                    } else {
                        // Download a book
                        try self.startLibraryItemDownload(libraryItem)
                    }
                    call.resolve()
                } catch {
                    debugPrint(error)
                    call.resolve(["error": "Failed to download"])
                }
            } else {
                call.resolve(["error": "Server request failed"])
            }
        }
    }
    
    private func startLibraryItemDownload(_ item: LibraryItem) throws {
        try startLibraryItemDownload(item, episode: nil)
    }
    
    private func startLibraryItemDownload(_ item: LibraryItem, episode: PodcastEpisode?) throws {
        var tracks: [AudioTrack]
        var episodeId: String?
        
        // Handle the different media type downloads
        switch item.mediaType {
        case "book":
            guard let bookTracks = item.media.tracks else { throw LibraryItemDownloadError.noTracks }
            tracks = bookTracks
        case "podcast":
            guard let episode = episode else { throw LibraryItemDownloadError.podcastEpisodeNotFound }
            guard let podcastTrack = episode.audioTrack else { throw LibraryItemDownloadError.noTracks }
            episodeId = episode.id
            tracks = [podcastTrack]
        default:
            throw LibraryItemDownloadError.unknownMediaType
        }
        
        // Queue up everything for downloading
        var downloadItem = DownloadItem(libraryItem: item, episodeId: episodeId, server: Store.serverConfig!)
        downloadItem.downloadItemParts = try tracks.enumerated().map({ i, track in
            try startLibraryItemTrackDownload(item: item, position: i, track: track)
        })
        
        // Also download the cover
        if item.media.coverPath != nil && !item.media.coverPath!.isEmpty {
            if let coverDownload = try? startLibraryItemCoverDownload(item: item) {
                downloadItem.downloadItemParts.append(coverDownload)
            }
        }
        
        // Persist in the database before status start coming in
        Database.shared.saveDownloadItem(downloadItem)
        
        // Start all the downloads
        for downloadItemPart in downloadItem.downloadItemParts {
            downloadItemPart.task.resume()
        }
    }
    
    private func startLibraryItemTrackDownload(item: LibraryItem, position: Int, track: AudioTrack) throws -> DownloadItemPart {
        NSLog("TRACK \(track.contentUrl!)")
        
        // If we don't name metadata, then we can't proceed
        guard let filename = track.metadata?.filename else {
            throw LibraryItemDownloadError.noMetadata
        }
        
        let serverUrl = urlForTrack(item: item, track: track)
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = itemDirectory.appendingPathComponent("\(filename)")
        
        let task = session.downloadTask(with: serverUrl)
        var downloadItemPart = DownloadItemPart(filename: filename, destination: localUrl, itemTitle: track.title ?? "Unknown", serverPath: Store.serverConfig!.address, audioTrack: track, episode: nil)
        
        // Store the id on the task so the download item can be pulled from the database later
        task.taskDescription = downloadItemPart.id
        downloadItemPart.task = task
        
        return downloadItemPart
    }
    
    private func startLibraryItemCoverDownload(item: LibraryItem) throws -> DownloadItemPart {
        let filename = "cover.jpg"
        let serverPath = "/api/items/\(item.id)/cover"
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = itemDirectory.appendingPathComponent("\(filename)")
        
        return DownloadItemPart(filename: filename, destination: localUrl, itemTitle: "cover", serverPath: serverPath, audioTrack: nil, episode: nil)
    }
    
    private func urlForTrack(item: LibraryItem, track: AudioTrack) -> URL {
        // filename needs to be encoded otherwise would just use contentUrl
        let filenameEncoded = track.metadata?.filename.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
        let urlstr = "\(Store.serverConfig!.address)/s/item/\(item.id)/\(filenameEncoded ?? "")?token=\(Store.serverConfig!.token)"
        return URL(string: urlstr)!
    }
    
    private func createLibraryItemFileDirectory(item: LibraryItem) throws -> URL {
        let itemDirectory = documentsDirectory.appendingPathComponent("\(item.id)")
        NSLog("ITEM DIR \(itemDirectory)")
        
        do {
            try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: true)
        } catch {
            NSLog("Failed to CREATE LI DIRECTORY \(error)")
            throw LibraryItemDownloadError.failedDirectory
        }
        
        return itemDirectory
    }
    
}

enum LibraryItemDownloadError: String, Error {
    case noTracks = "No tracks on library item"
    case noMetadata = "No metadata for track, unable to download"
    case libraryItemNotPodcast = "Library item is not a podcast but episode was requested"
    case podcastEpisodeNotFound = "Invalid podcast episode not found"
    case unknownMediaType = "Unknown media type"
    case failedDirectory = "Failed to create directory"
    case failedDownload = "Failed to download item"
    case noTaskDescription = "No task description"
    case downloadItemNotFound = "DownloadItem not found"
    case downloadItemPartNotFound = "DownloadItemPart not found"
    case downloadItemPartDestinationUrlNotDefined = "DownloadItemPart destination URL not defined"
    case libraryItemNotFound = "LibraryItem not found for id"
}
