//
//  DownloadRetryPolicy.swift
//  Audiobookshelf
//
//  When a download part is worth retrying, and how long to wait first.
//

import Foundation

enum DownloadRetryPolicy {

    /// Attempts a single part may make before it is failed permanently. Counted across app launches
    /// (see `DownloadStateStore`) so a part can't loop forever by having its counter reset on relaunch.
    static let maxAttemptsPerPart = 5

    /// Whether a failed transfer is worth retrying.
    ///
    /// This is a DENYLIST on purpose. It used to be an allowlist of nine "recoverable" URL error codes,
    /// which meant every code it forgot about was treated as fatal. That is how a transient
    /// `NSURLErrorCannotParseResponse` (-1017) from the server/proxy permanently failed download parts
    /// on their FIRST occurrence, with their retry budget completely unused — observed killing five
    /// parts of one book within four seconds on a real device.
    ///
    /// An allowlist can only ever be incomplete; the set of errors that retrying genuinely cannot fix
    /// is small and knowable, so enumerate that instead.
    static func isRecoverable(_ error: NSError) -> Bool {
        // Non-URL errors (POSIX ENOSPC, our own file-move failures) aren't transport blips.
        guard error.domain == NSURLErrorDomain else { return false }

        // NOTE: NSURLErrorCancelled (-999) is deliberately NOT here. Force-quitting the app makes iOS
        // tear down the background session and complete every in-flight task with -999; treating that
        // as terminal permanently failed all five adopted parts of a book "after 0 retries" the moment
        // it was reopened. Every cancel this app issues on purpose is already accounted for before this
        // is consulted — stall cancels set the intentional flag, duplicate cancels are filtered by task
        // identifier, and there is no user-facing cancel-download path — so a -999 arriving here is the
        // system, and the transfer should be resumed.
        switch error.code {
        case NSURLErrorBadURL,
             NSURLErrorUnsupportedURL,
             NSURLErrorUserAuthenticationRequired,
             NSURLErrorUserCancelledAuthentication,
             NSURLErrorNoPermissionsToReadFile,
             NSURLErrorCannotWriteToFile,
             NSURLErrorCannotCreateFile,
             NSURLErrorCannotMoveFile,
             NSURLErrorCannotRemoveFile,
             NSURLErrorCannotCloseFile,
             NSURLErrorCannotOpenFile,
             NSURLErrorFileDoesNotExist,
             NSURLErrorFileIsDirectory,
             NSURLErrorDataLengthExceedsMaximum:
            return false
        default:
            return true
        }
    }

    static func isSuccess(httpStatus: Int) -> Bool {
        (200...299).contains(httpStatus)
    }

    /// Whether a non-2xx response is worth retrying.
    ///
    /// URLSession treats any HTTP response as a successful transfer — an error page or JSON body is just
    /// the payload — so a download that doesn't check this writes that body to disk as the audio file.
    /// A 401 is the case that matters most on a long transfer: access tokens are short-lived JWTs, so a
    /// multi-gigabyte book can outlive the one it started with, and the retry rebuilds the URL with a
    /// fresh token.
    static func isRecoverable(httpStatus: Int) -> Bool {
        switch httpStatus {
        case 401, 403, 408, 425, 429: return true // auth refresh, or asked to back off
        case 500...599: return true               // server-side, may pass
        default: return false                     // 404/410/416/… won't fix themselves
        }
    }

    /// Exponential backoff, capped at a minute.
    ///
    /// Retries used to be re-queued immediately. That hammers a server which is already struggling, and
    /// on a sleeping device a tight retry loop can burn every attempt within seconds of each other —
    /// so the part is permanently dead before the user has any chance to bring the app back.
    static func backoffDelay(forAttempt attempt: Int) -> TimeInterval {
        guard attempt > 0 else { return 0 }
        return min(pow(2.0, Double(min(attempt, 6))), 60.0) // 2, 4, 8, 16, 32, 60, 60…
    }
}
