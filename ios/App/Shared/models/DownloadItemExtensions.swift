//
//  DownloadItemExtensions.swift
//  App
//
//  Created by Ron Heft on 8/9/22.
//

import Foundation

extension DownloadItem {
    convenience init(libraryItem: LibraryItem, episodeId: String?, server: ServerConnectionConfig) {
        self.init()
        
        self.id = libraryItem.id
        self.libraryItemId = libraryItem.id
        self.userMediaProgress = libraryItem.userMediaProgress
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
        self.mediaType = libraryItem.mediaType
        self.itemTitle = libraryItem.media?.metadata?.title
        self.media = libraryItem.media
        
        if let episodeId = episodeId {
            self.id! += "-\(episodeId)"
            self.episodeId = episodeId
        }
    }
    
    func isDoneDownloading() -> Bool {
        self.downloadItemParts.allSatisfy({ $0.completed })
    }
    
    func didDownloadSuccessfully() -> Bool {
        self.downloadItemParts.allSatisfy({ $0.failed == false })
    }
}

extension DownloadItemPart {
    convenience init(filename: String, destination: String, itemTitle: String, serverPath: String, audioTrack: AudioTrack?, episode: PodcastEpisode?) {
        self.init()
        
        self.filename = filename
        self.itemTitle = itemTitle
        self.serverPath = serverPath
        self.audioTrack = audioTrack
        self.episode = episode
        
        let config = Store.serverConfig!
        var downloadUrl = "\(config.address)\(serverPath)?token=\(config.token)"
        if (serverPath.hasSuffix("/cover")) {
            downloadUrl += "&format=jpeg" // For cover images force to jpeg
        }
        self.uri = downloadUrl
        self.destinationUri = destination
    }
    
    var downloadURL: URL? {
        if let uri = self.uri {
            return URL(string: uri)
        } else {
            return nil
        }
    }
    
    var destinationURL: URL? {
        if let destinationUri = self.destinationUri {
            return AbsDownloader.downloadsDirectory.appendingPathComponent(destinationUri)
        } else {
            return nil
        }
    }
    
    func mimeType() -> String? {
        audioTrack?.mimeType ?? episode?.audioTrack?.mimeType
    }
}
