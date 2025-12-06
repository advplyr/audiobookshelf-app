package com.audiobookshelf.app.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.core.PlaybackTelemetryHost
import com.audiobookshelf.app.server.ApiHandler
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

/**
 * UnifiedMediaProgressSyncer: Handles both ExoPlayer and Media3 with IDENTICAL behavior.
 *
 * Design Principles:
 * - Single implementation for both players (no flags, no branching logic)
 * - 15-second periodic sync loop for ALL playback
 * - Local progress saved to DB on every sync
 * - Server sync attempts on unmetered network or every 60s
 * - Event emission (Save/Pause/Stop) happens AFTER sync completes with result
 * - No dependency on player type
 *
 * Event Ownership:
 * - Play event: Emitted by listener/pipeline when playWhenReady becomes true
 * - Pause event: Emitted by pipeline when pause() method called (after sync)
 * - Stop event: Emitted when stop() method called (after sync)
 * - Save event: Emitted on periodic syncs (every 15s while playing)
 * - Seek event: Emitted by listener/pipeline on user seek
 * - Finished event: Emitted when playback completes
 *
 * The syncer's only job is:
 * 1. Run 15s periodic sync loop while playing
 * 2. Sync to DB + optionally to server based on network
 * 3. Emit events AFTER sync completes with result attached
 * 4. Stop loop and final sync on pause/stop
 */
class UnifiedMediaProgressSyncer(
  private val telemetryHost: PlaybackTelemetryHost,
  private val apiHandler: ApiHandler,
  private val eventEmitter: (event: String, session: PlaybackSession, syncResult: SyncResult?) -> Unit
) {
  private val tag = "UnifiedMediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000L // 60 seconds
  private val PERIODIC_SYNC_INTERVAL = 15000L // 15 seconds

  private val mainHandler = Handler(Looper.getMainLooper())

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false

  var currentPlaybackSession: PlaybackSession? = null
  var currentLocalMediaProgress: LocalMediaProgress? = null

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration
    get() = currentPlaybackSession?.duration ?: 0.0

  private var lastSyncTime: Long = 0
  private var failedSyncs: Int = 0
  private var serverSessionClosed: Boolean = false
  private var nextPlaybackEventSource = PlaybackEventSource.SYSTEM
  private var pendingManualPlaybackTime: Double? = null
  private var pendingManualPlaybackTimeExpiresAt: Long = 0

  fun markNextPlaybackEventSource(source: PlaybackEventSource) {
    nextPlaybackEventSource = source
  }

  /**
   * Start the 15-second periodic sync loop.
   */
  fun start(playbackSession: PlaybackSession) {
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (playbackSession.id != currentSessionId) {
        Log.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        lastSyncTime = 0L
        failedSyncs = 0
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
    }

    listeningTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()
    serverSessionClosed = false
    Log.d(tag, "start: Started 15s periodic sync loop for ${playbackSession.displayTitle}")

    // Schedule 15s periodic sync loop
    listeningTimerTask = Timer("UnifiedListeningTimer", false).schedule(
      PERIODIC_SYNC_INTERVAL,
      PERIODIC_SYNC_INTERVAL
    ) {
      mainHandler.post {
        if (telemetryHost.isPlayerActive()) {
          telemetryHost.checkAutoSleepTimer()

          // Determine if we should attempt server sync
          val shouldSyncServer =
            telemetryHost.isUnmeteredNetwork ||
              System.currentTimeMillis() - lastSyncTime >= METERED_CONNECTION_SYNC_INTERVAL

          val currentTime = telemetryHost.getCurrentTimeSeconds()
          if (currentTime > 0) {
            sync(shouldSyncServer, currentTime) { syncResult ->
              Log.d(tag, "Periodic sync complete for $currentDisplayTitle at ${currentTime}s")
              // Emit Save event with sync result attached
              currentPlaybackSession?.let { session ->
                eventEmitter("save", session, syncResult)
              }
            }
          }
        }
      }
    }
  }

  /**
   * Play: Start the sync loop if not already running.
   * Event emission happens at listener level (before syncer is called).
   */
  fun play(playbackSession: PlaybackSession) {
    Log.d(tag, "play: ${playbackSession.displayTitle}")
    start(playbackSession)
  }

  /**
   * Pause: Stop sync loop, perform final sync, emit Pause event with result.
   */
  fun pause(cb: () -> Unit) {
    if (!listeningTimerRunning) {
      // Loop already stopped; still emit pause with freshest time if available.
      val currentTime = telemetryHost.getCurrentTimeSeconds()
      if (currentTime > 0 && currentPlaybackSession != null) {
        sync(true, currentTime, force = true) { syncResult ->
          currentPlaybackSession?.let { session ->
            nextPlaybackEventSource
            nextPlaybackEventSource = PlaybackEventSource.SYSTEM
            applyRefreshedTimeToSession(session)
            eventEmitter("pause", session, syncResult)
          }
          cb()
        }
      } else {
        currentPlaybackSession?.let { session ->
          nextPlaybackEventSource
          nextPlaybackEventSource = PlaybackEventSource.SYSTEM
          applyRefreshedTimeToSession(session)
          eventEmitter("pause", session, null)
        }
        cb()
      }
      return
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.v(tag, "pause: Stopping sync loop for $currentDisplayTitle")

    val currentTime = telemetryHost.getCurrentTimeSeconds()
    if (currentTime > 0) {
      sync(true, currentTime, force = true) { syncResult ->
        lastSyncTime = 0L
        failedSyncs = 0
        currentPlaybackSession?.let { session ->
          nextPlaybackEventSource
          nextPlaybackEventSource = PlaybackEventSource.SYSTEM
          applyRefreshedTimeToSession(session)
          // Pause event emitted with sync result
          eventEmitter("pause", session, syncResult)
        }
        cb()
      }
    } else {
      lastSyncTime = 0L
      failedSyncs = 0
      currentPlaybackSession?.let { session ->
        nextPlaybackEventSource
        nextPlaybackEventSource = PlaybackEventSource.SYSTEM
        applyRefreshedTimeToSession(session)
        eventEmitter("pause", session, null)
      }
      cb()
    }
  }

  /**
   * Stop: Stop sync loop, perform final sync, emit Stop event with result.
   */
  fun stop(shouldSync: Boolean = true, cb: () -> Unit) {
    if (listeningTimerRunning) {
      listeningTimerTask?.cancel()
      listeningTimerTask = null
      listeningTimerRunning = false
      Log.v(tag, "stop: Stopping sync loop for $currentDisplayTitle")
    } else {
      Log.v(tag, "stop: Sync loop already stopped for $currentDisplayTitle")
    }

    val currentTime = if (shouldSync) telemetryHost.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0 && currentPlaybackSession != null) {
      sync(true, currentTime, force = true) { syncResult ->
        currentPlaybackSession?.let { session ->
          eventEmitter("stop", session, syncResult)
        }
        reset()
        cb()
      }
    } else {
      currentPlaybackSession?.let { session ->
        eventEmitter("stop", session, null)
      }
      reset()
      cb()
    }
  }

  /**
   * Finished: Similar to stop but emits finished event.
   */
  fun finished(cb: () -> Unit) {
    if (!listeningTimerRunning) {
      reset()
      cb()
      return
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "finished: Book finished for $currentDisplayTitle")

    val currentTime = telemetryHost.getCurrentTimeSeconds()
    if (currentTime > 0) {
      sync(true, currentTime, force = true) { syncResult ->
        currentPlaybackSession?.let { session ->
          eventEmitter("finished", session, syncResult)
        }
        reset()
        cb()
      }
    } else {
      currentPlaybackSession?.let { session ->
        eventEmitter("finished", session, null)
      }
      reset()
      cb()
    }
  }

  /**
   * Seek: Mark that a seek occurred for telemetry.
   * Seek event emission happens at listener/pipeline level.
   */
  fun seek() {
    Log.d(tag, "seek: Seek detected")
    // Just mark for metrics; event emission happens elsewhere
  }

  fun reset() {
    Log.d(tag, "reset")
    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    failedSyncs = 0
    serverSessionClosed = false
    nextPlaybackEventSource = PlaybackEventSource.SYSTEM
  }

  /**
   * Mark the current session as closed so we skip further server sync attempts.
   * Useful during handoff when the server has already invalidated the session.
   */
  fun markCurrentSessionClosed() {
    serverSessionClosed = true
  }

  fun syncNow(
    event: String,
    session: PlaybackSession,
    shouldSyncServer: Boolean = true,
    cb: (SyncResult?) -> Unit = {}
  ) {
    currentPlaybackSession = session.clone()
    currentLocalMediaProgress = null
    if (lastSyncTime == 0L) {
      lastSyncTime = System.currentTimeMillis() - 2000L // ensure diffSinceLastSync >= 1s
    }
    val currentTime = telemetryHost.getCurrentTimeSeconds().takeIf { it > 0 } ?: session.currentTime
    sync(shouldSyncServer, currentTime) { result ->
      if (result != null) {
        currentPlaybackSession?.let { playbackSession ->
          eventEmitter(event, playbackSession, result)
        }
      }
      cb(result)
    }
  }

  /**
   * Perform actual sync: save to DB, optionally sync to server.
   */
  private fun sync(
    shouldSyncServer: Boolean,
    currentTime: Double,
    force: Boolean = false,
    onComplete: (SyncResult?) -> Unit
  ) {
    val syncSessionId = currentSessionId
    if (syncSessionId.isEmpty()) {
      Log.d(tag, "sync: Abort; no active session id")
      onComplete(null)
      return
    }
    Log.d(tag, "sync: Starting sync for $currentDisplayTitle at ${currentTime}s")
    val diffSinceLastSync = System.currentTimeMillis() - lastSyncTime

    // If we synced very recently and playback time hasn't advanced, skip duplicate server calls.
    val lastSessionTime = currentPlaybackSession?.currentTime ?: 0.0
    val progressed = currentTime - lastSessionTime
    if (diffSinceLastSync in 1000L..5000L && progressed <= 0.5) {
      Log.d(
        tag,
        "sync: Skip; recent sync ($diffSinceLastSync ms ago) with no progress (delta=$progressed s)"
      )
      onComplete(null)
      return
    }

    if (!force && diffSinceLastSync < 1000L) {
      Log.d(tag, "sync: Skip; diffSinceLastSync=${diffSinceLastSync}ms (<1s) force=$force")
      onComplete(null) // Treat as no-op so we don't emit duplicate local saves
      return
    }

    // If the server already told us this session is closed, don't keep sending bad syncs.
    if (!currentIsLocal && serverSessionClosed) {
      Log.d(tag, "sync: Skip server sync because session is closed for $currentSessionId")
      onComplete(SyncResult(false, null, "server_session_closed"))
      return
    }

    val listeningTimeToAdd = (diffSinceLastSync / 1000L).coerceAtLeast(1L)
    val syncData = MediaProgressSyncData(listeningTimeToAdd, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(syncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(tag, "Invalid progress for session ${currentPlaybackSession?.id}")
      onComplete(null)
      return
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(telemetryHost.appContext)

    // Persist playback session locally (server-linked sessions only). Removed after successful server sync.
    currentPlaybackSession?.let { DeviceManager.dbManager.savePlaybackSession(it) }

    if (currentIsLocal) {
      currentPlaybackSession?.let { session ->
        saveLocalProgress(session)
        lastSyncTime = System.currentTimeMillis()

        val isConnectedToSameServer =
          session.serverConnectionConfigId != null &&
            DeviceManager.serverConnectionConfig?.id == session.serverConnectionConfigId

        if (
          hasNetworkConnection &&
          shouldSyncServer &&
          !session.libraryItemId.isNullOrEmpty() &&
          isConnectedToSameServer
        ) {
          apiHandler.sendLocalProgressSync(session) { syncSuccess, errorMsg ->
            if (syncSuccess) {
              failedSyncs = 0
              telemetryHost.alertSyncSuccess()
              DeviceManager.dbManager.removePlaybackSession(session.id)
            } else {
              failedSyncs++
              if (failedSyncs == 2) {
                telemetryHost.alertSyncFailing()
                failedSyncs = 0
              }
            }
            Log.d(
              tag,
              "sync(local): session=${session.id} serverAttempted=$shouldSyncServer success=$syncSuccess error=${errorMsg ?: "none"} listened=${listeningTimeToAdd}s current=${currentTime}s"
            )
            onComplete(SyncResult(true, syncSuccess, errorMsg))
          }
        } else {
          Log.d(
            tag,
            "sync(local): session=${session.id} not sent to server (hasNetworkConnection=$hasNetworkConnection  shouldSyncServer=$shouldSyncServer)"
          )
          onComplete(SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      if (currentPlaybackSession?.id != syncSessionId) {
        Log.d(
          tag,
          "sync(server): Abort; session changed (expected=$syncSessionId, actual=${currentPlaybackSession?.id})"
        )
        onComplete(null)
        return
      }
      apiHandler.sendProgressSync(syncSessionId, syncData) { syncSuccess, errorMsg ->
        if (syncSuccess) {
          failedSyncs = 0
          telemetryHost.alertSyncSuccess()
          lastSyncTime = System.currentTimeMillis()
          DeviceManager.dbManager.removePlaybackSession(syncSessionId)
        } else {
          if (errorMsg?.contains("404") == true) {
            Log.w(tag, "sync(server): session not found (404), marking closed for $syncSessionId")
            serverSessionClosed = true
            onComplete(SyncResult(true, false, errorMsg))
            return@sendProgressSync
          }
          failedSyncs++
          if (failedSyncs == 2) {
            telemetryHost.alertSyncFailing()
            failedSyncs = 0
          }
        }
        Log.d(
          tag,
          "sync(server): session=$currentSessionId success=$syncSuccess error=${errorMsg ?: "none"} listened=${listeningTimeToAdd}s current=${currentTime}s"
        )
        onComplete(SyncResult(true, syncSuccess, errorMsg))
      }
    } else {
      Log.d(
        tag,
        "sync: skip server; hasNetwork=$hasNetworkConnection shouldSyncServer=$shouldSyncServer currentTime=$currentTime"
      )
      onComplete(SyncResult(false, null, null))
    }
  }

  private fun saveLocalProgress(playbackSession: PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      val mediaProgress =
        DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      currentLocalMediaProgress = mediaProgress ?: playbackSession.getNewLocalMediaProgress()
      if (mediaProgress != null) {
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
        telemetryHost.notifyLocalProgressUpdate(it)
        Log.d(
          tag,
          "Saved Local Progress ID ${it.id} current=${it.currentTime} duration=${it.duration} progress=${it.progressPercent}%"
        )
      }
    }
  }

  private fun applyRefreshedTimeToSession(session: PlaybackSession) {
    val manual = pendingManualPlaybackTime
    if (manual != null && System.currentTimeMillis() <= pendingManualPlaybackTimeExpiresAt) {
      session.currentTime = manual
    }
    pendingManualPlaybackTime = null
  }
}
