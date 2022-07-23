//
//  DataClasses.swift
//  App
//
//  Created by benonymity on 4/20/22.
//

import Foundation
import CoreMedia
import RealmSwift

struct LibraryItem: Codable {
    var id: String
    var ino:String
    var libraryId: String
    var folderId: String
    var path: String
    var relPath: String
    var isFile: Bool
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
    var userMediaProgress:MediaProgress?
}
class MediaType: Object, Codable {
    var libraryItemId: String? = ""
    var metadata: Metadata?
    var coverPath: String? = ""
    var tags: List<String?>
    var audioFiles: List<AudioFile>
    var chapters: List<Chapter>
    var tracks: List<AudioTrack>
    var size: Int64? = nil
    var duration: Double? = nil
    var episodes: List<PodcastEpisode>
    var autoDownloadEpisodes: Bool? = nil
}
class Metadata: Object, Codable {
    var title: String
    var subtitle: String? = ""
    var authors: List<Author>
    var narrators: List<String?>
    var genres: List<String?>
    var publishedYear: String? = ""
    var publishedDate: String? = ""
    var publisher: String? = ""
    // I think calling the below variable description conflicts with some public variables declared in some of the Pods we use, so it's desc. ¯\_(ツ)_/¯
    final var description: String
    var isbn: String? = ""
    var asin: String? = ""
    var language: String? = ""
    var explicit: Bool
    var authorName: String? = ""
    var authorNameLF: String? = ""
    var narratorName: String? = ""
    var seriesName: String? = ""
    var feedUrl: String? = ""
}
class PodcastEpisode: Object, Codable {
    var id: String
    var index: Int
    var episode: String? = ""
    var episodeType: String? = ""
    var title: String
    var subtitle: String? = ""
    var escription: String? = ""
    var audioFile: AudioFile? = nil
    var audioTrack: AudioTrack? = nil
    var duration: Double
    var size: Int64
//    var serverEpisodeId: String?
}
class AudioFile: Object, Codable {
    @Persisted var index: Int
    @Persisted var ino: String
    @Persisted var metadata: FileMetadata?
}
class Author: Object, Codable {
    @Persisted var id: String
    @Persisted var name: String
    @Persisted var coverPath: String? = ""
}
class Chapter: Object, Codable {
    @Persisted var id: Int
    @Persisted var start: Double
    @Persisted var end: Double
    @Persisted var title: String? = nil
}
struct AudioTrack: Codable {
    var index: Int? = nil
    var startOffset: Double? = nil
    var duration: Double
    var title: String? = ""
    var contentUrl: String? = ""
    var mimeType: String
    var metadata: FileMetadata? = nil
    var isLocal: Bool
    var localFileId: String? = ""
//     var audioProbeResult: AudioProbeResult? // Needed for local playback. Requires local FFMPEG? Not sure how doable this is on iOS
    var serverIndex: Int? = nil
}
class FileMetadata: Object, Codable {
    @Persisted var filename: String
    @Persisted var ext: String
    @Persisted var path: String
    @Persisted var relPath: String
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
    var metadata: FileMetadata?
}
struct MediaProgress: Codable {
    var id: String
    var libraryItemId: String
    var episodeId: String?
    var duration: Double
    var progress: Double
    var currentTime: Double
    var isFinished: Bool
    var lastUpdate: Int64
    var startedAt: Int64
    var finishedAt: Int64?
}
struct PlaybackMetadata: Codable {
    var duration: Double
    var currentTime: Double
    var playerState: PlayerState
}
enum PlayerState: Codable {
    case IDLE
    case BUFFERING
    case READY
    case ENDED
}
