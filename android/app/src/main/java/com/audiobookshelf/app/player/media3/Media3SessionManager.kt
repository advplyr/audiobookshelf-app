package com.audiobookshelf.app.player.media3

import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** All public methods must run on the main thread or in [serviceScope]. */
class Media3SessionManager(
  private val serviceScope: CoroutineScope,
  private val mediaManager: MediaManager,
  private val host: PlaybackSessionHost
) {
  var currentPlaybackSession: PlaybackSession? = null
    private set

  @Volatile
  var sessionAssignTimestampMs: Long = 0L
    private set

  private var closePlaybackSignal: CompletableDeferred<Unit>? = null

  /** onDestroy can overlap asynchronous close teardown, so only one path may own the final sync. */
  @Volatile
  var terminalSyncClaimed: Boolean = false
    private set

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

    host.isPlayerInitialized = true
    terminalSyncClaimed = false

    val isNewSession = currentPlaybackSession?.id != session.id
    currentPlaybackSession = session
    DeviceManager.setLastPlaybackSession(session)
    mediaManager.updateLatestServerItemFromSession(session)

    session.mediaPlayer = host.currentMediaPlayerId()

    // A player switch continues the same listening session and must not split its metrics.
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
        // Resetting here advances the generation and drops the outgoing callback's failure state.
        host.maybeSyncProgress(SyncReason.SWITCH, true, previous) { _ ->
          // Starting earlier would invalidate this callback's generation.
          if (currentPlaybackSession?.id == session.id) {
            host.startProgressSyncIfPlaying(session)
          }
        }
      }
    }
    assignPlaybackSession(session)
  }

  fun claimTerminalSync(): Boolean {
    if (terminalSyncClaimed) return false
    terminalSyncClaimed = true
    return true
  }

  fun closePlayback(calledOnError: Boolean = false, afterStop: (() -> Unit)? = null) {
    val session = currentPlaybackSession
    if (session != null) {
      val terminalSyncAlreadyClaimed = terminalSyncClaimed
      val signal = CompletableDeferred<Unit>()
      closePlaybackSignal = signal
      terminalSyncClaimed = true

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

      if (calledOnError || terminalSyncAlreadyClaimed) {
        tearDown()
      } else {
        host.updateCurrentPosition(session)
        host.maybeSyncProgress(SyncReason.CLOSE, true, session) { _ -> tearDown() }
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
