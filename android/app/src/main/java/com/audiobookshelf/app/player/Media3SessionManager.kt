package com.audiobookshelf.app.player

import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.media.UnifiedMediaProgressSyncer
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Media3SessionManager(
  private val serviceScope: CoroutineScope,
  private val mediaManager: MediaManager,
  private val unifiedProgressSyncer: UnifiedMediaProgressSyncer,
  private val playbackMetrics: PlaybackMetricsRecorder,
  private val currentMediaPlayerIdProvider: () -> String,
  private val updateCurrentPosition: (PlaybackSession) -> Unit,
  private val maybeSyncProgress: (String, Boolean, PlaybackSession?, ((SyncResult?) -> Unit)?) -> Unit,
  private val stopPositionUpdates: () -> Unit,
  private val notifyWidgetState: (Boolean) -> Unit,
  private val playerProvider: () -> Player,
  private val isPlayerInitialized: () -> Boolean,
  private val pausePlayer: () -> Unit,
  private val stopPlayer: () -> Unit,
  private val clearPlayerMediaItems: () -> Unit,
  private val setPlayerNotInitialized: () -> Unit,
  private val setLastKnownIsPlaying: (Boolean) -> Unit,
  private val debugLog: (String) -> Unit
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

    val isNewSession = currentPlaybackSession?.id != session.id
    currentPlaybackSession = session
    mediaManager.updateLatestServerItemFromSession(session)

    session.mediaPlayer = currentMediaPlayerIdProvider()

    // Only reset metrics for NEW sessions, not player switches
    if (isNewSession) {
      playbackMetrics.begin(session.mediaPlayer, session.mediaItemId)
    }

    // Ensure syncer always tracks the latest session immediately (even before play fires)
    unifiedProgressSyncer.play(session)
    notifyWidgetState(false)
  }

  fun switchPlaybackSession(session: PlaybackSession) {
    markPlaybackSessionAssigned()
    val previous = currentPlaybackSession
    if (previous != null && previous.id != session.id) {
      updateCurrentPosition(previous)
      maybeSyncProgress("switch", true, previous) { _ -> }
    }
    assignPlaybackSession(session)
  }

  fun closePlayback(afterStop: (() -> Unit)? = null) {
    debugLog("closePlayback: user requested stop")

    val session = currentPlaybackSession
    if (session != null) {
      val signal = CompletableDeferred<Unit>()
      closePlaybackSignal = signal
      stopPositionUpdates()

      if (isPlayerInitialized() && playerProvider().isPlaying) {
        pausePlayer()
      }

      updateCurrentPosition(session)
      maybeSyncProgress("close", true, session) { result ->
        serviceScope.launch(Dispatchers.Main) {
          playbackMetrics.logSummary()
          if (isPlayerInitialized()) {
            stopPlayer()
            clearPlayerMediaItems()
            setPlayerNotInitialized()
          }
          currentPlaybackSession = null
          setLastKnownIsPlaying(false)
          notifyWidgetState(true)
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

  fun markPlaybackSessionAssigned() {
    sessionAssignTimestampMs = System.currentTimeMillis()
  }

  fun resetSessionAssignTimestamp() {
    sessionAssignTimestampMs = 0L
  }

  fun getClosePlaybackSignal(): CompletableDeferred<Unit>? = closePlaybackSignal
}
