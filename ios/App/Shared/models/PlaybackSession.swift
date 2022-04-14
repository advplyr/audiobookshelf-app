//
//  PlaybackSession.swift
//  App
//
//  Created by Rasmus Krämer on 12.04.22.
//

import Foundation

struct PlaybackSession: Decodable, Encodable {
    var id: String
    var userId: String?
    var libraryItemId: String?
    var episodeId: String?
    var mediaType: String
    // var mediaMetadata: MediaTypeMetadata - It is not implemented in android?
    var chapters: [Chapter]
    var displayTitle: String?
    var displayAuthor: String?
    var coverPath: String?
    var duration: Double
    var playMethod: Int
    var startedAt: Double?
    var updatedAt: Double?
    var timeListening: Double
    var audioTracks: [AudioTrack]
    var currentTime: Double
    // var libraryItem: LibraryItem?
    // var localLibraryItem: LocalLibraryItem?
    var serverConnectionConfigId: String?
    var serverAddress: String?
}
struct Chapter: Decodable, Encodable {
    var id: Int
    var start: Double
    var end: Double
    var title: String?
}
struct AudioTrack: Decodable, Encodable {
    var index: Int?
    var startOffset: Double
    var duration: Double
    var title: String
    var contentUrl: String
    var mimeType: String
    var metadata: FileMetadata?
    // var isLocal: Bool
    // var localFileId: String?
    // var audioProbeResult: AudioProbeResult? Needed for local playback
    var serverIndex: Int?
}
struct FileMetadata: Decodable, Encodable {
    var filename: String
    var ext: String
    var path: String
    var relPath: String
}
