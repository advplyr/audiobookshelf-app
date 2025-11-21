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
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.PlaybackMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PlayerState
import com.audiobookshelf.app.player.Media3PlaybackService.Companion.SleepTimer
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicBoolean

// Media3 controller/session APIs require UnstableApi opt-in.
@UnstableApi
class PlaybackController(private val context: Context) {
  private val mediaPlayerExtraKey = "media_player"
  private val PLAYER_CAST = "cast-player"

  interface Listener {
    fun onPlaybackSession(session: PlaybackSession)
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onPlaybackSpeedChanged(speed: Float)
    fun onPlaybackFailed(errorMessage: String)
    fun onPlaybackEnded()
    fun onPlaybackClosed() {}
    fun onMediaPlayerChanged(mediaPlayer: String) {}
    fun onSeekCompleted(positionMs: Long, mediaItemIndex: Int) {}
  }

  private val tag = "PlaybackController"

  private val mainHandler = Handler(Looper.getMainLooper())
  private val isConnecting = AtomicBoolean(false)
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var mediaController: MediaController? = null
  private var activePlaybackSession: PlaybackSession? = null
  private var currentMediaPlayer: String? = null
  private var hasEmittedClose = false

  private val setSleepTimerCommand = SessionCommand(SleepTimer.ACTION_SET, Bundle.EMPTY)
  private val cancelSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CANCEL, Bundle.EMPTY)
  private val adjustSleepTimerCommand = SessionCommand(SleepTimer.ACTION_ADJUST, Bundle.EMPTY)
  private val getSleepTimerTimeCommand = SessionCommand(SleepTimer.ACTION_GET_TIME, Bundle.EMPTY)
  private val checkAutoSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CHECK_AUTO, Bundle.EMPTY)

  var listener: Listener? = null
  private val progressUpdateIntervalMs = 1000L
  private var progressUpdaterScheduled = false

  private val progressUpdater = object : Runnable {
    override fun run() {
      mediaController?.let { emitMetadata(it) }
      if (progressUpdaterScheduled) {
        mainHandler.postDelayed(this, progressUpdateIntervalMs)
      }
    }
  }

  private fun maybeEmitMediaPlayerFromExtras() {
    val mediaPlayer =
      mediaController?.sessionExtras?.getString(mediaPlayerExtraKey)
    if (!mediaPlayer.isNullOrEmpty() && mediaPlayer != currentMediaPlayer) {
      currentMediaPlayer = mediaPlayer
      // Update the active session so UI consumers see the current player (cast vs local).
      activePlaybackSession?.let { session ->
        session.mediaPlayer = mediaPlayer
        listener?.onPlaybackSession(session)
      }
      listener?.onMediaPlayerChanged(mediaPlayer)
    }
  }


  private val controllerListener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      val controller = player as? MediaController
      if (controller != null && !controller.isConnected) {
        mainHandler.post { handleControllerDisconnected(controller) }
        return
      }
      maybeEmitMediaPlayerFromExtras()
      // Mirror legacy service behavior: resync playing state and metadata on any event.
      listener?.onPlayingUpdate(player.isPlaying)
      mediaController?.let { emitMetadata(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      maybeEmitMediaPlayerFromExtras()
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
      maybeEmitMediaPlayerFromExtras()
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

  companion object {
    private val DEFAULT_SUCCESS_RESULT = SessionResult(SessionResult.RESULT_SUCCESS)
    private val UNKNOWN_ERROR_RESULT = SessionResult(SessionError.ERROR_UNKNOWN)
  }

  private fun sendCommand(
    command: SessionCommand,
    args: Bundle = Bundle(),
    onComplete: ((SessionResult) -> Unit)? = null
  ) {
    executeWithController { controller ->
      val future = controller.sendCustomCommand(command, args)
      onComplete?.let { callback ->
        Futures.addCallback(
          future,
          object : FutureCallback<SessionResult> {
            override fun onSuccess(result: SessionResult?) {
              callback(result ?: DEFAULT_SUCCESS_RESULT)
            }

            override fun onFailure(t: Throwable) {
              Log.e(tag, "Custom command failure", t)

              callback(UNKNOWN_ERROR_RESULT)
            }
          },
          ContextCompat.getMainExecutor(context)
        )
      }
    }
  }


  private fun handleControllerDisconnected(controller: MediaController) {
    if (hasEmittedClose) return
    hasEmittedClose = true
    stopProgressUpdates()
    controller.removeListener(controllerListener)
    controller.release()
    mediaController = null
    controllerFuture = null
    isConnecting.set(false)
    listener?.onPlaybackClosed()
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

    val appContext = context.applicationContext
    val sessionToken = SessionToken(appContext, ComponentName(appContext, Media3PlaybackService::class.java))
    controllerFuture  = MediaController.Builder(appContext, sessionToken).buildAsync()


    Futures.addCallback(controllerFuture!!, object : FutureCallback<MediaController> {
      override fun onSuccess(result: MediaController?) {
        mainHandler.post {
          isConnecting.set(false)
          mediaController = result
          mediaController?.addListener(controllerListener)
          hasEmittedClose = false
          maybeEmitMediaPlayerFromExtras()
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
    mediaController?.let { handleControllerDisconnected(it) }
    mediaController = null
    controllerFuture = null
    isConnecting.set(false)
  }

  fun stopAndDisconnect() {
    try {
      mediaController?.let {
        try {
          it.pause()
        } catch (_: Exception) {
        }
        try {
          it.stop()
        } catch (_: Exception) {
        }
      }
    } catch (_: Exception) {
    }
    disconnect()
  }

  fun preparePlayback(playbackSession: PlaybackSession, playWhenReady: Boolean, playbackRate: Float?) {
    if (BuildConfig.DEBUG) {
      Log.d(tag, "preparePlayback: session=${playbackSession.id} title=${playbackSession.displayTitle}")
    }
    activePlaybackSession = playbackSession
    listener?.onPlaybackSession(playbackSession)

    ensureServiceStarted()

    connect {
      val controller = mediaController ?: return@connect
      val targetIsCast = playbackSession.isLocal && currentMediaPlayer == PLAYER_CAST
      val mediaItems =
        playbackSession.toPlayerMediaItems(context, preferServerUrisForCast = targetIsCast)
          .map { playerMediaItem ->
        val mediaId = "${playbackSession.id}_${playerMediaItem.mediaId}"
        MediaItem.Builder()
          .setUri(playerMediaItem.uri)
          .setMediaId(mediaId)
          .setMimeType(playerMediaItem.mimeType)
          .setMediaMetadata(
            MediaMetadata.Builder()
              .setTitle(playbackSession.displayTitle)
              .setArtist(playbackSession.displayAuthor)
              .setAlbumArtist(playbackSession.displayAuthor)
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
        Log.d(
          tag,
          "Prepared playback for ${playbackSession.displayTitle} items=${mediaItems.size} startIndex=$trackIndex pos=$positionInTrack"
        )
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
    return when {
      controller.isPlaying -> {
        controller.pause()
        false
      }

      else -> {
        controller.play()
        true
      }
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
      putLong(SleepTimer.EXTRA_TIME_MS, timeMs)
      putBoolean(SleepTimer.EXTRA_IS_CHAPTER, isChapterTime)
      playbackSessionId?.let { putString(SleepTimer.EXTRA_SESSION_ID, it) }
    }
    sendCommand(setSleepTimerCommand, args) { result ->
      onResult(result.resultCode == SessionResult.RESULT_SUCCESS)
    }
  }

  fun getSleepTimerTime(onResult: (Long) -> Unit) {
    sendCommand(getSleepTimerTimeCommand, Bundle()) { result ->
      onResult(result.extras.getLong(SleepTimer.EXTRA_TIME_MS, 0L))
    }
  }

  fun increaseSleepTimer(amountMs: Long) {
    if (amountMs <= 0L) return
    val args = Bundle().apply {
      putLong(SleepTimer.EXTRA_ADJUST_DELTA, amountMs)
      putBoolean(SleepTimer.EXTRA_ADJUST_INCREASE, true)
    }
    sendCommand(adjustSleepTimerCommand, args, null)
  }

  fun decreaseSleepTimer(amountMs: Long) {
    if (amountMs <= 0L) return
    val args = Bundle().apply {
      putLong(SleepTimer.EXTRA_ADJUST_DELTA, amountMs)
      putBoolean(SleepTimer.EXTRA_ADJUST_INCREASE, false)
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
