package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaEventManager
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
  fun updateCurrentPosition()
  fun maybeSyncProgress(reason: String, force: Boolean, onComplete: ((SyncResult?) -> Unit)?)
  fun onPlayStarted(sessionId: String)
  fun startPositionUpdates()
  fun stopPositionUpdates()
  fun notifyWidgetState()
  fun updatePlaybackSpeedButton(speed: Float)
  fun getErrorRetryJob(): Job?
  fun setErrorRetryJob(job: Job?)
  val serviceScope: CoroutineScope
  val errorResetWindowMs: Long
  val retryBackoffStepMs: Long
  fun debug(msg: () -> String)
}

class Media3PlayerEventListener(
  private val api: ListenerApi
) : Player.Listener {

  private var consecutiveErrorCount: Int = 0
  private var lastErrorTimeMs: Long = 0L

  override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
    val cmd = mutableListOf<String>()
    if (availableCommands.contains(Player.COMMAND_SEEK_BACK)) cmd.add("SEEK_BACK")
    if (availableCommands.contains(Player.COMMAND_SEEK_FORWARD)) cmd.add("SEEK_FORWARD")
    if (availableCommands.contains(Player.COMMAND_PLAY_PAUSE)) cmd.add("PLAY_PAUSE")
    if (availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) cmd.add("SEEK_IN_ITEM")
    if (availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) cmd.add("SEEK_TO_PREVIOUS")
    if (availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) cmd.add("SEEK_TO_NEXT")
    if (availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) cmd.add("PREV_ITEM")
    if (availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) cmd.add("NEXT_ITEM")
    if (availableCommands.contains(Player.COMMAND_GET_DEVICE_VOLUME)) cmd.add("GET_DEV_VOL")
    if (availableCommands.contains(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)) cmd.add("SET_DEV_VOL")
    if (availableCommands.contains(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) cmd.add("ADJ_DEV_VOL")
    api.debug { "AvailableCommandsChanged: ${cmd.joinToString(",")}" }
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    api.debug { "onIsPlayingChanged: $isPlaying" }

    if (api.lastKnownIsPlaying() == isPlaying) return

    val session = api.currentSession()
    if (session != null) {
      api.updateCurrentPosition()

      if (isPlaying) {
        api.getErrorRetryJob()?.cancel()
        api.setErrorRetryJob(null)
        consecutiveErrorCount = 0
        MediaEventManager.playEvent(session)
        api.onPlayStarted(session.id)
        if (api.isPlayerInitialized()) {
          api.activePlayer().volume = 1f
        }
        api.stopPositionUpdates()
        api.startPositionUpdates()
      } else {
        api.stopPositionUpdates()
        api.maybeSyncProgress("pause", true) { result ->
          MediaEventManager.pauseEvent(session, result)
        }
      }
    }

    api.setLastKnownIsPlaying(isPlaying)
    api.notifyWidgetState()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    api.debug { "onPlaybackStateChanged: $playbackState" }
    when (playbackState) {
      Player.STATE_READY -> api.playbackMetrics.recordFirstReadyIfUnset()
      Player.STATE_BUFFERING -> api.playbackMetrics.recordBuffer()
      Player.STATE_ENDED -> {
        api.debug { "Playback ended" }
        api.playbackMetrics.logSummary()
        api.currentSession()?.let { session ->
          api.updateCurrentPosition()
          api.maybeSyncProgress("ended", true) { result ->
            MediaEventManager.stopEvent(session, result)
          }
        }
        api.notifyWidgetState()
      }

      Player.STATE_IDLE -> {
        TODO()
      }
    }
  }

  override fun onPlayerError(error: PlaybackException) {
    Log.e(api.tag, "Player error: ${error.message}", error)
    api.playbackMetrics.recordError()

    val isRecoverable = when (error.errorCode) {
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_TIMEOUT,
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true

      else -> false
    }

    if (!isRecoverable) {
      api.debug { "Non-recoverable error: ${error.errorCodeName}" }
      consecutiveErrorCount = 0
      api.getErrorRetryJob()?.cancel()
      api.setErrorRetryJob(null)
      return
    }

    val now = System.currentTimeMillis()
    if (now - lastErrorTimeMs > api.errorResetWindowMs) {
      consecutiveErrorCount = 0
    }
    lastErrorTimeMs = now
    consecutiveErrorCount++

    val maxRetries = 3
    if (consecutiveErrorCount > maxRetries) {
      Log.w(api.tag, "Max retries ($maxRetries) exceeded for recoverable error")
      return
    }

    // Exponential backoff: 1s, 2s, 4s
    val backoffMs =
      (api.retryBackoffStepMs * (1 shl (consecutiveErrorCount - 1))).coerceAtMost(4 * api.retryBackoffStepMs)
    api.debug { "Recoverable error (attempt $consecutiveErrorCount/$maxRetries), retrying in ${backoffMs}ms" }

    api.getErrorRetryJob()?.cancel()
    api.setErrorRetryJob(
      api.serviceScope.launch {
        kotlinx.coroutines.delay(backoffMs)
        if (api.isPlayerInitialized() && api.currentSession() != null) {
          api.debug { "Retrying playback after error..." }
          val player = api.activePlayer()
          player.prepare()
          if (api.lastKnownIsPlaying()) {
            player.play()
          }
        }
      }
    )
  }

  override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
    api.updatePlaybackSpeedButton(playbackParameters.speed)
  }

  override fun onVolumeChanged(volume: Float) {
    api.debug { "onVolumeChanged: $volume (player volume updated)" }
  }

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    api.debug { "onMediaItemTransition: reason=$reason" }
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int
  ) {
    api.debug { "onPositionDiscontinuity: reason=$reason, oldPos=${oldPosition.positionMs}, newPos=${newPosition.positionMs}" }
    if (reason == Player.DISCONTINUITY_REASON_SEEK ||
      reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
    ) {
      api.currentSession()?.let { session ->
        val trackIndex = newPosition.mediaItemIndex
        val positionInTrack = newPosition.positionMs
        val trackStartOffset = session.getTrackStartOffsetMs(trackIndex)
        val absolutePositionMs = trackStartOffset + positionInTrack

        session.currentTime = absolutePositionMs / 1000.0

        MediaEventManager.seekEvent(session, null)
      }
    }
  }
}
