//
//  DataClasses.swift
//  App
//
//  Created by Benonymity on 4/20/22.
//

import Foundation
import CoreMedia

struct LibraryItem: Codable {
    var id: String
    var ino:String
    var libraryId: String
    var folderId: String
    var path: String
    var relPath: String
    var mtimeMs: Int64
    var ctimeMs: Int64
    var birthtimeMs: Int64
    var addedAt: Int64
    var updatedAt: Int64
    var lastScan: Int64?
    var scanVersion: String?
    var isMissing: Bool
    var isInvalid: Bool
    var mediaType: String
    var media: MediaType
    var libraryFiles: [LibraryFile]
}
struct MediaType: Codable {
    var libraryItemId: String?
    var metadata: Metadata
    var coverPath: String?
    var tags: [String]?
    var audioFiles: [AudioTrack]?
    var chapters: [Chapter]?
    var tracks: [AudioTrack]?
    var size: Int64?
    var duration: Double?
    var episodes: [PodcastEpisode]?
    var autoDownloadEpisodes: Bool?
}
struct Metadata: Codable {
    var title: String
    var subtitle: String?
    var authors: [Author]?
    var narrators: [String]?
    var genres: [String]
    var publishedYear: String?
    var publishedDate: String?
    var publisher: String?
    var description: String?
    var isbn: String?
    var asin: String?
    var language: String?
    var explicit: Bool
    var authorName: String?
    var authorNameLF: String?
    var narratorName: String?
    var seriesName: String?
    var feedUrl: String?
}
struct PodcastEpisode: Codable {
    var id: String
    var index: Int
    var episode: String?
    var episodeType: String?
    var title: String
    var subtitle: String?
    var description: String?
    var audioFile: AudioFile?
    var audioTrack: AudioTrack?
    var duration: Double
    var size: Int64
//    var serverEpisodeId: String?
}
struct AudioFile: Codable {
    var index: Int
    var ino: String
    var metadata: FileMetadata
}
struct Author: Codable {
    var id: String
    var name: String
    var coverPath: String?
}
struct Chapter: Codable {
    var id: Int
    var start: Double
    var end: Double
    var title: String?
}
struct AudioTrack: Codable {
    var index: Int?
    var startOffset: Double?
    var duration: Double
    var title: String?
    var contentUrl: String?
    var mimeType: String
    var metadata: FileMetadata?
    // var isLocal: Bool
    // var localFileId: String?
    // var audioProbeResult: AudioProbeResult? Needed for local playback
    var serverIndex: Int?
}
struct FileMetadata: Codable {
    var filename: String
    var ext: String
    var path: String
    var relPath: String
}
struct Library: Codable {
    var id: String
    var name: String
    var folders: [Folder]
    var icon: String
    var mediaType: String
}
struct Folder: Codable {
    var id: String
    var fullPath: String
}
struct LibraryFile: Codable {
    var ino: String
    var metadata: FileMetadata
}
