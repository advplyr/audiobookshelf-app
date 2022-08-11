//
//  LocalLibrary.swift
//  App
//
//  Created by benonymity on 6/15/22.
//

import Foundation
import Unrealm

struct LocalLibraryItem: Realmable, Codable {
    var id: String = ""
    var basePath: String = ""
    var _contentUrl: String?
    var isInvalid: Bool = false
    var mediaType: String = ""
    var media: MediaType?
    var localFiles: [LocalFile] = []
    var _coverContentUrl: String?
    var isLocal: Bool = true
    var serverConnectionConfigId: String?
    var serverAddress: String?
    var serverUserId: String?
    var libraryItemId: String?
    
    var contentUrl: String? {
        if let path = _contentUrl {
            return AbsDownloader.itemDownloadFolder(path: path)!.absoluteString
        } else {
            return nil
        }
    }
    
    var coverContentUrl: String? {
        if let path = self._coverContentUrl {
            return AbsDownloader.itemDownloadFolder(path: path)!.absoluteString
        } else {
            return nil
        }
    }
    
    var isBook: Bool { self.mediaType == "book" }
    var isPodcast: Bool { self.mediaType == "podcast" }
    
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
        isInvalid = try values.decode(Bool.self, forKey: .isInvalid)
        mediaType = try values.decode(String.self, forKey: .mediaType)
        media = try values.decode(MediaType.self, forKey: .media)
        localFiles = try values.decode([LocalFile].self, forKey: .localFiles)
        isLocal = try values.decode(Bool.self, forKey: .isLocal)
        serverConnectionConfigId = try? values.decode(String.self, forKey: .serverConnectionConfigId)
        serverAddress = try? values.decode(String.self, forKey: .serverAddress)
        serverUserId = try? values.decode(String.self, forKey: .serverUserId)
        libraryItemId = try? values.decode(String.self, forKey: .libraryItemId)
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

struct LocalFile: Realmable, Codable {
    var id: String = UUID().uuidString
    var filename: String?
    var _contentUrl: String = ""
    var mimeType: String?
    var size: Int = 0
    
    var contentUrl: String {
        return AbsDownloader.itemDownloadFolder(path: _contentUrl)!.absoluteString
    }
    
    static func primaryKey() -> String? {
        return "id"
    }
    
    private enum CodingKeys : String, CodingKey {
        case id, filename, contentUrl, mimeType, size
    }
    
    init() {}
    
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        filename = try values.decode(String.self, forKey: .filename)
        mimeType = try? values.decode(String.self, forKey: .mimeType)
        size = try values.decode(Int.self, forKey: .size)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(filename, forKey: .filename)
        try container.encode(contentUrl, forKey: .contentUrl)
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
