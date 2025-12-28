package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder

interface ListenerApi {
  val tag: String
  val playbackMetrics: PlaybackMetricsRecorder
  fun currentSession(): PlaybackSession?
  fun activePlayer(): Player
  fun isPlayerInitialized(): Boolean
  fun lastKnownIsPlaying(): Boolean
  fun updateCurrentPosition(sessionToUpdate: PlaybackSession? = null)
  fun maybeSyncProgress(
    changeReason: String,
    forceSync: Boolean,
    sessionToUpdate: PlaybackSession? = null,
    onSyncComplete: ((SyncResult?) -> Unit)?
  )

  fun progressSyncPlay(currentSession: PlaybackSession)
  fun onPlayStarted(currentSessionId: String)
  fun notifyWidgetState()
  fun updatePlaybackSpeedButton(speed: Float)
  fun getPlaybackSessionAssignTimestampMs(): Long
  fun resetPlaybackSessionAssignTimestamp()
  fun handlePlaybackError(playbackError: PlaybackException)
  fun onPlaybackEnded(session: PlaybackSession)
  fun onPlaybackResumed(pauseDurationMs: Long)
  fun debug(message: () -> String)
  fun currentMediaPlayerId(): String
}

/**
 * Media3 Player.Listener implementation that handles playback events and coordinates with the service.
 * Manages play/pause state and progress synchronization.
 */
class Media3PlayerEventListener(
  private val listener: ListenerApi,
  private val playerEventPipeline: Media3EventPipeline
) : Player.Listener {

  private var lastPauseTimestampMs: Long = 0L

  override fun onEvents(player: Player, events: Player.Events) {
    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
      events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
    ) {
      val stateLabel = when (player.playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> player.playbackState.toString()
      }
      listener.debug {
        "state=$stateLabel playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} buffered=${player.bufferedPercentage}%"
      }
    }
  }

  override fun onIsPlayingChanged(isNowPlaying: Boolean) {
    val isEffectivelyPlaying = listener.lastKnownIsPlaying()

    // Early exit if state hasn't changed - prevents redundant widget/sync operations
    // Note: We query the player's current state rather than trusting the callback parameter
    // because Media3 may fire this callback during transitions where playWhenReady=true
    // but playbackState=BUFFERING, which we consider "effectively playing"
    if (isEffectivelyPlaying == isNowPlaying) return

    val currentSession = listener.currentSession()
    if (currentSession != null) {
      listener.updateCurrentPosition(currentSession)

      if (isEffectivelyPlaying) {
        listener.onPlayStarted(currentSession.id)
        val sessionAssignmentTimestampMs = listener.getPlaybackSessionAssignTimestampMs()
        if (sessionAssignmentTimestampMs > 0L) {
          val playbackLatencyMs = System.currentTimeMillis() - sessionAssignmentTimestampMs
          listener.debug { "Ready latency after session assign: ${playbackLatencyMs}ms" }
          listener.resetPlaybackSessionAssignTimestamp()
        }
        playerEventPipeline.emitPlayEvent(currentSession)
        listener.progressSyncPlay(currentSession)
        if (listener.isPlayerInitialized()) {
          listener.activePlayer().volume = 1f
        }
        val pauseDurationMs =
          if (lastPauseTimestampMs > 0) System.currentTimeMillis() - lastPauseTimestampMs else 0L
        lastPauseTimestampMs = 0L
        listener.onPlaybackResumed(pauseDurationMs)
      } else {
        listener.debug { "Playback stopped. Syncing progress." }
        listener.currentSession()?.let { currentSession ->
          listener.maybeSyncProgress("pause", true, currentSession, null)
        }
        lastPauseTimestampMs = System.currentTimeMillis()
      }
    }

    listener.notifyWidgetState()

    // Notify web app about playing state change
    listener.debug { "PlayerListener: Notifying web app - isPlaying=$isEffectivelyPlaying" }
    com.audiobookshelf.app.media.MediaEventManager.clientEventEmitter?.onPlayingUpdate(
      isEffectivelyPlaying
    )
  }

  override fun onPlaybackStateChanged(state: Int) {
    when (state) {
      Player.STATE_READY -> listener.playbackMetrics.recordFirstReadyIfUnset()
      Player.STATE_BUFFERING -> listener.playbackMetrics.recordBuffer()
      Player.STATE_ENDED -> {
        listener.playbackMetrics.logSummary()
        listener.currentSession()?.let { currentSession ->
          listener.updateCurrentPosition(currentSession)
          listener.maybeSyncProgress("finished", true, currentSession) {
            listener.onPlaybackEnded(currentSession)
          }
        }
        listener.notifyWidgetState()
      }
      Player.STATE_IDLE -> Unit
    }
  }

  override fun onPlayerError(playbackError: PlaybackException) {
    Log.e(listener.tag, "Player error: ${playbackError.message}", playbackError)
    listener.playbackMetrics.recordError()

    listener.currentSession()?.takeIf { it.isDirectPlay && !it.isLocal }?.let {
      listener.handlePlaybackError(playbackError)
      return
    }

    val isNetworkError = when (playbackError.errorCode) {
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_TIMEOUT,
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
      PlaybackException.ERROR_CODE_UNSPECIFIED -> {
        playbackError.cause?.javaClass?.simpleName == "StuckPlayerException"
      }
      else -> false
    }

    if (isNetworkError) {
      listener.playbackMetrics.recordRecoverableRetry()
      listener.debug {
        "Network error - Media3 LoadErrorHandlingPolicy will retry automatically"
      }
    } else {
      listener.debug { "Fatal error: ${playbackError.errorCodeName}" }
    }
  }

  override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
    listener.updatePlaybackSpeedButton(parameters.speed)
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    changeReason: Int
  ) {
    listener.debug { "onPositionDiscontinuity: changeReason=$changeReason, oldPos=${oldPosition.positionMs}, newPos=${newPosition.positionMs}" }
    if (changeReason == Player.DISCONTINUITY_REASON_SEEK ||
      changeReason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
    ) {
      listener.currentSession()?.let { currentSession ->
        val newTrackIndex = newPosition.mediaItemIndex
        val newPositionInTrackMs = newPosition.positionMs
        val newTrackStartOffsetMs = currentSession.getTrackStartOffsetMs(newTrackIndex)
        val newAbsolutePositionMs = newTrackStartOffsetMs + newPositionInTrackMs

        currentSession.currentTime = newAbsolutePositionMs / 1000.0
        playerEventPipeline.emitSeekEvent(currentSession, null)
      }
    }
  }
}
