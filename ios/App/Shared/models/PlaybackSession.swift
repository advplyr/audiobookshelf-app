//
//  PlaybackSession.swift
//  App
//
//  Created by Rasmus Krämer on 12.04.22.
//

import Foundation
import RealmSwift
         
class PlaybackSession: Object, Codable {
    @Persisted(primaryKey: true) var id: String = ""
    var userId: String?
    @Persisted var libraryItemId: String?
    @Persisted var episodeId: String?
    @Persisted var mediaType: String = ""
    // var mediaMetadata: MediaTypeMetadata - It is not implemented in android?
    @Persisted var chapters = List<Chapter>()
    @Persisted var displayTitle: String?
    @Persisted var displayAuthor: String?
    @Persisted var coverPath: String?
    @Persisted var duration: Double = 0
    @Persisted var playMethod: Int = 1
    @Persisted var startedAt: Double?
    @Persisted var updatedAt: Double?
    @Persisted var timeListening: Double = 0
    @Persisted var audioTracks = List<AudioTrack>()
    @Persisted var currentTime: Double = 0
    @Persisted var libraryItem: LibraryItem?
    @Persisted var localLibraryItem: LocalLibraryItem?
    @Persisted var serverConnectionConfigId: String?
    @Persisted var serverAddress: String?
    
    var isLocal: Bool { self.localLibraryItem != nil }
    
    var localMediaProgressId: String? {
        if let localLibraryItem = localLibraryItem, let episodeId = episodeId {
            return "\(localLibraryItem.id)-\(episodeId)"
        } else if let localLibraryItem = localLibraryItem {
            return localLibraryItem.id
        } else {
            return nil
        }
    }
    
    var totalDuration: Double {
        var total = 0.0
        self.audioTracks.forEach { total += $0.duration }
        return total
    }
    
    var progress: Double { self.currentTime / self.totalDuration }
    
    internal init(id: String, userId: String? = nil, libraryItemId: String? = nil, episodeId: String? = nil, mediaType: String, chapters: List<Chapter> = List<Chapter>(), displayTitle: String? = nil, displayAuthor: String? = nil, coverPath: String? = nil, duration: Double, playMethod: Int, startedAt: Double? = nil, updatedAt: Double? = nil, timeListening: Double, audioTracks: List<AudioTrack> = List<AudioTrack>(), currentTime: Double, libraryItem: LibraryItem? = nil, localLibraryItem: LocalLibraryItem? = nil, serverConnectionConfigId: String? = nil, serverAddress: String? = nil) {
        self.id = id
        self.userId = userId
        self.libraryItemId = libraryItemId
        self.episodeId = episodeId
        self.mediaType = mediaType
        self.chapters = chapters
        self.displayTitle = displayTitle
        self.displayAuthor = displayAuthor
        self.coverPath = coverPath
        self.duration = duration
        self.playMethod = playMethod
        self.startedAt = startedAt
        self.updatedAt = updatedAt
        self.timeListening = timeListening
        self.audioTracks = audioTracks
        self.currentTime = currentTime
        self.libraryItem = libraryItem
        self.localLibraryItem = localLibraryItem
        self.serverConnectionConfigId = serverConnectionConfigId
        self.serverAddress = serverAddress
    }
    
    private enum CodingKeys : String, CodingKey {
        case id, userId, libraryItemId, episodeId, mediaType, chapters, displayTitle, displayAuthor, coverPath, duration, playMethod, startedAt, updatedAt, timeListening, audioTracks, currentTime, libraryItem, localLibraryItem, serverConnectionConfigId, serverAddress, isLocal, localMediaProgressId
    }
    
    override init() {}
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        userId = try values.decodeIfPresent(String.self, forKey: .userId)
        libraryItemId = try values.decodeIfPresent(String.self, forKey: .libraryItemId)
        episodeId = try values.decodeIfPresent(String.self, forKey: .episodeId)
        mediaType = try values.decode(String.self, forKey: .mediaType)
        if let chapterList = try values.decodeIfPresent([Chapter].self, forKey: .chapters) {
            chapters.append(objectsIn: chapterList)
        }
        displayTitle = try values.decodeIfPresent(String.self, forKey: .displayTitle)
        displayAuthor = try values.decodeIfPresent(String.self, forKey: .displayAuthor)
        coverPath = try values.decodeIfPresent(String.self, forKey: .coverPath)
        duration = try values.decode(Double.self, forKey: .duration)
        playMethod = try values.decode(Int.self, forKey: .playMethod)
        startedAt = try values.decodeIfPresent(Double.self, forKey: .startedAt)
        updatedAt = try values.decodeIfPresent(Double.self, forKey: .updatedAt)
        timeListening = try values.decode(Double.self, forKey: .timeListening)
        if let trackList = try values.decodeIfPresent([AudioTrack].self, forKey: .audioTracks) {
            audioTracks.append(objectsIn: trackList)
        }
        currentTime = try values.decode(Double.self, forKey: .currentTime)
        libraryItem = try values.decodeIfPresent(LibraryItem.self, forKey: .libraryItem)
        localLibraryItem = try values.decodeIfPresent(LocalLibraryItem.self, forKey: .localLibraryItem)
        serverConnectionConfigId = try values.decodeIfPresent(String.self, forKey: .serverConnectionConfigId)
        serverAddress = try values.decodeIfPresent(String.self, forKey: .serverAddress)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(userId, forKey: .userId)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(episodeId, forKey: .episodeId)
        try container.encode(mediaType, forKey: .mediaType)
        try container.encode(Array(chapters), forKey: .chapters)
        try container.encode(displayTitle, forKey: .displayTitle)
        try container.encode(displayAuthor, forKey: .displayAuthor)
        try container.encode(coverPath, forKey: .coverPath)
        try container.encode(duration, forKey: .duration)
        try container.encode(playMethod, forKey: .playMethod)
        try container.encode(startedAt, forKey: .startedAt)
        try container.encode(updatedAt, forKey: .updatedAt)
        try container.encode(timeListening, forKey: .timeListening)
        try container.encode(Array(audioTracks), forKey: .audioTracks)
        try container.encode(currentTime, forKey: .currentTime)
        try container.encode(libraryItem, forKey: .libraryItem)
        try container.encode(localLibraryItem, forKey: .localLibraryItem)
        try container.encode(serverConnectionConfigId, forKey: .serverConnectionConfigId)
        try container.encode(serverAddress, forKey: .serverAddress)
        try container.encode(isLocal, forKey: .isLocal)
        try container.encode(localMediaProgressId, forKey: .localMediaProgressId)
    }
}
