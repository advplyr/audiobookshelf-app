//
//  LocalLibrary.swift
//  App
//
//  Created by benonymity on 6/15/22.
//

import Foundation
import Unrealm

struct LocalLibraryItem: Realmable, Codable {
    var id: String = "local_\(UUID().uuidString)"
    var basePath: String = ""
    var absolutePath: String = ""
    var contentUrl: String = ""
    var isInvalid: Bool = false
    var mediaType: String = ""
    var media: MediaType?
    var localFiles: [LocalFile] = []
    var coverContentUrl: String?
    var coverAbsolutePath: String?
    var isLocal: Bool = true
    var serverConnectionConfigId: String?
    var serverAddress: String?
    var serverUserId: String?
    var libraryItemId: String?
    
    static func primaryKey() -> String? {
        return "id"
    }
}

struct LocalPodcastEpisode: Realmable, Codable {
    var id: String = UUID().uuidString
    var index: Int = 0
    var episode: String?
    var episodeType: String?
    var title: String = "Unknown"
    var subtitle: String?
    var desc: String?
    var audioFile: AudioFile?
    var audioTrack: AudioTrack?
    var duration: Double = 0
    var size: Int = 0
    var serverEpisodeId: String?
    
    static func primaryKey() -> String? {
        return "id"
    }
}

struct LocalFile: Realmable, Codable {
    var id: String = UUID().uuidString
    var filename: String?
    var contentUrl: String = ""
    var absolutePath: String = ""
    var mimeType: String?
    var size: Int = 0
    
    static func primaryKey() -> String? {
        return "id"
    }
}

struct LocalMediaProgress: Realmable, Codable {
    var id: String = ""
    var localLibraryItemId: String = ""
    var localEpisodeId: String?
    var duration: Double = 0
    var progress: Double = 0
    var currentTime: Double = 0
    var isFinished: Bool = false
    var lastUpdate: Int = 0
    var startedAt: Int = 0
    var finishedAt: Int?
    // For local lib items from server to support server sync
    var serverConnectionConfigId: String?
    var serverAddress: String?
    var serverUserId: String?
    var libraryItemId: String?
    var episodeId: String?
    
    static func primaryKey() -> String? {
        return "id"
    }
}
