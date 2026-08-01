//
//  PlaybackSessionMapping.swift
//  Audiobookshelf
//
//  Phase 1 of the ABSApiClient migration. Bridges the generated playbackSession DTO to the
//  Realm-backed PlaybackSession model (the richest mapping — used by startPlaybackSession in
//  Phase 4). Also maps the nested bookChapter/AudioTrack/fileMetadata DTOs.
//
//  SCOPE (Phase 1): DTO -> Realm for the scalar fields, `chapters`, and `audioTracks`
//  (including each track's `metadata`). DEFERRED:
//    - `mediaMetadata` — the DTO models it as a freeform `type: object`, so the generated type
//      is a freeform container that cannot faithfully rebuild the structured Realm `Metadata`.
//      (Handled in Phase 4/5 once the spec types it.)
//    - `libraryItem` — the DTO uses `libraryItemMinified`; the full Realm `LibraryItem` mapping
//      lands with Phase 5 (expanded item).
//    - The reverse direction (Realm PlaybackSession -> DTO), needed for syncLocalPlaybackSession,
//      is built in Phase 3 alongside its offline-reconciliation checks.
//  Server-connection fields (serverConnectionConfigId/serverAddress) are set by the caller,
//  exactly as ApiClient.startPlaybackSession does today.
//

import Foundation
import ABSApiClient

extension Chapter {
    static func from(dto: Components.Schemas.bookChapter) -> Chapter {
        let ch = Chapter()
        ch.id = dto.id ?? 0
        ch.start = dto.start ?? 0
        ch.end = dto.end ?? 0
        ch.title = dto.title
        return ch
    }
}

extension FileMetadata {
    static func from(dto: Components.Schemas.fileMetadata) -> FileMetadata {
        let m = FileMetadata()
        m.filename = dto.filename ?? ""
        m.ext = dto.ext ?? ""
        m.path = dto.path ?? ""
        m.relPath = dto.relPath ?? ""
        m.size = Double(dto.size ?? 0)      // DTO Int -> Realm Double
        return m
    }
}

extension AudioTrack {
    static func from(dto: Components.Schemas.AudioTrack) -> AudioTrack {
        let t = AudioTrack()
        t.index = dto.index
        t.startOffset = dto.startOffset.map { Double($0) }   // DTO Float -> Realm Double
        t.duration = Double(dto.duration ?? 0)               // DTO Float -> Realm Double
        t.title = dto.title
        t.contentUrl = dto.contentUrl
        t.mimeType = dto.mimeType ?? ""
        t.metadata = dto.metadata.map { FileMetadata.from(dto: $0) }
        // localFileId / serverIndex are local-only; not present in the server DTO.
        return t
    }
}

extension Chapter {
    /// Realm Chapter → generated bookChapter DTO (for sending local sessions to the server).
    func toDTO() -> Components.Schemas.bookChapter {
        Components.Schemas.bookChapter(id: id, start: start, end: end, title: title)
    }
}

extension FileMetadata {
    func toDTO() -> Components.Schemas.fileMetadata {
        Components.Schemas.fileMetadata(
            filename: filename,
            ext: ext,
            path: path,
            relPath: relPath,
            size: Int(size)   // Realm Double -> DTO Int
        )
    }
}

extension AudioTrack {
    func toDTO() -> Components.Schemas.AudioTrack {
        Components.Schemas.AudioTrack(
            index: index,
            startOffset: startOffset.map { Float($0) },   // Realm Double -> DTO Float
            duration: Float(duration),
            title: title,
            contentUrl: contentUrl,
            mimeType: mimeType,
            metadata: metadata?.toDTO()
        )
    }
}

extension PlaybackSession {
    /// Build a generated deviceInfo DTO from the app's `[String: String?]` device-info dictionary
    /// (deviceId/manufacturer/model/clientVersion). Server-enriched fields are left nil.
    static func deviceInfoDTO(from dict: [String: String?]?) -> Components.Schemas.deviceInfo? {
        guard let dict = dict else { return nil }
        return Components.Schemas.deviceInfo(
            deviceId: dict["deviceId"] ?? nil,
            clientVersion: dict["clientVersion"] ?? nil,
            manufacturer: dict["manufacturer"] ?? nil,
            model: dict["model"] ?? nil
        )
    }

    /// Realm PlaybackSession → generated playbackSession DTO (for syncLocalPlaybackSession /
    /// syncAllLocalPlaybackSessions). Maps scalars + chapters + audioTracks + deviceInfo; defers
    /// mediaMetadata (freeform in the spec) and libraryItem (the server reconstructs these from
    /// libraryItemId, which is included).
    func toDTO() -> Components.Schemas.playbackSession {
        Components.Schemas.playbackSession(
            id: id,
            userId: userId,
            libraryItemId: libraryItemId,
            episodeId: episodeId,
            mediaType: Components.Schemas.mediaType(rawValue: mediaType),
            chapters: chapters.map { $0.toDTO() },
            displayTitle: displayTitle,
            displayAuthor: displayAuthor,
            coverPath: coverPath,
            duration: duration,
            playMethod: Components.Schemas.playMethod(rawValue: playMethod),
            mediaPlayer: mediaPlayer,
            deviceInfo: PlaybackSession.deviceInfoDTO(from: deviceInfo),
            startedAt: startedAt.map { Int($0) },   // Realm Double -> DTO Int (ms)
            updatedAt: updatedAt.map { Int($0) },
            timeListening: timeListening,
            audioTracks: audioTracks.map { $0.toDTO() },
            currentTime: currentTime
        )
    }
}

extension PlaybackSession {
    /// Build an *unmanaged* Realm PlaybackSession from the generated DTO. Server-connection
    /// fields are left to the caller. The result must be persisted inside a write transaction.
    static func from(dto: Components.Schemas.playbackSession) -> PlaybackSession {
        let ps = PlaybackSession()
        ps.id = dto.id ?? ""
        ps.userId = dto.userId
        ps.libraryItemId = dto.libraryItemId
        ps.episodeId = dto.episodeId
        ps.mediaType = dto.mediaType?.rawValue ?? ""
        ps.displayTitle = dto.displayTitle
        ps.displayAuthor = dto.displayAuthor
        ps.coverPath = dto.coverPath
        ps.duration = dto.duration ?? 0
        ps.playMethod = dto.playMethod?.rawValue ?? PlayMethod.directplay.rawValue
        ps.startedAt = dto.startedAt.map { Double($0) }   // DTO Int ms -> Realm Double
        ps.updatedAt = dto.updatedAt.map { Double($0) }
        ps.timeListening = dto.timeListening ?? 0
        ps.currentTime = dto.currentTime ?? 0
        if let chapters = dto.chapters {
            ps.chapters.append(objectsIn: chapters.map { Chapter.from(dto: $0) })
        }
        if let tracks = dto.audioTracks {
            ps.audioTracks.append(objectsIn: tracks.map { AudioTrack.from(dto: $0) })
        }
        // isActiveSession defaults to true; mediaMetadata / libraryItem deferred (see header).
        return ps
    }
}
