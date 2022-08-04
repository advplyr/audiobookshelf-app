//
//  LibraryItemDownloadSession.swift
//  App
//
//  Created by Ron Heft on 8/2/22.
//

import Foundation

enum LibraryItemDownloadError: String, Error {
    case noTracks = "No tracks on library item"
    case noMetadata = "No metadata for track, unable to download"
    case failedDownload = "Failed to download item"
}

class LibraryItemDownloadSession {
    
    let item: LibraryItem
    
    private let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    
    init(_ item: LibraryItem) {
        self.item = item
    }
    
    public func startDownload() async throws -> LocalLibraryItem {
        guard let tracks = item.media.tracks else {
            throw LibraryItemDownloadError.noTracks
        }
        
        return try await withThrowingTaskGroup(of: LocalFile.self, returning: LocalLibraryItem.self) { group in
            for (position, track) in tracks.enumerated() {
                group.addTask { try await self.startLibraryItemTrackDownload(item: self.item, position: position, track: track) }
            }
            
            var files = [LocalFile]()
            for try await file in group {
                files.append(file)
            }
            
            return LocalLibraryItem(self.item, localUrl: self.documentsDirectory, server: Store.serverConfig!, files: files)
        }
    }
    
    private func startLibraryItemTrackDownload(item: LibraryItem, position: Int, track: AudioTrack) async throws -> LocalFile {
        NSLog("TRACK \(track.contentUrl!)")
        
        // If we don't name metadata, then we can't proceed
        guard let filename = track.metadata?.filename else {
            throw LibraryItemDownloadError.noMetadata
        }
        
        let serverUrl = urlForTrack(item: item, track: track)
        let itemDirectory = createLibraryItemFileDirectory(item: item)
        let localUrl = itemDirectory.appendingPathComponent("\(filename)")
        
        try await downloadFile(serverUrl: serverUrl, localUrl: localUrl)
        return LocalFile(item.id, filename, track.mimeType, localUrl)
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
    
    private func urlForTrack(item: LibraryItem, track: AudioTrack) -> URL {
        // filename needs to be encoded otherwise would just use contentUrl
        let filenameEncoded = track.metadata?.filename.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)
        let urlstr = "\(Store.serverConfig!.address)/s/item/\(item.id)/\(filenameEncoded ?? "")?token=\(Store.serverConfig!.token)"
        return URL(string: urlstr)!
    }
    
    private func downloadFile(serverUrl: URL, localUrl: URL) async throws {
        return try await withCheckedThrowingContinuation { continuation in
            let downloadTask = URLSession.shared.downloadTask(with: serverUrl) { urlOrNil, responseOrNil, errorOrNil in
                guard let tempUrl = urlOrNil else {
                    continuation.resume(throwing: errorOrNil!)
                    return
                }
                
                do {
                    try FileManager.default.moveItem(at: tempUrl, to: localUrl)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
            downloadTask.resume()
        }
    }
    
}
