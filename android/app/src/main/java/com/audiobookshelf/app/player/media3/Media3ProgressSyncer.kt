package com.audiobookshelf.app.player.media3

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackStateHost
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Media3 delivers state asynchronously, so its progress sync cannot use the synchronous
 * assumptions in [com.audiobookshelf.app.media.MediaProgressSyncer].
 */
class Media3ProgressSyncer(
  private val stateHost: PlaybackStateHost,
  private val progressApi: ApiHandler,
  private val onPlaybackEvent: (event: String, session: PlaybackSession, syncResult: SyncResult?) -> Unit
) {
  companion object {
    private const val TAG = "Media3ProgressSync"
    private const val PERSISTENT_LOG_TAG = "Media3ProgressSyncer"
    private const val METERED_CONNECTION_SYNC_INTERVAL = 60000L
    private const val PERIODIC_SYNC_INTERVAL = 15000L
  }

  // Main dispatcher: sync ticks read the player, which Media3 requires on the main thread.
  private val syncScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private val mainHandler = Handler(Looper.getMainLooper())
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
  private var lifecycleGeneration: Long = 0

  // Lifecycle
  fun start(playbackSession: PlaybackSession) {
    if (isSyncTimerRunning) {
      if (playbackSession.id != currentSessionId) {
        debugLog(TAG) { "Playback session changed, reset timer" }
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

    lifecycleGeneration++
    isSyncTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()
    serverSessionClosed = false
    debugLog(TAG) { "start: Started 15s periodic sync loop for ${playbackSession.displayTitle}" }

    syncJob = syncScope.launch {
      while (isActive) {
        delay(PERIODIC_SYNC_INTERVAL.milliseconds)

        if (stateHost.isPlayerActive()) {
          if (stateHost.isSleepTimerActive()) {
            stateHost.checkAutoSleepTimer()
          }

          val shouldSyncServer =
            stateHost.isUnmeteredNetwork ||
              System.currentTimeMillis() - lastSyncTime >= METERED_CONNECTION_SYNC_INTERVAL

          val currentTime = stateHost.getCurrentTimeSeconds()
          if (currentTime > 0) {
            sync(shouldSyncServer, currentTime) { syncResult ->
              Log.v(TAG, "Periodic sync complete for $currentDisplayTitle at ${currentTime}s")
              currentPlaybackSession?.let { session ->
                onPlaybackEvent(SyncReason.SAVE, session, syncResult)
              }
            }
          }
        }
      }
    }
  }

  fun pause(onComplete: () -> Unit) {
    if (isSyncTimerRunning) {
      syncJob?.cancel()
      syncJob = null
      isSyncTimerRunning = false
      Log.v(TAG, "pause: Stopping sync loop for $currentDisplayTitle")
    }

    // Capture the paused session and reset timing before an asynchronous response can race resume.
    // The callback may also arrive after the active session changes.
    val pausedSession = currentPlaybackSession
    val currentTime = stateHost.getCurrentTimeSeconds()
    if (currentTime > 0) {
      // sync() must read lastSyncTime before it is reset below.
      sync(true, currentTime, force = true) { syncResult ->
        pausedSession?.let { session -> onPlaybackEvent(SyncReason.PAUSE, session, syncResult) }
        onComplete()
      }
    } else {
      pausedSession?.let { session -> onPlaybackEvent(SyncReason.PAUSE, session, null) }
      onComplete()
    }
    lastSyncTime = 0L
    failedSyncs = 0
  }

  // State reset and cleanup
  fun reset() {
    lifecycleGeneration++
    syncJob?.cancel()
    syncJob = null
    isSyncTimerRunning = false
    currentPlaybackSession = null
    localMediaProgress = null
    lastSyncTime = 0L
    failedSyncs = 0
    serverSessionClosed = false
  }

  fun cleanup() {
    lifecycleGeneration++
    syncJob?.cancel()
    syncJob = null
    syncScope.cancel()
    isSyncTimerRunning = false
  }

  // Progress sync
  private fun sync(
    shouldSyncServer: Boolean,
    currentTime: Double,
    force: Boolean = false,
    onComplete: (SyncResult?) -> Unit
  ) {
    val sessionIdForSync = currentSessionId
    val generationForSync = lifecycleGeneration
    if (sessionIdForSync.isEmpty()) {
      debugLog(TAG) { "sync: Abort; no active session id" }
      onComplete(null)
      return
    }
    val timeSinceLastSyncMillis = System.currentTimeMillis() - lastSyncTime

    val lastSyncedPlaybackTime = currentPlaybackSession?.currentTime ?: 0.0
    val playbackTimeDeltaSeconds = currentTime - lastSyncedPlaybackTime
    // Forced syncs (pause/close/switch finals) must never be debounced: syncNow refreshes the
    // session's currentTime before calling sync, so the delta here is always ~0 for them and
    // skipping would drop the last few seconds of listening progress.
    if (!force && timeSinceLastSyncMillis in 1000L..5000L && playbackTimeDeltaSeconds <= 0.5) {
      Log.v(
        TAG,
        "sync: Skip; recent sync ($timeSinceLastSyncMillis ms ago) with no progress (delta=$playbackTimeDeltaSeconds s)"
      )
      onComplete(null)
      return
    }

    if (!force && timeSinceLastSyncMillis < 1000L) {
      Log.v(TAG, "sync: Skip; diffSinceLastSync=${timeSinceLastSyncMillis}ms (<1s) force=$force")
      onComplete(null)
      return
    }

    if (!currentIsLocal && serverSessionClosed) {
      debugLog(TAG) { "sync: Skip server sync because session is closed for $currentSessionId" }
      onComplete(SyncResult(false, null, "server_session_closed"))
      return
    }

    // Matches MediaProgressSyncer semantics: lastSyncTime marks the last time listening was
    // DELIVERED (saved locally, or accepted by the server), not the last tick. Ticks that skip
    // or fail the server sync leave it alone, so the next successful sync reports the full
    // elapsed listening time and the metered-connection interval can actually elapse.
    val listeningDurationSeconds = timeSinceLastSyncMillis / 1000L
    val progressSyncData =
      MediaProgressSyncData(listeningDurationSeconds, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(progressSyncData)
    AbsLogger.info(
      PERSISTENT_LOG_TAG,
      "sync start: shouldSyncServer=$shouldSyncServer currentTime=$currentTime " +
        "diffSinceLastSync=${timeSinceLastSyncMillis}ms listeningTime=$listeningDurationSeconds"
    )

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(TAG, "Invalid progress for session ${currentPlaybackSession?.id}")
      onComplete(null)
      return
    }

    // A transport check permits LAN-only servers that lack validated internet access.
    val hasNetworkConnection = DeviceManager.checkConnectivity(stateHost.appContext)

    currentPlaybackSession?.let { DeviceManager.dbManager.savePlaybackSession(it) }

    if (currentIsLocal) {
      currentPlaybackSession?.let { session ->
        saveLocalProgress(session)
        lastSyncTime = System.currentTimeMillis()
        AbsLogger.info(
          PERSISTENT_LOG_TAG,
          "sync: Saved local progress (title: \"$currentDisplayTitle\") " +
            "(currentTime: $currentTime) (session id: ${session.id})"
        )

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
              updateSyncStateIfCurrent(session.id, generationForSync) {
                failedSyncs = 0
                stateHost.alertSyncSuccess()
              }
              // Switch and destroy can make state stale before this callback; the row still needs cleanup.
              DeviceManager.dbManager.removePlaybackSession(session.id)
              AbsLogger.info(
                PERSISTENT_LOG_TAG,
                "sync: Successfully synced local progress (title: \"$currentDisplayTitle\") " +
                  "(currentTime: $currentTime) (session id: ${session.id})"
              )
            } else {
              updateSyncStateIfCurrent(session.id, generationForSync) {
                failedSyncs++
                if (failedSyncs == 2) {
                  stateHost.alertSyncFailing()
                  failedSyncs = 0
                }
              }
              AbsLogger.error(
                PERSISTENT_LOG_TAG,
                "sync: Local progress sync failed (count: $failedSyncs) " +
                  "(title: \"$currentDisplayTitle\") (currentTime: $currentTime) " +
                  "(session id: ${session.id}) (${DeviceManager.serverConnectionConfigName})"
              )
            }
            onComplete(SyncResult(true, syncSuccess, errorMsg))
          }
        } else {
          AbsLogger.info(
            PERSISTENT_LOG_TAG,
            "sync: Not sending local progress to server (title: \"$currentDisplayTitle\") " +
              "(currentTime: $currentTime) (session id: ${session.id}) " +
              "(hasNetworkConnection: $hasNetworkConnection) " +
              "(isConnectedToSameServer: $isConnectedToSameServer)"
          )
          onComplete(SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      if (currentPlaybackSession?.id != sessionIdForSync) {
        debugLog(TAG) {
          "sync(server): Abort; session changed (expected=$sessionIdForSync, actual=${currentPlaybackSession?.id})"
        }
        onComplete(null)
        return
      }
      AbsLogger.info(
        PERSISTENT_LOG_TAG,
        "sync: Sending progress sync to server (title: \"$currentDisplayTitle\") " +
          "(currentTime: $currentTime) (session id: $sessionIdForSync) " +
          "(${DeviceManager.serverConnectionConfigName})"
      )
      progressApi.sendProgressSync(sessionIdForSync, progressSyncData) { syncSuccess, errorMsg ->
        if (syncSuccess) {
          updateSyncStateIfCurrent(sessionIdForSync, generationForSync) {
            failedSyncs = 0
            stateHost.alertSyncSuccess()
            lastSyncTime = System.currentTimeMillis()
          }
          // Destroy blocks the main looper on a latch, so row cleanup cannot depend on a main-thread post.
          DeviceManager.dbManager.removePlaybackSession(sessionIdForSync)
          AbsLogger.info(
            PERSISTENT_LOG_TAG,
            "sync: Successfully synced progress (title: \"$currentDisplayTitle\") " +
              "(currentTime: $currentTime) (session id: $sessionIdForSync) " +
              "(${DeviceManager.serverConnectionConfigName})"
          )
        } else {
          AbsLogger.error(
            PERSISTENT_LOG_TAG,
            "sync: Progress sync failed (count: $failedSyncs) " +
              "(title: \"$currentDisplayTitle\") (currentTime: $currentTime) " +
              "(session id: $sessionIdForSync) (${DeviceManager.serverConnectionConfigName})"
          )
          if (errorMsg?.contains("404") == true) {
            Log.w(
              TAG,
              "sync(server): session not found (404), marking closed for $sessionIdForSync"
            )
            updateSyncStateIfCurrent(sessionIdForSync, generationForSync) {
              serverSessionClosed = true
            }
            onComplete(
              SyncResult(
                serverSyncAttempted = true,
                serverSyncSuccess = false,
                serverSyncMessage = errorMsg
              )
            )
            return@sendProgressSync
          }
          updateSyncStateIfCurrent(sessionIdForSync, generationForSync) {
            failedSyncs++
            if (failedSyncs == 2) {
              stateHost.alertSyncFailing()
              failedSyncs = 0
            }
          }
        }
        onComplete(SyncResult(true, syncSuccess, errorMsg))
      }
    } else {
      AbsLogger.info(
        PERSISTENT_LOG_TAG,
        "sync: Not sending progress to server (title: \"$currentDisplayTitle\") " +
          "(currentTime: $currentTime) (session id: $sessionIdForSync) " +
          "(${DeviceManager.serverConnectionConfigName}) " +
          "(hasNetworkConnection: $hasNetworkConnection)"
      )
      onComplete(SyncResult(false, null, null))
    }
  }

  /** Serializes callback state with lifecycle changes and rejects stale playback generations. */
  private fun updateSyncStateIfCurrent(
    sessionIdForSync: String,
    generationForSync: Long,
    update: () -> Unit
  ) {
    val applyUpdate = {
      val active = currentPlaybackSession?.id
      if (active == sessionIdForSync && lifecycleGeneration == generationForSync) {
        update()
      } else {
        debugLog(TAG) {
          "sync: Ignoring stale callback for $sessionIdForSync " +
            "(active=${active ?: "none"}, generation=$generationForSync, currentGeneration=$lifecycleGeneration)"
        }
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) applyUpdate() else mainHandler.post(applyUpdate)
  }

  private fun saveLocalProgress(playbackSession: PlaybackSession) {
    // Reusing another item's cached record would save progress under the wrong ID.
    val progressId = playbackSession.localMediaProgressId
    if (localMediaProgress?.id != progressId) {
      localMediaProgress = null
    }

    if (localMediaProgress == null) {
      val existingLocalMediaProgress =
        DeviceManager.dbManager.getLocalMediaProgress(progressId)
      localMediaProgress = existingLocalMediaProgress ?: playbackSession.getNewLocalMediaProgress()
      if (existingLocalMediaProgress != null) {
        localMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      localMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    localMediaProgress?.let {
      if (it.progress.isNaN()) {
        Log.e(TAG, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        stateHost.notifyLocalProgressUpdate(it)
        debugLog(TAG) {
          "Saved Local Progress ID ${it.id} current=${it.currentTime} duration=${it.duration} progress=${it.progressPercent}%"
        }
      }
    }
  }

  fun syncNow(
    event: String,
    session: PlaybackSession,
    shouldSyncServer: Boolean = true,
    callbackOnMainThread: Boolean = true,
    onComplete: (SyncResult?) -> Unit = {}
  ) {
    val requestSession = session.clone()
    currentPlaybackSession = requestSession
    localMediaProgress = null
    if (lastSyncTime == 0L) {
      lastSyncTime = System.currentTimeMillis() - 2000L
    }
    val currentTime =
      stateHost.getCurrentTimeSeconds().takeIf { it > 0 } ?: session.currentTime
    sync(shouldSyncServer, currentTime, force = true) { result ->
      val deliverCompletion = {
        if (result != null) {
          onPlaybackEvent(event, requestSession, result)
        } else {
          if (event in SyncReason.REPORT_WITHOUT_RESULT) {
            onPlaybackEvent(event, requestSession, null)
          }
        }
        onComplete(result)
      }
      if (callbackOnMainThread) {
        mainHandler.post(deliverCompletion)
      } else {
        deliverCompletion()
      }
    }
  }
}
