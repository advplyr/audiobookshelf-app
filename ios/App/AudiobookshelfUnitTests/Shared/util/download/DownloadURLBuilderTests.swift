//
//  DownloadURLBuilderTests.swift
//  AudiobookshelfUnitTests
//

import XCTest
@testable import Audiobookshelf

final class DownloadURLBuilderTests: XCTestCase {

    private let address = "https://abs.example.com"
    private let token = "tok123"

    func testTrackPathIsTheDownloadApiPath() {
        XCTAssertEqual(DownloadURLBuilder.trackPath(itemId: "item-1", ino: "9876"),
                       "/api/items/item-1/file/9876/download")
    }

    func testBuildsUrlFromAnApiPath() {
        let url = DownloadURLBuilder.url(address: address, token: token,
                                         serverPath: "/api/items/item-1/file/9876/download",
                                         contentUrlFallback: nil)
        XCTAssertEqual(url?.absoluteString,
                       "https://abs.example.com/api/items/item-1/file/9876/download?token=tok123")
    }

    func testCoverPathForcesJpeg() {
        let url = DownloadURLBuilder.url(address: address, token: token,
                                         serverPath: "/api/items/item-1/cover",
                                         contentUrlFallback: nil)
        XCTAssertEqual(url?.absoluteString,
                       "https://abs.example.com/api/items/item-1/cover?token=tok123&format=jpeg")
    }

    // THE root-cause regression. Track parts persisted the server ADDRESS as their "serverPath", so the
    // stored URI became address + address — which parses to the nonexistent host
    // "abs.example.comhttps". With waitsForConnectivity = true the session then sits there forever
    // instead of failing, so every download restarted from the database transferred zero bytes.
    func testLegacyPartThatStoredTheAddressIsRepairedFromContentUrl() {
        let url = DownloadURLBuilder.url(address: address, token: token,
                                         serverPath: address, // the corrupt value
                                         contentUrlFallback: "/api/items/item-1/file/9876")
        XCTAssertEqual(url?.absoluteString,
                       "https://abs.example.com/api/items/item-1/file/9876/download?token=tok123")
    }

    func testLegacyRepairDoesNotDoubleTheDownloadSuffix() {
        let url = DownloadURLBuilder.url(address: address, token: token,
                                         serverPath: address,
                                         contentUrlFallback: "/api/items/item-1/file/9876/download")
        XCTAssertEqual(url?.absoluteString,
                       "https://abs.example.com/api/items/item-1/file/9876/download?token=tok123")
    }

    func testUnrepairableLegacyPartYieldsNil() {
        XCTAssertNil(DownloadURLBuilder.url(address: address, token: token,
                                            serverPath: address, contentUrlFallback: nil))
    }

    // A malformed host must never be produced again, whatever is in the database.
    func testNeverProducesADoubledAddress() {
        let url = DownloadURLBuilder.url(address: address, token: token,
                                         serverPath: address,
                                         contentUrlFallback: "/api/items/item-1/file/9876")
        XCTAssertEqual(url?.host, "abs.example.com")
    }

    func testTrailingSlashOnAddressDoesNotDoubleUp() {
        let url = DownloadURLBuilder.url(address: "https://abs.example.com/", token: token,
                                         serverPath: "/api/items/item-1/file/9876/download",
                                         contentUrlFallback: nil)
        XCTAssertEqual(url?.absoluteString,
                       "https://abs.example.com/api/items/item-1/file/9876/download?token=tok123")
    }

    // The token is supplied by the caller from the CURRENT server config rather than being baked into
    // a persisted URI, so a download reconciled hours later doesn't present an expired JWT.
    // Resume data embeds the request it came from. If a part's URL was corrupt and has now been
    // repaired, reusing that resume data resurrects the dead request — which is exactly how the bad
    // URL kept perpetuating itself through retries after the stored record was fixed.
    func testSameEndpointIgnoresTheTokenQuery() {
        let a = URL(string: "https://abs.example.com/api/items/1/file/9/download?token=old")
        let b = URL(string: "https://abs.example.com/api/items/1/file/9/download?token=new")
        XCTAssertTrue(DownloadURLBuilder.sameEndpoint(a, b))
    }

    func testSameEndpointDetectsARepairedHost() {
        let broken = URL(string: "https://abs.example.comhttps://abs.example.com?token=t")
        let fixed = URL(string: "https://abs.example.com/api/items/1/file/9/download?token=t")
        XCTAssertFalse(DownloadURLBuilder.sameEndpoint(broken, fixed))
    }

    func testSameEndpointIsFalseWhenEitherIsNil() {
        XCTAssertFalse(DownloadURLBuilder.sameEndpoint(nil, URL(string: "https://a.com/x")))
        XCTAssertFalse(DownloadURLBuilder.sameEndpoint(URL(string: "https://a.com/x"), nil))
    }

    // The query holds the access token, so it must never reach the log.
    func testRedactedDropsTheQuery() {
        let url = URL(string: "https://abs.example.com/api/items/1/file/9/download?token=secret")
        XCTAssertEqual(DownloadURLBuilder.redacted(url), "https://abs.example.com/api/items/1/file/9/download")
        XCTAssertFalse(DownloadURLBuilder.redacted(url).contains("secret"))
    }

    func testTokenComesFromTheCallerSoItCanBeRefreshed() {
        let url = DownloadURLBuilder.url(address: address, token: "fresh-token",
                                         serverPath: "/api/items/item-1/file/9876/download",
                                         contentUrlFallback: nil)
        XCTAssertTrue(url!.absoluteString.hasSuffix("?token=fresh-token"))
    }
}
