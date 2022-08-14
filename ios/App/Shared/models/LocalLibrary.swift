//
//  LocalLibrary.swift
//  App
//
//  Created by benonymity on 6/15/22.
//

import Foundation
import RealmSwift

class LocalLibraryItem: Object, Codable {
    @Persisted(primaryKey: true) var id: String = "local_\(UUID().uuidString)"
    @Persisted var basePath: String = ""
    @Persisted var _contentUrl: String?
    @Persisted var isInvalid: Bool = false
    @Persisted var mediaType: String = ""
    @Persisted var media: MediaType?
    @Persisted var localFiles = List<LocalFile>()
    @Persisted var _coverContentUrl: String?
    @Persisted var isLocal: Bool = true
    @Persisted var serverConnectionConfigId: String?
    @Persisted var serverAddress: String?
    @Persisted var serverUserId: String?
    @Persisted(indexed: true) var libraryItemId: String?

    var contentUrl: String? {
        if let path = _contentUrl {
            return AbsDownloader.itemDownloadFolder(path: path)!.absoluteString
        } else {
            return nil
        }
    }
    
    var contentDirectory: URL? {
        if let path = _contentUrl {
            return AbsDownloader.itemDownloadFolder(path: path)
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
    
    private enum CodingKeys : String, CodingKey {
        case id, basePath, contentUrl, isInvalid, mediaType, media, localFiles, coverContentUrl, isLocal, serverConnectionConfigId, serverAddress, serverUserId, libraryItemId
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()

        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        basePath = try values.decode(String.self, forKey: .basePath)
        isInvalid = try values.decode(Bool.self, forKey: .isInvalid)
        mediaType = try values.decode(String.self, forKey: .mediaType)
        media = try? values.decode(MediaType.self, forKey: .media)
        if let files = try? values.decode([LocalFile].self, forKey: .localFiles) {
            localFiles.append(objectsIn: files)
        }
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
        try container.encode(Array(localFiles), forKey: .localFiles)
        try container.encode(coverContentUrl, forKey: .coverContentUrl)
        try container.encode(isLocal, forKey: .isLocal)
        try container.encode(serverConnectionConfigId, forKey: .serverConnectionConfigId)
        try container.encode(serverAddress, forKey: .serverAddress)
        try container.encode(serverUserId, forKey: .serverUserId)
        try container.encode(libraryItemId, forKey: .libraryItemId)
    }
}

class LocalPodcastEpisode: Object, Codable {
    @Persisted(primaryKey: true) var id: String = UUID().uuidString
    @Persisted var index: Int = 0
    @Persisted var episode: String?
    @Persisted var episodeType: String?
    @Persisted var title: String = "Unknown"
    @Persisted var subtitle: String?
    @Persisted var desc: String?
    @Persisted var audioFile: AudioFile?
    @Persisted var audioTrack: AudioTrack?
    @Persisted var duration: Double = 0
    @Persisted var size: Int = 0
    @Persisted(indexed: true) var serverEpisodeId: String?
    
    private enum CodingKeys : String, CodingKey {
        case id
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
    }
}

class LocalFile: Object, Codable {
    @Persisted(primaryKey: true) var id: String = UUID().uuidString
    @Persisted var filename: String?
    @Persisted var contentUrl: String = ""
    @Persisted var mimeType: String?
    @Persisted var size: Int = 0

    var contentUrl: String { AbsDownloader.itemDownloadFolder(path: _contentUrl)!.absoluteString }
    var contentPath: URL { AbsDownloader.itemDownloadFolder(path: _contentUrl)! }
    var basePath: String? { self.filename }
    
    private enum CodingKeys : String, CodingKey {
        case id, filename, contentUrl, mimeType, size, basePath
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        filename = try? values.decode(String.self, forKey: .filename)
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
        try container.encode(basePath, forKey: .basePath)
    }
}

class LocalMediaProgress: Object, Codable {
    @Persisted(primaryKey: true) var id: String = ""
    @Persisted(indexed: true) var localLibraryItemId: String = ""
    @Persisted(indexed: true) var localEpisodeId: String?
    @Persisted var duration: Double = 0
    @Persisted var progress: Double = 0
    @Persisted var currentTime: Double = 0
    @Persisted var isFinished: Bool = false
    @Persisted var lastUpdate: Int = 0
    @Persisted var startedAt: Int = 0
    @Persisted var finishedAt: Int?
    // For local lib items from server to support server sync
    @Persisted var serverConnectionConfigId: String?
    @Persisted var serverAddress: String?
    @Persisted var serverUserId: String?
    @Persisted(indexed: true) var libraryItemId: String?
    @Persisted(indexed: true) var episodeId: String?

    var progressPercent: Int { Int(self.progress * 100) }
    
    private enum CodingKeys : String, CodingKey {
        case id, localLibraryItemId, localEpisodeId, duration, progress, currentTime, isFinished, lastUpdate, startedAt, finishedAt, serverConnectionConfigId, serverAddress, serverUserId, libraryItemId, episodeId
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()

        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        localLibraryItemId = try values.decode(String.self, forKey: .localLibraryItemId)
        localEpisodeId = try? values.decode(String.self, forKey: .localEpisodeId)
        duration = try values.decode(Double.self, forKey: .duration)
        progress = try values.decode(Double.self, forKey: .progress)
        currentTime = try values.decode(Double.self, forKey: .currentTime)
        isFinished = try values.decode(Bool.self, forKey: .isFinished)
        lastUpdate = try values.decode(Int.self, forKey: .lastUpdate)
        startedAt = try values.decode(Int.self, forKey: .startedAt)
        finishedAt = try? values.decode(Int.self, forKey: .finishedAt)
        serverConnectionConfigId = try? values.decode(String.self, forKey: .serverConnectionConfigId)
        serverAddress = try? values.decode(String.self, forKey: .serverAddress)
        serverUserId = try? values.decode(String.self, forKey: .serverUserId)
        libraryItemId = try? values.decode(String.self, forKey: .libraryItemId)
        episodeId = try? values.decode(String.self, forKey: .episodeId)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(localLibraryItemId, forKey: .localLibraryItemId)
        try container.encode(localEpisodeId, forKey: .localEpisodeId)
        try container.encode(duration, forKey: .duration)
        try container.encode(progress, forKey: .progress)
        try container.encode(currentTime, forKey: .currentTime)
        try container.encode(isFinished, forKey: .isFinished)
        try container.encode(lastUpdate, forKey: .lastUpdate)
        try container.encode(startedAt, forKey: .startedAt)
        try container.encode(finishedAt, forKey: .finishedAt)
        try container.encode(serverConnectionConfigId, forKey: .serverConnectionConfigId)
        try container.encode(serverAddress, forKey: .serverAddress)
        try container.encode(serverUserId, forKey: .serverUserId)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(episodeId, forKey: .episodeId)
    }
}
