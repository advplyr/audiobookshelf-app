//
//  LocalLibrary.swift
//  App
//
//  Created by benonymity on 6/15/22.
//

import Foundation
import RealmSwift


class LocalLibraryItem: Object, Encodable {
    @Persisted(primaryKey: true) var id: String
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
    
    override init() {
        super.init()
    }
    
    init(item: LibraryItem, localUrl: URL, server: ServerConnectionConfig, files: [LocalFile]) {
        super.init()
        self.id = item.id
        self.contentUrl = localUrl.absoluteString
        self.mediaType = item.mediaType
        self.media = LocalMediaType(mediaType: item.media)
        self.localFiles.append(objectsIn: files)
        // TODO: self.coverContentURL
        // TODO: self.converAbsolutePath
        self.libraryItemId = item.id
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
    }
    
    enum CodingKeys: CodingKey {
        case id
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
    }
}

class LocalMediaType: Object {
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
    
    override init() {
        super.init()
    }
    
    init(mediaType: MediaType) {
        super.init()
        self.libraryItemId = mediaType.libraryItemId
        self.metadata = LocalMetadata(metadata: mediaType.metadata)
        // TODO: self.coverPath
        self.tags.append(objectsIn: mediaType.tags ?? [])
        self.audioFiles.append(objectsIn: mediaType.audioFiles!.enumerated().map() {
            i, audioFile -> LocalAudioFile in LocalAudioFile(audioFile: audioFile)
        })
        self.chapters.append(objectsIn: mediaType.chapters!.enumerated().map() {
            i, chapter -> LocalChapter in LocalChapter(chapter: chapter)
        })
        self.tracks.append(objectsIn: mediaType.tracks!.enumerated().map() {
            i, track in LocalAudioTrack(track: track)
        })
        self.size = mediaType.size
        self.duration = mediaType.duration
        // TODO: self.episodes
        self.autoDownloadEpisodes = mediaType.autoDownloadEpisodes
    }
}

class LocalMetadata: Object {
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
    
    override init() {
        super.init()
    }
    
    init(metadata: Metadata) {
        super.init()
        self.title = metadata.title
        self.subtitle = metadata.subtitle
        self.narrators.append(objectsIn: metadata.narrators ?? [])
        self.genres.append(objectsIn: metadata.genres)
        self.publishedYear = metadata.publishedYear
        self.publishedDate = metadata.publishedDate
        self.publisher = metadata.publisher
        self.desc = metadata.description
        self.isbn = metadata.isbn
        self.asin = metadata.asin
        self.language = metadata.language
        self.explicit = metadata.explicit
        self.authorName = metadata.authorName
        self.authorNameLF = metadata.authorNameLF
        self.narratorName = metadata.narratorName
        self.seriesName = metadata.seriesName
        self.feedUrl = metadata.feedUrl
    }
}

class LocalPodcastEpisode: Object {
    @Persisted var id: String
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
//    @Persisted var serverEpisodeId: String?
}

class LocalAudioFile: Object {
    @Persisted var index: Int
    @Persisted var ino: String
    @Persisted var metadata: LocalFileMetadata?
    
    override init() {
        super.init()
    }
    
    init(audioFile: AudioFile) {
        self.index = audioFile.index
        self.ino = audioFile.ino
        // TODO: self.metadata
    }
}

class LocalAuthor: Object {
    @Persisted var id: String
    @Persisted var name: String
    @Persisted var coverPath: String? = ""
}

class LocalChapter: Object {
    @Persisted var id: Int
    @Persisted var start: Double
    @Persisted var end: Double
    @Persisted var title: String? = nil
    
    override init() {
        super.init()
    }
    
    init(chapter: Chapter) {
        super.init()
        self.id = chapter.id
        self.start = chapter.start
        self.end = chapter.end
        self.title = chapter.title
    }
}

class LocalAudioTrack: Object {
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
    
    override init() {
        super.init()
    }
    
    init(track: AudioTrack) {
        self.index = track.index
        self.startOffset = track.startOffset
        self.duration = track.duration
        self.title = track.title
        self.contentUrl = track.contentUrl // TODO: Different URL
        self.mimeType = track.mimeType
        // TODO: self.metadata
        // TODO: self.localFileId
        self.serverIndex = track.serverIndex
    }
}

class LocalFileMetadata: Object {
    @Persisted var filename: String
    @Persisted var ext: String
    @Persisted var path: String
    @Persisted var relPath: String
}

class LocalFile: Object {
    @Persisted var id: String
    @Persisted var filename: String? = ""
    @Persisted var contentUrl: String
    @Persisted var basePath: String
    @Persisted var absolutePath: String
    @Persisted var simplePath: String
    @Persisted var mimeType: String? = ""
    @Persisted var size: Int64
    
    override init() {
        super.init()
    }
    
    init(filename: String, localUrl: URL) {
        self.filename = filename
        self.contentUrl = localUrl.absoluteString
        // TODO: self.baseUrl
        self.absolutePath = localUrl.absoluteString
        self.simplePath = localUrl.path
        // TODO: self.mimeType
        // TODO: self.size
    }
}

class LocalMediaProgress: Object {
    @Persisted var id: String
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
