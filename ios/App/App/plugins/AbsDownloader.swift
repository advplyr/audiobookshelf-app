//
//  AbsDownloader.swift
//  App
//
//  Created by advplyr on 5/13/22.
//

import Foundation
import Capacitor
import RealmSwift

@objc(AbsDownloader)
public class AbsDownloader: CAPPlugin, URLSessionDownloadDelegate {
    
    static private let downloadsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    
    private let logger = AppLogger(category: "AbsDownloader")
    
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "AbsDownloader")
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 5
        return URLSession(configuration: config, delegate: self, delegateQueue: queue)
    }()
    private let progressStatusQueue = DispatchQueue(label: "progress-status-queue", attributes: .concurrent)
    private var downloadItemProgress = [String: DownloadItem]()
    private var monitoringProgressTimer: Timer?
    
    
    // MARK: - Progress handling
    
    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        handleDownloadTaskUpdate(downloadTask: downloadTask) { downloadItem, downloadItemPart in
            let realm = try Realm()
            try realm.write {
                downloadItemPart.bytesDownloaded = downloadItemPart.fileSize
                downloadItemPart.progress = 100
                downloadItemPart.completed = true
            }
            
            do {
                // Move the downloaded file into place
                guard let destinationUrl = downloadItemPart.destinationURL else {
                    throw LibraryItemDownloadError.downloadItemPartDestinationUrlNotDefined
                }
                try? FileManager.default.removeItem(at: destinationUrl)
                try FileManager.default.moveItem(at: location, to: destinationUrl)
                try realm.write {
                    downloadItemPart.moved = true
                }
            } catch {
                try realm.write {
                    downloadItemPart.failed = true
                }
                throw error
            }
        }
    }
    
    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        handleDownloadTaskUpdate(downloadTask: task) { downloadItem, downloadItemPart in
            if let error = error {
                try Realm().write {
                    downloadItemPart.completed = true
                    downloadItemPart.failed = true
                }
                throw error
            }
        }
    }
    
    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        handleDownloadTaskUpdate(downloadTask: downloadTask) { downloadItem, downloadItemPart in
            // Calculate the download percentage
            let percentDownloaded = (Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) * 100

            // Only update the progress if we received accurate progress data
            if percentDownloaded >= 0.0 && percentDownloaded <= 100.0 {
                try Realm().write {
                    downloadItemPart.bytesDownloaded = Double(totalBytesWritten)
                    downloadItemPart.progress = percentDownloaded
                }
            }
        }
    }
    
    // Called when downloads are complete on the background thread
    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            guard let appDelegate = UIApplication.shared.delegate as? AppDelegate,
                let backgroundCompletionHandler =
                appDelegate.backgroundCompletionHandler else {
                    return
            }
            backgroundCompletionHandler()
        }
    }
    
    private func handleDownloadTaskUpdate(downloadTask: URLSessionTask, progressHandler: DownloadProgressHandler) {
        do {
            guard let downloadItemPartId = downloadTask.taskDescription else { throw LibraryItemDownloadError.noTaskDescription }
            logger.log("Received download update for \(downloadItemPartId)")
            
            // Find the download item
            let downloadItem = Database.shared.getDownloadItem(downloadItemPartId: downloadItemPartId)
            guard var downloadItem = downloadItem else { throw LibraryItemDownloadError.downloadItemNotFound }
        
            // Find the download item part
            let part = downloadItem.downloadItemParts.first(where: { $0.id == downloadItemPartId })
            guard let part = part else { throw LibraryItemDownloadError.downloadItemPartNotFound }
            
            // Call the progress handler
            do {
                try progressHandler(downloadItem, part)
                try? self.notifyListeners("onDownloadItemPartUpdate", data: part.asDictionary())
            } catch {
                logger.error("Error while processing progress")
                debugPrint(error)
            }
            
            // Update the progress
            downloadItem = downloadItem.freeze()
            self.progressStatusQueue.async(flags: .barrier) {
                self.downloadItemProgress.updateValue(downloadItem, forKey: downloadItem.id!)
            }
            self.notifyDownloadProgress()
        } catch {
            logger.error("DownloadItemError")
            debugPrint(error)
        }
    }
    
    // We want to handle updating the UI in the background and throttled so we don't overload the UI with progress updates
    private func notifyDownloadProgress() {
        if self.monitoringProgressTimer?.isValid ?? false {
            logger.log("Already monitoring progress, no need to start timer again")
        } else {
            DispatchQueue.runOnMainQueue {
                self.monitoringProgressTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true, block: { [unowned self] t in
                    self.logger.log("Starting monitoring download progress...")
                    
                    // Fetch active downloads in a thread-safe way
                    func fetchActiveDownloads() -> [String: DownloadItem]? {
                        self.progressStatusQueue.sync {
                            let activeDownloads = self.downloadItemProgress
                            if activeDownloads.isEmpty {
                                logger.log("Finishing monitoring download progress...")
                                t.invalidate()
                            }
                            return activeDownloads
                        }
                    }
                    
                    // Remove a completed download item in a thread-safe way
                    func handleDoneDownloadItem(_ item: DownloadItem) {
                        self.progressStatusQueue.async(flags: .barrier) {
                            self.downloadItemProgress.removeValue(forKey: item.id!)
                        }
                        self.handleDownloadTaskCompleteFromDownloadItem(item)
                        if let item = Database.shared.getDownloadItem(downloadItemId: item.id!) {
                            try? item.delete()
                        }
                    }
                    
                    // Check for items done downloading
                    if let activeDownloads = fetchActiveDownloads() {
                        for item in activeDownloads.values {
                            if item.isDoneDownloading() { handleDoneDownloadItem(item) }
                        }
                    }
                })
            }
        }
    }
    
    private func handleDownloadTaskCompleteFromDownloadItem(_ downloadItem: DownloadItem) {
        var statusNotification = [String: Any]()
        statusNotification["libraryItemId"] = downloadItem.id
        
        if ( downloadItem.didDownloadSuccessfully() ) {
            ApiClient.getLibraryItemWithProgress(libraryItemId: downloadItem.libraryItemId!, episodeId: downloadItem.episodeId) { [weak self] libraryItem in
                guard let libraryItem = libraryItem else { self?.logger.error("LibraryItem not found"); return }
                let localDirectory = libraryItem.id
                var coverFile: String?
                
                // Assemble the local library item
                let files = downloadItem.downloadItemParts.enumerated().compactMap { _, part -> LocalFile? in
                    var mimeType = part.mimeType()
                    if part.filename == "cover.jpg" {
                        coverFile = part.destinationUri
                        mimeType = "image/jpg"
                    }
                    return LocalFile(libraryItem.id, part.filename!, mimeType!, part.destinationUri!, fileSize: Int(part.destinationURL!.fileSize))
                }
                var localLibraryItem = Database.shared.getLocalLibraryItem(byServerLibraryItemId: libraryItem.id)
                if (localLibraryItem != nil && localLibraryItem!.isPodcast) {
                    try? Realm().write {
                        try? localLibraryItem?.addFiles(files, item: libraryItem)
                    }
                } else {
                    localLibraryItem = LocalLibraryItem(libraryItem, localUrl: localDirectory, server: Store.serverConfig!, files: files, coverPath: coverFile)
                    try? Database.shared.saveLocalLibraryItem(localLibraryItem: localLibraryItem!)
                }
                
                statusNotification["localLibraryItem"] = try? localLibraryItem.asDictionary()
                
                if let progress = libraryItem.userMediaProgress {
                    let episode = downloadItem.media?.episodes.first(where: { $0.id == downloadItem.episodeId })
                    let localMediaProgress = LocalMediaProgress(localLibraryItem: localLibraryItem!, episode: episode, progress: progress)
                    try? localMediaProgress.save()
                    statusNotification["localMediaProgress"] = try? localMediaProgress.asDictionary()
                }
                
                self?.notifyListeners("onItemDownloadComplete", data: statusNotification)
            }
        } else {
            self.notifyListeners("onItemDownloadComplete", data: statusNotification)
        }
    }
    
    
    // MARK: - Capacitor functions
    
    @objc func downloadLibraryItem(_ call: CAPPluginCall) {
        let libraryItemId = call.getString("libraryItemId")
        var episodeId = call.getString("episodeId")
        if ( episodeId == "null" ) { episodeId = nil }
        
        logger.log("Download library item \(libraryItemId ?? "N/A") / episode \(episodeId ?? "N/A")")
        guard let libraryItemId = libraryItemId else { return call.resolve(["error": "libraryItemId not specified"]) }
        
        ApiClient.getLibraryItemWithProgress(libraryItemId: libraryItemId, episodeId: episodeId) { [weak self] libraryItem in
            if let libraryItem = libraryItem {
                self?.logger.log("Got library item from server \(libraryItem.id)")
                do {
                    if let episodeId = episodeId {
                        // Download a podcast episode
                        guard libraryItem.mediaType == "podcast" else { throw LibraryItemDownloadError.libraryItemNotPodcast }
                        let episode = libraryItem.media?.episodes.enumerated().first(where: { $1.id == episodeId })?.element
                        guard let episode = episode else { throw LibraryItemDownloadError.podcastEpisodeNotFound }
                        try self?.startLibraryItemDownload(libraryItem, episode: episode)
                    } else {
                        // Download a book
                        try self?.startLibraryItemDownload(libraryItem)
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
        let tracks = List<AudioTrack>()
        var episodeId: String?
        
        // Handle the different media type downloads
        switch item.mediaType {
        case "book":
            guard item.media?.tracks.count ?? 0 > 0 || item.media?.ebookFile != nil else { throw LibraryItemDownloadError.noTracks }
            item.media?.tracks.forEach { t in tracks.append(AudioTrack.detachCopy(of: t)!) }
        case "podcast":
            guard let episode = episode else { throw LibraryItemDownloadError.podcastEpisodeNotFound }
            guard let podcastTrack = episode.audioTrack else { throw LibraryItemDownloadError.noTracks }
            episodeId = episode.id
            tracks.append(AudioTrack.detachCopy(of: podcastTrack)!)
        default:
            throw LibraryItemDownloadError.unknownMediaType
        }
        
        // Queue up everything for downloading
        let downloadItem = DownloadItem(libraryItem: item, episodeId: episodeId, server: Store.serverConfig!)
        var tasks = [DownloadItemPartTask]()
        for (i, track) in tracks.enumerated() {
            let task = try startLibraryItemTrackDownload(downloadItemId: downloadItem.id!, item: item, position: i, track: track, episode: episode)
            downloadItem.downloadItemParts.append(task.part)
            tasks.append(task)
        }
        
        if (item.media?.ebookFile != nil) {
            let task = try startLibraryItemEbookDownload(downloadItemId: downloadItem.id!, item: item, ebookFile: item.media!.ebookFile!)
            downloadItem.downloadItemParts.append(task.part)
            tasks.append(task)
        }
        
        // Also download the cover
        if item.media?.coverPath != nil && !(item.media?.coverPath!.isEmpty ?? true) {
            if let task = try? startLibraryItemCoverDownload(downloadItemId: downloadItem.id!, item: item) {
                downloadItem.downloadItemParts.append(task.part)
                tasks.append(task)
            }
        }
        
        // Notify client of download item
        try? self.notifyListeners("onDownloadItem", data: downloadItem.asDictionary())
        
        // Persist in the database before status start coming in
        try Database.shared.saveDownloadItem(downloadItem)
        
        // Start all the downloads
        for task in tasks {
            task.task.resume()
        }
    }
    
    private func startLibraryItemTrackDownload(downloadItemId: String, item: LibraryItem, position: Int, track: AudioTrack, episode: PodcastEpisode?) throws -> DownloadItemPartTask {
        logger.log("TRACK \(track.contentUrl!)")
        
        // If we don't name metadata, then we can't proceed
        guard let filename = track.metadata?.filename else {
            throw LibraryItemDownloadError.noMetadata
        }
        
        let serverUrl = urlForTrack(item: item, track: track)
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = "\(itemDirectory)/\(filename)"
        
        let task = session.downloadTask(with: serverUrl)
        let part = DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: localUrl, itemTitle: track.title ?? "Unknown", serverPath: Store.serverConfig!.address, audioTrack: track, episode: episode, ebookFile: nil, size: track.metadata?.size ?? 0)
        
        // Store the id on the task so the download item can be pulled from the database later
        task.taskDescription = part.id
        
        return DownloadItemPartTask(part: part, task: task)
    }
    
    private func startLibraryItemEbookDownload(downloadItemId: String, item: LibraryItem, ebookFile: EBookFile) throws -> DownloadItemPartTask {
        let filename = ebookFile.metadata?.filename ?? "ebook.\(ebookFile.ebookFormat)"
        let serverPath = "/api/items/\(item.id)/file/\(ebookFile.ino)/download"
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = "\(itemDirectory)/\(filename)"
        
        let part = DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: localUrl, itemTitle: filename, serverPath: serverPath, audioTrack: nil, episode: nil, ebookFile: ebookFile, size: ebookFile.metadata?.size ?? 0)
        let task = session.downloadTask(with: part.downloadURL!)
        
        // Store the id on the task so the download item can be pulled from the database later
        task.taskDescription = part.id
        
        return DownloadItemPartTask(part: part, task: task)
    }
    
    private func startLibraryItemCoverDownload(downloadItemId: String, item: LibraryItem) throws -> DownloadItemPartTask {
        let filename = "cover.jpg"
        let serverPath = "/api/items/\(item.id)/cover"
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = "\(itemDirectory)/\(filename)"
        
        // Find library file to get cover size
        let coverLibraryFile = item.libraryFiles.first(where: {
            $0.metadata?.path == item.media?.coverPath
        })
        
        let part = DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: localUrl, itemTitle: "cover", serverPath: serverPath, audioTrack: nil, episode: nil, ebookFile: nil, size: coverLibraryFile?.metadata?.size ?? 0)
        let task = session.downloadTask(with: part.downloadURL!)
        
        // Store the id on the task so the download item can be pulled from the database later
        task.taskDescription = part.id
        
        return DownloadItemPartTask(part: part, task: task)
    }
    
    private func urlForTrack(item: LibraryItem, track: AudioTrack) -> URL {
        // TODO: Future server release should include ino with AudioFile or FileMetadata
        let trackPath = track.metadata?.path ?? ""
        
        var audioFileIno = ""
        if (item.mediaType == "podcast") {
            let podcastEpisodes = item.media?.episodes ?? List<PodcastEpisode>()
            let matchingEpisode = podcastEpisodes.first(where: { $0.audioFile?.metadata?.path == trackPath })
            audioFileIno = matchingEpisode?.audioFile?.ino ?? ""
        } else {
            let audioFiles = item.media?.audioFiles ?? List<AudioFile>()
            let matchingAudioFile = audioFiles.first(where: { $0.metadata?.path == trackPath })
            audioFileIno = matchingAudioFile?.ino ?? ""
        }

        let urlstr = "\(Store.serverConfig!.address)/api/items/\(item.id)/file/\(audioFileIno)/download?token=\(Store.serverConfig!.token)"
        return URL(string: urlstr)!
    }
    
    private func createLibraryItemFileDirectory(item: LibraryItem) throws -> String {
        let itemDirectory = item.id
        logger.log("ITEM DIR \(itemDirectory)")
        
        guard AbsDownloader.itemDownloadFolder(path: itemDirectory) != nil else {
            logger.error("Failed to CREATE LI DIRECTORY \(itemDirectory)")
            throw LibraryItemDownloadError.failedDirectory
        }
        
        return itemDirectory
    }
    
    static func itemDownloadFolder(path: String) -> URL? {
        do {
            var itemFolder = AbsDownloader.downloadsDirectory.appendingPathComponent(path)
            
            if !FileManager.default.fileExists(atPath: itemFolder.path) {
                try FileManager.default.createDirectory(at: itemFolder, withIntermediateDirectories: true)
            }
            
            // Make sure we don't backup download files to iCloud
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try itemFolder.setResourceValues(resourceValues)
            
            return itemFolder
        } catch {
            AppLogger().error("Failed to CREATE LI DIRECTORY \(error)")
            return nil
        }
    }
    
}


// MARK: - Class structs

typealias DownloadProgressHandler = (_ downloadItem: DownloadItem, _ downloadItemPart: DownloadItemPart) throws -> Void

struct DownloadItemPartTask {
    let part: DownloadItemPart
    let task: URLSessionDownloadTask
}

enum LibraryItemDownloadError: String, Error {
    case noTracks = "No tracks on library item"
    case noMetadata = "No metadata for track, unable to download"
    case libraryItemNotPodcast = "Library item is not a podcast but episode was requested"
    case podcastEpisodeNotFound = "Invalid podcast episode not found"
    case podcastOnlySupported = "Only podcasts are supported for this function"
    case unknownMediaType = "Unknown media type"
    case failedDirectory = "Failed to create directory"
    case failedDownload = "Failed to download item"
    case noTaskDescription = "No task description"
    case downloadItemNotFound = "DownloadItem not found"
    case downloadItemPartNotFound = "DownloadItemPart not found"
    case downloadItemPartDestinationUrlNotDefined = "DownloadItemPart destination URL not defined"
    case libraryItemNotFound = "LibraryItem not found for id"
}
