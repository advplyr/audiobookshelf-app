//
//  DataClasses.swift
//  App
//
//  Created by benonymity on 4/20/22.
//

import Foundation
import CoreMedia
import RealmSwift

class LibraryItem: Object, Codable {
    @Persisted var id: String = ""
    @Persisted var ino: String = ""
    @Persisted var libraryId: String = ""
    @Persisted var folderId: String = ""
    @Persisted var path: String = ""
    @Persisted var relPath: String = ""
    @Persisted var isFile: Bool = true
    @Persisted var mtimeMs: Int = 0
    @Persisted var ctimeMs: Int = 0
    @Persisted var birthtimeMs: Int = 0
    @Persisted var addedAt: Int = 0
    @Persisted var updatedAt: Int = 0
    @Persisted var lastScan: Int?
    @Persisted var scanVersion: String?
    @Persisted var isMissing: Bool = false
    @Persisted var isInvalid: Bool = false
    @Persisted var mediaType: String = ""
    @Persisted var media: MediaType?
    @Persisted var libraryFiles = List<LibraryFile>()
    @Persisted var userMediaProgress: MediaProgress?
    
    private enum CodingKeys : String, CodingKey {
        case id, ino, libraryId, folderId, path, relPath, isFile, mtimeMs, ctimeMs, birthtimeMs, addedAt, updatedAt, lastScan, scanVersion, isMissing, isInvalid, mediaType, media, libraryFiles, userMediaProgress
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        ino = try values.decode(String.self, forKey: .ino)
        libraryId = try values.decode(String.self, forKey: .libraryId)
        folderId = try values.decode(String.self, forKey: .folderId)
        path = try values.decode(String.self, forKey: .path)
        relPath = try values.decode(String.self, forKey: .relPath)
        isFile = try values.decode(Bool.self, forKey: .isFile)
        mtimeMs = try values.decode(Int.self, forKey: .mtimeMs)
        ctimeMs = try values.decode(Int.self, forKey: .ctimeMs)
        birthtimeMs = try values.decode(Int.self, forKey: .birthtimeMs)
        addedAt = try values.decode(Int.self, forKey: .addedAt)
        updatedAt = try values.decode(Int.self, forKey: .updatedAt)
        lastScan = try? values.decode(Int.self, forKey: .lastScan)
        scanVersion = try? values.decode(String.self, forKey: .scanVersion)
        isMissing = try values.decode(Bool.self, forKey: .isMissing)
        isInvalid = try values.decode(Bool.self, forKey: .isInvalid)
        mediaType = try values.decode(String.self, forKey: .mediaType)
        media = try? values.decode(MediaType.self, forKey: .media)
        if let files = try? values.decode([LibraryFile].self, forKey: .libraryFiles) {
            libraryFiles.append(objectsIn: files)
        }
        userMediaProgress = try? values.decode(MediaProgress.self, forKey: .userMediaProgress)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(ino, forKey: .ino)
        try container.encode(libraryId, forKey: .libraryId)
        try container.encode(folderId, forKey: .folderId)
        try container.encode(path, forKey: .path)
        try container.encode(relPath, forKey: .relPath)
        try container.encode(isFile, forKey: .isFile)
        try container.encode(mtimeMs, forKey: .mtimeMs)
        try container.encode(ctimeMs, forKey: .ctimeMs)
        try container.encode(birthtimeMs, forKey: .birthtimeMs)
        try container.encode(addedAt, forKey: .addedAt)
        try container.encode(updatedAt, forKey: .updatedAt)
        try container.encode(lastScan, forKey: .lastScan)
        try container.encode(scanVersion, forKey: .scanVersion)
        try container.encode(isMissing, forKey: .isMissing)
        try container.encode(isInvalid, forKey: .isInvalid)
        try container.encode(mediaType, forKey: .mediaType)
        try container.encode(media, forKey: .media)
        try container.encode(Array(libraryFiles), forKey: .libraryFiles)
        try container.encode(userMediaProgress, forKey: .userMediaProgress)
    }
}

class MediaType: Object, Codable {
    @Persisted var libraryItemId: String?
    @Persisted var metadata: Metadata?
    @Persisted var coverPath: String?
    @Persisted var tags = List<String>()
    @Persisted var audioFiles = List<AudioFile>()
    @Persisted var chapters = List<Chapter>()
    @Persisted var tracks = List<AudioTrack>()
    @Persisted var size: Int?
    @Persisted var duration: Double?
    @Persisted var episodes = List<PodcastEpisode>()
    @Persisted var autoDownloadEpisodes: Bool?
    
    private enum CodingKeys : String, CodingKey {
        case libraryItemId, metadata, coverPath, tags, audioFiles, chapters, tracks, size, duration, episodes, autoDownloadEpisodes
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        libraryItemId = try? values.decode(String.self, forKey: .libraryItemId)
        metadata = try? values.decode(Metadata.self, forKey: .metadata)
        coverPath = try? values.decode(String.self, forKey: .coverPath)
        if let tagList = try? values.decode([String].self, forKey: .tags) {
            tags.append(objectsIn: tagList)
        }
        if let fileList = try? values.decode([AudioFile].self, forKey: .audioFiles) {
            audioFiles.append(objectsIn: fileList)
        }
        if let chapterList = try? values.decode([Chapter].self, forKey: .chapters) {
            chapters.append(objectsIn: chapterList)
        }
        if let trackList = try? values.decode([AudioTrack].self, forKey: .tracks) {
            tracks.append(objectsIn: trackList)
        }
        size = try? values.decode(Int.self, forKey: .size)
        duration = try? values.decode(Double.self, forKey: .duration)
        if let episodeList = try? values.decode([PodcastEpisode].self, forKey: .episodes) {
            episodes.append(objectsIn: episodeList)
        }
        autoDownloadEpisodes = try? values.decode(Bool.self, forKey: .autoDownloadEpisodes)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(metadata, forKey: .metadata)
        try container.encode(coverPath, forKey: .coverPath)
        try container.encode(Array(tags), forKey: .tags)
        try container.encode(Array(audioFiles), forKey: .audioFiles)
        try container.encode(Array(chapters), forKey: .chapters)
        try container.encode(Array(tracks), forKey: .tracks)
        try container.encode(size, forKey: .size)
        try container.encode(duration, forKey: .duration)
        try container.encode(Array(episodes), forKey: .episodes)
        try container.encode(autoDownloadEpisodes, forKey: .autoDownloadEpisodes)
    }
}

class Metadata: Object, Codable {
    @Persisted var title: String = "Unknown"
    @Persisted var subtitle: String?
    @Persisted var authors = List<Author>()
    @Persisted var narrators = List<String>()
    @Persisted var genres = List<String>()
    @Persisted var publishedYear: String?
    @Persisted var publishedDate: String?
    @Persisted var publisher: String?
    @Persisted var desc: String?
    @Persisted var isbn: String?
    @Persisted var asin: String?
    @Persisted var language: String?
    @Persisted var explicit: Bool = false
    @Persisted var authorName: String?
    @Persisted var authorNameLF: String?
    @Persisted var narratorName: String?
    @Persisted var seriesName: String?
    @Persisted var feedUrl: String?
    
    var authorDisplayName: String { self.authorName ?? "Unknown" }
    
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
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        title = try values.decode(String.self, forKey: .title)
        subtitle = try? values.decode(String.self, forKey: .subtitle)
        if let authorList = try? values.decode([Author].self, forKey: .authors) {
            authors.append(objectsIn: authorList)
        }
        if let narratorList = try? values.decode([String].self, forKey: .narrators) {
            narrators.append(objectsIn: narratorList)
        }
        if let genreList = try? values.decode([String].self, forKey: .genres) {
            genres.append(objectsIn: genreList)
        }
        publishedYear = try? values.decode(String.self, forKey: .publishedYear)
        publishedDate = try? values.decode(String.self, forKey: .publishedDate)
        publisher = try? values.decode(String.self, forKey: .publisher)
        desc = try? values.decode(String.self, forKey: .desc)
        isbn = try? values.decode(String.self, forKey: .isbn)
        asin = try? values.decode(String.self, forKey: .asin)
        language = try? values.decode(String.self, forKey: .language)
        explicit = try values.decode(Bool.self, forKey: .explicit)
        authorName = try? values.decode(String.self, forKey: .authorName)
        authorNameLF = try? values.decode(String.self, forKey: .authorNameLF)
        narratorName = try? values.decode(String.self, forKey: .narratorName)
        seriesName = try? values.decode(String.self, forKey: .seriesName)
        feedUrl = try? values.decode(String.self, forKey: .feedUrl)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(title, forKey: .title)
        try container.encode(subtitle, forKey: .subtitle)
        try container.encode(Array(authors), forKey: .authors)
        try container.encode(Array(narrators), forKey: .narrators)
        try container.encode(Array(genres), forKey: .genres)
        try container.encode(publishedYear, forKey: .publishedYear)
        try container.encode(publishedDate, forKey: .publishedDate)
        try container.encode(publisher, forKey: .publisher)
        try container.encode(desc, forKey: .desc)
        try container.encode(isbn, forKey: .isbn)
        try container.encode(asin, forKey: .asin)
        try container.encode(language, forKey: .language)
        try container.encode(explicit, forKey: .explicit)
        try container.encode(authorName, forKey: .authorName)
        try container.encode(authorNameLF, forKey: .authorNameLF)
        try container.encode(narratorName, forKey: .narratorName)
        try container.encode(seriesName, forKey: .seriesName)
        try container.encode(feedUrl, forKey: .feedUrl)
    }
}

class PodcastEpisode: Object, Codable {
    @Persisted var id: String = ""
    @Persisted var index: Int?
    @Persisted var episode: String?
    @Persisted var episodeType: String?
    @Persisted var title: String = "Unknown"
    @Persisted var subtitle: String?
    @Persisted var desc: String?
    @Persisted var audioFile: AudioFile?
    @Persisted var audioTrack: AudioTrack?
    @Persisted var duration: Double?
    @Persisted var size: Int?
    var serverEpisodeId: String { self.id }
    
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
    
    override init() {}
    
    required init(from decoder: Decoder) throws {
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

class AudioFile: Object, Codable {
    @Persisted var index: Int?
    @Persisted var ino: String = ""
    @Persisted var metadata: FileMetadata?
    
    private enum CodingKeys : String, CodingKey {
        case index, ino, metadata
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        index = try? values.decode(Int.self, forKey: .index)
        ino = try values.decode(String.self, forKey: .ino)
        metadata = try? values.decode(FileMetadata.self, forKey: .metadata)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(index, forKey: .index)
        try container.encode(ino, forKey: .ino)
        try container.encode(metadata, forKey: .metadata)
    }
}

class Author: Object, Codable {
    @Persisted var id: String = ""
    @Persisted var name: String = "Unknown"
    @Persisted var coverPath: String?
    
    private enum CodingKeys : String, CodingKey {
        case id, name, coverPath
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        coverPath = try? values.decode(String.self, forKey: .coverPath)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(coverPath, forKey: .coverPath)
    }
}

class Chapter: Object, Codable {
    @Persisted var id: Int = 0
    @Persisted var start: Double = 0
    @Persisted var end: Double = 0
    @Persisted var title: String?
    
    private enum CodingKeys : String, CodingKey {
        case id, start, end, title
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(Int.self, forKey: .id)
        start = try values.decode(Double.self, forKey: .start)
        end = try values.decode(Double.self, forKey: .end)
        title = try? values.decode(String.self, forKey: .title)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(start, forKey: .start)
        try container.encode(end, forKey: .end)
        try container.encode(title, forKey: .title)
    }
}

class AudioTrack: Object, Codable {
    @Persisted var index: Int?
    @Persisted var startOffset: Double?
    @Persisted var duration: Double = 0
    @Persisted var title: String?
    @Persisted var contentUrl: String?
    @Persisted var mimeType: String = ""
    @Persisted var metadata: FileMetadata?
    var localFileId: String?
    // var audioProbeResult: AudioProbeResult? Needed for local playback
    @Persisted var serverIndex: Int?
    
    private enum CodingKeys : String, CodingKey {
        case index, startOffset, duration, title, contentUrl, mimeType, metadata, serverIndex
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        index = try? values.decode(Int.self, forKey: .index)
        startOffset = try? values.decode(Double.self, forKey: .startOffset)
        duration = try values.decode(Double.self, forKey: .duration)
        title = try? values.decode(String.self, forKey: .title)
        contentUrl = try? values.decode(String.self, forKey: .contentUrl)
        mimeType = try values.decode(String.self, forKey: .mimeType)
        metadata = try? values.decode(FileMetadata.self, forKey: .metadata)
        serverIndex = try? values.decode(Int.self, forKey: .serverIndex)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(index, forKey: .index)
        try container.encode(startOffset, forKey: .startOffset)
        try container.encode(duration, forKey: .duration)
        try container.encode(title, forKey: .title)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(mimeType, forKey: .mimeType)
        try container.encode(metadata, forKey: .metadata)
        try container.encode(serverIndex, forKey: .serverIndex)
    }

    func setLocalInfo(filenameIdMap: [String: String], serverIndex: Int) -> Bool {
        if let localFileId = filenameIdMap[self.metadata?.filename ?? ""] {
            self.localFileId = localFileId
            self.serverIndex = serverIndex
            return true
        }
        return false
    }
    
    func getLocalFile() -> LocalFile? {
        guard let localFileId = self.localFileId else { return nil }
        return Database.shared.getLocalFile(localFileId: localFileId)
    }
}

class FileMetadata: Object, Codable {
    @Persisted var filename: String = ""
    @Persisted var ext: String = ""
    @Persisted var path: String = ""
    @Persisted var relPath: String = ""
    
    private enum CodingKeys : String, CodingKey {
        case filename, ext, path, relPath
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        filename = try values.decode(String.self, forKey: .filename)
        ext = try values.decode(String.self, forKey: .ext)
        path = try values.decode(String.self, forKey: .path)
        relPath = try values.decode(String.self, forKey: .relPath)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(filename, forKey: .filename)
        try container.encode(ext, forKey: .ext)
        try container.encode(path, forKey: .path)
        try container.encode(relPath, forKey: .relPath)
    }
}

class Library: Object, Codable {
    @Persisted var id: String = ""
    @Persisted var name: String = "Unknown"
    @Persisted var folders = List<Folder>()
    @Persisted var icon: String = ""
    @Persisted var mediaType: String = ""
    
    private enum CodingKeys : String, CodingKey {
        case id, name, folders, icon, mediaType
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        if let folderList = try? values.decode([Folder].self, forKey: .folders) {
            folders.append(objectsIn: folderList)
        }
        icon = try values.decode(String.self, forKey: .icon)
        mediaType = try values.decode(String.self, forKey: .mediaType)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(folders, forKey: .folders)
        try container.encode(icon, forKey: .icon)
        try container.encode(mediaType, forKey: .mediaType)
    }
}

class Folder: Object, Codable {
    @Persisted var id: String = ""
    @Persisted var fullPath: String = ""
    
    private enum CodingKeys : String, CodingKey {
        case id, fullPath
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        fullPath = try values.decode(String.self, forKey: .fullPath)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(fullPath, forKey: .fullPath)
    }
}

class LibraryFile: Object, Codable {
    @Persisted var ino: String = ""
    @Persisted var metadata: FileMetadata?
    
    private enum CodingKeys : String, CodingKey {
        case ino, metadata
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        ino = try values.decode(String.self, forKey: .ino)
        metadata = try values.decode(FileMetadata.self, forKey: .metadata)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(ino, forKey: .ino)
        try container.encode(metadata, forKey: .metadata)
    }
}

class MediaProgress: Object, Codable {
    @Persisted var id: String = ""
    @Persisted var libraryItemId: String = ""
    @Persisted var episodeId: String?
    @Persisted var duration: Double = 0
    @Persisted var progress: Double = 0
    @Persisted var currentTime: Double = 0
    @Persisted var isFinished: Bool = false
    @Persisted var lastUpdate: Int = 0
    @Persisted var startedAt: Int = 0
    @Persisted var finishedAt: Int?
    
    private enum CodingKeys : String, CodingKey {
        case id, libraryItemId, episodeId, duration, progress, currentTime, isFinished, lastUpdate, startedAt, finishedAt
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        libraryItemId = try values.decode(String.self, forKey: .libraryItemId)
        episodeId = try? values.decode(String.self, forKey: .episodeId)
        duration = try values.doubleOrStringDecoder(key: .duration)
        progress = try values.doubleOrStringDecoder(key: .progress)
        currentTime = try values.doubleOrStringDecoder(key: .currentTime)
        isFinished = try values.decode(Bool.self, forKey: .isFinished)
        lastUpdate = try values.intOrStringDecoder(key: .lastUpdate)
        startedAt = try values.intOrStringDecoder(key: .startedAt)
        finishedAt = try? values.intOrStringDecoder(key: .finishedAt)
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
}

class PlaybackMetadata: Codable {
    var duration: Double = 0
    var currentTime: Double = 0
    var playerState: PlayerState = PlayerState.IDLE
}

enum PlayerState: Codable {
    case IDLE
    case BUFFERING
    case READY
    case ENDED
}
