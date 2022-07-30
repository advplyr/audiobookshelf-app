//
//  LocalLibrary.swift
//  App
//
//  Created by benonymity on 6/15/22.
//

import Foundation
import RealmSwift

class LocalLibraryItem: Object, Encodable {
    @Persisted(primaryKey: true) var id: String = "local_\(UUID().uuidString)"
    @Persisted var basePath: String = ""
    @Persisted var absolutePath: String = ""
    @Persisted var contentUrl: String
    @Persisted var isInvalid: Bool = false
    @Persisted var mediaType: String
    @Persisted var media: LocalMediaType?
    @Persisted var localFiles: List<LocalFile>
    @Persisted var coverContentUrl: String? = nil
    @Persisted var coverAbsolutePath: String? = nil
    @Persisted var isLocal: Bool = true
    @Persisted var serverConnectionConfigId: String? = nil
    @Persisted var serverAddress: String? = nil
    @Persisted var serverUserId: String? = nil
    @Persisted var libraryItemId: String? = nil
}

class LocalMediaType: Object, Encodable {
    @Persisted var libraryItemId: String? = ""
    @Persisted var metadata: LocalMetadata?
    @Persisted var coverPath: String? = ""
    @Persisted var tags: List<String?>
    @Persisted var audioFiles: List<LocalAudioFile>
    @Persisted var chapters: List<LocalChapter>
    @Persisted var tracks: List<LocalAudioTrack>
    @Persisted var size: Int64? = nil
    @Persisted var duration: Double? = nil
    @Persisted var episodes: List<LocalPodcastEpisode>
    @Persisted var autoDownloadEpisodes: Bool? = nil
}

class LocalMetadata: Object, Encodable {
    @Persisted var title: String
    @Persisted var subtitle: String? = ""
    @Persisted var authors: List<LocalAuthor>
    @Persisted var narrators: List<String?>
    @Persisted var genres: List<String?>
    @Persisted var publishedYear: String? = ""
    @Persisted var publishedDate: String? = ""
    @Persisted var publisher: String? = ""
    @Persisted var desc: String? = ""
    @Persisted var isbn: String? = ""
    @Persisted var asin: String? = ""
    @Persisted var language: String? = ""
    @Persisted var explicit: Bool
    @Persisted var authorName: String? = ""
    @Persisted var authorNameLF: String? = ""
    @Persisted var narratorName: String? = ""
    @Persisted var seriesName: String? = ""
    @Persisted var feedUrl: String? = ""
}

class LocalPodcastEpisode: Object, Encodable {
    @Persisted var id: String = UUID().uuidString
    @Persisted var index: Int
    @Persisted var episode: String? = ""
    @Persisted var episodeType: String? = ""
    @Persisted var title: String
    @Persisted var subtitle: String? = ""
    @Persisted var desc: String? = ""
    @Persisted var audioFile: LocalAudioFile? = nil
    @Persisted var audioTrack: LocalAudioTrack? = nil
    @Persisted var duration: Double
    @Persisted var size: Int64
    @Persisted var serverEpisodeId: String?
}

class LocalAudioFile: Object, Encodable {
    @Persisted var index: Int
    @Persisted var ino: String
    @Persisted var metadata: LocalFileMetadata?
}

class LocalAuthor: Object, Encodable {
    @Persisted var id: String = UUID().uuidString
    @Persisted var name: String
    @Persisted var coverPath: String? = ""
}

class LocalChapter: Object, Encodable {
    @Persisted var id: Int
    @Persisted var start: Double
    @Persisted var end: Double
    @Persisted var title: String? = nil
}

class LocalAudioTrack: Object, Encodable {
    @Persisted var index: Int? = nil
    @Persisted var startOffset: Double? = nil
    @Persisted var duration: Double
    @Persisted var title: String? = ""
    @Persisted var contentUrl: String? = ""
    @Persisted var mimeType: String
    @Persisted var metadata: LocalFileMetadata? = nil
    @Persisted var isLocal: Bool = true
    @Persisted var localFileId: String? = ""
    @Persisted var serverIndex: Int? = nil
}

class LocalFileMetadata: Object, Encodable {
    @Persisted var filename: String
    @Persisted var ext: String
    @Persisted var path: String
    @Persisted var relPath: String
}

class LocalFile: Object, Encodable {
    @Persisted var id: String = UUID().uuidString
    @Persisted var filename: String? = ""
    @Persisted var contentUrl: String
    @Persisted var absolutePath: String
    @Persisted var mimeType: String? = ""
    @Persisted var size: Int64
}

class LocalMediaProgress: Object, Encodable {
    @Persisted var id: String = UUID().uuidString
    @Persisted var localLibraryItemId: String
    @Persisted var localEpisodeId: String? = ""
    @Persisted var duration: Double
    @Persisted var progress: Double // 0 to 1
    @Persisted var currentTime: Double
    @Persisted var isFinished: Bool
    @Persisted var lastUpdate: Int64
    @Persisted var startedAt: Int64
    @Persisted var finishedAt: Int64? = nil
    // For local lib items from server to support server sync
    @Persisted var serverConnectionConfigId: String? = ""
    @Persisted var serverAddress: String? = ""
    @Persisted var serverUserId: String? = ""
    @Persisted var libraryItemId: String? = ""
    @Persisted var episodeId: String? = ""
}
