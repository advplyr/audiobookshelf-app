//
//  LocalLibraryExtensions.swift
//  App
//
//  Created by Ron Heft on 7/16/22.
//

import Foundation

extension LocalLibraryItem {
    enum CodingKeys: CodingKey {
        case id
        case basePath
        case absolutePath
        case contentUrl
        case isInvalid
        case mediaType
        case media
        case localFiles
        case coverContentUrl
        case coverAbsolutePath
        case isLocal
        case serverConnectionConfigId
        case serverAddress
        case serverUserId
        case libraryItemId
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(basePath, forKey: .basePath)
        try container.encode(absolutePath, forKey: .absolutePath)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(isInvalid, forKey: .isInvalid)
        try container.encode(mediaType, forKey: .mediaType)
        try container.encode(media, forKey: .media)
        try container.encode(localFiles, forKey: .localFiles)
        try container.encode(coverContentUrl, forKey: .coverContentUrl)
        try container.encode(coverAbsolutePath, forKey: .coverAbsolutePath)
        try container.encode(isLocal, forKey: .isLocal)
        try container.encode(serverConnectionConfigId, forKey: .serverConnectionConfigId)
        try container.encode(serverAddress, forKey: .serverAddress)
        try container.encode(serverUserId, forKey: .serverUserId)
        try container.encode(libraryItemId, forKey: .libraryItemId)
    }
    
    convenience init(_ item: LibraryItem, localUrl: URL, server: ServerConnectionConfig, files: [LocalFile]) {
        self.init()
        self.contentUrl = localUrl.absoluteString
        self.mediaType = item.mediaType
        self.media = LocalMediaType(item.media)
        self.localFiles.append(objectsIn: files)
        // TODO: self.coverContentURL
        // TODO: self.converAbsolutePath
        self.libraryItemId = item.id
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
    }
}

extension LocalMediaType {
    enum CodingKeys: CodingKey {
        case libraryItemId
        case metadata
        case coverPath
        case tags
        case audioFiles
        case chapters
        case tracks
        case size
        case duration
        case episodes
        case autoDownloadEpisodes
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(metadata, forKey: .metadata)
        try container.encode(coverPath, forKey: .coverPath)
        try container.encode(tags, forKey: .tags)
        try container.encode(audioFiles, forKey: .audioFiles)
        try container.encode(chapters, forKey: .chapters)
        try container.encode(tracks, forKey: .tracks)
        try container.encode(size, forKey: .size)
        try container.encode(duration, forKey: .duration)
        try container.encode(episodes, forKey: .episodes)
        try container.encode(autoDownloadEpisodes, forKey: .autoDownloadEpisodes)
    }
    
    convenience init(_ mediaType: MediaType) {
        self.init()
        self.libraryItemId = mediaType.libraryItemId
        self.metadata = LocalMetadata(mediaType.metadata)
        // TODO: self.coverPath
        self.tags.append(objectsIn: mediaType.tags ?? [])
        self.audioFiles.append(objectsIn: mediaType.audioFiles!.enumerated().map() {
            i, audioFile -> LocalAudioFile in LocalAudioFile(audioFile)
        })
        self.chapters.append(objectsIn: mediaType.chapters!.enumerated().map() {
            i, chapter -> LocalChapter in LocalChapter(chapter)
        })
        self.tracks.append(objectsIn: mediaType.tracks!.enumerated().map() {
            i, track in LocalAudioTrack(track)
        })
        self.size = mediaType.size
        self.duration = mediaType.duration
        // TODO: self.episodes
        self.autoDownloadEpisodes = mediaType.autoDownloadEpisodes
    }
}

extension LocalMetadata {
    enum CodingKeys: CodingKey {
        case title
        case subtitle
        case authors
        case narrators
        case genres
        case publishedYear
        case publishedDate
        case publisher
        case desc
        case isbn
        case asin
        case language
        case explicit
        case authorName
        case authorNameLF
        case narratorName
        case seriesName
        case feedUrl
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(title, forKey: .title)
        try container.encode(subtitle, forKey: .subtitle)
        try container.encode(authors, forKey: .authors)
        try container.encode(narrators, forKey: .narrators)
        try container.encode(genres, forKey: .genres)
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
    
    convenience init(_ metadata: Metadata) {
        self.init()
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

extension LocalPodcastEpisode {
    enum CodingKeys: CodingKey {
        case id
        case index
        case episode
        case episodeType
        case title
        case subtitle
        case desc
        case audioFile
        case audioTrack
        case duration
        case size
        case serverEpisodeId
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

extension LocalAudioFile {
    enum CodingKeys: CodingKey {
        case index
        case ino
        case metadata
    }
    
    convenience init(_ audioFile: AudioFile) {
        self.init()
        self.index = audioFile.index
        self.ino = audioFile.ino
        // TODO: self.metadata
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(index, forKey: .index)
        try container.encode(ino, forKey: .ino)
        try container.encode(metadata, forKey: .metadata)
    }
}

extension LocalAuthor {
    enum CodingKeys: CodingKey {
        case id
        case name
        case coverPath
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(coverPath, forKey: .coverPath)
    }
}

extension LocalChapter {
    enum CodingKeys: CodingKey {
        case id
        case start
        case end
        case title
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(start, forKey: .start)
        try container.encode(end, forKey: .end)
        try container.encode(title, forKey: .title)
    }
    
    convenience init(_ chapter: Chapter) {
        self.init()
        self.id = chapter.id
        self.start = chapter.start
        self.end = chapter.end
        self.title = chapter.title
    }
}

extension LocalAudioTrack {
    enum CodingKeys: CodingKey {
        case index
        case startOffset
        case duration
        case title
        case contentUrl
        case mimeType
        case metadata
        case isLocal
        case localFileId
        case serverIndex
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
        try container.encode(isLocal, forKey: .isLocal)
        try container.encode(localFileId, forKey: .localFileId)
        try container.encode(serverIndex, forKey: .serverIndex)
    }
    
    convenience init(_ track: AudioTrack) {
        self.init()
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

extension LocalFileMetadata {
    enum CodingKeys: CodingKey {
        case filename
        case ext
        case path
        case relPath
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(filename, forKey: .filename)
        try container.encode(ext, forKey: .ext)
        try container.encode(path, forKey: .path)
        try container.encode(relPath, forKey: .relPath)
    }
}

extension LocalFile {
    enum CodingKeys: CodingKey {
        case id
        case filename
        case contentUrl
        case absolutePath
        case mimeType
        case size
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
    
    convenience init(_ filename: String, _ mimeType: String, _ localUrl: URL) {
        self.init()
        self.id = localUrl.absoluteString.toBase64()
        self.filename = filename
        self.contentUrl = localUrl.absoluteString
        self.absolutePath = localUrl.path
        self.size = localUrl.fileSize
    }
    
    func isAudioFile() -> Bool {
        switch self.mimeType {
        case "application/octet-stream",
            "video/mp4":
            return true
        default:
            return self.mimeType?.starts(with: "audio") ?? false
        }
    }
}

extension LocalMediaProgress {
    enum CodingKeys: CodingKey {
        case id
        case localLibraryItemId
        case localEpisodeId
        case duration
        case progress
        case currentTime
        case isFinished
        case lastUpdate
        case startedAt
        case finishedAt
        case serverConnectionConfigId
        case serverAddress
        case serverUserId
        case libraryItemId
        case episodeId
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
