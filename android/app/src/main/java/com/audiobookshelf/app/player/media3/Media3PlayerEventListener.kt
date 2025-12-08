package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

interface ListenerApi {
  val tag: String
  val playbackMetrics: PlaybackMetricsRecorder
  fun currentSession(): PlaybackSession?
  fun activePlayer(): Player
  fun isPlayerInitialized(): Boolean
  fun lastKnownIsPlaying(): Boolean
  fun setLastKnownIsPlaying(value: Boolean)
  fun updateCurrentPosition(sessionToUpdate: PlaybackSession? = null)
  fun maybeSyncProgress(
    changeReason: String,
    forceSync: Boolean,
    sessionToUpdate: PlaybackSession? = null,
    onSyncComplete: ((SyncResult?) -> Unit)?
  )

  fun progressSyncPlay(currentSession: PlaybackSession)
  fun onPlayStarted(currentSessionId: String)
  fun startPositionUpdates()
  fun stopPositionUpdates()
  fun notifyWidgetState()
  fun updatePlaybackSpeedButton(speed: Float)
  fun getErrorRetryJob(): Job?
  fun setErrorRetryJob(job: Job?)
  fun getPlaybackSessionAssignTimestampMs(): Long
  fun resetPlaybackSessionAssignTimestamp()
  val serviceScope: CoroutineScope
  val errorResetWindowMs: Long
  val retryBackoffStepMs: Long
  fun debug(message: () -> String)
  fun ensureAudioFocus(): Boolean
  fun abandonAudioFocus()
}

/**
 * Media3 Player.Listener implementation that handles playback events and coordinates with the service.
 * Manages play/pause state, error recovery with exponential backoff, and progress synchronization.
 */
class Media3PlayerEventListener(
  private val listener: ListenerApi,
  private val playerEventPipeline: Media3EventPipeline
) : Player.Listener {

  private var consecutiveErrorCount: Int = 0
  private var lastErrorTimeMs: Long = 0L

  override fun onAvailableCommandsChanged(commands: Player.Commands) {
    val availableCommandNames = mutableListOf<String>()
    if (commands.contains(Player.COMMAND_SEEK_BACK)) availableCommandNames.add("SEEK_BACK")
    if (commands.contains(Player.COMMAND_SEEK_FORWARD)) availableCommandNames.add("SEEK_FORWARD")
    if (commands.contains(Player.COMMAND_PLAY_PAUSE)) availableCommandNames.add("PLAY_PAUSE")
    if (commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) availableCommandNames.add("SEEK_IN_ITEM")
    if (commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) availableCommandNames.add("SEEK_TO_PREVIOUS")
    if (commands.contains(Player.COMMAND_SEEK_TO_NEXT)) availableCommandNames.add("SEEK_TO_NEXT")
    if (commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) availableCommandNames.add("PREV_ITEM")
    if (commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) availableCommandNames.add("NEXT_ITEM")
    if (commands.contains(Player.COMMAND_GET_DEVICE_VOLUME)) availableCommandNames.add("GET_DEV_VOL")
    if (commands.contains(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)) availableCommandNames.add("SET_DEV_VOL")
    if (commands.contains(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) availableCommandNames.add(
      "ADJ_DEV_VOL"
    )
    listener.debug { "AvailableCommandsChanged: ${availableCommandNames.joinToString(",")}" }
  }

  override fun onIsPlayingChanged(isNowPlaying: Boolean) {
    listener.debug {
      val player = if (listener.isPlayerInitialized()) listener.activePlayer() else null
      "onIsPlayingChanged: raw=$isNowPlaying playWhenReady=${player?.playWhenReady} state=${player?.playbackState}"
    }

    val activePlayer = if (listener.isPlayerInitialized()) listener.activePlayer() else null
    val isEffectivelyPlaying = when {
      isNowPlaying -> true
      activePlayer?.playWhenReady == true && activePlayer.playbackState == Player.STATE_BUFFERING -> true
      else -> false
    }

    if (listener.lastKnownIsPlaying() == isEffectivelyPlaying) return

    val currentSession = listener.currentSession()
    if (currentSession != null) {
      listener.updateCurrentPosition(currentSession)

      if (isEffectivelyPlaying) {
        if (!listener.ensureAudioFocus()) {
          listener.debug { "Audio focus not granted; pausing playback" }
          if (listener.isPlayerInitialized()) {
            listener.activePlayer().pause()
          }
          listener.setLastKnownIsPlaying(false)
          return
        }
        listener.getErrorRetryJob()?.cancel()
        listener.setErrorRetryJob(null)
        consecutiveErrorCount = 0
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
        listener.stopPositionUpdates()
        listener.startPositionUpdates()
      } else {
        listener.debug { "Playback stopped. Syncing progress." }
        listener.stopPositionUpdates()
        listener.currentSession()?.let { currentSession ->
          listener.maybeSyncProgress("pause", true, currentSession, null)
        }
        listener.abandonAudioFocus()
      }
    }

    listener.setLastKnownIsPlaying(isEffectivelyPlaying)
    listener.notifyWidgetState()
  }

  override fun onPlayWhenReadyChanged(isPlayWhenReady: Boolean, changeReason: Int) {
    if (BuildConfig.DEBUG) {
      listener.debug {
        "onPlayWhenReadyChanged: isPlayWhenReady=$isPlayWhenReady changeReason=$changeReason"
      }
    }
  }

  override fun onPlaybackStateChanged(state: Int) {
    listener.debug { "onPlaybackStateChanged: $state" }
    when (state) {
      Player.STATE_READY -> listener.playbackMetrics.recordFirstReadyIfUnset()
      Player.STATE_BUFFERING -> listener.playbackMetrics.recordBuffer()
      Player.STATE_ENDED -> {
        listener.debug { "Playback ended" }
        listener.playbackMetrics.logSummary()
        listener.currentSession()?.let { currentSession ->
          listener.updateCurrentPosition(currentSession)
          listener.maybeSyncProgress("finished", true, currentSession, null)
        }
        listener.notifyWidgetState()
      }

      Player.STATE_IDLE -> listener.debug { "Player idle" }
    }
  }

  override fun onPlayerError(playbackError: PlaybackException) {
    Log.e(listener.tag, "Player playbackError: ${playbackError.message}", playbackError)
    listener.playbackMetrics.recordError()

    val isErrorRecoverable = when (playbackError.errorCode) {
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_TIMEOUT,
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true

      else -> false
    }

    if (!isErrorRecoverable) {
      listener.debug { "Non-recoverable playbackError: ${playbackError.errorCodeName}" }
      consecutiveErrorCount = 0
      listener.getErrorRetryJob()?.cancel()
      listener.setErrorRetryJob(null)
      return
    }

    val currentTimeMs = System.currentTimeMillis()
    if (currentTimeMs - lastErrorTimeMs > listener.errorResetWindowMs) {
      consecutiveErrorCount = 0
    }
    lastErrorTimeMs = currentTimeMs
    consecutiveErrorCount++

    val maxRetryAttempts = 3
    if (consecutiveErrorCount > maxRetryAttempts) {
      Log.w(listener.tag, "Max retries ($maxRetryAttempts) exceeded for recoverable playbackError")
      return
    }

    listener.playbackMetrics.recordRecoverableRetry()

    // Exponential backoff: 1s, 2s, 4s
    val retryBackoffDelayMs =
      (listener.retryBackoffStepMs * (1 shl (consecutiveErrorCount - 1))).coerceAtMost(4 * listener.retryBackoffStepMs)
    listener.debug { "Recoverable playbackError (attempt $consecutiveErrorCount/$maxRetryAttempts), retrying in ${retryBackoffDelayMs}ms" }

    listener.getErrorRetryJob()?.cancel()
    listener.setErrorRetryJob(
      listener.serviceScope.launch {
        kotlinx.coroutines.delay(retryBackoffDelayMs)
        if (listener.isPlayerInitialized() && listener.currentSession() != null) {
          listener.debug { "Retrying playback after playbackError..." }
          val activePlayer = listener.activePlayer()
          activePlayer.prepare()
          if (listener.lastKnownIsPlaying()) {
            activePlayer.play()
          }
        }
      }
    )
  }

  override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
    listener.updatePlaybackSpeedButton(parameters.speed)
  }

  override fun onVolumeChanged(volume: Float) {
    listener.debug { "onVolumeChanged: $volume (player volume updated)" }
  }

  override fun onMediaItemTransition(newMediaItem: MediaItem?, changeReason: Int) {
    listener.debug { "onMediaItemTransition: changeReason=$changeReason" }
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
