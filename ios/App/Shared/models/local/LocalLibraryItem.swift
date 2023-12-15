//
//  LocalLibraryItem.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
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
    
    var coverUrl: URL? {
        if let path = self._coverContentUrl {
            return AbsDownloader.itemDownloadFolder(path: path)
        } else {
            return nil
        }
    }
    
    var coverContentUrl: String? {
        return self.coverUrl?.absoluteString
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

extension LocalLibraryItem {
    convenience init(_ item: LibraryItem, localUrl: String, server: ServerConnectionConfig, files: [LocalFile], coverPath: String?) {
        self.init()
        
        self._contentUrl = localUrl
        self.mediaType = item.mediaType
        self.localFiles.append(objectsIn: files)
        self._coverContentUrl = coverPath
        self.libraryItemId = item.id
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
        
        // Link the audio tracks and files
        linkLocalFiles(self.localFiles, fromMedia: item.media)
    }
    
    func addFiles(_ files: [LocalFile], item: LibraryItem) throws {
        guard self.isPodcast else { throw LibraryItemDownloadError.podcastOnlySupported }
        self.localFiles.append(objectsIn: files.filter({ $0.isAudioFile() }))
        linkLocalFiles(self.localFiles, fromMedia: item.media)
    }
    
    private func linkLocalFiles(_ files: List<LocalFile>, fromMedia: MediaType?) {
        guard let fromMedia = MediaType.detachCopy(of: fromMedia) else { return }
        let fileMap = files.map { ($0.filename ?? "", $0.id) }
        let fileIdByFilename = Dictionary(fileMap, uniquingKeysWith: { (_, last) in last })
        if ( self.isBook ) {
            for i in fromMedia.tracks.indices {
                _ = fromMedia.tracks[i].setLocalInfo(filenameIdMap: fileIdByFilename, serverIndex: i)
            }
            if fromMedia.ebookFile != nil {
                let ebookLocalFile = files.first(where: { $0.filename == fromMedia.ebookFile?.metadata?.filename ?? "" })
                if ebookLocalFile != nil {
                    _ = fromMedia.ebookFile?.setLocalInfo(localFile: ebookLocalFile!)
                }
            }
        } else if ( self.isPodcast ) {
            let episodes = List<PodcastEpisode>()
            for episode in fromMedia.episodes {
                // Filter out episodes not downloaded
                let episodeIsDownloaded = episode.audioTrack?.setLocalInfo(filenameIdMap: fileIdByFilename, serverIndex: 0) ?? false
                if episodeIsDownloaded {
                    episodes.append(episode)
                }
            }
            fromMedia.episodes = episodes
        }
        self.media = fromMedia
    }
    
    func getDuration() -> Double {
        var total = 0.0
        self.media?.tracks.enumerated().forEach { _, track in total += track.duration }
        return total
    }
    
    func getPodcastEpisode(episodeId: String?) -> PodcastEpisode? {
        guard self.isPodcast else { return nil }
        guard let episodes = self.media?.episodes else { return nil }
        return episodes.first(where: { $0.id == episodeId })
    }
    
    func getPlaybackSession(episode: PodcastEpisode?) -> PlaybackSession {
        let localEpisodeId = episode?.id
        let sessionId = UUID().uuidString
        
        // Get current progress from local media
        let mediaProgressId = (localEpisodeId != nil) ? "\(self.id)-\(localEpisodeId!)" : self.id
        let mediaProgress = Database.shared.getLocalMediaProgress(localMediaProgressId: mediaProgressId)
        
        let mediaMetadata = Metadata.detachCopy(of: self.media?.metadata)
        let chapters = List<Chapter>()
        self.media?.chapters.forEach { chapter in chapters.append(Chapter.detachCopy(of: chapter)!) }
        let authorName = mediaMetadata?.authorDisplayName
        
        var duration = getDuration()
        var displayTitle = mediaMetadata?.title

        let audioTracks = List<AudioTrack>()
        if let episode = episode, let track = episode.audioTrack {
            audioTracks.append(AudioTrack.detachCopy(of: track)!)
            duration = track.duration
            displayTitle = episode.title
            episode.chapters.forEach { chapter in chapters.append(Chapter.detachCopy(of: chapter)!) }
        } else if let tracks = self.media?.tracks {
            tracks.forEach { t in audioTracks.append(AudioTrack.detachCopy(of: t)!) }
        }
        
        let dateNow = Date().timeIntervalSince1970 * 1000
        return PlaybackSession(
            id: sessionId,
            userId: self.serverUserId,
            libraryItemId: self.libraryItemId,
            episodeId: episode?.serverEpisodeId,
            mediaType: self.mediaType,
            mediaMetadata: mediaMetadata,
            chapters: chapters,
            displayTitle: displayTitle,
            displayAuthor: authorName,
            coverPath: self.coverContentUrl,
            duration: duration,
            playMethod: PlayMethod.local.rawValue,
            startedAt: dateNow,
            updatedAt: dateNow,
            timeListening: 0.0,
            audioTracks: audioTracks,
            currentTime: mediaProgress?.currentTime ?? 0.0,
            libraryItem: nil,
            localLibraryItem: self,
            serverConnectionConfigId: self.serverConnectionConfigId,
            serverAddress: self.serverAddress
        )
    }
    
    func delete() throws {
        try self.realm?.write {
            self.realm?.delete(self.localFiles)
            self.realm?.delete(self)
        }
    }
}
