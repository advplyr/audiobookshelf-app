package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaEventManager
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
    fun progressSyncPause()
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
    fun onCastDeviceChanged(isCast: Boolean)
}

/**
 * Media3 Player.Listener implementation that handles playback events and coordinates with the service.
 * Manages play/pause state and progress synchronization.
 */
class Media3PlayerEventListener(
    private val serviceCallbacks: ListenerApi,
  private val playerEventPipeline: Media3EventPipeline
) : Player.Listener {

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
        serviceCallbacks.debug {
        "state=$stateLabel playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} buffered=${player.bufferedPercentage}%"
      }
    }
  }

    override fun onIsPlayingChanged(callbackIsPlaying: Boolean) {
        val isEffectivelyPlaying = serviceCallbacks.lastKnownIsPlaying()

        // Early exit if state hasn't changed - prevents redundant widget/sync operations.
        // We query the player's current state rather than trusting the callback parameter
    // because Media3 may fire this callback during transitions where playWhenReady=true
        // but playbackState=BUFFERING, which we consider "effectively playing".
        if (isEffectivelyPlaying==lastIsPlayingState) return

        val currentSession = serviceCallbacks.currentSession()
    if (currentSession != null) {
      if (isEffectivelyPlaying) {
          serviceCallbacks.onPlayStarted(currentSession.id)
          val sessionAssignmentTimestampMs = serviceCallbacks.getPlaybackSessionAssignTimestampMs()
        if (sessionAssignmentTimestampMs > 0L) {
            val playbackLatencyMs = System.currentTimeMillis() - sessionAssignmentTimestampMs
            serviceCallbacks.debug { "Ready latency after session assign: ${playbackLatencyMs}ms" }
            serviceCallbacks.resetPlaybackSessionAssignTimestamp()
        }
        playerEventPipeline.emitPlayEvent(currentSession)
          serviceCallbacks.progressSyncPlay(currentSession)
          if (serviceCallbacks.isPlayerInitialized()) {
              serviceCallbacks.activePlayer().volume = 1f
        }
        val pauseDurationMs =
          if (lastPauseTimestampMs > 0) System.currentTimeMillis() - lastPauseTimestampMs else 0L
        lastPauseTimestampMs = 0L
          serviceCallbacks.onPlaybackResumed(pauseDurationMs)
      } else {
          serviceCallbacks.debug { "Playback stopped. Syncing progress." }
          serviceCallbacks.progressSyncPause()
        lastPauseTimestampMs = System.currentTimeMillis()
      }
    }

        serviceCallbacks.notifyWidgetState()

        serviceCallbacks.debug { "PlayerListener: Notifying web app - isPlaying=$isEffectivelyPlaying" }
        MediaEventManager.clientEventEmitter?.onPlayingUpdate(isEffectivelyPlaying)

        lastIsPlayingState = isEffectivelyPlaying
  }

  override fun onPlaybackStateChanged(state: Int) {
    when (state) {
        Player.STATE_READY -> serviceCallbacks.playbackMetrics.recordFirstReadyIfUnset()
        Player.STATE_BUFFERING -> serviceCallbacks.playbackMetrics.recordBuffer()
      Player.STATE_ENDED -> {
          serviceCallbacks.playbackMetrics.logSummary()
          serviceCallbacks.currentSession()?.let { currentSession ->
              serviceCallbacks.maybeSyncProgress("finished", true, currentSession) {
                  serviceCallbacks.onPlaybackEnded(currentSession)
          }
        }
          serviceCallbacks.notifyWidgetState()
      }
      Player.STATE_IDLE -> Unit
    }
  }

  override fun onPlayerError(playbackError: PlaybackException) {
      Log.e(serviceCallbacks.tag, "Player error: ${playbackError.message}", playbackError)
      serviceCallbacks.playbackMetrics.recordError()

      val isTransientDecoderError =
          playbackError.errorCode==PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

      val shouldAttemptTranscodeFallback = !isTransientDecoderError &&
              serviceCallbacks.currentSession()?.let { it.isDirectPlay && !it.isLocal }==true

      if (shouldAttemptTranscodeFallback) {
          serviceCallbacks.handlePlaybackError(playbackError)
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
        serviceCallbacks.playbackMetrics.recordRecoverableRetry()
        serviceCallbacks.debug {
        "Network error - Media3 LoadErrorHandlingPolicy will retry automatically"
        }
    } else if (isTransientDecoderError) {
        serviceCallbacks.debug {
            "Transient decoder error - Android reclaimed resources, will recover on resume"
      }
    } else {
        serviceCallbacks.debug { "Fatal error: ${playbackError.errorCodeName}" }
    }
  }

  override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
      serviceCallbacks.updatePlaybackSpeedButton(parameters.speed)
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    changeReason: Int
  ) {
      serviceCallbacks.debug {
          "onPositionDiscontinuity: changeReason=$changeReason, oldPos=${oldPosition.positionMs}, newPos=${newPosition.positionMs}"
      }
    if (changeReason == Player.DISCONTINUITY_REASON_SEEK ||
      changeReason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
    ) {
        lastPauseTimestampMs = 0L
        serviceCallbacks.currentSession()?.let { currentSession ->
        val newTrackIndex = newPosition.mediaItemIndex
        val newPositionInTrackMs = newPosition.positionMs
        val newTrackStartOffsetMs = currentSession.getTrackStartOffsetMs(newTrackIndex)
        val newAbsolutePositionMs = newTrackStartOffsetMs + newPositionInTrackMs

        currentSession.currentTime = newAbsolutePositionMs / 1000.0
        playerEventPipeline.emitSeekEvent(currentSession, null)
      }
    }
  }

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
        val isCast = deviceInfo.playbackType==DeviceInfo.PLAYBACK_TYPE_REMOTE
        serviceCallbacks.debug { "Device changed: playbackType=${deviceInfo.playbackType}, isCast=$isCast" }
        serviceCallbacks.onCastDeviceChanged(isCast)
    }
}
