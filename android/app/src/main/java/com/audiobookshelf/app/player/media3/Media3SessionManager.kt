package com.audiobookshelf.app.player.media3

import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
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
  private val host: Media3ServiceHost
) {
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
    host.isPlayerInitialized = true

    val isNewSession = currentPlaybackSession?.id != session.id
    currentPlaybackSession = session
    DeviceManager.setLastPlaybackSession(session)
    mediaManager.updateLatestServerItemFromSession(session)

    session.mediaPlayer = host.currentMediaPlayerId()

    // Only reset metrics for NEW sessions, not player switches
    if (isNewSession) {
      host.playbackMetrics.begin(session.mediaPlayer, session.mediaItemId)
    }

    host.notifyWidgetState(false)
  }

  fun switchPlaybackSession(session: PlaybackSession, syncPreviousSession: Boolean = true) {
    markPlaybackSessionAssigned()
    val previous = currentPlaybackSession
    if (previous != null && previous.id != session.id) {
      host.updateCurrentPosition(previous)
      if (syncPreviousSession) {
        host.maybeSyncProgress("switch", true, previous) { _ -> }
      }
    }
    assignPlaybackSession(session)
  }

  fun closePlayback(calledOnError: Boolean = false, afterStop: (() -> Unit)? = null) {
    val session = currentPlaybackSession
    if (session != null) {
      val signal = CompletableDeferred<Unit>()
      closePlaybackSignal = signal

      val tearDown = {
        serviceScope.launch(Dispatchers.Main) {
          host.playbackMetrics.logSummary()

          if (!session.isLocal && session.id.isNotEmpty()) {
            host.closeSessionOnServer(session.id)
          }

          if (host.isPlayerInitialized) {
            host.playerOrNull()?.run {
              stop()
              clearMediaItems()
            }
            host.isPlayerInitialized = false
          }
          host.resetProgressSyncState()
          currentPlaybackSession = null
          host.notifyWidgetState(true)
          signal.complete(Unit)
          closePlaybackSignal = null
          afterStop?.invoke()
        }
        Unit
      }

      if (calledOnError) {
        tearDown()
      } else {
        host.updateCurrentPosition(session)
        host.maybeSyncProgress("close", true, session) { _ -> tearDown() }
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
