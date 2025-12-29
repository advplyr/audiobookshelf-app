package com.audiobookshelf.app.media

import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.core.PlaybackTelemetryHost
import com.audiobookshelf.app.server.ApiHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
  private val playbackTelemetryProvider: PlaybackTelemetryHost,
  private val progressApi: ApiHandler,
  private val onPlaybackEvent: (event: String, session: PlaybackSession, syncResult: SyncResult?) -> Unit
) {
  private val tag = "UnifiedMediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000L
  private val PERIODIC_SYNC_INTERVAL = 15000L

  // Coroutine scope for periodic sync loop - uses Main dispatcher for consistency
  private val syncScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var syncJob: Job? = null
  var isSyncTimerRunning: Boolean = false

  var currentPlaybackSession: PlaybackSession? = null
  var localMediaProgress: LocalMediaProgress? = null

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
  private var playbackEventSource = PlaybackEventSource.SYSTEM
  private var pendingPlaybackTime: Double? = null
  private var pendingPlaybackTimeExpiry: Long = 0

  fun markNextPlaybackEventSource(source: PlaybackEventSource) {
    playbackEventSource = source
  }

  // ------------ Lifecycle control ------------
  /**
   * Start the 15-second periodic sync loop.
   */
  fun start(playbackSession: PlaybackSession) {
    if (isSyncTimerRunning) {
      if (playbackSession.id != currentSessionId) {
        Log.d(tag, "Playback session changed, reset timer")
        localMediaProgress = null
        syncJob?.cancel()
        lastSyncTime = 0L
        failedSyncs = 0
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      localMediaProgress = null
    }

    isSyncTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()
    serverSessionClosed = false
    Log.d(tag, "start: Started 15s periodic sync loop for ${playbackSession.displayTitle}")

    // Coroutine-based periodic sync - replaces Timer + Handler.post chain
    syncJob = syncScope.launch {
      while (isActive) {
        delay(PERIODIC_SYNC_INTERVAL)

        if (playbackTelemetryProvider.isPlayerActive()) {
          if (playbackTelemetryProvider.isSleepTimerActive()) {
            playbackTelemetryProvider.checkAutoSleepTimer()
          }

          val shouldSyncServer =
            playbackTelemetryProvider.isUnmeteredNetwork ||
              System.currentTimeMillis() - lastSyncTime >= METERED_CONNECTION_SYNC_INTERVAL

          val currentTime = playbackTelemetryProvider.getCurrentTimeSeconds()
          if (currentTime > 0) {
            sync(shouldSyncServer, currentTime) { syncResult ->
              Log.v(tag, "Periodic sync complete for $currentDisplayTitle at ${currentTime}s")
              currentPlaybackSession?.let { session ->
                onPlaybackEvent("save", session, syncResult)
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
    start(playbackSession)
  }

  /**
   * Pause: Stop sync loop, perform final sync, emit Pause event with result.
   */
  fun pause(onComplete: () -> Unit) {
    if (!isSyncTimerRunning) {
      val currentTime = playbackTelemetryProvider.getCurrentTimeSeconds()
      if (currentTime > 0 && currentPlaybackSession != null) {
        sync(true, currentTime, force = true) { syncResult ->
          currentPlaybackSession?.let { session ->
            playbackEventSource
            playbackEventSource = PlaybackEventSource.SYSTEM
            applyRefreshedTimeToSession(session)
            onPlaybackEvent("pause", session, syncResult)
          }
          onComplete()
        }
      } else {
        currentPlaybackSession?.let { session ->
          playbackEventSource
          playbackEventSource = PlaybackEventSource.SYSTEM
          applyRefreshedTimeToSession(session)
          onPlaybackEvent("pause", session, null)
        }
        onComplete()
      }
      return
    }

    syncJob?.cancel()
    syncJob = null
    isSyncTimerRunning = false
    Log.v(tag, "pause: Stopping sync loop for $currentDisplayTitle")

    val currentTime = playbackTelemetryProvider.getCurrentTimeSeconds()
    if (currentTime > 0) {
      sync(true, currentTime, force = true) { syncResult ->
        lastSyncTime = 0L
        failedSyncs = 0
        currentPlaybackSession?.let { session ->
          playbackEventSource
          playbackEventSource = PlaybackEventSource.SYSTEM
          applyRefreshedTimeToSession(session)
          onPlaybackEvent("pause", session, syncResult)
        }
        onComplete()
      }
    } else {
      lastSyncTime = 0L
      failedSyncs = 0
      currentPlaybackSession?.let { session ->
        playbackEventSource
        playbackEventSource = PlaybackEventSource.SYSTEM
        applyRefreshedTimeToSession(session)
        onPlaybackEvent("pause", session, null)
      }
      onComplete()
    }
  }

  /**
   * Stop: Stop sync loop, perform final sync, emit Stop event with result.
   */
  fun stop(shouldSyncOnStop: Boolean = true, onComplete: () -> Unit) {
    if (isSyncTimerRunning) {
      syncJob?.cancel()
      syncJob = null
      isSyncTimerRunning = false
      Log.v(tag, "stop: Stopping sync loop for $currentDisplayTitle")
    } else {
      Log.v(tag, "stop: Sync loop already stopped for $currentDisplayTitle")
    }

    val currentTime =
      if (shouldSyncOnStop) playbackTelemetryProvider.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0 && currentPlaybackSession != null) {
      sync(true, currentTime, force = true) { syncResult ->
        currentPlaybackSession?.let { session ->
          onPlaybackEvent("stop", session, syncResult)
        }
        reset()
        onComplete()
      }
    } else {
      currentPlaybackSession?.let { session ->
        onPlaybackEvent("stop", session, null)
      }
      reset()
      onComplete()
    }
  }

  /**
   * Finished: Similar to stop but emits finished event.
   */
  fun finished(onComplete: () -> Unit) {
    if (!isSyncTimerRunning) {
      reset()
      onComplete()
      return
    }

    syncJob?.cancel()
    syncJob = null
    isSyncTimerRunning = false
    Log.d(tag, "finished: Book finished for $currentDisplayTitle")

    val currentTime = playbackTelemetryProvider.getCurrentTimeSeconds()
    if (currentTime > 0) {
      sync(true, currentTime, force = true) { syncResult ->
        currentPlaybackSession?.let { session ->
          onPlaybackEvent("finished", session, syncResult)
        }
        reset()
        onComplete()
      }
    } else {
      currentPlaybackSession?.let { session ->
        onPlaybackEvent("finished", session, null)
      }
      reset()
      onComplete()
    }
  }

  // ------------ Event helpers ------------
  /**
   * Seek: Mark that a seek occurred for telemetry.
   * Seek event emission happens at listener/pipeline level.
   */
  fun seek() {
    Log.d(tag, "seek: Seek detected")
  }

  fun reset() {
    Log.d(tag, "reset")
    syncJob?.cancel()
    syncJob = null
    isSyncTimerRunning = false
    currentPlaybackSession = null
    localMediaProgress = null
    lastSyncTime = 0L
    failedSyncs = 0
    serverSessionClosed = false
    playbackEventSource = PlaybackEventSource.SYSTEM
  }

  /**
   * Cleanup: Cancel sync scope and all jobs.
   * Call this when the syncer is no longer needed (e.g., service destroyed).
   */
  fun cleanup() {
    syncJob?.cancel()
    syncJob = null
    syncScope.cancel()
    isSyncTimerRunning = false
  }

  // ------------ Sync helpers ------------

  /**
   * Perform actual sync: save to DB, optionally sync to server.
   */
  private fun sync(
    shouldSyncServer: Boolean,
    currentTime: Double,
    force: Boolean = false,
    onComplete: (SyncResult?) -> Unit
  ) {
    val sessionIdForSync = currentSessionId
    if (sessionIdForSync.isEmpty()) {
      Log.d(tag, "sync: Abort; no active session id")
      onComplete(null)
      return
    }
    Log.d(tag, "sync: Starting sync for $currentDisplayTitle at ${currentTime}s")
    val timeSinceLastSyncMillis = System.currentTimeMillis() - lastSyncTime

    val lastSyncedPlaybackTime = currentPlaybackSession?.currentTime ?: 0.0
    val playbackTimeDeltaSeconds = currentTime - lastSyncedPlaybackTime
    if (timeSinceLastSyncMillis in 1000L..5000L && playbackTimeDeltaSeconds <= 0.5) {
      Log.v(
        tag,
        "sync: Skip; recent sync ($timeSinceLastSyncMillis ms ago) with no progress (delta=$playbackTimeDeltaSeconds s)"
      )
      onComplete(null)
      return
    }

    if (!force && timeSinceLastSyncMillis < 1000L) {
      Log.v(tag, "sync: Skip; diffSinceLastSync=${timeSinceLastSyncMillis}ms (<1s) force=$force")
      onComplete(null)
      return
    }

    if (!currentIsLocal && serverSessionClosed) {
      Log.d(tag, "sync: Skip server sync because session is closed for $currentSessionId")
      onComplete(SyncResult(false, null, "server_session_closed"))
      return
    }

    val listeningDurationSeconds = (timeSinceLastSyncMillis / 1000L).coerceAtLeast(1L)
    val progressSyncData =
      MediaProgressSyncData(listeningDurationSeconds, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(progressSyncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(tag, "Invalid progress for session ${currentPlaybackSession?.id}")
      onComplete(null)
      return
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(playbackTelemetryProvider.appContext)

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
          progressApi.sendLocalProgressSync(session) { syncSuccess, errorMsg ->
            if (syncSuccess) {
              failedSyncs = 0
              playbackTelemetryProvider.alertSyncSuccess()
              DeviceManager.dbManager.removePlaybackSession(session.id)
            } else {
              failedSyncs++
              if (failedSyncs == 2) {
                playbackTelemetryProvider.alertSyncFailing()
                failedSyncs = 0
              }
            }
            Log.d(
              tag,
              "sync(local): session=${session.id} serverAttempted=$shouldSyncServer success=$syncSuccess error=${errorMsg ?: "none"} listened=${listeningDurationSeconds}s current=${currentTime}s"
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
      if (currentPlaybackSession?.id != sessionIdForSync) {
        Log.d(
          tag,
          "sync(server): Abort; session changed (expected=$sessionIdForSync, actual=${currentPlaybackSession?.id})"
        )
        onComplete(null)
        return
      }
      progressApi.sendProgressSync(sessionIdForSync, progressSyncData) { syncSuccess, errorMsg ->
        if (syncSuccess) {
          failedSyncs = 0
          playbackTelemetryProvider.alertSyncSuccess()
          lastSyncTime = System.currentTimeMillis()
          DeviceManager.dbManager.removePlaybackSession(sessionIdForSync)
        } else {
          if (errorMsg?.contains("404") == true) {
            Log.w(
              tag,
              "sync(server): session not found (404), marking closed for $sessionIdForSync"
            )
            serverSessionClosed = true
            onComplete(
              SyncResult(
                serverSyncAttempted = true,
                serverSyncSuccess = false,
                serverSyncMessage = errorMsg
              )
            )
            return@sendProgressSync
          }
          failedSyncs++
          if (failedSyncs == 2) {
            playbackTelemetryProvider.alertSyncFailing()
            failedSyncs = 0
          }
        }
        Log.d(
          tag,
          "sync(server): session=$currentSessionId success=$syncSuccess error=${errorMsg ?: "none"} listened=${listeningDurationSeconds}s current=${currentTime}s"
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
    if (localMediaProgress == null) {
      val existingLocalMediaProgress =
        DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      localMediaProgress = existingLocalMediaProgress ?: playbackSession.getNewLocalMediaProgress()
      if (existingLocalMediaProgress != null) {
        localMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      localMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    localMediaProgress?.let {
      if (it.progress.isNaN()) {
        Log.e(tag, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        playbackTelemetryProvider.notifyLocalProgressUpdate(it)
        Log.d(
          tag,
          "Saved Local Progress ID ${it.id} current=${it.currentTime} duration=${it.duration} progress=${it.progressPercent}%"
        )
      }
    }
  }

  private fun applyRefreshedTimeToSession(session: PlaybackSession) {
    val pendingTime = pendingPlaybackTime
    if (pendingTime != null && System.currentTimeMillis() <= pendingPlaybackTimeExpiry) {
      session.currentTime = pendingTime
    }
    pendingPlaybackTime = null
  }

  fun syncNow(
    event: String,
    session: PlaybackSession,
    shouldSyncServer: Boolean = true,
    onComplete: (SyncResult?) -> Unit = {}
  ) {
    currentPlaybackSession = session.clone()
    localMediaProgress = null
    if (lastSyncTime == 0L) {
      lastSyncTime = System.currentTimeMillis() - 2000L
    }
    val currentTime =
      playbackTelemetryProvider.getCurrentTimeSeconds().takeIf { it > 0 } ?: session.currentTime
    sync(shouldSyncServer, currentTime) { result ->
      if (result != null) {
        currentPlaybackSession?.let { playbackSession ->
          onPlaybackEvent(event, playbackSession, result)
        }
      } else {
        if (event == "pause" || event == "stop" || event == "finished") {
          currentPlaybackSession?.let { playbackSession ->
            onPlaybackEvent(event, playbackSession, null)
          }
        }
      }
      onComplete(result)
    }
  }
}
