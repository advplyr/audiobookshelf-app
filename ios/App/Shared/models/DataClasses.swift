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
//    var serverEpisodeId: String?
    
    init() {
        id = ""
        index = 0
        title = "Unknown"
        duration = 0
        size = 0
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
             size
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
    
    mutating func setLocalInfo(filenameIdMap: [String: String], serverIndex: Int) {
        if let localFileId = filenameIdMap[self.metadata?.filename ?? ""] {
            self.localFileId = localFileId
            self.serverIndex = serverIndex
        }
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
