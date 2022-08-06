//
//  DownloadItem.swift
//  App
//
//  Created by Ron Heft on 8/5/22.
//

import Foundation
import Unrealm

struct DownloadItem: Realmable, Codable {
    var id: String = UUID().uuidString
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
}

extension DownloadItem {
    init(libraryItem: LibraryItem, server: ServerConnectionConfig) {
        self.libraryItemId = libraryItem.id
        //self.episodeId // TODO
        self.userMediaProgress = libraryItem.userMediaProgress
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
        self.mediaType = libraryItem.mediaType
        self.itemTitle = libraryItem.media.metadata.title
        self.media = libraryItem.media
    }
}

struct DownloadItemPart: Realmable, Codable {
    var id: String = UUID().uuidString
    var filename: String?
    var finalDestinationPath: String?
    var itemTitle: String?
    var serverPath: String?
    var audioTrack: AudioTrack?
    var episode: PodcastEpisode?
    var completed: Bool = false
    var moved: Bool = false
    var failed: Bool = false
    var uri: String?
    var destinationUri: String?
    var finalDestinationUri: String?
    var downloadId: Int?
    var progress: Int = 0
    var task: URLSessionDownloadTask!
    
    private enum CodingKeys : String, CodingKey {
        case id, progress
    }
    
    static func ignoredProperties() -> [String] {
        ["task"]
    }
}

extension DownloadItemPart {
    init(filename: String, destination: URL, itemTitle: String, serverPath: String, audioTrack:AudioTrack?, episode: PodcastEpisode?) {
        var downloadUrl = "" // TODO: Set this
        if (serverPath.hasSuffix("/cover")) {
            downloadUrl += "&format=jpeg" // For cover images force to jpeg
        }
    }
}
