package com.audiobookshelf.app.player

import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages playback session lifecycle for Media3PlaybackService.
 * Coordinates session assignment, metrics tracking, and deferred close operations.
 * Threading: All public methods must be called on main thread or within serviceScope.
 */
class Media3SessionManager(
  private val serviceScope: CoroutineScope,
  private val mediaManager: MediaManager,
  private val playbackMetrics: PlaybackMetricsRecorder,
  private val playerControl: PlayerControl,
  private val serviceCallbacks: ServiceCallbacks
) {

    interface PlayerControl {
        var isInitialized: Boolean
        fun stop()
        fun clearMediaItems()
    }

    interface ServiceCallbacks {
        fun currentMediaPlayerId(): String
        fun updateCurrentPosition(session: PlaybackSession)
        fun maybeSyncProgress(reason: String, force: Boolean, session: PlaybackSession?, onComplete: ((SyncResult?) -> Unit)?)
        fun notifyWidgetState(isPlaybackClosed: Boolean)
        fun closeSessionOnServer(sessionId: String)
    }
  var currentPlaybackSession: PlaybackSession? = null
    private set

  @Volatile
  var sessionAssignTimestampMs: Long = 0L
    private set

  private var closePlaybackSignal: CompletableDeferred<Unit>? = null

  fun assignPlaybackSession(session: PlaybackSession, allowDefer: Boolean = true) {
    val pendingClose = closePlaybackSignal
    if (allowDefer && pendingClose != null && !pendingClose.isCompleted) {
      serviceScope.launch {
        try {
          pendingClose.await()
        } catch (_: Exception) {
        }
        assignPlaybackSession(session, false)
      }
      return
    }

    // Ensure flags return to a ready state after a closePlayback call
      playerControl.isInitialized = true

    val isNewSession = currentPlaybackSession?.id != session.id
    currentPlaybackSession = session
    DeviceManager.setLastPlaybackSession(session)
    mediaManager.updateLatestServerItemFromSession(session)

      session.mediaPlayer = serviceCallbacks.currentMediaPlayerId()

    // Only reset metrics for NEW sessions, not player switches
    if (isNewSession) {
      playbackMetrics.begin(session.mediaPlayer, session.mediaItemId)
    }

      serviceCallbacks.notifyWidgetState(false)
  }

  fun switchPlaybackSession(session: PlaybackSession, syncPreviousSession: Boolean = true) {
    markPlaybackSessionAssigned()
    val previous = currentPlaybackSession
    if (previous != null && previous.id != session.id) {
        serviceCallbacks.updateCurrentPosition(previous)
      if (syncPreviousSession) {
          serviceCallbacks.maybeSyncProgress("switch", true, previous) { _ -> }
      }
    }
    assignPlaybackSession(session)
  }

  fun closePlayback(afterStop: (() -> Unit)? = null) {
    val session = currentPlaybackSession
    if (session != null) {
      val signal = CompletableDeferred<Unit>()
      closePlaybackSignal = signal

        serviceCallbacks.updateCurrentPosition(session)
        serviceCallbacks.maybeSyncProgress("close", true, session) { _ ->
        serviceScope.launch(Dispatchers.Main) {
          playbackMetrics.logSummary()

          if (!session.isLocal && session.id.isNotEmpty()) {
              serviceCallbacks.closeSessionOnServer(session.id)
          }

            if (playerControl.isInitialized) {
                playerControl.stop()
                playerControl.clearMediaItems()
                playerControl.isInitialized = false
          }
          currentPlaybackSession = null
            serviceCallbacks.notifyWidgetState(true)
          signal.complete(Unit)
          closePlaybackSignal = null
          afterStop?.invoke()
        }
      }
    } else {
      closePlaybackSignal?.complete(Unit)
      closePlaybackSignal = null
      afterStop?.invoke()
    }
  }

  fun syncSessionFromHostController() {
    val latest = DeviceManager.getLastPlaybackSession() ?: return
    val currentId = currentPlaybackSession?.id
    if (currentId == latest.id) return
    assignPlaybackSession(latest)
  }

    private fun markPlaybackSessionAssigned() {
    sessionAssignTimestampMs = System.currentTimeMillis()
  }

  fun resetSessionAssignTimestamp() {
    sessionAssignTimestampMs = 0L
  }

    val closePlaybackSignalSnapshot: CompletableDeferred<Unit>?
        get() = closePlaybackSignal
}
