package com.audiobookshelf.app.player.media3

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
import com.audiobookshelf.app.player.Media3PlaybackService
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.toPlayerMediaItems
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
/**
 * Controls Media3 playback via MediaController, handling session connections and command execution.
 * Manages playback state, metadata, and provides callbacks for UI updates and error handling.
 */
class PlaybackController(private val context: Context) {
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

  private val TAG = "PlaybackController"

  private val mainHandler = Handler(Looper.getMainLooper())
  private val isConnectionInProgress = AtomicBoolean(false)
  private val isDisconnectionInProgress = AtomicBoolean(false)
  private var mediaControllerFuture: ListenableFuture<MediaController>? = null
  private var mediaController: MediaController? = null
  private var activePlaybackSession: PlaybackSession? = null
  private var currentMediaPlayer: String? = null
  private var hasEmittedCloseEvent = false
  private var lastNotifiedIsPlaying: Boolean? = null
  private var forceNextPlayingStateUpdate = false
  @Volatile
  private var lastKnownPositionMs: Long = 0L
  @Volatile
  private var lastKnownMediaItemIndex: Int = 0

  private val setSleepTimerCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_SET)
  private val cancelSleepTimerCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_CANCEL)
  private val adjustSleepTimerCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_ADJUST)
  private val getSleepTimerTimeCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_GET_TIME)

  private val forceSyncProgressCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SYNC_PROGRESS_FORCE)
  private val markUiPlaybackEventCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.MARK_UI_PLAYBACK_EVENT)

  var listener: Listener? = null
  private var isProgressUpdaterScheduled = false

  private val progressUpdater = object : Runnable {
    override fun run() {
      mediaController?.let { mediaController ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
          lastKnownPositionMs = mediaController.currentPosition
        }
        emitMetadata(mediaController)
      }
      if (isProgressUpdaterScheduled) {
        mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
      }
    }
  }

  /* ======== Connection & Lifecycle ======== */

  fun connect(onConnectionSuccess: (() -> Unit)? = null) {
    if (mediaController != null) {
      onConnectionSuccess?.let { mainHandler.post(it) }
      return
    }
    if (isDisconnectionInProgress.get()) {
      return
    }
    if (!isConnectionInProgress.compareAndSet(false, true)) {
      return
    }

    val applicationContext = context.applicationContext
    val sessionToken = SessionToken(
      applicationContext,
      ComponentName(applicationContext, Media3PlaybackService::class.java)
    )
    // Add connection hint to identify this as the app's UI controller
    // This allows the session to differentiate the app UI from other controllers (notification, wear, etc)
    val connectionHints = Bundle().apply {
      putBoolean(KEY_IS_APP_UI_CONTROLLER, true)
    }
    val future = MediaController.Builder(applicationContext, sessionToken)
      .setConnectionHints(connectionHints)
      .buildAsync()
    mediaControllerFuture = future

    Futures.addCallback(future, object : FutureCallback<MediaController> {
      override fun onSuccess(sessionResult: MediaController?) {
        mainHandler.post {
          isConnectionInProgress.set(false)
          mediaController = sessionResult
          mediaController?.addListener(controllerListener)
          hasEmittedCloseEvent = false
          maybeEmitMediaPlayerFromExtras()
          sessionResult?.let { listener?.onPlaybackSpeedChanged(it.playbackParameters.speed) }
          onConnectionSuccess?.invoke()
        }
      }

      override fun onFailure(throwable: Throwable) {
        Log.e(TAG, "MediaController connection failure", throwable)
        isConnectionInProgress.set(false)
        mainHandler.post {
          listener?.onPlaybackFailed(
            throwable.message ?: "Controller connection failed"
          )
        }
      }
    }, ContextCompat.getMainExecutor(context))
  }

  fun disconnect() {
    stopProgressUpdates()
    mediaControllerFuture?.cancel(true)
    mediaController?.let { disconnectControllerSync(it) }
    mediaController = null
    mediaControllerFuture = null
    isConnectionInProgress.set(false)
  }

  fun stopAndDisconnect() {
    try {
      mediaController?.let {
        try {
          it.pause()
        } catch (exception: Exception) {
          Log.w(TAG, "Failed to pause controller in stopAndDisconnect", exception)
        }
        try {
          it.stop()
        } catch (exception: Exception) {
          Log.w(TAG, "Failed to stop controller in stopAndDisconnect", exception)
        }
      }
    } catch (exception: Exception) {
      Log.e(TAG, "Exception in stopAndDisconnect", exception)
    }
    disconnect()
  }

  private fun handleControllerDisconnected(mediaController: MediaController) {
    if (hasEmittedCloseEvent) return
    hasEmittedCloseEvent = true
    stopProgressUpdates()
    mediaController.removeListener(controllerListener)
    mediaController.release()
    this@PlaybackController.mediaController = null
    mediaControllerFuture = null
    isConnectionInProgress.set(false)
    lastNotifiedIsPlaying = null
    forceNextPlayingStateUpdate = false
    activePlaybackSession = null
    listener?.onPlaybackClosed()
  }

  private fun disconnectControllerSync(mediaController: MediaController) {
    if (!isDisconnectionInProgress.compareAndSet(false, true)) return
    try {
      runOnMainSync { handleControllerDisconnected(mediaController) }
    } finally {
      isDisconnectionInProgress.set(false)
    }
  }

  private fun ensureServiceStarted() {
    val intent = Intent(context, Media3PlaybackService::class.java)
    ContextCompat.startForegroundService(context, intent)
  }

  /* ======== Player Events & Listeners ======== */

  private val controllerListener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      val mediaController = player as? MediaController
      if (mediaController != null && !mediaController.isConnected) {
        disconnectControllerSync(mediaController)
        return
      }
      maybeEmitMediaPlayerFromExtras()
      notifyPlayingState(effectiveIsPlaying(player))
      lastKnownPositionMs = player.currentPosition
      lastKnownMediaItemIndex = player.currentMediaItemIndex
      this@PlaybackController.mediaController?.let { emitMetadata(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      maybeEmitMediaPlayerFromExtras()
      val mediaController = this@PlaybackController.mediaController
      val effective = mediaController?.let { effectiveIsPlaying(it) } ?: isPlaying
      notifyPlayingState(effective)
      if (mediaController != null) {
        lastKnownPositionMs = mediaController.currentPosition
        lastKnownMediaItemIndex = mediaController.currentMediaItemIndex
        emitMetadata(mediaController)
      }
      if (effective) {
        startProgressUpdates()
      } else {
        stopProgressUpdates()
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      val mediaController = this@PlaybackController.mediaController ?: return
      maybeEmitMediaPlayerFromExtras()
      lastKnownPositionMs = mediaController.currentPosition
      lastKnownMediaItemIndex = mediaController.currentMediaItemIndex
      emitMetadata(mediaController)
      if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
        stopProgressUpdates()
      }
      when (playbackState) {
        Player.STATE_ENDED -> listener?.onPlaybackEnded()
        Player.STATE_IDLE -> {
          // If player is idle with no media items, playback has been closed
          if (mediaController.mediaItemCount == 0 && activePlaybackSession != null) {
            activePlaybackSession = null
            listener?.onPlaybackClosed()
          }
        }
        Player.STATE_BUFFERING,
        Player.STATE_READY -> Unit
      }
      notifyPlayingState(effectiveIsPlaying(mediaController))
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
        listener?.onSeekCompleted(newPosition.positionMs, newPosition.mediaItemIndex)
      }
    }
  }

  private fun maybeEmitMediaPlayerFromExtras() {
    val mediaPlayer =
      mediaController?.sessionExtras?.getString(KEY_MEDIA_PLAYER)
    if (!mediaPlayer.isNullOrEmpty() && mediaPlayer != currentMediaPlayer) {
      currentMediaPlayer = mediaPlayer
      activePlaybackSession?.let { session ->
        session.mediaPlayer = mediaPlayer
        listener?.onPlaybackSession(session)
      }
      listener?.onMediaPlayerChanged(mediaPlayer)
    }
  }

  /* ======== Playback Session & Preparation ======== */

  fun preparePlayback(
    playbackSession: PlaybackSession,
    playWhenReady: Boolean,
    playbackRate: Float?
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        TAG,
        "preparePlayback: session=${playbackSession.id} title=${playbackSession.displayTitle}"
      )
    }
    activePlaybackSession?.id == playbackSession.id

    activePlaybackSession = playbackSession
    listener?.onPlaybackSession(playbackSession)

    ensureServiceStarted()

    connect {
      val controller = mediaController ?: return@connect

      // Sync any pending progress before re-preparing to keep resume positions accurate.
      val latch = CountDownLatch(1)
      sendCommand(forceSyncProgressCommand, Bundle.EMPTY) { latch.countDown() }
      latch.await(CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS)

      val targetIsCast = playbackSession.isLocal && currentMediaPlayer == PLAYER_CAST
      val mediaItems =
        playbackSession.toPlayerMediaItems(context, preferServerUrisForCast = targetIsCast)
          .map { playerMediaItem ->
            MediaItem.Builder()
              .setUri(playerMediaItem.uri.toString())
              .setMediaId(playerMediaItem.mediaId)
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
          TAG,
          "Prepared playback for ${playbackSession.displayTitle} items=${mediaItems.size} startIndex=$trackIndex pos=$positionInTrack"
        )
      }
    }
  }

  /* ======== Playback Control ======== */

  fun play() {
    forceNextPlayingStateUpdate = true
    mediaController?.play()
  }

  fun pause() {
    forceNextPlayingStateUpdate = true
    mediaController?.pause()
  }

  fun playPause(): Boolean {
    val mediaController = this@PlaybackController.mediaController ?: return false
    forceNextPlayingStateUpdate = true
    return when {
      mediaController.isPlaying -> {
        mediaController.pause()
        false
      }

      else -> {
        mediaController.play()
        true
      }
    }
  }

  fun seekTo(positionMs: Long) {
    val session = activePlaybackSession ?: return
    val controller = mediaController ?: return

    val clampedPositionMs = positionMs.coerceIn(0L, session.totalDurationMs)
    session.currentTime = clampedPositionMs / 1000.0

    val audioTracks = session.audioTracks
    if (audioTracks.isEmpty()) {
      controller.seekTo(clampedPositionMs)
      return
    }

    val currentTrackIndex =
      session.getCurrentTrackIndex().coerceIn(0, controller.mediaItemCount - 1)
    val currentTrackPositionMs = session.getCurrentTrackTimeMs()

    controller.seekTo(currentTrackIndex, currentTrackPositionMs)
  }

  fun seekBy(seekDeltaMs: Long) {
    val controller = mediaController ?: return
    val targetPositionMs = (controller.currentPosition + seekDeltaMs).coerceAtLeast(0L)
    controller.seekTo(targetPositionMs)
  }

  fun setPlaybackSpeed(speed: Float) {
    mediaController?.setPlaybackSpeed(speed)
  }

  fun closePlayback(onCommandComplete: ((Boolean) -> Unit)? = null) {
    val closeCommand = PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CLOSE_PLAYBACK)
    sendCommand(closeCommand, Bundle.EMPTY) { sessionResult ->
      onCommandComplete?.invoke(sessionResult.resultCode == SessionResult.RESULT_SUCCESS)
    }
  }

  /* ======== Sleep Timer Control ======== */

  fun setSleepTimer(
    durationMs: Long,
    isRelativeToChapter: Boolean,
    playbackSessionId: String?,
    onCompletion: (Boolean) -> Unit
  ) {
    val commandArgs = Bundle().apply {
      putLong(PlaybackConstants.SleepTimer.EXTRA_TIME_MS, durationMs)
      putBoolean(PlaybackConstants.SleepTimer.EXTRA_IS_CHAPTER, isRelativeToChapter)
      playbackSessionId?.let { putString(PlaybackConstants.SleepTimer.EXTRA_SESSION_ID, it) }
    }
    sendCommand(setSleepTimerCommand, commandArgs) { sessionResult ->
      onCompletion(sessionResult.resultCode == SessionResult.RESULT_SUCCESS)
    }
  }

  fun getSleepTimerTime(onCompletion: (Long) -> Unit) {
    sendCommand(getSleepTimerTimeCommand, Bundle()) { sessionResult ->
      onCompletion(sessionResult.extras.getLong(PlaybackConstants.SleepTimer.EXTRA_TIME_MS, 0L))
    }
  }

  fun increaseSleepTimer(deltaMs: Long) {
    if (deltaMs <= 0L) return
    val commandArgs = Bundle().apply {
      putLong(PlaybackConstants.SleepTimer.EXTRA_ADJUST_DELTA, deltaMs)
      putBoolean(PlaybackConstants.SleepTimer.EXTRA_ADJUST_INCREASE, true)
    }
    sendCommand(adjustSleepTimerCommand, commandArgs, null)
  }

  fun decreaseSleepTimer(deltaMs: Long) {
    if (deltaMs <= 0L) return
    val commandArgs = Bundle().apply {
      putLong(PlaybackConstants.SleepTimer.EXTRA_ADJUST_DELTA, deltaMs)
      putBoolean(PlaybackConstants.SleepTimer.EXTRA_ADJUST_INCREASE, false)
    }
    sendCommand(adjustSleepTimerCommand, commandArgs, null)
  }

  fun cancelSleepTimer() {
    sendCommand(cancelSleepTimerCommand, Bundle(), null)
  }

  /* ======== Progress & Metadata Updates ======== */

  private fun startProgressUpdates() {
    if (isProgressUpdaterScheduled) return
    isProgressUpdaterScheduled = true
    mainHandler.post(progressUpdater)
  }

  private fun stopProgressUpdates() {
    if (!isProgressUpdaterScheduled) return
    isProgressUpdaterScheduled = false
    mainHandler.removeCallbacks(progressUpdater)
  }

  private fun emitMetadata(mediaController: MediaController) {
    val durationMs = computeAbsoluteDuration(mediaController)
    val currentMs = computeAbsolutePosition(mediaController)
    lastKnownPositionMs = currentMs
    lastKnownMediaItemIndex = mediaController.currentMediaItemIndex
    val metadata =
      PlaybackMetadata(
        durationMs / 1000.0,
        currentMs / 1000.0,
        controllerPlaybackState(mediaController)
      )
    listener?.onMetadata(metadata)
  }

  fun forceNextPlayingStateDispatch() {
    forceNextPlayingStateUpdate = true
  }

  private fun notifyPlayingState(isPlaying: Boolean) {
    val shouldForce = forceNextPlayingStateUpdate
    if (!shouldForce && lastNotifiedIsPlaying == isPlaying) return
    lastNotifiedIsPlaying = isPlaying
    if (shouldForce) {
      forceNextPlayingStateUpdate = false
    }
    listener?.onPlayingUpdate(isPlaying)
  }

  fun forceSyncProgress(onCommandComplete: (() -> Unit)? = null) {
    sendCommand(forceSyncProgressCommand, Bundle.EMPTY) {
      onCommandComplete?.invoke()
    }
  }

  fun markNextUiPlaybackEvent() {
    sendCommand(markUiPlaybackEventCommand, Bundle.EMPTY, null)
  }

  /* ======== State Query Functions ======== */

  fun currentPosition(): Long {
    val mediaController = this@PlaybackController.mediaController
    return if (mediaController != null && Looper.myLooper() == Looper.getMainLooper()) {
      val absolutePositionMs = computeAbsolutePosition(mediaController)
      lastKnownPositionMs = absolutePositionMs
      absolutePositionMs
    } else {
      lastKnownPositionMs
    }
  }

  fun bufferedPosition(): Long {
    val mediaController = this@PlaybackController.mediaController
    if (mediaController != null) {
      val trackStartOffsetMs =
        activePlaybackSession?.getTrackStartOffsetMs(mediaController.currentMediaItemIndex) ?: 0L
      return (mediaController.bufferedPosition + trackStartOffsetMs).coerceAtLeast(0L)
    }
    return currentPosition()
  }

  fun isPlaying(): Boolean = mediaController?.isPlaying ?: false

  fun currentMediaItemIndex(): Int {
    val mediaController = this@PlaybackController.mediaController
    return if (mediaController != null && Looper.myLooper() == Looper.getMainLooper()) {
      mediaController.currentMediaItemIndex.also { lastKnownMediaItemIndex = it }
    } else {
      lastKnownMediaItemIndex.takeIf { it >= 0 }
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

  /* ======== Helper Functions ======== */

  private fun effectiveIsPlaying(player: Player): Boolean {
    if (player.isPlaying) return true
    return player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
  }

  private fun executeWithController(onControllerReady: (MediaController) -> Unit) {
    val mediaController = this@PlaybackController.mediaController
    if (mediaController != null) {
      onControllerReady(mediaController)
      lastKnownPositionMs = mediaController.currentPosition
      return
    }
    connect {
      this@PlaybackController.mediaController?.let(onControllerReady)
    }
  }

  private fun controllerPlaybackState(mediaController: MediaController): PlayerState {
    return when (mediaController.playbackState) {
      Player.STATE_READY -> PlayerState.READY
      Player.STATE_ENDED -> PlayerState.ENDED
      Player.STATE_BUFFERING -> PlayerState.BUFFERING
      else -> PlayerState.IDLE
    }
  }

  private fun computeAbsolutePosition(mediaController: MediaController): Long {
    val trackStartOffsetMs =
      activePlaybackSession?.getTrackStartOffsetMs(mediaController.currentMediaItemIndex) ?: 0L
    return (mediaController.currentPosition + trackStartOffsetMs).coerceAtLeast(0L)
  }

  private fun computeAbsoluteDuration(mediaController: MediaController): Long {
    val sessionDuration = activePlaybackSession?.totalDurationMs ?: 0L
    val controllerDuration = mediaController.duration.takeIf { it > 0 } ?: 0L
    return when {
      sessionDuration > 0 -> sessionDuration
      controllerDuration > 0 -> controllerDuration
      else -> 0L
    }
  }

  private fun runOnMainSync(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
      return
    }
    val latch = CountDownLatch(1)
    mainHandler.post {
      try {
        action()
      } finally {
        latch.countDown()
      }
    }
    try {
      latch.await(CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
    }
  }

  private fun sendCommand(
    command: SessionCommand,
    commandArgs: Bundle = Bundle(),
    onCommandComplete: ((SessionResult) -> Unit)? = null
  ) {
    executeWithController { mediaController ->
      val future = mediaController.sendCustomCommand(command, commandArgs)
      onCommandComplete?.let { callback ->
        Futures.addCallback(
          future,
          object : FutureCallback<SessionResult> {
            override fun onSuccess(sessionResult: SessionResult?) {
              callback(sessionResult ?: DEFAULT_SUCCESS_RESULT)
            }

            override fun onFailure(throwable: Throwable) {
              Log.e(TAG, "Custom command failure", throwable)

              callback(UNKNOWN_ERROR_RESULT)
            }
          },
          ContextCompat.getMainExecutor(context)
        )
      }
    }
  }

  companion object {
    private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
    private const val CONNECTION_TIMEOUT_SEC = 2L

    // Bundle keys
    private const val KEY_MEDIA_PLAYER = "media_player"
    private const val KEY_IS_APP_UI_CONTROLLER = "isAppUiController"

    private val DEFAULT_SUCCESS_RESULT = SessionResult(SessionResult.RESULT_SUCCESS)
    private val UNKNOWN_ERROR_RESULT = SessionResult(SessionError.ERROR_UNKNOWN)
  }
}
