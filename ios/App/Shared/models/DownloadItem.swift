//
//  DownloadItem.swift
//  App
//
//  Created by Ron Heft on 8/5/22.
//

import Foundation
import Unrealm

struct DownloadItem: Realmable, Codable {
    var id: String?
    var libraryItemId: String?
    var episodeId: String?
    var userMediaProgress: MediaProgress?
    var serverConnectionConfigId: String?
    var serverAddress: String?
    var serverUserId: String?
    var mediaType: String?
    var itemTitle: String?
    var media: MediaType?
    var downloadItemParts: [DownloadItemPart] = []
    
    static func primaryKey() -> String? {
        return "id"
    }
    
    static func indexedProperties() -> [String] {
        ["libraryItemId"]
    }
    
    private enum CodingKeys : String, CodingKey {
        case id, libraryItemId, episodeId, serverConnectionConfigId, serverAddress, serverUserId, mediaType, itemTitle, downloadItemParts
    }
}

extension DownloadItem {
    init(libraryItem: LibraryItem, episodeId: String?, server: ServerConnectionConfig) {
        self.id = libraryItem.id
        self.libraryItemId = libraryItem.id
        self.userMediaProgress = libraryItem.userMediaProgress
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
        self.mediaType = libraryItem.mediaType
        self.itemTitle = libraryItem.media.metadata.title
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

struct DownloadItemPart: Realmable, Codable {
    var id: String = UUID().uuidString
    var filename: String?
    var itemTitle: String?
    var serverPath: String?
    var audioTrack: AudioTrack?
    var episode: PodcastEpisode?
    var completed: Bool = false
    var moved: Bool = false
    var failed: Bool = false
    var uri: String?
    var downloadURL: URL? {
        if let uri = self.uri {
            return URL(string: uri)
        } else {
            return nil
        }
    }
    var destinationUri: String?
    var destinationURL: URL? {
        if let destinationUri = self.destinationUri {
            return AbsDownloader.itemDownloadFolder(path: destinationUri)!
        } else {
            return nil
        }
    }
    var progress: Double = 0
    var task: URLSessionDownloadTask!
    
    static func primaryKey() -> String? {
        return "id"
    }
    
    static func ignoredProperties() -> [String] {
        ["task"]
    }
    
    private enum CodingKeys : String, CodingKey {
        case id, filename, itemTitle, completed, moved, failed, progress
    }
}

extension DownloadItemPart {
    init(filename: String, destination: String, itemTitle: String, serverPath: String, audioTrack: AudioTrack?, episode: PodcastEpisode?) {
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
    
    func mimeType() -> String? {
        if let track = audioTrack {
            return track.mimeType
        } else if let podcastTrack = episode?.audioTrack {
            return podcastTrack.mimeType
        } else if serverPath?.hasSuffix("/cover") ?? false {
            return "image/jpg"
        } else {
            return nil
        }
    }
}
