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
import com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands
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
  private val isDisconnecting = AtomicBoolean(false)
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var mediaController: MediaController? = null
  private var activePlaybackSession: PlaybackSession? = null
  private var currentMediaPlayer: String? = null
  private var hasEmittedClose = false
  private var lastNotifiedIsPlaying: Boolean? = null
  private var forceNextPlayingStateDispatch = false
  @Volatile
  private var cachedPositionMs: Long = 0L
  @Volatile
  private var cachedMediaItemIndex: Int = 0
  @Volatile
  private var suppressNextSeekEvent = false

  private val setSleepTimerCommand = SessionCommand(SleepTimer.ACTION_SET, Bundle.EMPTY)
  private val cancelSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CANCEL, Bundle.EMPTY)
  private val adjustSleepTimerCommand = SessionCommand(SleepTimer.ACTION_ADJUST, Bundle.EMPTY)
  private val getSleepTimerTimeCommand = SessionCommand(SleepTimer.ACTION_GET_TIME, Bundle.EMPTY)
  private val checkAutoSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CHECK_AUTO, Bundle.EMPTY)
  private val forceSyncProgressCommand =
    SessionCommand(CustomCommands.SYNC_PROGRESS_FORCE, Bundle.EMPTY)

  var listener: Listener? = null
  private val progressUpdateIntervalMs = 1000L
  private var progressUpdaterScheduled = false

  private val progressUpdater = object : Runnable {
    override fun run() {
      mediaController?.let { controller ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
          cachedPositionMs = controller.currentPosition
        }
        emitMetadata(controller)
      }
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
        // Make disconnect synchronous to avoid races with new connection attempts
        disconnectControllerSync(controller)
        return
      }
      maybeEmitMediaPlayerFromExtras()
      notifyPlayingState(effectiveIsPlaying(player))
      cachedPositionMs = player.currentPosition
      cachedMediaItemIndex = player.currentMediaItemIndex
      mediaController?.let { emitMetadata(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      maybeEmitMediaPlayerFromExtras()
      val controller = mediaController
      val effective = controller?.let { effectiveIsPlaying(it) } ?: isPlaying
      notifyPlayingState(effective)
      if (controller != null) {
        cachedPositionMs = controller.currentPosition
        cachedMediaItemIndex = controller.currentMediaItemIndex
        emitMetadata(controller)
      }
      if (effective) {
        startProgressUpdates()
      } else {
        stopProgressUpdates()
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      val controller = mediaController ?: return
      maybeEmitMediaPlayerFromExtras()
      cachedPositionMs = controller.currentPosition
      cachedMediaItemIndex = controller.currentMediaItemIndex
      emitMetadata(controller)
      if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
        stopProgressUpdates()
      }
      when (playbackState) {
        Player.STATE_ENDED -> listener?.onPlaybackEnded()
      }
      notifyPlayingState(effectiveIsPlaying(controller))
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
      if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
        if (suppressNextSeekEvent) {
          suppressNextSeekEvent = false
        } else {
          listener?.onSeekCompleted(newPosition.positionMs, newPosition.mediaItemIndex)
        }
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
    val durationMs = computeAbsoluteDuration(controller)
    val currentMs = computeAbsolutePosition(controller)
    cachedPositionMs = currentMs
    cachedMediaItemIndex = controller.currentMediaItemIndex
    val metadata =
      PlaybackMetadata(durationMs / 1000.0, currentMs / 1000.0, controllerPlaybackState(controller))
    listener?.onMetadata(metadata)
  }

  fun forceNextPlayingStateDispatch() {
    forceNextPlayingStateDispatch = true
  }

  private fun notifyPlayingState(isPlaying: Boolean) {
    val shouldForce = forceNextPlayingStateDispatch
    if (!shouldForce && lastNotifiedIsPlaying == isPlaying) return
    lastNotifiedIsPlaying = isPlaying
    if (shouldForce) {
      forceNextPlayingStateDispatch = false
    }
    listener?.onPlayingUpdate(isPlaying)
  }

  private fun effectiveIsPlaying(player: Player): Boolean {
    if (player.isPlaying) return true
    return player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
  }

  private fun executeWithController(onReady: (MediaController) -> Unit) {
    val controller = mediaController
    if (controller != null) {
      onReady(controller)
      cachedPositionMs = controller.currentPosition
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
    lastNotifiedIsPlaying = null
    forceNextPlayingStateDispatch = false
    listener?.onPlaybackClosed()
  }

  private fun disconnectControllerSync(controller: MediaController) {
    if (!isDisconnecting.compareAndSet(false, true)) return
    try {
      runOnMainSync { handleControllerDisconnected(controller) }
    } finally {
      isDisconnecting.set(false)
    }
  }

  private fun runOnMainSync(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
      return
    }
    val latch = java.util.concurrent.CountDownLatch(1)
    mainHandler.post {
      try {
        block()
      } finally {
        latch.countDown()
      }
    }
    try {
      // Wait briefly; this keeps semantics synchronous without risking deadlock
      latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
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
    if (isDisconnecting.get()) {
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
          result?.let { notifyPlayingState(it.isPlaying) }
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
    mediaController?.let { disconnectControllerSync(it) }
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
      sendCommand(forceSyncProgressCommand, Bundle.EMPTY) { _ ->
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
      suppressNextSeekEvent = true
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
  }

  fun play() {
    forceNextPlayingStateDispatch = true
    mediaController?.play()
  }

  fun pause() {
    forceNextPlayingStateDispatch = true
    mediaController?.pause()
  }

  fun playPause(): Boolean {
    val controller = mediaController ?: return false
    forceNextPlayingStateDispatch = true
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
    val session = activePlaybackSession
    val controller = mediaController
    if (session == null || controller == null) return

    val clampedMs = positionMs.coerceIn(0L, session.totalDurationMs)
    session.currentTime = clampedMs / 1000.0

    val tracks = session.audioTracks
    if (tracks.isEmpty()) {
      controller.seekTo(clampedMs)
      return
    }

    val trackIndex = session.getCurrentTrackIndex().coerceIn(0, controller.mediaItemCount - 1)
    val positionInTrack = session.getCurrentTrackTimeMs()

    controller.seekTo(trackIndex, positionInTrack)
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

  fun closePlayback(onComplete: ((Boolean) -> Unit)? = null) {
    val closeCommand = SessionCommand(CustomCommands.CLOSE_PLAYBACK, Bundle.EMPTY)
    sendCommand(closeCommand, Bundle.EMPTY) { result ->
      onComplete?.invoke(result.resultCode == SessionResult.RESULT_SUCCESS)
    }
  }

  fun setPlaybackSpeed(speed: Float) {
    mediaController?.setPlaybackSpeed(speed)
  }

  fun currentPosition(): Long {
    val controller = mediaController
    return if (controller != null && Looper.myLooper() == Looper.getMainLooper()) {
      val absolute = computeAbsolutePosition(controller)
      cachedPositionMs = absolute
      absolute
    } else {
      cachedPositionMs
    }
  }

  fun bufferedPosition(): Long {
    val controller = mediaController
    if (controller != null) {
      val offset =
        activePlaybackSession?.getTrackStartOffsetMs(controller.currentMediaItemIndex) ?: 0L
      return (controller.bufferedPosition + offset).coerceAtLeast(0L)
    }
    return currentPosition()
  }

  fun isPlaying(): Boolean = mediaController?.isPlaying ?: false

  fun playbackState(): Int = mediaController?.playbackState ?: Player.STATE_IDLE

  fun currentMediaItemIndex(): Int {
    val controller = mediaController
    return if (controller != null && Looper.myLooper() == Looper.getMainLooper()) {
      controller.currentMediaItemIndex.also { cachedMediaItemIndex = it }
    } else {
      cachedMediaItemIndex.takeIf { it >= 0 }
        ?: activePlaybackSession?.getCurrentTrackIndex()
        ?: 0
    }
  }

  fun duration(): Long {
    val sessionDuration = activePlaybackSession?.totalDurationMs ?: 0L
    val controllerDuration = mediaController?.duration ?: C.TIME_UNSET
    return when {
      sessionDuration > 0 -> sessionDuration
      controllerDuration != C.TIME_UNSET -> controllerDuration
      else -> 0L
    }
  }

  private fun computeAbsolutePosition(controller: MediaController): Long {
    val offset =
      activePlaybackSession?.getTrackStartOffsetMs(controller.currentMediaItemIndex) ?: 0L
    return (controller.currentPosition + offset).coerceAtLeast(0L)
  }

  private fun computeAbsoluteDuration(controller: MediaController): Long {
    val sessionDuration = activePlaybackSession?.totalDurationMs ?: 0L
    val controllerDuration = controller.duration.takeIf { it > 0 } ?: 0L
    return when {
      sessionDuration > 0 -> sessionDuration
      controllerDuration > 0 -> controllerDuration
      else -> 0L
    }
  }

  private fun ensureServiceStarted() {
    val intent = Intent(context, Media3PlaybackService::class.java)
    ContextCompat.startForegroundService(context, intent)
  }
}
