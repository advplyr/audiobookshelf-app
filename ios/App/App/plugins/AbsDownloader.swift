//
//  AbsDownloader.swift
//  App
//
//  Created by advplyr on 5/13/22.
//

import Foundation
import Capacitor
import RealmSwift

@objc(AbsDownloader)
public class AbsDownloader: CAPPlugin, CAPBridgedPlugin, URLSessionDownloadDelegate {
    public var identifier = "AbsDownloaderPlugin"
    public var jsName = "AbsDownloader"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "downloadLibraryItem", returnType: CAPPluginReturnPromise)
    ]
    
    static private let downloadsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    
    // Serial delegate queue, SHARED by both sessions: guarantees a task's didFinishDownloadingTo (which
    // commits `completed = true`) fully finishes before its didCompleteWithError runs. With a concurrent
    // queue these could overlap on different threads, so the completion check could read the part's own
    // `completed` as still false and — with no polling fallback — wedge the item at "Download complete.
    // Processing...". Sharing one queue extends that guarantee across a session migration too.
    private lazy var delegateQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        return queue
    }()

    /// Survives app suspension, but iOS throttles it hard: measured at ~0.6 MB/s AGGREGATE regardless of
    /// how many transfers run (3 got ~0.20 MB/s each, 6 got ~0.10 MB/s each) on a connection serving
    /// 30-40 MB/s to a desktop browser. Used only while the app is not in the foreground.
    private lazy var backgroundSession: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "AbsDownloader")
        // Hardening: bound task lifetime and wait for connectivity instead of failing on a
        // transient blip. A background session's DEFAULT resource timeout is 7 DAYS, which is
        // why a silently stalled task could otherwise sit "downloading" forever.
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 12 * 60 * 60 // 12h hard cap
        config.waitsForConnectivity = true
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        return URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
    }()

    /// In-process and unthrottled — measured at 21.6 MB/s on the same device, network and moment the
    /// background session was managing 0.6 MB/s. iOS suspends it when the app leaves the foreground,
    /// so transfers are migrated to the background session on the way out and back again on return.
    private lazy var foregroundSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 12 * 60 * 60
        config.waitsForConnectivity = true
        return URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
    }()

    /// Whether the app is in the foreground. Only ever written on the main queue.
    private var isAppInForeground = true
    private var lastMigrationAt: Date?
    /// Rapid app switching must not turn into a cancel/reissue storm.
    private let migrationCooldown: TimeInterval = 5
    private var migratingPartIds: Set<String> = [] // guarded by downloadQueueLock

    private var preferredSession: URLSession { isAppInForeground ? foregroundSession : backgroundSession }

    // Stall detection + retry tuning
    private let stallTimeout: TimeInterval = 30     // no bytes for this long => treat as stalled
    private let stallCheckInterval: TimeInterval = 10
    // A task adopted from a previous launch, or one whose clock we just re-baselined after a suspension,
    // hasn't had a chance to report progress yet. Without this it was cancelled 30s after every launch.
    private let stallGracePeriod: TimeInterval = 90
    // Bytes a task must transfer before we consider the connection healthy and hand the part a fresh
    // retry budget. Otherwise three unrelated blips spread over a multi-hour download kill it.
    private let healthyTransferThreshold: Int64 = 1_000_000
    private var stalledPartIds: Set<String> = []    // parts we cancelled on purpose to retry (guarded by downloadQueueLock)
    private var invalidStatusPartIds: [String: Int] = [:] // partId -> non-2xx status seen (guarded by downloadQueueLock)
    private var discardedTaskIds: Set<Int> = []     // duplicate tasks we cancelled; their completions carry no meaning (guarded by downloadQueueLock)
    private var finalizedItemIds: Set<String> = []  // items already finalized, so concurrent part completions finalize once (guarded by downloadQueueLock)
    private var stallWatchdogTimer: Timer?
    private var lastWatchdogTick: Date?             // main queue only

    // Retry attempts + resume data that must outlive the process. See DownloadStateStore.
    // Eagerly initialised: it's touched from both the session's delegate queue and the plugin call
    // queue, and `lazy` initialisation is not thread-safe.
    private let stateStore = DownloadStateStore(
        directory: AbsDownloader.downloadsDirectory.appendingPathComponent(".download-state"))

    // Progress-write throttling (avoid a Realm write + bridge call on every byte callback)
    private let progressThrottleLock = NSLock()
    private var lastPersistAt: [String: Date] = [:]
    private let progressPersistInterval: TimeInterval = 1.0

    // Download queue management
    private let downloadQueueLock = NSLock()
    private var pendingDownloadTasks: [PendingDownload] = []
    private var activeDownloads: [String: ActiveDownload] = [:] // partId -> live state
    // Measured 2026-07-22: the background session's throttle is PER-SESSION, not per-task. Three
    // transfers got ~0.20 MB/s each, six got ~0.10 MB/s each, aggregate pinned at ~0.6 MB/s either way,
    // while the same server serves 30-40 MB/s to a desktop browser. So concurrency buys nothing here;
    // back to 3, which keeps per-file rates (and ETAs) as high as the cap allows.
    private let maxConcurrentDownloads = 3

    // Aggregate throughput sampling, so total rate is directly readable rather than summed by hand.
    private var aggregateBytesWritten: Int64 = 0
    private var aggregateWindowStart: Date?
    private var aggregateBytesAtWindowStart: Int64 = 0
    
    
    // MARK: - Download Queue Management

    private func startNextDownloadInQueue() {
        downloadQueueLock.lock()
        var duplicates: [URLSessionDownloadTask] = []
        // Start downloads up to the max concurrent limit
        while activeDownloads.count < maxConcurrentDownloads && !pendingDownloadTasks.isEmpty {
            let next = pendingDownloadTasks.removeFirst()

            // Never overwrite a live entry. Part ids are deterministic (base64 of the destination path),
            // so a re-requested or reconciled item produces pending tasks for parts that may already be
            // in flight. Assigning over them silently orphaned the running task — it kept downloading to
            // the same destination, untracked, while its completion callback mutated the new task's
            // bookkeeping. Drop the duplicate instead.
            guard activeDownloads[next.partId] == nil else {
                AbsLogger.info(message: "Skipping duplicate download task for \(next.filename) — already in flight")
                discardedTaskIds.insert(next.task.taskIdentifier)
                duplicates.append(next.task)
                continue
            }

            activeDownloads[next.partId] = ActiveDownload(partId: next.partId, filename: next.filename, downloadURL: next.downloadURL, task: next.task, usesForegroundSession: next.usesForegroundSession, lastBytesWritten: 0, lastProgressAt: Date(), graceUntil: nil, clearedRetryState: false, startedAt: Date())
            AbsLogger.info(message: "Starting download for \(next.filename) → \(DownloadURLBuilder.redacted(next.task.originalRequest?.url ?? next.downloadURL)) (\(activeDownloads.count)/\(maxConcurrentDownloads) active, \(pendingDownloadTasks.count) pending)")
            next.task.resume()
        }
        let hasActive = !activeDownloads.isEmpty
        downloadQueueLock.unlock()

        // Cancel outside the lock; cancel() delivers didCompleteWithError on the delegate queue.
        for task in duplicates { task.cancel() }

        if hasActive { startStallWatchdogIfNeeded() }
    }

    // MARK: - Session migration

    /// Downloads run on whichever session is fast enough and legal for the current app state: the
    /// in-process default session while the app is in front (unthrottled), the background session once
    /// it isn't (throttled, but the only kind that survives suspension).
    ///
    /// Moving a transfer means cancelling it for resume data and reissuing it on the other session,
    /// which only preserves progress because the server answers Range requests with 206 — verified
    /// before this was built. Without that, every migration would restart the file from zero.
    private func observeAppStateForSessionMigration() {
        DispatchQueue.runOnMainQueue {
            self.isAppInForeground = UIApplication.shared.applicationState != .background
            NotificationCenter.default.addObserver(self, selector: #selector(self.handleAppBackgrounded),
                                                   name: UIApplication.didEnterBackgroundNotification, object: nil)
            NotificationCenter.default.addObserver(self, selector: #selector(self.handleAppForegrounded),
                                                   name: UIApplication.willEnterForegroundNotification, object: nil)
        }
    }

    @objc private func handleAppBackgrounded() {
        isAppInForeground = false
        // Buy time for the cancel/reissue round trip; without it iOS can suspend us mid-migration and
        // the transfer only resumes at the next launch via reconcile.
        var assertion: UIBackgroundTaskIdentifier = .invalid
        assertion = UIApplication.shared.beginBackgroundTask(withName: "download-session-migration") {
            if assertion != .invalid { UIApplication.shared.endBackgroundTask(assertion) }
            assertion = .invalid
        }
        migrateActiveDownloads(reason: "app backgrounded")
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 15) {
            if assertion != .invalid { UIApplication.shared.endBackgroundTask(assertion) }
            assertion = .invalid
        }
    }

    @objc private func handleAppForegrounded() {
        isAppInForeground = true
        migrateActiveDownloads(reason: "app foregrounded")
    }

    private func migrateActiveDownloads(reason: String) {
        let wantForeground = isAppInForeground
        let now = Date()

        downloadQueueLock.lock()
        // Rapid app switching must not become a cancel/reissue storm — each migration costs a round trip
        // and, when resume data is refused, real progress.
        if let last = lastMigrationAt, now.timeIntervalSince(last) < migrationCooldown {
            downloadQueueLock.unlock()
            return
        }

        let mismatched = activeDownloads.values.filter { $0.usesForegroundSession != wantForeground }

        // Queued-but-unstarted tasks were built against the other session. They carry no progress, so
        // just rebuild them rather than migrating.
        var rebuiltPending: [PendingDownload] = []
        var staleTasks: [URLSessionDownloadTask] = []
        pendingDownloadTasks = pendingDownloadTasks.map { pending in
            guard pending.usesForegroundSession != wantForeground else { return pending }
            discardedTaskIds.insert(pending.task.taskIdentifier)
            staleTasks.append(pending.task)
            let session = wantForeground ? foregroundSession : backgroundSession
            let task = session.downloadTask(with: pending.downloadURL)
            task.taskDescription = pending.partId
            let replacement = PendingDownload(partId: pending.partId, filename: pending.filename,
                                              downloadURL: pending.downloadURL, task: task,
                                              usesForegroundSession: wantForeground)
            rebuiltPending.append(replacement)
            return replacement
        }

        guard !mismatched.isEmpty || !rebuiltPending.isEmpty else {
            downloadQueueLock.unlock()
            return
        }
        for dl in mismatched { migratingPartIds.insert(dl.partId) }
        lastMigrationAt = now
        downloadQueueLock.unlock()

        AbsLogger.info(message: "Migrating \(mismatched.count) transfer(s) (+\(rebuiltPending.count) queued) to the \(wantForeground ? "foreground" : "background") session — \(reason)")

        for task in staleTasks { task.cancel() }
        // Cancelling for resume data lands in didCompleteWithError, which reissues on the new session.
        for dl in mismatched { dl.task.cancel(byProducingResumeData: { _ in }) }
    }

    /// Reissue a transfer that was cancelled purely to move it between sessions. Deliberately does NOT
    /// consume a retry attempt or touch failure state — nothing went wrong.
    private func reissueMigratedDownload(partId: String, filename: String, previousURL: URL?, resumeData: Data?) {
        // Persist it before reissuing. Backgrounding is exactly when the app is most likely to be killed
        // next, and a task killed outright delivers no completion — so without this, reconcile finds
        // nothing to resume from and restarts the file. Observed costing 310 MB of a 380 MB transfer.
        if let resumeData = resumeData {
            stateStore.saveResumeData(resumeData, forPartId: partId)
        }

        let useForeground = isAppInForeground
        let session = useForeground ? foregroundSession : backgroundSession
        let part = Database.shared.getDownloadItem(downloadItemPartId: partId)?
            .downloadItemParts.first(where: { $0.id == partId })
        let url = part.flatMap { self.downloadURL(for: $0) } ?? previousURL

        let task: URLSessionDownloadTask?
        if let resumeData = resumeData, DownloadURLBuilder.sameEndpoint(previousURL, url) {
            task = session.downloadTask(withResumeData: resumeData)
        } else if let url = url {
            task = session.downloadTask(with: url)
        } else {
            task = nil
        }

        guard let task = task, let resolvedURL = url ?? task.originalRequest?.url else {
            AbsLogger.error(message: "Could not migrate \(filename); leaving it for reconcile on next launch")
            return
        }
        task.taskDescription = partId
        AbsLogger.info(message: "Migrated \(filename) to the \(useForeground ? "foreground" : "background") session (resume=\(resumeData != nil))")
        enqueuePendingDownload(PendingDownload(partId: partId, filename: filename, downloadURL: resolvedURL,
                                               task: task, usesForegroundSession: useForeground))
        startNextDownloadInQueue()
    }

    /// Tear down everything in flight or queued for these parts, so a replaced download can't leave
    /// tasks running against the old record's rows. Their completions are ignored (discardedTaskIds).
    private func cancelInFlightDownloads(partIds: [String]) {
        downloadQueueLock.lock()
        var tasks: [URLSessionDownloadTask] = []
        for partId in partIds {
            if let active = activeDownloads.removeValue(forKey: partId) {
                discardedTaskIds.insert(active.task.taskIdentifier)
                tasks.append(active.task)
            }
            pendingDownloadTasks.removeAll { pending in
                guard pending.partId == partId else { return false }
                discardedTaskIds.insert(pending.task.taskIdentifier)
                tasks.append(pending.task)
                return true
            }
            stalledPartIds.remove(partId)
        }
        downloadQueueLock.unlock()

        if !tasks.isEmpty { AbsLogger.info(message: "Cancelled \(tasks.count) in-flight task(s) for the replaced download") }
        for task in tasks { task.cancel() }
    }

    /// Queue a part, unless it is already active or already queued.
    ///
    /// Retries are scheduled with a delay, so by the time one lands the part may have been queued again
    /// by a reconcile or by another task's completion. Without this the queue grew without bound — a
    /// 10-part book reached "16 pending", with the same part retried twice within 40ms, each spawning
    /// more tasks.
    private func enqueuePendingDownload(_ pending: PendingDownload) {
        downloadQueueLock.lock()
        let alreadyQueued = activeDownloads[pending.partId] != nil
            || pendingDownloadTasks.contains(where: { $0.partId == pending.partId })
        if alreadyQueued {
            discardedTaskIds.insert(pending.task.taskIdentifier)
        } else {
            pendingDownloadTasks.append(pending)
        }
        downloadQueueLock.unlock()

        if alreadyQueued {
            AbsLogger.info(message: "Dropping duplicate queue entry for \(pending.filename)")
            pending.task.cancel()
        }
    }

    // Reconnect to background downloads left over from a previous app launch so a relaunch can't
    // orphan an in-flight task (a background URLSession's tasks persist across process restarts).
    public override func load() {
        observeAppStateForSessionMigration()
        // Only the background session persists tasks across launches.
        backgroundSession.getAllTasks { tasks in
            var adoptedPartIds = Set<String>()
            self.downloadQueueLock.lock()
            var adopted = 0
            for task in tasks {
                guard let dl = task as? URLSessionDownloadTask, let partId = dl.taskDescription, let url = dl.originalRequest?.url else {
                    task.cancel()
                    continue
                }
                if self.activeDownloads[partId] == nil {
                    switch dl.state {
                    case .running, .suspended:
                        // Grace period: an adopted background task can take a while to start delivering
                        // progress callbacks again. Without it the watchdog cancelled these ~30s after
                        // every single launch, burning retries on downloads that were perfectly fine.
                        self.activeDownloads[partId] = ActiveDownload(partId: partId, filename: partId, downloadURL: url, task: dl, usesForegroundSession: false, lastBytesWritten: 0, lastProgressAt: Date(), graceUntil: Date().addingTimeInterval(self.stallGracePeriod), clearedRetryState: false, startedAt: Date())
                        if dl.state == .suspended { dl.resume() }
                        adopted += 1
                        adoptedPartIds.insert(partId)
                    default:
                        break
                    }
                } else {
                    adoptedPartIds.insert(partId)
                }
            }
            self.downloadQueueLock.unlock()
            if adopted > 0 {
                AbsLogger.info(message: "Reconciled \(adopted) in-flight download task(s) from a previous session")
                self.startStallWatchdogIfNeeded()
            }

            // Live background tasks are only half the picture: the JS store learns about a download
            // solely via onDownloadItem at start time, so any DownloadItem persisted in the DB whose
            // tasks didn't survive the relaunch would be orphaned (never finishes, never clears).
            // Re-emit those to JS, restart their orphaned/failed parts, and finalize any that already
            // finished on disk. Runs on the session's (serial) delegate queue, so it can't race the
            // download callbacks.
            self.reconcilePersistedDownloads(adoptedPartIds: adoptedPartIds)

            // Tasks adopted here belong to the background session, and launching straight into the
            // foreground fires no transition notification — so without this, a resumed download would
            // stay on the throttled session until the user happened to switch apps.
            if self.isAppInForeground {
                self.migrateActiveDownloads(reason: "launched in the foreground")
            }
        }
    }

    private func reconcilePersistedDownloads(adoptedPartIds: Set<String>) {
        let realm: Realm
        do { realm = try Realm() } catch { return }
        realm.refresh()
        let items = Array(realm.objects(DownloadItem.self))
        guard !items.isEmpty else { return }

        var restartTasks: [PendingDownload] = []
        var partIdsToFinalize: [String] = []

        for item in items {
            var allDone = true
            var itemRestarts: [PendingDownload] = []
            for part in item.downloadItemParts {
                if part.completed && !part.failed { continue } // genuinely finished
                allDone = false
                if adoptedPartIds.contains(part.id) { continue } // still downloading via an adopted task
                // Rebuild against the current config: the persisted URI carries a stale token and, for
                // parts queued before the serverPath fix, an unreachable host.
                guard let url = self.downloadURL(for: part) else { continue }

                // Continue from where the previous run left off when we have resume data. This used to
                // unconditionally reset progress to 0 and re-download the whole file, so a book too big
                // to finish in one app session could never finish at all — every relaunch threw the
                // partial transfer away.
                let task: URLSessionDownloadTask
                if let resumeData = self.stateStore.resumeData(forPartId: part.id) {
                    task = self.preferredSession.downloadTask(withResumeData: resumeData)
                    try? realm.write {
                        part.failed = false
                        part.completed = false
                    }
                    AbsLogger.info(message: "Resuming \(part.filename ?? part.id) from saved resume data")
                } else {
                    task = self.preferredSession.downloadTask(with: url)
                    try? realm.write {
                        part.progress = 0
                        part.bytesDownloaded = 0
                        part.failed = false
                        part.completed = false
                    }
                }
                task.taskDescription = part.id
                itemRestarts.append(PendingDownload(partId: part.id, filename: part.filename ?? part.id, downloadURL: url, task: task, usesForegroundSession: self.isAppInForeground))
            }

            // Everything already on disk but never finalized (app died mid-finalization). If a local
            // library item already exists it was finalized before the crash — just drop the stale
            // record; otherwise finalize it now.
            if allDone, Database.shared.getLocalLibraryItem(byServerLibraryItemId: item.libraryItemId ?? item.id ?? "") != nil {
                try? realm.write {
                    realm.delete(item.downloadItemParts)
                    realm.delete(item)
                }
                continue
            }

            // Genuine in-flight download — re-add it to the JS store.
            //
            // retainUntilConsumed matters here: load() runs at plugin-init, ~125ms BEFORE the web layer
            // mounts and registers its listeners, so a plain notifyListeners is dropped on the floor and
            // the resumed download never appears in the downloads list even though it is running.
            try? self.notifyListeners("onDownloadItem", data: item.asDictionary(), retainUntilConsumed: true)
            restartTasks.append(contentsOf: itemRestarts)
            if allDone, let firstPartId = item.downloadItemParts.first?.id {
                partIdsToFinalize.append(firstPartId)
            }
        }

        for partId in partIdsToFinalize {
            checkItemCompletion(forPartId: partId)
        }

        if !restartTasks.isEmpty {
            downloadQueueLock.lock()
            pendingDownloadTasks.append(contentsOf: restartTasks)
            downloadQueueLock.unlock()
            AbsLogger.info(message: "Reconciled \(restartTasks.count) orphaned download part(s) from a previous session")
            startNextDownloadInQueue()
        }
    }


    // MARK: - Progress handling

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // To URLSession any HTTP response is a successful transfer — an error page or JSON body is just
        // the payload. Without this check that body was moved into place AS THE AUDIO FILE, so the
        // download reported success and the track was garbage. The likely trigger is an access token
        // expiring mid-transfer (401), which a multi-gigabyte book can easily outlive.
        if let http = downloadTask.response as? HTTPURLResponse,
           !DownloadRetryPolicy.isSuccess(httpStatus: http.statusCode) {
            let partId = downloadTask.taskDescription
            if let partId = partId {
                downloadQueueLock.lock()
                invalidStatusPartIds[partId] = http.statusCode
                downloadQueueLock.unlock()
            }
            try? FileManager.default.removeItem(at: location)
            AbsLogger.error(message: "Server returned HTTP \(http.statusCode) for \(partId ?? "?") — discarding the response body instead of saving it as the track")
            return
        }

        handleDownloadTaskUpdate(downloadTask: downloadTask) { downloadItem, downloadItemPart in
            let realm = try Realm()
            let partId = downloadItemPart.id

            // Get fresh reference to the object in this realm
            guard let liveDownloadItemPart = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId) else {
                throw LibraryItemDownloadError.downloadItemPartNotFound
            }

            try realm.write {
                liveDownloadItemPart.bytesDownloaded = liveDownloadItemPart.fileSize
                liveDownloadItemPart.progress = 100
                liveDownloadItemPart.completed = true
            }
            
            do {
                // Move the downloaded file into place
                guard let destinationUrl = liveDownloadItemPart.destinationURL else {
                    throw LibraryItemDownloadError.downloadItemPartDestinationUrlNotDefined
                }
                try? FileManager.default.removeItem(at: destinationUrl)
                try FileManager.default.moveItem(at: location, to: destinationUrl)
                try realm.write {
                    liveDownloadItemPart.moved = true
                }
            } catch {
                try realm.write {
                    liveDownloadItemPart.failed = true
                }
                throw error
            }
        }
    }
    
    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let partId = task.taskDescription else { return }

        // Atomically release the concurrency slot and read state, so a finished/failed/stalled
        // task can NEVER permanently occupy a slot and jam the queue.
        downloadQueueLock.lock()
        // A duplicate we deliberately cancelled. Its completion says nothing about the part — and must
        // not be read as a failure, since the real task may already have finished and cleared the entry.
        if discardedTaskIds.remove(task.taskIdentifier) != nil {
            downloadQueueLock.unlock()
            return
        }
        // A superseded duplicate — or an orphan left over from an earlier launch — must not clobber the
        // task that is actually downloading this part, or release its concurrency slot.
        if let registered = activeDownloads[partId], registered.task.taskIdentifier != task.taskIdentifier {
            downloadQueueLock.unlock()
            AbsLogger.info(message: "Ignoring completion of a superseded task for \(registered.filename)")
            return
        }
        let active = activeDownloads.removeValue(forKey: partId)
        let intentionalStallCancel = stalledPartIds.remove(partId) != nil
        let isMigrating = migratingPartIds.remove(partId) != nil
        let invalidStatus = invalidStatusPartIds.removeValue(forKey: partId)
        downloadQueueLock.unlock()

        // Moved between sessions on purpose. Not a failure: no attempt consumed, no failure state.
        // (If error is nil the transfer actually finished first — fall through and finalize it.)
        if isMigrating, let error = error as NSError? {
            reissueMigratedDownload(partId: partId,
                                    filename: active?.filename ?? partId,
                                    previousURL: active?.downloadURL ?? task.originalRequest?.url,
                                    resumeData: error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data)
            startNextDownloadInQueue()
            return
        }
        progressThrottleLock.lock()
        lastPersistAt.removeValue(forKey: partId)
        progressThrottleLock.unlock()

        if let error = error as NSError? {
            let resumeData = error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
            // Persist resume data so a relaunch continues the transfer instead of starting the file over.
            if let resumeData = resumeData {
                stateStore.saveResumeData(resumeData, forPartId: partId)
            }

            retryOrFailPart(partId: partId,
                            filename: active?.filename ?? partId,
                            previousURL: active?.downloadURL ?? task.originalRequest?.url,
                            resumeData: resumeData,
                            recoverable: intentionalStallCancel || DownloadRetryPolicy.isRecoverable(error),
                            stalled: intentionalStallCancel,
                            failureDescription: error.localizedDescription)
        } else if let status = invalidStatus {
            // The transfer completed, but the server refused it — the body was an error page, and
            // didFinishDownloadingTo already threw it away rather than saving it as the track.
            //
            // No resume data on purpose: replaying the request that produced the failure would just
            // reproduce it, and a 401 specifically needs the URL rebuilt with a freshly refreshed token.
            retryOrFailPart(partId: partId,
                            filename: active?.filename ?? partId,
                            previousURL: active?.downloadURL ?? task.originalRequest?.url,
                            resumeData: nil,
                            recoverable: DownloadRetryPolicy.isRecoverable(httpStatus: status),
                            stalled: false,
                            failureDescription: "server returned HTTP \(status)")
        } else {
            if let active = active {
                let elapsed = Date().timeIntervalSince(active.startedAt)
                let waited = active.firstByteAt.map { $0.timeIntervalSince(active.startedAt) }
                let rate = DownloadThroughput.megabytesPerSecond(bytes: active.lastBytesWritten, seconds: elapsed)
                AbsLogger.info(message: String(format: "Finished %@: %@ in %.0fs (%@ avg%@)",
                                               active.filename,
                                               DownloadThroughput.describeBytes(active.lastBytesWritten),
                                               elapsed,
                                               DownloadThroughput.describeRate(rate),
                                               waited.map { String(format: ", %.1fs to first byte", $0) } ?? ""))
            }
            stateStore.clear(forPartId: partId)
            checkItemCompletion(forPartId: partId)
        }

        startNextDownloadInQueue()
    }
    
    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let partId = downloadTask.taskDescription else { return }

        // Feed the stall watchdog on EVERY callback (not throttled) so it sees real forward progress
        let now = Date()
        downloadQueueLock.lock()
        var becameHealthy = false
        var firstByteMessage: String?
        var rateMessage: String?
        var aggregateMessage: String?
        if var dl = activeDownloads[partId] {
            // Accumulate before lastBytesWritten is overwritten. A task's FIRST report carries whatever a
            // previous attempt already transferred, which is not throughput happening now.
            let delta = dl.hasReportedBytes ? max(0, totalBytesWritten - dl.lastBytesWritten) : 0
            dl.hasReportedBytes = true
            aggregateBytesWritten += delta
            if aggregateWindowStart == nil {
                aggregateWindowStart = now
                aggregateBytesAtWindowStart = aggregateBytesWritten
            } else if let windowStart = aggregateWindowStart,
                      now.timeIntervalSince(windowStart) >= DownloadThroughput.logInterval {
                let windowBytes = aggregateBytesWritten - aggregateBytesAtWindowStart
                let rate = DownloadThroughput.megabytesPerSecond(bytes: windowBytes, seconds: now.timeIntervalSince(windowStart))
                aggregateMessage = "AGGREGATE: \(DownloadThroughput.describeRate(rate)) across \(activeDownloads.count) transfers"
                aggregateWindowStart = now
                aggregateBytesAtWindowStart = aggregateBytesWritten
            }

            dl.lastBytesWritten = totalBytesWritten
            dl.lastProgressAt = now
            dl.graceUntil = nil // it's reporting now; it no longer needs the benefit of the doubt
            if !dl.clearedRetryState && totalBytesWritten >= healthyTransferThreshold {
                dl.clearedRetryState = true
                becameHealthy = true
            }

            // Instrumentation: time-to-first-byte separates "the server is slow to start" from
            // "the transfer itself is slow".
            if dl.firstByteAt == nil {
                dl.firstByteAt = now
                dl.lastRateLogAt = now
                dl.bytesAtLastRateLog = totalBytesWritten
                dl.bytesAtFirstByte = totalBytesWritten
                firstByteMessage = String(format: "First byte for %@ after %.1fs (expecting %@)",
                                          dl.filename,
                                          now.timeIntervalSince(dl.startedAt),
                                          DownloadThroughput.describeBytes(totalBytesExpectedToWrite))
            } else if let lastLog = dl.lastRateLogAt, now.timeIntervalSince(lastLog) >= DownloadThroughput.logInterval {
                let intervalSeconds = now.timeIntervalSince(lastLog)
                let intervalBytes = totalBytesWritten - dl.bytesAtLastRateLog
                let elapsed = now.timeIntervalSince(dl.firstByteAt ?? dl.startedAt)
                let current = DownloadThroughput.megabytesPerSecond(bytes: intervalBytes, seconds: intervalSeconds)
                // Only bytes THIS task moved — a resumed task's head start isn't throughput.
                let average = DownloadThroughput.megabytesPerSecond(bytes: totalBytesWritten - dl.bytesAtFirstByte, seconds: elapsed)
                var line = "\(dl.filename): \(DownloadThroughput.describeBytes(totalBytesWritten))"
                if let expected = DownloadThroughput.percent(written: totalBytesWritten, expected: totalBytesExpectedToWrite) {
                    line += String(format: " / %@ (%.1f%%)", DownloadThroughput.describeBytes(totalBytesExpectedToWrite), expected)
                }
                line += " — \(DownloadThroughput.describeRate(current)) now, \(DownloadThroughput.describeRate(average)) avg"
                if let remaining = DownloadThroughput.secondsRemaining(written: totalBytesWritten, expected: totalBytesExpectedToWrite, elapsed: elapsed) {
                    line += String(format: ", ~%.0fs left", remaining)
                }
                rateMessage = line
                dl.lastRateLogAt = now
                dl.bytesAtLastRateLog = totalBytesWritten
            }

            activeDownloads[partId] = dl
        }
        let concurrent = activeDownloads.count
        downloadQueueLock.unlock()

        if let firstByteMessage = firstByteMessage { AbsLogger.info(message: firstByteMessage) }
        if let rateMessage = rateMessage { AbsLogger.info(message: "\(rateMessage) [\(concurrent) transfers active]") }
        if let aggregateMessage = aggregateMessage { AbsLogger.info(message: aggregateMessage) }

        // This task is moving real data, so the connection recovered. Hand the part a fresh retry
        // budget — otherwise a few unrelated blips spread across a multi-hour download add up and kill
        // it. (Also drops now-stale resume data; a live task regenerates it if cancelled.)
        if becameHealthy { stateStore.clear(forPartId: partId) }

        // Throttle the Realm write + JS bridge notify so we don't churn on every packet
        let isFinalChunk = totalBytesExpectedToWrite > 0 && totalBytesWritten >= totalBytesExpectedToWrite
        guard shouldPersistProgress(partId: partId, isFinalChunk: isFinalChunk) else { return }

        handleDownloadTaskUpdate(downloadTask: downloadTask) { downloadItem, downloadItemPart in
            // Calculate the download percentage
            let percentDownloaded = (Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) * 100

            // Only update the progress if we received accurate progress data
            if percentDownloaded >= 0.0 && percentDownloaded <= 100.0 {
                let realm = try Realm()
                let partId = downloadItemPart.id

                // Get fresh reference to the object in this realm
                guard let liveDownloadItemPart = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId) else {
                    throw LibraryItemDownloadError.downloadItemPartNotFound
                }

                try realm.write {
                    liveDownloadItemPart.bytesDownloaded = Double(totalBytesWritten)
                    liveDownloadItemPart.progress = percentDownloaded
                }
            }
        }
    }
    
    // Called when downloads are complete on the background thread
    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            guard let appDelegate = UIApplication.shared.delegate as? AppDelegate,
                let backgroundCompletionHandler =
                appDelegate.backgroundCompletionHandler else {
                    return
            }
            backgroundCompletionHandler()
        }
    }
    
    private func handleDownloadTaskUpdate(downloadTask: URLSessionTask, progressHandler: DownloadProgressHandler) {
        do {
            guard let downloadItemPartId = downloadTask.taskDescription else { throw LibraryItemDownloadError.noTaskDescription }

            // Find the download item
            let downloadItem = Database.shared.getDownloadItem(downloadItemPartId: downloadItemPartId)
            guard let downloadItem = downloadItem else { throw LibraryItemDownloadError.downloadItemNotFound }

            // Find the download item part
            let part = downloadItem.downloadItemParts.first(where: { $0.id == downloadItemPartId })
            guard let part = part else { throw LibraryItemDownloadError.downloadItemPartNotFound }

            // Call the progress handler and push the (throttled) update to the JS layer
            do {
                try progressHandler(downloadItem, part)
                try? self.notifyListeners("onDownloadItemPartUpdate", data: part.asDictionary())
            } catch {
                AbsLogger.error(message: "Error while processing progress")
                debugPrint(error)
            }
        } catch {
            AbsLogger.error(message: "DownloadItemError")
            debugPrint(error)
        }
    }

    private func shouldPersistProgress(partId: String, isFinalChunk: Bool) -> Bool {
        progressThrottleLock.lock()
        defer { progressThrottleLock.unlock() }
        let now = Date()
        let last = lastPersistAt[partId] ?? .distantPast
        if isFinalChunk || now.timeIntervalSince(last) >= progressPersistInterval {
            lastPersistAt[partId] = now
            return true
        }
        return false
    }


    /// Retry a part, or fail it permanently when it is out of attempts or the cause cannot fix itself.
    ///
    /// Shared by the transport-error path and the HTTP-status path so both get identical treatment:
    /// a URL rebuilt against the current server config, exponential backoff, a persisted attempt count,
    /// and resume data used only when it still points at the same endpoint.
    private func retryOrFailPart(partId: String,
                                 filename: String,
                                 previousURL: URL?,
                                 resumeData: Data?,
                                 recoverable: Bool,
                                 stalled: Bool,
                                 failureDescription: String) {
        let attemptsUsed = stateStore.attempts(forPartId: partId)

        if recoverable && attemptsUsed < DownloadRetryPolicy.maxAttemptsPerPart {
            // Rebuild from the database + current config rather than reusing the URL this task ran with.
            // A task adopted from a previous launch carries whatever URL it was created with, so a
            // corrupt one (see DownloadURLBuilder) would otherwise propagate through every retry
            // forever — repairing the stored record alone was not enough. It also picks up a refreshed
            // access token, which is what makes a 401 retry worth attempting.
            let part = Database.shared.getDownloadItem(downloadItemPartId: partId)?
                .downloadItemParts.first(where: { $0.id == partId })
            let url = part.flatMap { self.downloadURL(for: $0) } ?? previousURL

            // Resume data embeds the request it came from, so it can only be reused if we're still
            // talking to the same endpoint. After a repair it points at the dead URL.
            let endpointUnchanged = DownloadURLBuilder.sameEndpoint(previousURL, url)
            let usableResumeData = endpointUnchanged ? resumeData : nil
            if !endpointUnchanged && resumeData != nil {
                AbsLogger.info(message: "Download endpoint for \(filename) changed to \(DownloadURLBuilder.redacted(url)) — restarting instead of resuming")
            }

            let retryTask: URLSessionDownloadTask? = {
                if let usableResumeData = usableResumeData { return preferredSession.downloadTask(withResumeData: usableResumeData) }
                if let url = url { return preferredSession.downloadTask(with: url) }
                return nil
            }()
            if let retryTask = retryTask, let resolvedURL = url ?? retryTask.originalRequest?.url {
                retryTask.taskDescription = partId
                let attempt = stateStore.recordAttempt(forPartId: partId)
                let delay = DownloadRetryPolicy.backoffDelay(forAttempt: attempt)
                AbsLogger.info(message: "Retrying \(filename) — attempt \(attempt)/\(DownloadRetryPolicy.maxAttemptsPerPart) in \(Int(delay))s (resume=\(usableResumeData != nil), stalled=\(stalled)): \(failureDescription)")
                let pending = PendingDownload(partId: partId, filename: filename, downloadURL: resolvedURL, task: retryTask, usesForegroundSession: isAppInForeground)
                DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + delay) { [weak self] in
                    self?.enqueuePendingDownload(pending)
                    self?.startNextDownloadInQueue()
                }
                return
            }
        }

        // Out of retries or unrecoverable — mark failed and let the item resolve so the UI reverts.
        // Drop the stored state too: the part is terminal, and a re-download should start clean rather
        // than inherit an exhausted budget (which would fail it again instantly).
        AbsLogger.error(message: "Download failed for \(filename) after \(attemptsUsed) retries: \(failureDescription)")
        stateStore.clear(forPartId: partId)
        markPartFailed(partId)
        checkItemCompletion(forPartId: partId)
    }

    // MARK: - Stall watchdog

    private func startStallWatchdogIfNeeded() {
        DispatchQueue.runOnMainQueue {
            guard self.stallWatchdogTimer?.isValid != true else { return }
            self.stallWatchdogTimer = Timer.scheduledTimer(withTimeInterval: self.stallCheckInterval, repeats: true, block: { [weak self] t in
                self?.checkForStalledDownloads(t)
            })
        }
    }

    private func checkForStalledDownloads(_ timer: Timer) {
        let now = Date()
        let previousTick = lastWatchdogTick
        lastWatchdogTick = now

        downloadQueueLock.lock()
        let candidates = activeDownloads.values.map {
            StallCandidate(partId: $0.partId, lastProgressAt: $0.lastProgressAt, graceUntil: $0.graceUntil)
        }
        let decision = StallEvaluator.evaluate(now: now, lastTick: previousTick,
                                               checkInterval: stallCheckInterval,
                                               stallTimeout: stallTimeout,
                                               candidates: candidates)

        var stalled: [ActiveDownload] = []
        switch decision {
        case .rebaseline:
            // The app was suspended, so these timestamps say nothing about the transfers. Restart their
            // clocks (with grace, since callbacks queued during suspension take a moment to drain)
            // instead of cancelling every healthy download the instant the user comes back.
            for (partId, var dl) in activeDownloads {
                dl.lastProgressAt = now
                dl.graceUntil = now.addingTimeInterval(stallGracePeriod)
                activeDownloads[partId] = dl
            }
        case .cancel(let partIds):
            let ids = Set(partIds)
            stalled = activeDownloads.values.filter { ids.contains($0.partId) }
            for dl in stalled { stalledPartIds.insert(dl.partId) }
        }
        let idle = activeDownloads.isEmpty && pendingDownloadTasks.isEmpty
        downloadQueueLock.unlock()

        if case .rebaseline = decision {
            AbsLogger.info(message: "Watchdog resumed after a gap — re-baselining \(candidates.count) download(s) instead of treating them as stalled")
            return
        }

        for dl in stalled {
            AbsLogger.error(message: "Stall detected on \(dl.filename): no data for >\(Int(stallTimeout))s — cancelling to retry")
            // Cancel producing resume data; this fires didCompleteWithError, which retries the part
            dl.task.cancel(byProducingResumeData: { _ in })
        }

        if idle && stalled.isEmpty {
            timer.invalidate()
            if self.stallWatchdogTimer == timer { self.stallWatchdogTimer = nil }
            self.lastWatchdogTick = nil
            AbsLogger.info(message: "No active downloads — stopping stall watchdog")
        }
    }

    // MARK: - Completion / failure

    private func markPartFailed(_ partId: String) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId) else { return }
            try realm.write {
                part.completed = true
                part.failed = true
            }
            try? self.notifyListeners("onDownloadItemPartUpdate", data: part.asDictionary())
        } catch {
            AbsLogger.error(message: "Failed to mark part failed \(partId)")
            debugPrint(error)
        }
    }

    // Event-driven completion (replaces the old 0.2s polling timer): when every part of an item
    // is done (completed or failed), assemble the local item and notify the JS layer once.
    private func checkItemCompletion(forPartId partId: String) {
        // The serial delegate queue can still run successive callbacks on different run-loop-less
        // threads, and a Realm opened on such a thread stays pinned to the snapshot it was first
        // opened at. So a sibling part's just-committed `completed = true` may be invisible here,
        // making isDoneDownloading() return false. Refresh to the latest committed version first —
        // otherwise, now that the old 0.2s polling loop is gone, this one-shot check never re-runs
        // and the item wedges forever at "Download complete. Processing...".
        _ = try? Realm().refresh()

        guard let item = Database.shared.getDownloadItem(downloadItemPartId: partId) else { return }
        guard item.isDoneDownloading() else { return }

        // Each part's completion calls this concurrently, so more than one can pass the check above.
        // Claim the item atomically so it finalizes exactly once (no duplicate local items / events).
        guard let itemId = item.id else { return }
        downloadQueueLock.lock()
        let alreadyFinalized = finalizedItemIds.contains(itemId)
        if !alreadyFinalized { finalizedItemIds.insert(itemId) }
        downloadQueueLock.unlock()
        guard !alreadyFinalized else { return }

        for p in item.downloadItemParts { stateStore.clear(forPartId: p.id) }

        // Freeze so the item can be handed to the (main-queue) finalizer safely across threads.
        let frozen = item.freeze()
        handleDownloadTaskCompleteFromDownloadItem(frozen)

        if let live = Database.shared.getDownloadItem(downloadItemId: item.id!) {
            try? live.delete()
        }

        // Item is finalized and deleted; drop its claim so the set doesn't grow for the process
        // lifetime. Safe because the delegate queue is serial (no concurrent re-claim), and a later
        // duplicate call would early-return on the now-missing item anyway.
        downloadQueueLock.lock()
        finalizedItemIds.remove(itemId)
        downloadQueueLock.unlock()
    }
    
    private func handleDownloadTaskCompleteFromDownloadItem(_ downloadItem: DownloadItem) {
        AbsLogger.info(message: "Finalizing completed download \(downloadItem.itemTitle ?? downloadItem.id ?? "?")")
        var statusNotification = [String: Any]()
        statusNotification["libraryItemId"] = downloadItem.id

        guard downloadItem.didDownloadSuccessfully() else {
            // A part failed permanently — still notify so the UI reverts instead of hanging.
            self.notifyListeners("onItemDownloadComplete", data: statusNotification)
            return
        }

        // The files are already fully on disk, so finalize the local item IMMEDIATELY from the
        // metadata captured in the DownloadItem and notify JS right away. Completion must NEVER
        // depend on a network round-trip: previously the event was only sent from inside a
        // getLibraryItemWithProgress callback, so if that call hung or failed (server offline,
        // HTTP 429, or an expired token) the item wedged forever at "Download complete. Processing...".
        // The download-time snapshot is current (the user just tapped download); the app's normal
        // sync refreshes server-side progress afterward.
        DispatchQueue.main.async {
            self.finalizeDownloadedItem(downloadItem.asLibraryItem(), downloadItem: downloadItem, statusNotification: statusNotification)
        }
    }

    // Assemble the local library item from the finished parts and notify the JS layer exactly once.
    // Called with either the freshly-fetched server LibraryItem or a fallback rebuilt from the
    // DownloadItem, so completion is guaranteed regardless of server reachability.
    private func finalizeDownloadedItem(_ libraryItem: LibraryItem, downloadItem: DownloadItem, statusNotification: [String: Any]) {
        var statusNotification = statusNotification
        let localDirectory = libraryItem.id
        var coverFile: String?

        let files = downloadItem.downloadItemParts.enumerated().compactMap { _, part -> LocalFile? in
            var mimeType = part.mimeType()
            if part.filename == "cover.jpg" {
                coverFile = part.destinationUri
                mimeType = "image/jpg"
            }
            return LocalFile(libraryItem.id, part.filename!, mimeType!, part.destinationUri!, fileSize: Int(part.destinationURL!.fileSize))
        }
        var localLibraryItem = Database.shared.getLocalLibraryItem(byServerLibraryItemId: libraryItem.id)
        if (localLibraryItem != nil && localLibraryItem!.isPodcast) {
            try? Realm().write {
                try? localLibraryItem?.addFiles(files, item: libraryItem)
            }
        } else {
            localLibraryItem = LocalLibraryItem(libraryItem, localUrl: localDirectory, server: Store.serverConfig!, files: files, coverPath: coverFile)
            try? Database.shared.saveLocalLibraryItem(localLibraryItem: localLibraryItem!)
        }

        statusNotification["localLibraryItem"] = try? localLibraryItem.asDictionary()

        if let progress = libraryItem.userMediaProgress {
            let episode = downloadItem.media?.episodes.first(where: { $0.id == downloadItem.episodeId })
            let localMediaProgress = LocalMediaProgress(localLibraryItem: localLibraryItem!, episode: episode, progress: progress)
            try? localMediaProgress.save()
            statusNotification["localMediaProgress"] = try? localMediaProgress.asDictionary()
        }

        self.notifyListeners("onItemDownloadComplete", data: statusNotification)
    }
    
    
    // MARK: - Capacitor functions
    
    @objc func downloadLibraryItem(_ call: CAPPluginCall) {
        let libraryItemId = call.getString("libraryItemId")
        var episodeId = call.getString("episodeId")
        if ( episodeId == "null" ) { episodeId = nil }
        
        AbsLogger.info(message: "Download library item \(libraryItemId ?? "N/A") / episode \(episodeId ?? "N/A")")
        guard let libraryItemId = libraryItemId else { return call.resolve(["error": "libraryItemId not specified"]) }
        
        ApiClient.getLibraryItemWithProgress(libraryItemId: libraryItemId, episodeId: episodeId) { [weak self] libraryItem in
            if let libraryItem = libraryItem {
                AbsLogger.info(message: "Got library item from server \(libraryItem.id)")
                do {
                    if let episodeId = episodeId {
                        // Download a podcast episode
                        guard libraryItem.mediaType == "podcast" else { throw LibraryItemDownloadError.libraryItemNotPodcast }
                        let episode = libraryItem.media?.episodes.enumerated().first(where: { $1.id == episodeId })?.element
                        guard let episode = episode else { throw LibraryItemDownloadError.podcastEpisodeNotFound }
                        try self?.startLibraryItemDownload(libraryItem, episode: episode)
                    } else {
                        // Download a book
                        try self?.startLibraryItemDownload(libraryItem)
                    }
                    call.resolve()
                } catch {
                    debugPrint(error)
                    call.resolve(["error": "Failed to download"])
                }
            } else {
                call.resolve(["error": "Server request failed"])
            }
        }
    }
    
    private func startLibraryItemDownload(_ item: LibraryItem) throws {
        try startLibraryItemDownload(item, episode: nil)
    }
    
    private func startLibraryItemDownload(_ item: LibraryItem, episode: PodcastEpisode?) throws {
        let tracks = List<AudioTrack>()
        var episodeId: String?
        
        // Handle the different media type downloads
        switch item.mediaType {
        case "book":
            guard item.media?.tracks.count ?? 0 > 0 || item.media?.ebookFile != nil else { throw LibraryItemDownloadError.noTracks }
            item.media?.tracks.forEach { t in tracks.append(AudioTrack.detachCopy(of: t)!) }
        case "podcast":
            guard let episode = episode else { throw LibraryItemDownloadError.podcastEpisodeNotFound }
            guard let podcastTrack = episode.audioTrack else { throw LibraryItemDownloadError.noTracks }
            episodeId = episode.id
            tracks.append(AudioTrack.detachCopy(of: podcastTrack)!)
        default:
            throw LibraryItemDownloadError.unknownMediaType
        }
        
        // Don't start a second copy of a download that is already running. DownloadItem ids and part ids
        // are both deterministic, so a repeat request builds tasks for the very same parts — which then
        // ran concurrently against the same destination files, overwriting each other's queue entries
        // and leaving orphaned tasks running untracked.
        // Tapping download means "download this book", so a leftover record must never block it. Earlier
        // this returned early when a record existed, which left a wedged download permanently
        // un-restartable — and it reported the wrong title, because a cross-linked row from the
        // duplicate-overwrite bug can describe a different book entirely.
        //
        // Replacing it is also what makes the part ids safe to reuse: they're derived from the
        // destination path, so a fresh queue would otherwise collide with the old record's rows.
        let expectedItemId = episodeId.map { "\(item.id)-\($0)" } ?? item.id
        if let existing = Database.shared.getDownloadItem(downloadItemId: expectedItemId) {
            AbsLogger.info(message: "Replacing existing download record for \(existing.itemTitle ?? expectedItemId)")
            let staleParts = Array(existing.downloadItemParts.map { $0.id })
            cancelInFlightDownloads(partIds: staleParts)
            for partId in staleParts { stateStore.clear(forPartId: partId) }
            try? existing.delete()
        }

        // Queue up everything for downloading
        let downloadItem = DownloadItem(libraryItem: item, episodeId: episodeId, server: Store.serverConfig!)
        var tasks = [DownloadItemPartTask]()
        for (i, track) in tracks.enumerated() {
            let task = try startLibraryItemTrackDownload(downloadItemId: downloadItem.id!, item: item, position: i, track: track, episode: episode)
            downloadItem.downloadItemParts.append(task.part)
            tasks.append(task)
        }
        
        if (item.media?.ebookFile != nil) {
            let task = try startLibraryItemEbookDownload(downloadItemId: downloadItem.id!, item: item, ebookFile: item.media!.ebookFile!)
            downloadItem.downloadItemParts.append(task.part)
            tasks.append(task)
        }
        
        // Also download the cover
        if item.media?.coverPath != nil && !(item.media?.coverPath!.isEmpty ?? true) {
            if let task = try? startLibraryItemCoverDownload(downloadItemId: downloadItem.id!, item: item) {
                downloadItem.downloadItemParts.append(task.part)
                tasks.append(task)
            }
        }
        
        // Notify client of download item
        try? self.notifyListeners("onDownloadItem", data: downloadItem.asDictionary())
        
        // Persist in the database before status start coming in
        try Database.shared.saveDownloadItem(downloadItem)

        // Add all tasks to the download queue
        downloadQueueLock.lock()
        // Clear any stale "already finalized" marker so a re-download of the same item finalizes again.
        if let itemId = downloadItem.id { finalizedItemIds.remove(itemId) }
        for t in tasks {
            let url = t.task.originalRequest?.url ?? t.part.downloadURL!
            pendingDownloadTasks.append(PendingDownload(partId: t.partId, filename: t.filename, downloadURL: url, task: t.task, usesForegroundSession: isAppInForeground))
        }
        downloadQueueLock.unlock()

        AbsLogger.info(message: "Added \(tasks.count) tasks to download queue. Starting downloads...")

        // Start downloading (up to maxConcurrentDownloads at a time)
        startNextDownloadInQueue()
    }
    
    private func startLibraryItemTrackDownload(downloadItemId: String, item: LibraryItem, position: Int, track: AudioTrack, episode: PodcastEpisode?) throws -> DownloadItemPartTask {
        AbsLogger.info(message: "TRACK \(track.contentUrl!)")

        // If we don't name metadata, then we can't proceed
        guard let filename = track.metadata?.filename else {
            throw LibraryItemDownloadError.noMetadata
        }

        // Persist the API PATH, not the server address. Storing the address here is what produced the
        // "address + address" URI — a nonexistent host — for every track part, so any download restarted
        // from the database could never transfer a byte. See DownloadURLBuilder.
        let serverPath = serverPathForTrack(item: item, track: track)
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = "\(itemDirectory)/\(filename)"

        let part = DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: localUrl, itemTitle: track.title ?? "Unknown", serverPath: serverPath, audioTrack: track, episode: episode, ebookFile: nil, size: track.metadata?.size ?? 0)
        guard let serverUrl = downloadURL(for: part) else { throw LibraryItemDownloadError.noMetadata }
        let task = preferredSession.downloadTask(with: serverUrl)

        // Store the id on the task so the download item can be pulled from the database later
        task.taskDescription = part.id

        return DownloadItemPartTask(part: part, task: task, partId: part.id, filename: filename)
    }
    
    private func startLibraryItemEbookDownload(downloadItemId: String, item: LibraryItem, ebookFile: EBookFile) throws -> DownloadItemPartTask {
        let filename = ebookFile.metadata?.filename ?? "ebook.\(ebookFile.ebookFormat)"
        let serverPath = "/api/items/\(item.id)/file/\(ebookFile.ino)/download"
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = "\(itemDirectory)/\(filename)"

        let part = DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: localUrl, itemTitle: filename, serverPath: serverPath, audioTrack: nil, episode: nil, ebookFile: ebookFile, size: ebookFile.metadata?.size ?? 0)
        let task = preferredSession.downloadTask(with: part.downloadURL!)

        // Store the id on the task so the download item can be pulled from the database later
        task.taskDescription = part.id

        return DownloadItemPartTask(part: part, task: task, partId: part.id, filename: filename)
    }
    
    private func startLibraryItemCoverDownload(downloadItemId: String, item: LibraryItem) throws -> DownloadItemPartTask {
        let filename = "cover.jpg"
        let serverPath = "/api/items/\(item.id)/cover"
        let itemDirectory = try createLibraryItemFileDirectory(item: item)
        let localUrl = "\(itemDirectory)/\(filename)"

        // Find library file to get cover size
        let coverLibraryFile = item.libraryFiles.first(where: {
            $0.metadata?.path == item.media?.coverPath
        })

        let part = DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: localUrl, itemTitle: "cover", serverPath: serverPath, audioTrack: nil, episode: nil, ebookFile: nil, size: coverLibraryFile?.metadata?.size ?? 0)
        let task = preferredSession.downloadTask(with: part.downloadURL!)

        // Store the id on the task so the download item can be pulled from the database later
        task.taskDescription = part.id

        return DownloadItemPartTask(part: part, task: task, partId: part.id, filename: filename)
    }
    
    private func serverPathForTrack(item: LibraryItem, track: AudioTrack) -> String {
        // TODO: Future server release should include ino with AudioFile or FileMetadata
        let trackPath = track.metadata?.path ?? ""

        var audioFileIno = ""
        if (item.mediaType == "podcast") {
            let podcastEpisodes = item.media?.episodes ?? List<PodcastEpisode>()
            let matchingEpisode = podcastEpisodes.first(where: { $0.audioFile?.metadata?.path == trackPath })
            audioFileIno = matchingEpisode?.audioFile?.ino ?? ""
        } else {
            let audioFiles = item.media?.audioFiles ?? List<AudioFile>()
            let matchingAudioFile = audioFiles.first(where: { $0.metadata?.path == trackPath })
            audioFileIno = matchingAudioFile?.ino ?? ""
        }

        return DownloadURLBuilder.trackPath(itemId: item.id, ino: audioFileIno)
    }

    /// Build a part's download URL against the CURRENT server config, rather than trusting the URI that
    /// was persisted when the download was first queued. That stored URI embeds an access token (now a
    /// short-lived JWT, so it expires) and, for track parts created before the serverPath fix, points at
    /// a malformed host. Both only ever bit downloads that had to be restarted from the database.
    private func downloadURL(for part: DownloadItemPart) -> URL? {
        guard let config = Store.serverConfig else { return part.downloadURL }
        return DownloadURLBuilder.url(address: config.address,
                                      token: config.token,
                                      serverPath: part.serverPath,
                                      contentUrlFallback: part.audioTrack?.contentUrl)
            ?? part.downloadURL
    }
    
    private func createLibraryItemFileDirectory(item: LibraryItem) throws -> String {
        let itemDirectory = item.id
        AbsLogger.info(message: "ITEM DIR \(itemDirectory)")
        
        guard AbsDownloader.itemDownloadFolder(path: itemDirectory) != nil else {
            AbsLogger.error(message: "Failed to CREATE LI DIRECTORY \(itemDirectory)")
            throw LibraryItemDownloadError.failedDirectory
        }
        
        return itemDirectory
    }
    
    static func itemDownloadFolder(path: String) -> URL? {
        do {
            var itemFolder = AbsDownloader.downloadsDirectory.appendingPathComponent(path)
            
            if !FileManager.default.fileExists(atPath: itemFolder.path) {
                try FileManager.default.createDirectory(at: itemFolder, withIntermediateDirectories: true)
            }
            
            // Make sure we don't backup download files to iCloud
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try itemFolder.setResourceValues(resourceValues)
            
            return itemFolder
        } catch {
            AbsLogger.error(message: "Failed to CREATE LI DIRECTORY \(error)", error: error)
            return nil
        }
    }
    
}


// MARK: - Class structs

typealias DownloadProgressHandler = (_ downloadItem: DownloadItem, _ downloadItemPart: DownloadItemPart) throws -> Void

struct DownloadItemPartTask {
    let part: DownloadItemPart
    let task: URLSessionDownloadTask
    let partId: String // Cache the ID to avoid cross-thread Realm access
    let filename: String // Cache the filename to avoid cross-thread Realm access
}

// Queued (not yet resumed) download. Carries only value-type/thread-safe fields — no Realm object.
struct PendingDownload {
    let partId: String
    let filename: String
    let downloadURL: URL
    let task: URLSessionDownloadTask
    /// Which session this task was created against, so a migration knows what needs moving.
    let usesForegroundSession: Bool
}

// In-flight download plus the bookkeeping the stall watchdog needs.
struct ActiveDownload {
    let partId: String
    let filename: String
    let downloadURL: URL
    let task: URLSessionDownloadTask
    let usesForegroundSession: Bool
    var lastBytesWritten: Int64
    var lastProgressAt: Date
    /// Exempt from stall detection until this time — set for tasks adopted from a previous launch and
    /// for downloads re-baselined after the app was suspended, neither of which has reported yet.
    var graceUntil: Date?
    /// Whether this task has transferred enough to have earned the part a fresh retry budget.
    var clearedRetryState: Bool
    // Throughput instrumentation
    let startedAt: Date
    var firstByteAt: Date?
    var lastRateLogAt: Date?
    var bytesAtLastRateLog: Int64 = 0
    /// totalBytesWritten when this task first reported. A resumed task starts with the bytes an earlier
    /// attempt already transferred, and counting those as though this task moved them inflates both the
    /// average and the aggregate (a relaunch showed "12.13 MB/s" that never happened).
    var bytesAtFirstByte: Int64 = 0
    var hasReportedBytes = false
}

enum LibraryItemDownloadError: String, Error {
    case noTracks = "No tracks on library item"
    case noMetadata = "No metadata for track, unable to download"
    case libraryItemNotPodcast = "Library item is not a podcast but episode was requested"
    case podcastEpisodeNotFound = "Invalid podcast episode not found"
    case podcastOnlySupported = "Only podcasts are supported for this function"
    case unknownMediaType = "Unknown media type"
    case failedDirectory = "Failed to create directory"
    case failedDownload = "Failed to download item"
    case noTaskDescription = "No task description"
    case downloadItemNotFound = "DownloadItem not found"
    case downloadItemPartNotFound = "DownloadItemPart not found"
    case downloadItemPartDestinationUrlNotDefined = "DownloadItemPart destination URL not defined"
    case libraryItemNotFound = "LibraryItem not found for id"
}
