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
    dynamic var _contentUrl: String?
    var isInvalid: Bool = false
    var mediaType: String = ""
    var media: MediaType?
    var localFiles: [LocalFile] = []
    dynamic var _coverContentUrl: String?
    var isLocal: Bool = true
    var serverConnectionConfigId: String?
    var serverAddress: String?
    var serverUserId: String?
    var libraryItemId: String?
    
    var contentUrl: String? {
        set(url) {
            _contentUrl = url
        }
        get {
            if let path = _contentUrl {
                return AbsDownloader.downloadsDirectory.appendingPathComponent(path).absoluteString
            } else {
                return nil
            }
        }
    }
    
    var coverContentUrl: String? {
        set(url) {
            _coverContentUrl = url
        }
        get {
            if let path = self._coverContentUrl {
                return AbsDownloader.downloadsDirectory.appendingPathComponent(path).absoluteString
            } else {
                return nil
            }
        }
    }
    
    static func primaryKey() -> String? {
        return "id"
    }
    
    private enum CodingKeys : String, CodingKey {
        case id, basePath, contentUrl, isInvalid, mediaType, media, localFiles, coverContentUrl, isLocal, serverConnectionConfigId, serverAddress, serverUserId, libraryItemId
    }
    
    init() {}
    
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        basePath = try values.decode(String.self, forKey: .basePath)
        contentUrl = try values.decode(String.self, forKey: .contentUrl)
        isInvalid = try values.decode(Bool.self, forKey: .isInvalid)
        mediaType = try values.decode(String.self, forKey: .mediaType)
        media = try values.decode(MediaType.self, forKey: .media)
        localFiles = try values.decode([LocalFile].self, forKey: .localFiles)
        coverContentUrl = try values.decode(String.self, forKey: .coverContentUrl)
        isLocal = try values.decode(Bool.self, forKey: .isLocal)
        serverConnectionConfigId = try values.decode(String.self, forKey: .serverConnectionConfigId)
        serverAddress = try values.decode(String.self, forKey: .serverAddress)
        serverUserId = try values.decode(String.self, forKey: .serverUserId)
        libraryItemId = try values.decode(String.self, forKey: .libraryItemId)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(basePath, forKey: .basePath)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(isInvalid, forKey: .isInvalid)
        try container.encode(mediaType, forKey: .mediaType)
        try container.encode(media, forKey: .media)
        try container.encode(localFiles, forKey: .localFiles)
        try container.encode(coverContentUrl, forKey: .coverContentUrl)
        try container.encode(isLocal, forKey: .isLocal)
        try container.encode(serverConnectionConfigId, forKey: .serverConnectionConfigId)
        try container.encode(serverAddress, forKey: .serverAddress)
        try container.encode(serverUserId, forKey: .serverUserId)
        try container.encode(libraryItemId, forKey: .libraryItemId)
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
    var absolutePath: String {
        return AbsDownloader.downloadsDirectory.appendingPathComponent(self.contentUrl).absoluteString
    }
    var mimeType: String?
    var size: Int = 0
    
    static func primaryKey() -> String? {
        return "id"
    }
    
    private enum CodingKeys : String, CodingKey {
        case id, filename, contentUrl, absolutePath, mimeType, size
    }
    
    init() {}
    
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        filename = try values.decode(String.self, forKey: .filename)
        contentUrl = try values.decode(String.self, forKey: .contentUrl)
        mimeType = try values.decode(String.self, forKey: .mimeType)
        size = try values.decode(Int.self, forKey: .size)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(filename, forKey: .filename)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(absolutePath, forKey: .absolutePath)
        try container.encode(mimeType, forKey: .mimeType)
        try container.encode(size, forKey: .size)
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
