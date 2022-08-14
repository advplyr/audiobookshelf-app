//
//  PlaybackSession.swift
//  App
//
//  Created by Rasmus Krämer on 12.04.22.
//

import Foundation
         
struct PlaybackSession: Codable {
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
    var libraryItem: LibraryItem?
    var localLibraryItem: LocalLibraryItem?
    var serverConnectionConfigId: String?
    var serverAddress: String?
    
    var isLocal: Bool { self.localLibraryItem != nil }
    
    var localMediaProgressId: String {
        if let episodeId = episodeId {
            return "\(localLibraryItem!.id)-\(episodeId)"
        } else {
            return localLibraryItem!.id
        }
    }
    
    var totalDuration: Double {
        var total = 0.0
        self.audioTracks.forEach { total += $0.duration }
        return total
    }
    
    var progress: Double { self.currentTime / self.totalDuration }
}
