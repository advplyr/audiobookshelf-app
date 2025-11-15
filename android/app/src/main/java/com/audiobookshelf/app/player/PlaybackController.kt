package com.audiobookshelf.app.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.PlaybackMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PlayerState
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level controller that wraps a MediaController connection to Media3PlaybackService.
 * Coordinates lifecycle, media item preparation, and propagates player events to the UI layer.
 */
class PlaybackController(private val context: Context) {
  interface Listener {
    fun onPlaybackSession(session: PlaybackSession)
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onPlaybackSpeedChanged(speed: Float)
    fun onPlaybackFailed(errorMessage: String)
    fun onPlaybackEnded()
    fun onSeekCompleted(positionMs: Long, mediaItemIndex: Int) {}
  }

  private val tag = "PlaybackController"

  private val mainHandler = Handler(Looper.getMainLooper())
  private val isConnecting = AtomicBoolean(false)
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var mediaController: MediaController? = null
  private var activePlaybackSession: PlaybackSession? = null

  private val setSleepTimerCommand = SessionCommand(Media3PlaybackService.CUSTOM_COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY)
  private val cancelSleepTimerCommand = SessionCommand(Media3PlaybackService.CUSTOM_COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY)
  private val adjustSleepTimerCommand = SessionCommand(Media3PlaybackService.CUSTOM_COMMAND_ADJUST_SLEEP_TIMER, Bundle.EMPTY)
  private val getSleepTimerTimeCommand = SessionCommand(Media3PlaybackService.CUSTOM_COMMAND_GET_SLEEP_TIMER_TIME, Bundle.EMPTY)
  private val checkAutoSleepTimerCommand = SessionCommand(Media3PlaybackService.CUSTOM_COMMAND_CHECK_AUTO_SLEEP_TIMER, Bundle.EMPTY)

  var listener: Listener? = null
  private val progressUpdateIntervalMs = 1000L
  private var progressUpdaterScheduled = false
  private val progressUpdater = object : Runnable {
    override fun run() {
      val controller = mediaController
      if (controller != null) {
        emitMetadata(controller)
      }
      if (progressUpdaterScheduled) {
        mainHandler.postDelayed(this, progressUpdateIntervalMs)
      }
    }
  }

  private val controllerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      listener?.onPlayingUpdate(isPlaying)
      mediaController?.let { emitMetadata(it) }
      if (isPlaying) {
        startProgressUpdates()
      } else {
        stopProgressUpdates()
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      val controller = mediaController ?: return
      emitMetadata(controller)
      if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
        stopProgressUpdates()
      }
      when (playbackState) {
        Player.STATE_ENDED -> listener?.onPlaybackEnded()
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      listener?.onPlaybackFailed(error.message ?: "Unknown playback error")
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      listener?.onPlaybackSpeedChanged(playbackParameters.speed)
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int
    ) {
      if (reason == Player.DISCONTINUITY_REASON_SEEK) {
        listener?.onSeekCompleted(newPosition.positionMs, newPosition.mediaItemIndex)
      }
    }
  }

  private fun startProgressUpdates() {
    if (progressUpdaterScheduled) return
    progressUpdaterScheduled = true
    mainHandler.post(progressUpdater)
  }

  private fun stopProgressUpdates() {
    if (!progressUpdaterScheduled) return
    progressUpdaterScheduled = false
    mainHandler.removeCallbacks(progressUpdater)
  }

  private fun emitMetadata(controller: MediaController) {
    val fallbackDuration = activePlaybackSession?.let { (it.getTotalDuration() * 1000).toLong() } ?: 0L
    val duration = controller.duration.takeIf { it > 0 } ?: fallbackDuration
    val current = controller.currentPosition
    val metadata = PlaybackMetadata(duration / 1000.0, current / 1000.0, controllerPlaybackState(controller))
    listener?.onMetadata(metadata)
  }

  private fun executeWithController(onReady: (MediaController) -> Unit) {
    val controller = mediaController
    if (controller != null) {
      onReady(controller)
      return
    }
    connect {
      mediaController?.let(onReady)
    }
  }

  private fun sendCommand(
    command: SessionCommand,
    args: Bundle = Bundle(),
    onComplete: ((SessionResult) -> Unit)? = null
  ) {
    executeWithController { controller ->
      val future = controller.sendCustomCommand(command, args)
      if (onComplete == null) {
        return@executeWithController
      }
      Futures.addCallback(future, object : FutureCallback<SessionResult> {
        override fun onSuccess(result: SessionResult?) {
          val resolved = result ?: SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
          mainHandler.post { onComplete(resolved) }
        }

        override fun onFailure(t: Throwable) {
          Log.e(tag, "Custom command failure", t)
          val fallback = SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
          mainHandler.post { onComplete(fallback) }
        }
      }, ContextCompat.getMainExecutor(context))
    }
  }

  private fun controllerPlaybackState(controller: MediaController): PlayerState {
    return when (controller.playbackState) {
      Player.STATE_READY -> PlayerState.READY
      Player.STATE_ENDED -> PlayerState.ENDED
      Player.STATE_BUFFERING -> PlayerState.BUFFERING
      else -> PlayerState.IDLE
    }
  }

  fun connect(onConnected: (() -> Unit)? = null) {
    if (mediaController != null) {
      onConnected?.let { mainHandler.post(it) }
      return
    }
    if (!isConnecting.compareAndSet(false, true)) {
      return
    }

    val sessionToken = SessionToken(context, ComponentName(context, Media3PlaybackService::class.java))
    controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

    Futures.addCallback(controllerFuture!!, object : FutureCallback<MediaController> {
      override fun onSuccess(result: MediaController?) {
        mainHandler.post {
          isConnecting.set(false)
          mediaController = result
          mediaController?.addListener(controllerListener)
          result?.let { listener?.onPlaybackSpeedChanged(it.playbackParameters.speed) }
          if (result?.isPlaying == true) {
            startProgressUpdates()
          }
          onConnected?.invoke()
        }
      }

      override fun onFailure(t: Throwable) {
        Log.e(tag, "MediaController connection failure", t)
        isConnecting.set(false)
        mainHandler.post { listener?.onPlaybackFailed(t.message ?: "Controller connection failed") }
      }
    }, ContextCompat.getMainExecutor(context))
  }

  fun disconnect() {
    stopProgressUpdates()
    controllerFuture?.cancel(true)
    mediaController?.removeListener(controllerListener)
    mediaController?.release()
    mediaController = null
    controllerFuture = null
    isConnecting.set(false)
  }

  fun preparePlayback(playbackSession: PlaybackSession, playWhenReady: Boolean, playbackRate: Float?) {
    activePlaybackSession = playbackSession
    listener?.onPlaybackSession(playbackSession)

    ensureServiceStarted()

    connect {
      val controller = mediaController ?: return@connect
      val mediaItems = playbackSession.toPlayerMediaItems(context).mapIndexed { index, playerMediaItem ->
        MediaItem.Builder()
          .setUri(playerMediaItem.uri)
          .setMediaId("${playbackSession.mediaItemId}_$index")
          .setMediaMetadata(
            MediaMetadata.Builder()
              .setTitle(playbackSession.displayTitle)
              .setArtist(playbackSession.displayAuthor)
              .setAlbumTitle(playbackSession.displayAuthor)
              .setArtworkUri(playerMediaItem.artworkUri)
              .build()
          )
          .build()
      }

      val trackIndex = playbackSession.getCurrentTrackIndex().coerceIn(0, mediaItems.lastIndex)
      val trackStartOffsetMs = playbackSession.getTrackStartOffsetMs(trackIndex)
      val positionInTrack = (playbackSession.currentTimeMs - trackStartOffsetMs).coerceAtLeast(0L)

      controller.setMediaItems(mediaItems, trackIndex, positionInTrack)
      controller.prepare()
      controller.playWhenReady = playWhenReady
      playbackRate?.let { controller.setPlaybackSpeed(it) }
      emitMetadata(controller)
      if (BuildConfig.DEBUG) {
        Log.d(tag, "Prepared playback for ${playbackSession.displayTitle}")
      }
    }
  }

  fun play() {
    mediaController?.play()
  }

  fun pause() {
    mediaController?.pause()
  }

  fun playPause(): Boolean {
    val controller = mediaController ?: return false
    return if (controller.isPlaying) {
      controller.pause()
      false
    } else {
      controller.play()
      true
    }
  }

  fun seekTo(positionMs: Long) {
    mediaController?.seekTo(positionMs)
  }

  fun seekBy(deltaMs: Long) {
    mediaController?.let { controller ->
      val target = (controller.currentPosition + deltaMs).coerceAtLeast(0L)
      controller.seekTo(target)
    }
  }

  fun setSleepTimer(timeMs: Long, isChapterTime: Boolean, playbackSessionId: String?, onResult: (Boolean) -> Unit) {
    val args = Bundle().apply {
      putLong(Media3PlaybackService.EXTRA_SLEEP_TIMER_TIME_MS, timeMs)
      putBoolean(Media3PlaybackService.EXTRA_SLEEP_TIMER_IS_CHAPTER, isChapterTime)
      playbackSessionId?.let { putString(Media3PlaybackService.EXTRA_SLEEP_TIMER_SESSION_ID, it) }
    }
    sendCommand(setSleepTimerCommand, args) { result ->
      onResult(result.resultCode == SessionResult.RESULT_SUCCESS)
    }
  }

  fun getSleepTimerTime(onResult: (Long) -> Unit) {
    sendCommand(getSleepTimerTimeCommand, Bundle()) { result ->
      val extras = result.extras ?: Bundle.EMPTY
      onResult(extras.getLong(Media3PlaybackService.EXTRA_SLEEP_TIMER_TIME_MS, 0L))
    }
  }

  fun increaseSleepTimer(amountMs: Long) {
    if (amountMs <= 0L) return
    val args = Bundle().apply {
      putLong(Media3PlaybackService.EXTRA_SLEEP_TIMER_ADJUST_DELTA, amountMs)
      putBoolean(Media3PlaybackService.EXTRA_SLEEP_TIMER_ADJUST_INCREASE, true)
    }
    sendCommand(adjustSleepTimerCommand, args, null)
  }

  fun decreaseSleepTimer(amountMs: Long) {
    if (amountMs <= 0L) return
    val args = Bundle().apply {
      putLong(Media3PlaybackService.EXTRA_SLEEP_TIMER_ADJUST_DELTA, amountMs)
      putBoolean(Media3PlaybackService.EXTRA_SLEEP_TIMER_ADJUST_INCREASE, false)
    }
    sendCommand(adjustSleepTimerCommand, args, null)
  }

  fun cancelSleepTimer() {
    sendCommand(cancelSleepTimerCommand, Bundle(), null)
  }

  fun checkAutoSleepTimer() {
    sendCommand(checkAutoSleepTimerCommand, Bundle(), null)
  }

  fun setPlaybackSpeed(speed: Float) {
    mediaController?.setPlaybackSpeed(speed)
  }

  fun currentPosition(): Long = mediaController?.currentPosition ?: 0L

  fun bufferedPosition(): Long = mediaController?.bufferedPosition ?: currentPosition()

  fun isPlaying(): Boolean = mediaController?.isPlaying ?: false

  fun playbackState(): Int = mediaController?.playbackState ?: Player.STATE_IDLE

  fun currentMediaItemIndex(): Int {
    return mediaController?.currentMediaItemIndex
      ?: activePlaybackSession?.getCurrentTrackIndex()
      ?: 0
  }

  fun duration(): Long {
    val controllerDuration = mediaController?.duration ?: C.TIME_UNSET
    return if (controllerDuration != C.TIME_UNSET) {
      controllerDuration
    } else {
      activePlaybackSession?.totalDurationMs ?: 0L
    }
  }

  private fun ensureServiceStarted() {
    val intent = Intent(context, Media3PlaybackService::class.java)
    ContextCompat.startForegroundService(context, intent)
  }
}
