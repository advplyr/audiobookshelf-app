package com.audiobookshelf.app.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.server.RequestCancellation
import com.audiobookshelf.app.server.SyncOutcome
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.schedule
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class MediaProgressSyncData(
        var timeListened: Long, // seconds
        var duration: Double, // seconds
        var currentTime: Double // seconds
)

data class SyncResult(
        var serverSyncAttempted: Boolean,
        var serverSyncSuccess: Boolean?,
        var serverSyncMessage: String?
)

sealed class SyncRequest {
  abstract val sessionId: String
  abstract val currentTime: Double
  abstract val displayTitle: String

  data class Server(
          override val sessionId: String,
          val syncData: MediaProgressSyncData,
          override val displayTitle: String,
          val seq: Long = 0 // Order of queueing, the newer seq is kept when merging
  ) : SyncRequest() {
    override val currentTime
      get() = syncData.currentTime
  }

  data class Local(val session: PlaybackSession, override val displayTitle: String) :
          SyncRequest() {
    override val sessionId
      get() = session.id
    override val currentTime
      get() = session.currentTime
  }

  companion object {
    /** Merges a pending request with a newer one for the same session, or null if they can't merge. */
    fun merge(older: SyncRequest, newer: SyncRequest): SyncRequest? {
      if (older.sessionId != newer.sessionId) return null
      return when {
        older is Server && newer is Server ->
                newer.copy(
                        syncData =
                                newer.syncData.copy(
                                        timeListened =
                                                older.syncData.timeListened +
                                                        newer.syncData.timeListened
                                )
                )
        // Local syncs send the whole session, which already includes the older listening time
        older is Local && newer is Local -> newer
        else -> null
      }
    }
  }
}

class MediaProgressSyncer(
        val playerNotificationService: PlayerNotificationService,
        private val apiHandler: ApiHandler
) {
  private val tag = "MediaProgressSync"
  // OkHttp's default connect/read/write timeouts are 10s each, so this only ends a stuck request
  private val SYNC_REQUEST_TIMEOUT_MS = 30000L
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false

  private var lastSyncTime: Long = 0
  @Volatile private var failedSyncs: Int = 0
  private val unsyncedListeningTime = AtomicLong(0) // seconds from syncs the server did not apply

  private val latestServerSyncSeq = AtomicLong(0)

  /**
   * Current time the server confirmed for the current session, or null while a sync is pending or
   * after one failed. If the server progress later differs from it, another device moved it.
   */
  @Volatile
  var confirmedServerTime: Double? = null
    private set

  /** When the syncer was last paused (ms), 0 while playing */
  var pausedAt: Long = 0L
    private set

  private val syncQueue =
          ProgressSyncQueue<SyncRequest, SyncResult>(
                  CoroutineScope(SupervisorJob() + Dispatchers.IO),
                  SyncRequest::merge,
                  ::sendSyncRequest
          ) { e -> Log.e(tag, "Progress sync callback failed", e) }

  /**
   * Runs [action] after all queued syncs completed, right away if none are queued. Used to close
   * the session on the server only after the final position has been sent.
   */
  fun afterPendingSyncs(action: () -> Unit) = syncQueue.whenIdle(action)

  @Volatile var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  var currentLocalMediaProgress: LocalMediaProgress? = null

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration
    get() = currentPlaybackSession?.duration ?: 0.0

  fun start(playbackSession: PlaybackSession) {
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (playbackSession.id != currentSessionId) {
        Log.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        lastSyncTime = 0L
        Log.d(tag, "start: Set last sync time 0 $lastSyncTime")
        resetSessionSyncState()
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
      resetSessionSyncState()
    }

    listeningTimerRunning = true
    pausedAt = 0L
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()
    Log.d(
            tag,
            "start: init last sync time $lastSyncTime with playback session id=${currentPlaybackSession?.id}"
    )

    listeningTimerTask =
            Timer("ListeningTimer", false).schedule(15000L, 15000L) {
              Handler(Looper.getMainLooper()).post() {
                if (playerNotificationService.currentPlayer.isPlaying) {
                  // Set auto sleep timer if enabled and within start/end time
                  playerNotificationService.sleepTimerManager.checkAutoSleepTimer()

                  // Only sync with server on unmetered connection every 15s OR sync with server if
                  // last sync time is >= 60s
                  val shouldSyncServer =
                          PlayerNotificationService.isUnmeteredNetwork ||
                                  System.currentTimeMillis() - lastSyncTime >=
                                          METERED_CONNECTION_SYNC_INTERVAL

                  val currentTime = playerNotificationService.getCurrentTimeSeconds()
                  if (currentTime > 0) {
                    sync(shouldSyncServer, currentTime) { syncResult ->
                      Log.d(tag, "Sync complete")

                      currentPlaybackSession?.let { playbackSession ->
                        MediaEventManager.saveEvent(playbackSession, syncResult)
                      }
                    }
                  }
                }
              }
            }
  }

  fun play(playbackSession: PlaybackSession) {
    Log.d(tag, "play ${playbackSession.displayTitle}")
    MediaEventManager.playEvent(playbackSession)

    start(playbackSession)
  }

  fun stop(shouldSync: Boolean? = true, cb: () -> Unit) {
    if (!listeningTimerRunning) {
      reset()
      // The pause sync may still be queued; callers close or replace the session in cb
      return afterPendingSyncs(cb)
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "stop: Stopping listening for $currentDisplayTitle")

    val currentTime =
            if (shouldSync == true) playerNotificationService.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      // The server sync completes asynchronously, so reset right after it is queued and let the
      // callback use the captured session. Resetting in a late callback could wipe a session
      // started in the meantime. When sync calls back synchronously, reset before cb as before.
      val playbackSession = currentPlaybackSession
      val resetOnce = resetOnce()
      sync(true, currentTime, force = true) { syncResult ->
        resetOnce()
        playbackSession?.let { MediaEventManager.stopEvent(it, syncResult) }
        cb()
      }
      resetOnce()
    } else {
      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.stopEvent(playbackSession, null)
      }

      reset()
      cb()
    }
  }

  fun pause(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    pausedAt = System.currentTimeMillis()
    Log.d(tag, "pause: Pausing progress syncer for $currentDisplayTitle")
    Log.d(tag, "pause: Last sync time $lastSyncTime")

    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      // Reset the sync time now rather than in the async callback, which could otherwise run
      // after playback resumed and stop the periodic sync from sending anything.
      val playbackSession = currentPlaybackSession
      sync(true, currentTime, force = true) { syncResult ->
        playbackSession?.let { MediaEventManager.pauseEvent(it, syncResult) }
        cb()
      }
      lastSyncTime = 0L
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime")
      failedSyncs = 0
    } else {
      lastSyncTime = 0L
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime (current time < 0)")
      failedSyncs = 0

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.pauseEvent(playbackSession, null)
      }

      cb()
    }
  }

  fun finished(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "finished: Stopping listening for $currentDisplayTitle")

    // See stop() for why reset is not left to the async callback
    val playbackSession = currentPlaybackSession
    val resetOnce = resetOnce()
    sync(true, playbackSession?.duration ?: 0.0, force = true) { syncResult ->
      resetOnce()
      playbackSession?.let { MediaEventManager.finishedEvent(it, syncResult) }
      cb()
    }
    resetOnce()
  }

  fun seek() {
    // A seek while paused picks the position to resume from (the user, or the webview applying
    // server progress), so the progress check on resume must not override it
    if (!playerNotificationService.currentPlayer.isPlaying) pausedAt = 0L
    currentPlaybackSession?.currentTime = playerNotificationService.getCurrentTimeSeconds()
    Log.d(tag, "seek: $currentDisplayTitle, currentTime=${currentPlaybackSession?.currentTime}")

    if (currentPlaybackSession == null) {
      Log.e(tag, "seek: Playback session not set")
      return
    }

    MediaEventManager.seekEvent(currentPlaybackSession!!, null)
  }

  // Currently unused
  fun syncFromServerProgress(mediaProgress: MediaProgress) {
    currentPlaybackSession?.let {
      it.updatedAt = mediaProgress.lastUpdate
      it.currentTime = mediaProgress.currentTime

      MediaEventManager.syncEvent(
              mediaProgress,
              "Received from server get media progress request while playback session open"
      )
      saveLocalProgress(it)
    }
  }

  /** @param force sync even if less than a second passed since the last sync (pause/stop) */
  fun sync(shouldSyncServer: Boolean, currentTime: Double, force: Boolean = false, cb: (SyncResult?) -> Unit) {
    if (lastSyncTime <= 0) {
      Log.e(tag, "Last sync time is not set $lastSyncTime")
      return cb(null)
    }

    val diffSinceLastSync = System.currentTimeMillis() - lastSyncTime
    if (diffSinceLastSync < 1000L && !force) {
      return cb(null)
    }
    val listeningTimeToAdd = diffSinceLastSync / 1000L

    val syncData = MediaProgressSyncData(listeningTimeToAdd, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(syncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(
              tag,
              "Current Playback Session invalid progress ${currentPlaybackSession?.progress} | Current Time: ${currentPlaybackSession?.currentTime} | Duration: ${currentPlaybackSession?.getTotalDuration()}"
      )
      return cb(null)
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(playerNotificationService)

    // Save playback session to db (server linked sessions only)
    //   Sessions are removed once successfully synced with the server
    currentPlaybackSession?.let { DeviceManager.dbManager.savePlaybackSession(it) }

    if (currentIsLocal) {
      // Save local progress sync
      currentPlaybackSession?.let {
        saveLocalProgress(it)
        lastSyncTime = System.currentTimeMillis()

        Log.d(
                tag,
                "Sync local device current serverConnectionConfigId=${DeviceManager.serverConnectionConfig?.id}"
        )
        AbsLogger.info("MediaProgressSyncer", "sync: Saved local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")

        // Local library item is linked to a server library item
        // Send sync to server also if connected to this server and local item belongs to this
        // server
        val isConnectedToSameServer = it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId
        if (hasNetworkConnection &&
                        shouldSyncServer &&
                        !it.libraryItemId.isNullOrEmpty() &&
                        isConnectedToSameServer
        ) {
          // Snapshot the session so the request sends the state at the time of this sync
          val request = SyncRequest.Local(it.clone(), currentDisplayTitle)
          syncQueue.enqueue(request, cb)
        } else {
          AbsLogger.info("MediaProgressSyncer", "sync: Not sending local progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (hasNetworkConnection: $hasNetworkConnection) (isConnectedToSameServer: $isConnectedToSameServer)")
          cb(SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      // Advance the sync time now instead of on success so overlapping syncs don't count the same
      // listening time twice. Time from syncs the server did not apply is carried over in
      // unsyncedListeningTime.
      lastSyncTime += listeningTimeToAdd * 1000L
      val requestSyncData =
              syncData.copy(timeListened = listeningTimeToAdd + unsyncedListeningTime.getAndSet(0))
      val request =
              SyncRequest.Server(
                      currentSessionId,
                      requestSyncData,
                      currentDisplayTitle,
                      latestServerSyncSeq.incrementAndGet()
              )
      confirmedServerTime = null
      AbsLogger.info("MediaProgressSyncer", "sync: Queue progress sync to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${request.sessionId}) (${DeviceManager.serverConnectionConfigName})")

      syncQueue.enqueue(request, cb)
    } else {
      AbsLogger.info("MediaProgressSyncer", "sync: Not sending progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName}) (hasNetworkConnection: $hasNetworkConnection)")
      cb(SyncResult(false, null, null))
    }
  }

  /** Runs on the sync queue, one request at a time. Never throws, so callbacks always run. */
  private suspend fun sendSyncRequest(request: SyncRequest): SyncResult {
    return try {
      sendSyncRequestOrThrow(request)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      AbsLogger.error("MediaProgressSyncer", "sync: Error sending progress sync (session id: ${request.sessionId}) (${e.message})")
      SyncResult(true, false, e.message)
    }
  }

  private suspend fun sendSyncRequestOrThrow(request: SyncRequest): SyncResult {
    // The timeout guards the queue: if a callback is ever lost, later syncs must not wait forever
    val (outcome, errorMsg) =
            withTimeoutOrNull(SYNC_REQUEST_TIMEOUT_MS) {
              suspendCancellableCoroutine<Pair<SyncOutcome, String?>> { cont ->
                // Cancel the HTTP call on timeout. Otherwise a stalled request could still reach the
                // server after the next queued sync and overwrite it with an older position.
                val cancellation = RequestCancellation()
                cont.invokeOnCancellation { cancellation.cancel() }
                when (request) {
                  is SyncRequest.Server ->
                          apiHandler.sendProgressSync(request.sessionId, request.syncData, cancellation) { outcome, error ->
                            cont.resume(outcome to error)
                          }
                  is SyncRequest.Local ->
                          apiHandler.sendLocalProgressSync(request.session, cancellation) { outcome, error ->
                            cont.resume(outcome to error)
                          }
                }
              }
            } ?: (SyncOutcome.UNKNOWN to "Sync request timed out")
    val syncSuccess = outcome == SyncOutcome.APPLIED

    val sessionId = request.sessionId
    if (request is SyncRequest.Server &&
                    sessionId == currentSessionId &&
                    request.seq == latestServerSyncSeq.get()
    ) {
      confirmedServerTime = if (syncSuccess) request.currentTime else null
    }
    if (syncSuccess) {
      failedSyncs = 0
      playerNotificationService.alertSyncSuccess()
      DeviceManager.dbManager.removePlaybackSession(sessionId) // Remove session from db
      AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress (title: \"${request.displayTitle}\") (currentTime: ${request.currentTime}) (session id: $sessionId) (${DeviceManager.serverConnectionConfigName})")
    } else {
      // The server adds the listening time of every sync it receives and cannot deduplicate, so
      // only resend it when the request certainly was not applied. After a timeout or dropped
      // connection the server has usually applied it; the position is resent by the next sync anyway.
      if (request is SyncRequest.Server && sessionId == currentSessionId && outcome == SyncOutcome.NOT_APPLIED) {
        unsyncedListeningTime.addAndGet(request.syncData.timeListened)
      }
      failedSyncs++
      if (failedSyncs == 2) {
        playerNotificationService.alertSyncFailing() // Show alert in client
        failedSyncs = 0
      }
      AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed (count: $failedSyncs) (title: \"${request.displayTitle}\") (currentTime: ${request.currentTime}) (session id: $sessionId) (${DeviceManager.serverConnectionConfigName}) (outcome: $outcome) (error: $errorMsg)")
    }
    return SyncResult(true, syncSuccess, errorMsg)
  }

  private fun saveLocalProgress(playbackSession: PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      val mediaProgress =
              DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      if (mediaProgress == null) {
        currentLocalMediaProgress = playbackSession.getNewLocalMediaProgress()
      } else {
        currentLocalMediaProgress = mediaProgress
        currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    currentLocalMediaProgress?.let {
      if (it.progress.isNaN()) {
        Log.e(tag, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
        Log.d(
                tag,
                "Saved Local Progress Current Time: ID ${it.id} | ${it.currentTime} | Duration ${it.duration} | Progress ${it.progressPercent}%"
        )
      }
    }
  }

  /** Sync state that belongs to one playback session and must not carry over to the next */
  private fun resetSessionSyncState() {
    failedSyncs = 0
    unsyncedListeningTime.set(0)
    confirmedServerTime = null
    pausedAt = 0L
  }

  private fun resetOnce(): () -> Unit {
    val done = AtomicBoolean(false)
    return { if (done.compareAndSet(false, true)) reset() }
  }

  fun reset() {
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    Log.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
    unsyncedListeningTime.set(0)
    confirmedServerTime = null
    pausedAt = 0L
  }
}
