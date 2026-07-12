package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.audiobookshelf.app.media.MediaEventManager

/**
 * Media3 Player.Listener implementation that handles playback events and coordinates with the service.
 * Manages play/pause state and progress synchronization.
 */
class Media3PlayerEventListener(
  private val host: Media3ServiceHost,
  private val playerEventPipeline: Media3EventPipeline
) : Player.Listener {

  companion object {
    private const val TAG = "Media3PlayerListener"
  }

  private var lastPauseTimestampMs: Long = 0L
  private var lastIsPlayingState: Boolean = false

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
      host.debug {
        "state=$stateLabel playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} buffered=${player.bufferedPercentage}%"
      }
    }
  }

  override fun onIsPlayingChanged(callbackIsPlaying: Boolean) {
    val isEffectivelyPlaying = host.isEffectivelyPlaying()

    // Early exit if state hasn't changed - prevents redundant widget/sync operations.
    // We query the player's current state rather than trusting the callback parameter
    // because Media3 may fire this callback during transitions where playWhenReady=true
    // but playbackState=BUFFERING, which we consider "effectively playing".
    if (isEffectivelyPlaying == lastIsPlayingState) return

    val currentSession = host.currentSession()
    if (currentSession != null) {
      if (isEffectivelyPlaying) {
        host.onPlayStarted(currentSession.id)
        playerEventPipeline.emitPlayEvent(currentSession)
        host.progressSyncPlay(currentSession)
        if (host.isPlayerInitialized) {
          host.playerOrNull()?.volume = 1f
        }
        val pauseDurationMs =
          if (lastPauseTimestampMs > 0) System.currentTimeMillis() - lastPauseTimestampMs else 0L
        lastPauseTimestampMs = 0L
        host.handlePlaybackResumed(pauseDurationMs)
      } else {
        host.debug { "Playback stopped. Syncing progress." }
        host.progressSyncPause()
        lastPauseTimestampMs = System.currentTimeMillis()
      }
    }

    host.notifyWidgetState()

    host.debug { "PlayerListener: Notifying web app - isPlaying=$isEffectivelyPlaying" }
    MediaEventManager.clientEventEmitter?.onPlayingUpdate(isEffectivelyPlaying)

    lastIsPlayingState = isEffectivelyPlaying
  }

  override fun onPlaybackStateChanged(state: Int) {
    when (state) {
      Player.STATE_READY -> host.playbackMetrics.recordFirstReadyIfUnset()
      Player.STATE_BUFFERING -> host.playbackMetrics.recordBuffer()
      Player.STATE_ENDED -> {
        host.playbackMetrics.logSummary()
        host.currentSession()?.let { currentSession ->
          host.maybeSyncProgress("finished", true, currentSession) {
            host.handlePlaybackEnded(currentSession)
          }
        }
        host.notifyWidgetState()
      }
      Player.STATE_IDLE -> Unit
    }
  }

  override fun onPlayerError(playbackError: PlaybackException) {
    Log.e(TAG, "Player error: ${playbackError.message}", playbackError)
    host.playbackMetrics.recordError()

    val isTransientDecoderError =
      playbackError.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

    val shouldAttemptTranscodeFallback = !isTransientDecoderError &&
      host.currentSession()?.let { it.isDirectPlay && !it.isLocal } == true

    if (shouldAttemptTranscodeFallback) {
      host.handlePlaybackError(playbackError)
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
      host.playbackMetrics.recordRecoverableRetry()
      host.debug {
        "Network error - Media3 LoadErrorHandlingPolicy will retry automatically"
      }
    } else if (isTransientDecoderError) {
      host.debug {
        "Transient decoder error - Android reclaimed resources, will recover on resume"
      }
    } else {
      host.debug { "Fatal error: ${playbackError.errorCodeName}" }
      host.handleFatalPlaybackError(playbackError.message ?: "Playback error")
    }
  }

  override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
    host.updatePlaybackSpeedButton(parameters.speed)
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    changeReason: Int
  ) {
    host.debug {
      "onPositionDiscontinuity: changeReason=$changeReason, oldPos=${oldPosition.positionMs}, newPos=${newPosition.positionMs}"
    }
    if (changeReason == Player.DISCONTINUITY_REASON_SEEK ||
      changeReason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
    ) {
      lastPauseTimestampMs = 0L
      host.currentSession()?.let { currentSession ->
        val newTrackIndex = newPosition.mediaItemIndex
        val newPositionInTrackMs = newPosition.positionMs
        val newTrackStartOffsetMs = currentSession.getTrackStartOffsetMs(newTrackIndex)
        val newAbsolutePositionMs = newTrackStartOffsetMs + newPositionInTrackMs

        currentSession.currentTime = newAbsolutePositionMs / 1000.0
        // Refresh the chapter metadata now so it isn't stale until the next position tick.
        host.updateCurrentPosition(currentSession)
        playerEventPipeline.emitSeekEvent(currentSession, null)
      }
    }
  }

  override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
    val isCast = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
    host.debug { "Device changed: playbackType=${deviceInfo.playbackType}, isCast=$isCast" }
    host.handleCastDeviceChanged(isCast)
  }
}
