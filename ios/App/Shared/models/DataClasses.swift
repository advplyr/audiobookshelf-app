//
//  DataClasses.swift
//  App
//
//  Created by benonymity on 4/20/22.
//

import Foundation
import CoreMedia
import Unrealm

struct LibraryItem: Realmable, Codable {
    var id: String
    var ino: String
    var libraryId: String
    var folderId: String
    var path: String
    var relPath: String
    var isFile: Bool
    var mtimeMs: Int
    var ctimeMs: Int
    var birthtimeMs: Int
    var addedAt: Int
    var updatedAt: Int
    var lastScan: Int?
    var scanVersion: String?
    var isMissing: Bool
    var isInvalid: Bool
    var mediaType: String
    var media: MediaType
    var libraryFiles: [LibraryFile]
    var userMediaProgress: MediaProgress?
    
    init() {
        id = ""
        ino = ""
        libraryId = ""
        folderId = ""
        path = ""
        relPath = ""
        isFile = true
        mtimeMs = 0
        ctimeMs = 0
        birthtimeMs = 0
        addedAt = 0
        updatedAt = 0
        isMissing = false
        isInvalid = false
        mediaType = ""
        media = MediaType()
        libraryFiles = []
    }
}

struct MediaType: Realmable, Codable {
    var libraryItemId: String?
    var metadata: Metadata
    var coverPath: String?
    var tags: [String]?
    var audioFiles: [AudioFile]?
    var chapters: [Chapter]?
    var tracks: [AudioTrack]?
    var size: Int?
    var duration: Double?
    var episodes: [PodcastEpisode]?
    var autoDownloadEpisodes: Bool?
    
    init() {
        metadata = Metadata()
    }
}

struct Metadata: Realmable, Codable {
    var title: String
    var subtitle: String?
    var authors: [Author]?
    var narrators: [String]?
    var genres: [String]
    var publishedYear: String?
    var publishedDate: String?
    var publisher: String?
    var desc: String?
    var isbn: String?
    var asin: String?
    var language: String?
    var explicit: Bool
    var authorName: String?
    var authorNameLF: String?
    var narratorName: String?
    var seriesName: String?
    var feedUrl: String?
    
    init() {
        title = "Unknown"
        genres = []
        explicit = false
    }
    
    private enum CodingKeys : String, CodingKey {
        case title,
             subtitle,
             authors,
             narrators,
             genres,
             publishedYear,
             publishedDate,
             publisher,
             desc = "description", // Fixes a collision with the base Swift object's field "description"
             isbn,
             asin,
             language,
             explicit,
             authorName,
             authorNameLF,
             narratorName,
             seriesName,
             feedUrl
    }
}

struct PodcastEpisode: Realmable, Codable {
    var id: String
    var index: Int?
    var episode: String?
    var episodeType: String?
    var title: String
    var subtitle: String?
    var desc: String?
    var audioFile: AudioFile?
    var audioTrack: AudioTrack?
    var duration: Double?
    var size: Int?
    var serverEpisodeId: String { self.id }
    
    init() {
        id = ""
        index = 0
        title = "Unknown"
        duration = 0
        size = 0
    }
    
    static func ignoredProperties() -> [String] {
        ["serverEpisodeId"]
    }
    
    private enum CodingKeys : String, CodingKey {
        case id,
             index,
             episode,
             episodeType,
             title,
             subtitle,
             desc = "description", // Fixes a collision with the base Swift object's field "description"
             audioFile,
             audioTrack,
             duration,
             size,
             serverEpisodeId
    }
    
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        index = try? values.decode(Int.self, forKey: .index)
        episode = try? values.decode(String.self, forKey: .episode)
        episodeType = try? values.decode(String.self, forKey: .episodeType)
        title = try values.decode(String.self, forKey: .title)
        subtitle = try? values.decode(String.self, forKey: .subtitle)
        desc = try? values.decode(String.self, forKey: .desc)
        audioFile = try? values.decode(AudioFile.self, forKey: .audioFile)
        audioTrack = try? values.decode(AudioTrack.self, forKey: .audioTrack)
        duration = try? values.decode(Double.self, forKey: .duration)
        size = try? values.decode(Int.self, forKey: .size)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(index, forKey: .index)
        try container.encode(episode, forKey: .episode)
        try container.encode(episodeType, forKey: .episodeType)
        try container.encode(title, forKey: .title)
        try container.encode(subtitle, forKey: .subtitle)
        try container.encode(desc, forKey: .desc)
        try container.encode(audioFile, forKey: .audioFile)
        try container.encode(audioTrack, forKey: .audioTrack)
        try container.encode(duration, forKey: .duration)
        try container.encode(size, forKey: .size)
        try container.encode(serverEpisodeId, forKey: .serverEpisodeId)
    }
}

struct AudioFile: Realmable, Codable {
    var index: Int?
    var ino: String
    var metadata: FileMetadata
    
    init() {
        ino = ""
        metadata = FileMetadata()
    }
}

struct Author: Realmable, Codable {
    var id: String
    var name: String
    var coverPath: String?
    
    init() {
        id = ""
        name = "Unknown"
    }
}

struct Chapter: Realmable, Codable {
    var id: Int
    var start: Double
    var end: Double
    var title: String?
    
    init() {
        id = 0
        start = 0
        end = 0
    }
}

struct AudioTrack: Realmable, Codable {
    var index: Int?
    var startOffset: Double?
    var duration: Double
    var title: String?
    var contentUrl: String?
    var mimeType: String
    var metadata: FileMetadata?
    var localFileId: String?
    // var audioProbeResult: AudioProbeResult? Needed for local playback
    var serverIndex: Int?
    
    init() {
        duration = 0
        mimeType = ""
    }
    
    mutating func setLocalInfo(filenameIdMap: [String: String], serverIndex: Int) -> Bool {
        if let localFileId = filenameIdMap[self.metadata?.filename ?? ""] {
            self.localFileId = localFileId
            self.serverIndex = serverIndex
            return true
        }
        return false
    }
}

struct FileMetadata: Realmable, Codable {
    var filename: String
    var ext: String
    var path: String
    var relPath: String
    
    init() {
        filename = ""
        ext = ""
        path = ""
        relPath = ""
    }
}

struct Library: Realmable, Codable {
    var id: String
    var name: String
    var folders: [Folder]
    var icon: String
    var mediaType: String
    
    init() {
        id = ""
        name = "Unknown"
        folders = []
        icon = ""
        mediaType = ""
    }
}

struct Folder: Realmable, Codable {
    var id: String
    var fullPath: String
    
    init() {
        id = ""
        fullPath = ""
    }
}

struct LibraryFile: Realmable, Codable {
    var ino: String
    var metadata: FileMetadata
    
    init() {
        ino = ""
        metadata = FileMetadata()
    }
}

struct MediaProgress: Realmable, Codable {
    var id: String
    var libraryItemId: String
    var episodeId: String?
    var duration: Double
    var progress: Double
    var currentTime: Double
    var isFinished: Bool
    var lastUpdate: Int
    var startedAt: Int
    var finishedAt: Int?
    
    init() {
        id = ""
        libraryItemId = ""
        duration = 0
        progress = 0
        currentTime = 0
        isFinished = false
        lastUpdate = 0
        startedAt = 0
    }
    
    private enum CodingKeys : String, CodingKey {
        case id, libraryItemId, episodeId, duration, progress, currentTime, isFinished, lastUpdate, startedAt, finishedAt
    }
    
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        libraryItemId = try values.decode(String.self, forKey: .libraryItemId)
        episodeId = try? values.decode(String.self, forKey: .episodeId)
        duration = try MediaProgress.doubleOrStringDecoder(from: decoder, with: values, key: .duration)
        progress = try MediaProgress.doubleOrStringDecoder(from: decoder, with: values, key: .progress)
        currentTime = try MediaProgress.doubleOrStringDecoder(from: decoder, with: values, key: .currentTime)
        isFinished = try values.decode(Bool.self, forKey: .isFinished)
        lastUpdate = try MediaProgress.intOrStringDecoder(from: decoder, with: values, key: .lastUpdate)
        startedAt = try MediaProgress.intOrStringDecoder(from: decoder, with: values, key: .startedAt)
        finishedAt = try? MediaProgress.intOrStringDecoder(from: decoder, with: values, key: .finishedAt)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(episodeId, forKey: .episodeId)
        try container.encode(duration, forKey: .duration)
        try container.encode(progress, forKey: .progress)
        try container.encode(currentTime, forKey: .currentTime)
        try container.encode(isFinished, forKey: .isFinished)
        try container.encode(lastUpdate, forKey: .lastUpdate)
        try container.encode(startedAt, forKey: .startedAt)
        try container.encode(finishedAt, forKey: .finishedAt)
    }
    
    static private func doubleOrStringDecoder(from decoder: Decoder, with values: KeyedDecodingContainer<CodingKeys>, key: MediaProgress.CodingKeys) throws -> Double {
        do {
            return try values.decode(Double.self, forKey: key)
        } catch {
            let stringDuration = try values.decode(String.self, forKey: key)
            return Double(stringDuration) ?? 0.0
        }
    }
    
    static private func intOrStringDecoder(from decoder: Decoder, with values: KeyedDecodingContainer<CodingKeys>, key: MediaProgress.CodingKeys) throws -> Int {
        do {
            return try values.decode(Int.self, forKey: key)
        } catch {
            let stringDuration = try values.decode(String.self, forKey: key)
            return Int(stringDuration) ?? 0
        }
    }
}

struct PlaybackMetadata: Realmable, Codable {
    var duration: Double
    var currentTime: Double
    var playerState: PlayerState
    
    init() {
        duration = 0
        currentTime = 0
        playerState = PlayerState.IDLE
    }
    
    static func ignoredProperties() -> [String] {
        return ["playerState"]
    }
}

enum PlayerState: Codable {
    case IDLE
    case BUFFERING
    case READY
    case ENDED
}
