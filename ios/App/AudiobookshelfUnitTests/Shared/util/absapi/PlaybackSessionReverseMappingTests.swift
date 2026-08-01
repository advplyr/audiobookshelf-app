//
//  PlaybackSessionReverseMappingTests.swift
//  AudiobookshelfUnitTests
//
//  Phase 3: verifies the reverse Realm → DTO mapping used when sending locally-recorded playback
//  sessions to the server (syncLocalPlaybackSession / syncAllLocalPlaybackSessions).
//  Covers Double→Int(ms), Double→Float, Double→Int, playMethod Int→enum, mediaType String→enum,
//  and the deviceInfo subset.
//

import XCTest
import ABSApiClient
@testable import Audiobookshelf

final class PlaybackSessionReverseMappingTests: XCTestCase {

    func testChapterToDTO() {
        let ch = Chapter()
        ch.id = 3
        ch.start = 12.9   // fractional seconds preserved (DTO start is now number/Double)
        ch.end = 600.5
        ch.title = "Ch"

        let dto = ch.toDTO()
        XCTAssertEqual(dto.id, 3)
        XCTAssertEqual(dto.start, 12.9)
        XCTAssertEqual(dto.end, 600.5)
        XCTAssertEqual(dto.title, "Ch")
    }

    func testFileMetadataToDTO() {
        let m = FileMetadata()
        m.filename = "f.mp3"
        m.ext = ".mp3"
        m.path = "/p"
        m.relPath = "p"
        m.size = 12345.7   // Double → DTO Int

        let dto = m.toDTO()
        XCTAssertEqual(dto.filename, "f.mp3")
        XCTAssertEqual(dto.ext, ".mp3")
        XCTAssertEqual(dto.path, "/p")
        XCTAssertEqual(dto.relPath, "p")
        XCTAssertEqual(dto.size, 12345)   // truncated
    }

    func testAudioTrackToDTO() {
        let t = AudioTrack()
        t.index = 1
        t.startOffset = 10.5
        t.duration = 1800.25
        t.title = "t"
        t.contentUrl = "/u"
        t.mimeType = "audio/mpeg"
        let meta = FileMetadata()
        meta.filename = "a.mp3"
        t.metadata = meta

        let dto = t.toDTO()
        XCTAssertEqual(dto.index, 1)
        XCTAssertEqual(Double(dto.startOffset ?? -1), 10.5, accuracy: 0.001)
        XCTAssertEqual(Double(dto.duration ?? -1), 1800.25, accuracy: 0.001)
        XCTAssertEqual(dto.title, "t")
        XCTAssertEqual(dto.contentUrl, "/u")
        XCTAssertEqual(dto.mimeType, "audio/mpeg")
        XCTAssertEqual(dto.metadata?.filename, "a.mp3")
    }

    func testDeviceInfoDTOFromDict() {
        let dict: [String: String?] = [
            "deviceId": "dev-1",
            "manufacturer": "Apple",
            "model": "iPhone15,2",
            "clientVersion": "0.13.0"
        ]
        let dto = PlaybackSession.deviceInfoDTO(from: dict)
        XCTAssertNotNil(dto)
        XCTAssertEqual(dto?.deviceId, "dev-1")
        XCTAssertEqual(dto?.manufacturer, "Apple")
        XCTAssertEqual(dto?.model, "iPhone15,2")
        XCTAssertEqual(dto?.clientVersion, "0.13.0")
    }

    func testDeviceInfoDTONilForNilDict() {
        XCTAssertNil(PlaybackSession.deviceInfoDTO(from: nil))
    }

    func testPlaybackSessionToDTOScalars() {
        let ps = PlaybackSession()
        ps.id = "ps_1"
        ps.userId = "u_1"
        ps.libraryItemId = "li_1"
        ps.episodeId = "ep_1"
        ps.mediaType = "book"
        ps.displayTitle = "Title"
        ps.displayAuthor = "Author"
        ps.coverPath = "/c.jpg"
        ps.duration = 3600.5
        ps.playMethod = PlayMethod.transcode.rawValue // 2
        ps.startedAt = 1600000000000
        ps.updatedAt = 1600000001000
        ps.timeListening = 120.5
        ps.currentTime = 900.5

        let dto = ps.toDTO()
        XCTAssertEqual(dto.id, "ps_1")
        XCTAssertEqual(dto.userId, "u_1")
        XCTAssertEqual(dto.libraryItemId, "li_1")
        XCTAssertEqual(dto.episodeId, "ep_1")
        XCTAssertEqual(dto.mediaType, .book)
        XCTAssertEqual(dto.displayTitle, "Title")
        XCTAssertEqual(dto.displayAuthor, "Author")
        XCTAssertEqual(dto.coverPath, "/c.jpg")
        XCTAssertEqual(dto.duration, 3600.5)
        XCTAssertEqual(dto.playMethod, ._2)
        XCTAssertEqual(dto.startedAt, 1600000000000)   // Double → Int
        XCTAssertEqual(dto.updatedAt, 1600000001000)
        XCTAssertEqual(dto.timeListening, 120.5)
        XCTAssertEqual(dto.currentTime, 900.5)
        XCTAssertEqual(dto.mediaPlayer, "AVPlayer")
        // deviceInfo is populated from the session's (real-device) computed info.
        XCTAssertEqual(dto.deviceInfo?.manufacturer, "Apple")
    }

    func testPlaybackSessionToDTONestedCollections() {
        let ps = PlaybackSession()
        ps.id = "ps_2"
        let ch = Chapter(); ch.id = 1; ch.start = 0; ch.end = 10; ch.title = "c1"
        ps.chapters.append(ch)
        let track = AudioTrack(); track.duration = 100; track.mimeType = "audio/mpeg"
        ps.audioTracks.append(track)

        let dto = ps.toDTO()
        XCTAssertEqual(dto.chapters?.count, 1)
        XCTAssertEqual(dto.chapters?.first?.title, "c1")
        XCTAssertEqual(dto.audioTracks?.count, 1)
        XCTAssertEqual(Double(dto.audioTracks?.first?.duration ?? -1), 100, accuracy: 0.001)
    }

    func testPlaybackSessionToDTONilStartedUpdated() {
        let ps = PlaybackSession()
        ps.id = "ps_3"
        ps.startedAt = nil
        ps.updatedAt = nil

        let dto = ps.toDTO()
        XCTAssertNil(dto.startedAt)
        XCTAssertNil(dto.updatedAt)
    }
}
