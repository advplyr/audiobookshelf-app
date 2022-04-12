//
//  PlaybackSession.swift
//  App
//
//  Created by Rasmus Krämer on 12.04.22.
//

import Foundation

struct PlaybackSession {
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
    var startedAt: Double
    var updatedAt: Double
    var timeListening: Double
    var audioTracks: [AudioTrack]
    var currentTime: Double
    // var libraryItem: LibraryItem?
    // var localLibraryItem: LocalLibraryItem?
    var serverConnectionConfigId: String?
    var serverAddress: String?
}
struct Chapter {
    var id: Int
    var start: Double
    var end: Double
    var title: String?
}
struct AudioTrack {
    var index: Int
    var startOffset: Double
    var duration: Double
    var title: String
    var contentUrl: String
    var mimeType: String
    var metadata: FileMetadata?
    // var isLocal: Bool
    // var localFileId: String?
    // var audioProbeResult: AudioProbeResult? Need for local playback
    var serverIndex: Int?
}
struct FileMetadata {
    var filename: String
    var ext: String
    var path: String
    var relPath: String
}
