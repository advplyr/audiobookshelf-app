//
//  LocalLibraryExtensions.swift
//  App
//
//  Created by Ron Heft on 7/16/22.
//

import Foundation

extension LocalLibraryItem {
    init(_ item: LibraryItem, localUrl: URL, server: ServerConnectionConfig, files: [LocalFile]) {
        self.init()
        self.contentUrl = localUrl.absoluteString
        self.mediaType = item.mediaType
        self.media = item.media
        self.localFiles = files
        // TODO: self.coverContentURL
        // TODO: self.converAbsolutePath
        self.libraryItemId = item.id
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
    }
    
    func getDuration() -> Double {
        var total = 0.0
        self.media?.tracks?.forEach { track in total += track.duration }
        return total
    }
    
    func getPlaybackSession(episode: LocalPodcastEpisode?) -> PlaybackSession {
        let localEpisodeId = episode?.id
        let sessionId = "play_local_\(UUID().uuidString)"
        
        // Get current progress from local media
        let mediaProgressId = (localEpisodeId != nil) ? "\(self.id)-\(localEpisodeId!)" : self.id
        let mediaProgress = Database.shared.getLocalMediaProgress(localMediaProgressId: mediaProgressId)
        
        // TODO: Clean up add mediaType methods for displayTitle and displayAuthor
        let mediaMetadata = self.media?.metadata
        let audioTracks = self.media?.tracks
        let authorName = mediaMetadata?.authorName
        
        if let episode = episode {
            // TODO: Implement podcast
        }
        
        let dateNow = Date().timeIntervalSince1970
        return PlaybackSession(
            id: sessionId,
            userId: self.serverUserId,
            libraryItemId: self.libraryItemId,
            episodeId: episode?.serverEpisodeId,
            mediaType: self.mediaType,
            chapters: [],
            displayTitle: mediaMetadata?.title,
            displayAuthor: authorName,
            coverPath: nil,
            duration: self.getDuration(),
            playMethod: 3,
            startedAt: dateNow,
            updatedAt: 0,
            timeListening: 0.0,
            audioTracks: [],
            currentTime: mediaProgress?.currentTime ?? 0.0,
            libraryItem: nil,
            serverConnectionConfigId: self.serverConnectionConfigId,
            serverAddress: self.serverAddress
        )
    }
}

extension LocalFile {
    init(_ libraryItemId: String, _ filename: String, _ mimeType: String, _ localUrl: URL) {
        self.init()
        self.id = "\(libraryItemId)_\(filename.toBase64())"
        self.filename = filename
        self.contentUrl = localUrl.absoluteString
        self.absolutePath = localUrl.path
        self.size = Int(localUrl.fileSize)
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
