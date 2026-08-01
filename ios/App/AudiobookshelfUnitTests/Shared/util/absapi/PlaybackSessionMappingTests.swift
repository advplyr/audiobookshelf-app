//
//  PlaybackSessionMappingTests.swift
//  AudiobookshelfUnitTests
//
//  Phase 1 of the ABSApiClient migration: verifies the playbackSession DTO -> Realm mapping,
//  including nested chapters/audioTracks/fileMetadata and the type coercions (Int/Float -> Double,
//  playMethod enum -> Int, mediaType enum -> String).
//

import XCTest
import ABSApiClient
@testable import Audiobookshelf

final class PlaybackSessionMappingTests: XCTestCase {

    private func makeFullDTO() -> Components.Schemas.playbackSession {
        // Argument order must follow the generated struct's declaration order.
        Components.Schemas.playbackSession(
            id: "ps_1",
            userId: "u_1",
            libraryItemId: "li_1",
            episodeId: "ep_1",
            mediaType: .book,
            chapters: [
                Components.Schemas.bookChapter(id: 1, start: 0, end: 600, title: "Ch1")
            ],
            displayTitle: "Title",
            displayAuthor: "Author",
            coverPath: "/cover.jpg",
            duration: 3600.5,
            playMethod: ._1, // directstream
            startedAt: 1600000000000,
            updatedAt: 1600000001000,
            timeListening: 120.5,
            audioTracks: [
                Components.Schemas.AudioTrack(
                    index: 0,
                    startOffset: 0,
                    duration: 1800.25,
                    title: "t1",
                    contentUrl: "/url",
                    mimeType: "audio/mpeg",
                    metadata: Components.Schemas.fileMetadata(
                        filename: "f.mp3",
                        ext: ".mp3",
                        path: "/p",
                        relPath: "p",
                        size: 12345
                    )
                )
            ],
            currentTime: 900.5
        )
    }

    func testMapsScalarFields() {
        let ps = PlaybackSession.from(dto: makeFullDTO())

        XCTAssertEqual(ps.id, "ps_1")
        XCTAssertEqual(ps.userId, "u_1")
        XCTAssertEqual(ps.libraryItemId, "li_1")
        XCTAssertEqual(ps.episodeId, "ep_1")
        XCTAssertEqual(ps.mediaType, "book")
        XCTAssertEqual(ps.displayTitle, "Title")
        XCTAssertEqual(ps.displayAuthor, "Author")
        XCTAssertEqual(ps.coverPath, "/cover.jpg")
        XCTAssertEqual(ps.duration, 3600.5)
        XCTAssertEqual(ps.playMethod, PlayMethod.directstream.rawValue)
        XCTAssertEqual(ps.startedAt, 1600000000000)
        XCTAssertEqual(ps.updatedAt, 1600000001000)
        XCTAssertEqual(ps.timeListening, 120.5)
        XCTAssertEqual(ps.currentTime, 900.5)
        XCTAssertTrue(ps.isActiveSession)
    }

    func testMapsChapters() {
        let ps = PlaybackSession.from(dto: makeFullDTO())

        XCTAssertEqual(ps.chapters.count, 1)
        let ch = ps.chapters[0]
        XCTAssertEqual(ch.id, 1)
        XCTAssertEqual(ch.start, 0)     // DTO Int -> Realm Double
        XCTAssertEqual(ch.end, 600)
        XCTAssertEqual(ch.title, "Ch1")
    }

    func testMapsAudioTracksIncludingMetadata() {
        let ps = PlaybackSession.from(dto: makeFullDTO())

        XCTAssertEqual(ps.audioTracks.count, 1)
        let track = ps.audioTracks[0]
        XCTAssertEqual(track.index, 0)
        XCTAssertEqual(track.startOffset ?? -1, 0, accuracy: 0.0001)   // Float -> Double
        XCTAssertEqual(track.duration, 1800.25, accuracy: 0.0001)     // Float -> Double
        XCTAssertEqual(track.title, "t1")
        XCTAssertEqual(track.contentUrl, "/url")
        XCTAssertEqual(track.mimeType, "audio/mpeg")
        // local-only fields are not present in the server DTO
        XCTAssertNil(track.localFileId)
        XCTAssertNil(track.serverIndex)

        let meta = track.metadata
        XCTAssertNotNil(meta)
        XCTAssertEqual(meta?.filename, "f.mp3")
        XCTAssertEqual(meta?.ext, ".mp3")
        XCTAssertEqual(meta?.path, "/p")
        XCTAssertEqual(meta?.relPath, "p")
        XCTAssertEqual(meta?.size, 12345)   // DTO Int -> Realm Double
    }

    func testNilOptionalsUseRealmDefaults() {
        let ps = PlaybackSession.from(dto: Components.Schemas.playbackSession(id: "ps_2"))

        XCTAssertEqual(ps.id, "ps_2")
        XCTAssertNil(ps.userId)
        XCTAssertNil(ps.libraryItemId)
        XCTAssertNil(ps.episodeId)
        XCTAssertEqual(ps.mediaType, "")
        XCTAssertEqual(ps.duration, 0)
        XCTAssertEqual(ps.playMethod, PlayMethod.directplay.rawValue)
        XCTAssertNil(ps.startedAt)
        XCTAssertNil(ps.updatedAt)
        XCTAssertEqual(ps.timeListening, 0)
        XCTAssertEqual(ps.currentTime, 0)
        XCTAssertEqual(ps.chapters.count, 0)
        XCTAssertEqual(ps.audioTracks.count, 0)
    }

    func testChapterSubMapperDefaults() {
        let ch = Chapter.from(dto: Components.Schemas.bookChapter())
        XCTAssertEqual(ch.id, 0)
        XCTAssertEqual(ch.start, 0)
        XCTAssertEqual(ch.end, 0)
        XCTAssertNil(ch.title)
    }

    func testAudioTrackSubMapperDefaults() {
        let track = AudioTrack.from(dto: Components.Schemas.AudioTrack())
        XCTAssertNil(track.index)
        XCTAssertNil(track.startOffset)
        XCTAssertEqual(track.duration, 0)
        XCTAssertEqual(track.mimeType, "")
        XCTAssertNil(track.metadata)
    }
}
