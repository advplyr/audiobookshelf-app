//
//  DownloadRetryPolicyTests.swift
//  AudiobookshelfUnitTests
//

import XCTest
@testable import Audiobookshelf

final class DownloadRetryPolicyTests: XCTestCase {

    private func urlError(_ code: Int) -> NSError {
        NSError(domain: NSURLErrorDomain, code: code)
    }

    // THE regression this whole change exists for: a real device log showed five download parts
    // permanently failed with "cannot parse response" while their retry budgets were untouched,
    // because the old allowlist of nine codes didn't include -1017.
    func testCannotParseResponseIsRecoverable() {
        XCTAssertTrue(DownloadRetryPolicy.isRecoverable(urlError(NSURLErrorCannotParseResponse)))
    }

    func testTransientNetworkErrorsAreRecoverable() {
        let transient = [
            NSURLErrorTimedOut,
            NSURLErrorNetworkConnectionLost,
            NSURLErrorNotConnectedToInternet,
            NSURLErrorCannotConnectToHost,
            NSURLErrorCannotFindHost,
            NSURLErrorDNSLookupFailed,
            NSURLErrorResourceUnavailable,
            NSURLErrorBadServerResponse,
            NSURLErrorZeroByteResource,
            NSURLErrorSecureConnectionFailed,
            NSURLErrorRequestBodyStreamExhausted,
        ]
        for code in transient {
            XCTAssertTrue(DownloadRetryPolicy.isRecoverable(urlError(code)),
                          "URL error \(code) should be retried, not treated as fatal")
        }
    }

    // Force-quitting the app makes iOS tear down the background session, completing every in-flight
    // task with NSURLErrorCancelled (-999). Treating that as terminal permanently failed all five
    // adopted parts of a book "after 0 retries" the instant it was reopened — observed on device.
    //
    // Every cancel this app issues deliberately is accounted for before the classifier is consulted:
    // stall cancels set the intentional flag, duplicate cancels are filtered by task identifier, and
    // there is no user-facing cancel-download path. So a -999 reaching here is the system, and the
    // download should resume rather than die.
    func testSystemCancellationIsRecoverable() {
        XCTAssertTrue(DownloadRetryPolicy.isRecoverable(urlError(NSURLErrorCancelled)))
    }

    // The denylist: things retrying can never fix.
    func testTerminalErrorsAreNotRecoverable() {
        let terminal = [
            NSURLErrorBadURL,
            NSURLErrorUnsupportedURL,
            NSURLErrorUserAuthenticationRequired,
            NSURLErrorNoPermissionsToReadFile,
            NSURLErrorCannotWriteToFile,
            NSURLErrorCannotCreateFile,
            NSURLErrorCannotMoveFile,
            NSURLErrorFileDoesNotExist,
            NSURLErrorDataLengthExceedsMaximum,
        ]
        for code in terminal {
            XCTAssertFalse(DownloadRetryPolicy.isRecoverable(urlError(code)),
                           "URL error \(code) is terminal and should not be retried")
        }
    }

    func testNonUrlDomainErrorsAreNotRecoverable() {
        XCTAssertFalse(DownloadRetryPolicy.isRecoverable(
            NSError(domain: NSPOSIXErrorDomain, code: 28))) // ENOSPC — disk full
    }

    // Retrying instantly hammers a struggling server, and on a sleeping device it can burn every
    // attempt within seconds (raised in review of PR #1924).
    func testBackoffGrowsAndIsCapped() {
        XCTAssertEqual(DownloadRetryPolicy.backoffDelay(forAttempt: 1), 2)
        XCTAssertEqual(DownloadRetryPolicy.backoffDelay(forAttempt: 2), 4)
        XCTAssertEqual(DownloadRetryPolicy.backoffDelay(forAttempt: 3), 8)
        XCTAssertEqual(DownloadRetryPolicy.backoffDelay(forAttempt: 4), 16)
        XCTAssertEqual(DownloadRetryPolicy.backoffDelay(forAttempt: 5), 32)
        XCTAssertEqual(DownloadRetryPolicy.backoffDelay(forAttempt: 99), 60)
    }

    func testFirstAttemptHasNoDelay() {
        XCTAssertEqual(DownloadRetryPolicy.backoffDelay(forAttempt: 0), 0)
    }

    // A non-2xx response completes "successfully" as far as URLSession is concerned — the error page or
    // JSON body is simply the payload. Downloads must classify it themselves, or that body gets written
    // to disk as the audiobook track.
    func testAuthAndThrottlingStatusesAreRetryable() {
        for status in [401, 403, 408, 425, 429, 500, 502, 503, 504] {
            XCTAssertTrue(DownloadRetryPolicy.isRecoverable(httpStatus: status),
                          "HTTP \(status) should be retried — a refreshed token or a later attempt can fix it")
        }
    }

    func testPermanentStatusesAreNotRetryable() {
        for status in [400, 404, 410, 416, 451] {
            XCTAssertFalse(DownloadRetryPolicy.isRecoverable(httpStatus: status),
                           "HTTP \(status) will not fix itself")
        }
    }

    func testSuccessfulStatusesAreNotFailures() {
        for status in [200, 206] {
            XCTAssertTrue(DownloadRetryPolicy.isSuccess(httpStatus: status))
        }
        XCTAssertFalse(DownloadRetryPolicy.isSuccess(httpStatus: 401))
        XCTAssertFalse(DownloadRetryPolicy.isSuccess(httpStatus: 302))
    }
}
