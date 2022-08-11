//
//  LocalLibraryExtensions.swift
//  App
//
//  Created by Ron Heft on 7/16/22.
//

import Foundation

extension LocalLibraryItem {
    init(_ item: LibraryItem, localUrl: String, server: ServerConnectionConfig, files: [LocalFile], coverPath: String?) {
        self.init()
        self.id = "local_\(item.id)"
        self._contentUrl = localUrl
        self.mediaType = item.mediaType
        self.localFiles = files
        self._coverContentUrl = coverPath
        self.libraryItemId = item.id
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
        
        // Link the audio tracks and files
        linkLocalFiles(files, fromMedia: item.media)
    }
    
    mutating func addFiles(_ files: [LocalFile], item: LibraryItem) throws {
        guard self.isPodcast else { throw LibraryItemDownloadError.podcastOnlySupported }
        self.localFiles.append(contentsOf: files.filter({ $0.isAudioFile() }))
        linkLocalFiles(self.localFiles, fromMedia: item.media)
    }
    
    mutating private func linkLocalFiles(_ files: [LocalFile], fromMedia: MediaType) {
        var fromMedia = fromMedia
        let fileMap = files.map { ($0.filename ?? "", $0.id) }
        let fileIdByFilename = Dictionary(fileMap, uniquingKeysWith: { (_, last) in last })
        if ( self.isBook ) {
            if let tracks = fromMedia.tracks {
                for i in tracks.indices {
                    _ = fromMedia.tracks?[i].setLocalInfo(filenameIdMap: fileIdByFilename, serverIndex: i)
                }
            }
        } else if ( self.isPodcast ) {
            if let episodes = fromMedia.episodes {
                fromMedia.episodes = episodes.compactMap { episode in
                    // Filter out episodes not downloaded
                    var episode = episode
                    let episodeIsDownloaded = episode.audioTrack?.setLocalInfo(filenameIdMap: fileIdByFilename, serverIndex: 0) ?? false
                    return episodeIsDownloaded ? episode : nil
                }
            }
        }
        self.media = fromMedia
    }
    
    func getDuration() -> Double {
        var total = 0.0
        self.media?.tracks?.forEach { track in total += track.duration }
        return total
    }
    
    func getPodcastEpisode(episodeId: String?) -> PodcastEpisode? {
        guard self.isPodcast else { return nil }
        guard let episodes = self.media?.episodes else { return nil }
        return episodes.first(where: { $0.id == episodeId })
    }
    
    func getPlaybackSession(episode: PodcastEpisode?) -> PlaybackSession {
        let localEpisodeId = episode?.id
        let sessionId = "play_local_\(UUID().uuidString)"
        
        // Get current progress from local media
        let mediaProgressId = (localEpisodeId != nil) ? "\(self.id)-\(localEpisodeId!)" : self.id
        let mediaProgress = Database.shared.getLocalMediaProgress(localMediaProgressId: mediaProgressId)
        
        let mediaMetadata = self.media?.metadata
        let chapters = self.media?.chapters
        var audioTracks = self.media?.tracks
        let authorName = mediaMetadata?.authorDisplayName
        
        if let episode = episode, let track = episode.audioTrack {
            audioTracks = [track]
        }
        
        let dateNow = Date().timeIntervalSince1970
        return PlaybackSession(
            id: sessionId,
            userId: self.serverUserId,
            libraryItemId: self.libraryItemId,
            episodeId: episode?.serverEpisodeId,
            mediaType: self.mediaType,
            chapters: chapters ?? [],
            displayTitle: mediaMetadata?.title,
            displayAuthor: authorName,
            coverPath: self.coverContentUrl,
            duration: self.getDuration(),
            playMethod: PlayMethod.local.rawValue,
            startedAt: dateNow,
            updatedAt: 0,
            timeListening: 0.0,
            audioTracks: audioTracks ?? [],
            currentTime: mediaProgress?.currentTime ?? 0.0,
            libraryItem: nil,
            serverConnectionConfigId: self.serverConnectionConfigId,
            serverAddress: self.serverAddress
        )
    }
}

extension LocalFile {
    init(_ libraryItemId: String, _ filename: String, _ mimeType: String, _ localUrl: String, fileSize: Int) {
        self.init()
        self.id = "\(libraryItemId)_\(filename.toBase64())"
        self.filename = filename
        self.mimeType = mimeType
        self._contentUrl = localUrl
        self.size = fileSize
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
    init(localLibraryItem: LocalLibraryItem, episode: PodcastEpisode?, progress: MediaProgress) {
        self.id = localLibraryItem.id
        self.localLibraryItemId = localLibraryItem.id
        self.libraryItemId = localLibraryItem.libraryItemId
        
        if let episode = episode {
            self.id += "-\(episode.id)"
            self.episodeId = episode.id
        }
        
        self.serverAddress = localLibraryItem.serverAddress
        self.serverUserId = localLibraryItem.serverUserId
        self.serverConnectionConfigId = localLibraryItem.serverConnectionConfigId
        
        self.duration = progress.duration
        self.progress = progress.progress
        self.currentTime = progress.currentTime
        self.isFinished = false
        self.lastUpdate = progress.lastUpdate
        self.startedAt = progress.startedAt
        self.finishedAt = progress.finishedAt
    }
}
