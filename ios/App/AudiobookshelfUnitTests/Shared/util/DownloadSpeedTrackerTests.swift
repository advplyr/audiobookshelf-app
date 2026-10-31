import XCTest
@testable import Audiobookshelf

final class DownloadSpeedTrackerTests: XCTestCase {
    func testFirstSampleHasNoSpeed() {
        var tracker = DownloadSpeedTracker()

        tracker.record(totalBytes: 500, at: 10)

        XCTAssertEqual(tracker.bytesPerSecond, 0)
    }

    func testSecondSampleCalculatesBytesPerSecond() {
        var tracker = DownloadSpeedTracker()
        tracker.record(totalBytes: 500, at: 10)

        tracker.record(totalBytes: 1_500, at: 11)

        XCTAssertEqual(tracker.bytesPerSecond, 1_000, accuracy: 0.001)
    }

    func testLaterSamplesUseExponentialSmoothing() {
        var tracker = DownloadSpeedTracker()
        tracker.record(totalBytes: 0, at: 10)
        tracker.record(totalBytes: 1_000, at: 11)

        tracker.record(totalBytes: 3_000, at: 12)

        XCTAssertEqual(tracker.bytesPerSecond, 1_300, accuracy: 0.001)
    }

    func testRapidCallbacksUseLastAcceptedSample() {
        var tracker = DownloadSpeedTracker()
        tracker.record(totalBytes: 0, at: 10)
        tracker.record(totalBytes: 50, at: 10.05)

        tracker.record(totalBytes: 200, at: 10.2)

        XCTAssertEqual(tracker.bytesPerSecond, 1_000, accuracy: 0.001)
    }

    func testDecreasingByteCountNeverProducesNegativeSpeed() {
        var tracker = DownloadSpeedTracker()
        tracker.record(totalBytes: 1_000, at: 10)

        tracker.record(totalBytes: 500, at: 11)

        XCTAssertEqual(tracker.bytesPerSecond, 0)
    }
}
