//
//  MediaProgressConflictResolver.swift
//  Audiobookshelf
//

import Foundation

enum MediaProgressSyncDirection: Equatable {
    case localToServer
    case serverToLocal
    case none
}

enum MediaProgressConflictResolver {
    static func resolveAudioProgress(
        localCurrentTime: Double,
        localIsFinished: Bool,
        localLastUpdate: Double,
        serverCurrentTime: Double,
        serverIsFinished: Bool,
        serverLastUpdate: Double
    ) -> MediaProgressSyncDirection {
        if localIsFinished != serverIsFinished {
            if localLastUpdate > serverLastUpdate {
                return .localToServer
            } else if serverLastUpdate > localLastUpdate {
                return .serverToLocal
            }
            return .none
        }

        if localCurrentTime > serverCurrentTime {
            return .localToServer
        } else if serverCurrentTime > localCurrentTime {
            return .serverToLocal
        }
        return .none
    }
}
