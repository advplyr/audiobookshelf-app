package com.audiobookshelf.app.player.media3

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
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
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PLAYER_CAST
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.toMedia3MediaItems
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class PlaybackController(private val context: Context) {

  /** All callbacks are invoked on the main thread. */
  interface Listener {
    fun onPlaybackSession(session: PlaybackSession)
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onPlaybackSpeedChanged(speed: Float)
    fun onPlaybackFailed(errorMessage: String)
    fun onPlaybackEnded()
    fun onPlaybackClosed() {}
    fun onMediaPlayerChanged(mediaPlayer: String) {}
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val isOnMainThread get() = Looper.myLooper() == Looper.getMainLooper()
  private val isConnectionInProgress = AtomicBoolean(false)
  private val isDisconnectionInProgress = AtomicBoolean(false)
  private var mediaControllerFuture: ListenableFuture<MediaController>? = null
  private var mediaController: MediaController? = null
  private var activePlaybackSession: PlaybackSession? = null
  private var currentMediaPlayer: String? = null
  private var hasEmittedCloseEvent = false
  private var hasEmittedSessionToUi = false
  private var lastNotifiedIsPlaying: Boolean? = null
  private var forceNextPlayingStateUpdate = false
  private var isPreparingPlayback = false
  private var lastEmittedMetadata: PlaybackMetadata? = null

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
  private val resyncSleepTimerCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.RESYNC_SLEEP_TIMER)

  var listener: Listener? = null
  private var isProgressUpdaterScheduled = false

  private val progressUpdater = object : Runnable {
    override fun run() {
      mediaController?.let { emitMetadata(it) }
      if (isProgressUpdaterScheduled) {
        mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
      }
    }
  }


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
    // The app UI always receives seek commands; external controllers follow the user's setting.
    val connectionHints = Bundle().apply {
      putBoolean(PlaybackConstants.KEY_IS_APP_UI_CONTROLLER, true)
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
          sessionResult?.let {
            maybeAttachToServiceSession(it)
            maybeClearStaleUiSession(it)
          }
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

  private fun disconnect() {
    stopProgressUpdates()
    mediaControllerFuture?.cancel(true)
    mediaController?.let { disconnectControllerSync(it, playbackEnded = false) }
    mediaController = null
    mediaControllerFuture = null
    isConnectionInProgress.set(false)
  }

  /** Releases the UI's controller without stopping playback. */
  fun releaseController() {
    disconnect()
  }

  /** Losing the UI's controller is not the end of playback; only a real close reports one. */
  private fun handleControllerDisconnected(
    mediaController: MediaController,
    playbackEnded: Boolean
  ) {
    if (hasEmittedCloseEvent) return
    stopProgressUpdates()
    mediaController.removeListener(controllerListener)
    mediaController.release()
    this@PlaybackController.mediaController = null
    mediaControllerFuture = null
    isConnectionInProgress.set(false)
    isPreparingPlayback = false
    lastNotifiedIsPlaying = null
    forceNextPlayingStateUpdate = false
    if (playbackEnded) {
      hasEmittedCloseEvent = true
      activePlaybackSession = null
      listener?.onPlaybackClosed()
    }
  }

  private fun disconnectControllerSync(mediaController: MediaController, playbackEnded: Boolean) {
    if (!isDisconnectionInProgress.compareAndSet(false, true)) return
    try {
      runOnMainSync { handleControllerDisconnected(mediaController, playbackEnded) }
    } finally {
      isDisconnectionInProgress.set(false)
    }
  }


  private val controllerListener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      val mediaController = player as? MediaController
      if (mediaController != null && !mediaController.isConnected) {
        disconnectControllerSync(mediaController, playbackEnded = true)
        return
      }
      maybeEmitMediaPlayerFromExtras()
      notifyPlayingState(effectiveIsPlaying(player))
      this@PlaybackController.mediaController?.let {
        emitMetadata(it)
        // Covers playback started outside the app (e.g. Android Auto) while connected
        maybeAttachToServiceSession(it)
      }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      val controller = mediaController

      if (controller != null) {
        updateStateSnapshot(controller)
      }

      val isEffectivelyPlaying = controller?.let { effectiveIsPlaying(it) } ?: isPlaying

      notifyPlayingState(isEffectivelyPlaying)

      if (isEffectivelyPlaying) {
        startProgressUpdates()
      } else {
        stopProgressUpdates()
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      val controller = mediaController ?: return

      updateStateSnapshot(controller)

      when (playbackState) {
        Player.STATE_BUFFERING,
        Player.STATE_READY -> {
          isPreparingPlayback = false
        }

        Player.STATE_ENDED -> {
          stopProgressUpdates()
          listener?.onPlaybackEnded()
        }
        Player.STATE_IDLE -> {
          handleIdleState(controller)
        }
      }

      notifyPlayingState(effectiveIsPlaying(controller))
      if (effectiveIsPlaying(controller)) startProgressUpdates()
    }

    private fun updateStateSnapshot(controller: MediaController) {
      maybeEmitMediaPlayerFromExtras()
      emitMetadata(controller)
    }

    private fun handleIdleState(controller: Player) {
      // A cast handoff also passes through IDLE with playWhenReady still set while the queue
      // moves to the receiver, so this guard is what stops it being treated as a close.
      if (isPreparingPlayback || controller.playWhenReady) return

      stopProgressUpdates()

      if (controller.mediaItemCount == 0 && activePlaybackSession != null) {
        activePlaybackSession = null
        listener?.onPlaybackClosed()
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      isPreparingPlayback = false

      if (BuildConfig.DEBUG) {
        Log.e(TAG, "Player error: ${error.message}", error)
      }

      val isNetworkError = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true

        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
          error.cause?.javaClass?.simpleName == "StuckPlayerException"
        }

        else -> false
      }

      if (isNetworkError) {
        debugLog(TAG) { "Network error - Media3 LoadErrorHandlingPolicy will retry automatically" }
      } else {
        debugLog(TAG) { "Fatal error: ${error.errorCodeName}" }
        listener?.onPlaybackFailed(error.message ?: "Playback error")
      }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      listener?.onPlaybackSpeedChanged(playbackParameters.speed)
    }

  }

  // Sessions started outside the app UI are published through DeviceManager. Match the session
  // id against the loaded queue so a stale persisted session cannot be attached.
  private fun maybeAttachToServiceSession(mediaController: MediaController) {
    // preparePlayback sets the new session before the service reloads the queue; don't let the
    // still-loaded old queue re-attach us to the outgoing session
    if (isPreparingPlayback) return
    if (mediaController.mediaItemCount == 0) return
    val currentMediaId = mediaController.currentMediaItem?.mediaId ?: return
    val attachedSessionId = activePlaybackSession?.id
    if (attachedSessionId != null && currentMediaId.startsWith(attachedSessionId)) return
    val session = DeviceManager.getLastPlaybackSession() ?: return
    if (!currentMediaId.startsWith(session.id)) {
      Log.w(
        TAG,
        "maybeAttachToServiceSession: loaded queue does not match last session ${session.id}"
      )
      return
    }
    activePlaybackSession = session
    emitPlaybackSession(session)
    emitMetadata(mediaController)
    notifyPlayingState(effectiveIsPlaying(mediaController))
    if (effectiveIsPlaying(mediaController)) startProgressUpdates()
  }

  // Drops the dedup caches, which hold what the previous webview consumed.
  private fun emitPlaybackSession(session: PlaybackSession) {
    hasEmittedSessionToUi = true
    hasEmittedCloseEvent = false
    lastEmittedMetadata = null
    forceNextPlayingStateUpdate = true
    listener?.onPlaybackSession(session)
  }

  // Capacitor retains onPlaybackSession until consumed, so a webview reloading inside a live
  // process shows a session already dropped here. Skipping a cold start avoids clearing the widget.
  private fun maybeClearStaleUiSession(mediaController: MediaController) {
    if (!hasEmittedSessionToUi) return
    if (isPreparingPlayback) return
    if (mediaController.mediaItemCount > 0) return
    if (activePlaybackSession != null) return
    if (hasEmittedCloseEvent) return
    hasEmittedCloseEvent = true
    listener?.onPlaybackClosed()
  }

  private fun maybeEmitMediaPlayerFromExtras() {
    val mediaPlayer =
      mediaController?.sessionExtras?.getString(PlaybackConstants.MEDIA_PLAYER)
    if (!mediaPlayer.isNullOrEmpty() && mediaPlayer != currentMediaPlayer) {
      currentMediaPlayer = mediaPlayer
      if (hasLoadedQueue()) {
        activePlaybackSession?.let { session ->
          session.mediaPlayer = mediaPlayer
          emitPlaybackSession(session)
        }
      }
      listener?.onMediaPlayerChanged(mediaPlayer)
    }
  }


  fun preparePlayback(
    playbackSession: PlaybackSession,
    playWhenReady: Boolean,
    playbackRate: Float?
  ) {
    isPreparingPlayback = true
    activePlaybackSession = playbackSession
    emitPlaybackSession(playbackSession)

    // Connecting the MediaController binds (and creates) the service; the service promotes
    // itself to foreground once playback starts. Starting it eagerly with
    // startForegroundService here risks ForegroundServiceDidNotStartInTimeException when the
    // app backgrounds before the player reaches READY.
    connect {
      val controller = mediaController ?: return@connect

      // A replacement session must wait for the outgoing session's final progress sync.
      if (controller.mediaItemCount > 0 && controller.currentMediaItem != null) {
        sendCommand(forceSyncProgressCommand, Bundle.EMPTY) {
          executeWithController { ctrl ->
            prepareSessionOnController(ctrl, playbackSession, playWhenReady, playbackRate)
          }
        }
      } else {
        prepareSessionOnController(controller, playbackSession, playWhenReady, playbackRate)
      }
    }
  }

  private fun prepareSessionOnController(
    controller: MediaController,
    playbackSession: PlaybackSession,
    playWhenReady: Boolean,
    playbackRate: Float?
  ) {
    val targetIsCast = playbackSession.isLocal && currentMediaPlayer == PLAYER_CAST
    val mediaItems =
      playbackSession.toMedia3MediaItems(context, preferServerUrisForCast = targetIsCast)
    if (mediaItems.isEmpty()) return

    val (trackIndex, positionInTrack) = PlaybackPositionModel(playbackSession, null)
      .seekTargetForSessionTime(mediaItems.lastIndex)

    controller.setMediaItems(mediaItems, trackIndex, positionInTrack)
    controller.prepare()
    controller.playWhenReady = playWhenReady
    playbackRate?.let { controller.setPlaybackSpeed(it) }
    emitMetadata(controller)
  }


  fun play() {
    forceNextPlayingStateUpdate = true
    mediaController?.play()
  }

  fun pause() {
    forceNextPlayingStateUpdate = true
    mediaController?.pause()
  }

  fun playPause(): Boolean {
    val controller = mediaController ?: return false
    forceNextPlayingStateUpdate = true
    return if (controller.isPlaying) {
      controller.pause()
      false
    } else {
      controller.play()
      true
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
    val targetPositionMs = (computeAbsolutePosition(controller) + seekDeltaMs).coerceAtLeast(0L)
    seekTo(targetPositionMs)
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


  // A cast handoff stops the local player only after handing state to the receiver, and nothing
  // distinguishes that from a real stop. Hold the last known state instead.
  private fun hasMeaningfulPlayerState(mediaController: MediaController): Boolean {
    val isTornDownWithSessionHeld =
      mediaController.playbackState == Player.STATE_IDLE &&
        mediaController.mediaItemCount == 0 &&
        activePlaybackSession != null
    return !isTornDownWithSessionHeld
  }

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
    if (!hasMeaningfulPlayerState(mediaController)) return

    val durationMs = computeAbsoluteDuration(mediaController)
    val currentMs = computeAbsolutePosition(mediaController)
    val metadata =
      PlaybackMetadata(
        durationMs / 1000.0,
        currentMs / 1000.0,
        controllerPlaybackState(mediaController)
      )
    // currentTime is reported in whole seconds, so consecutive ticks within the same second
    // (and repeated paused/buffering ticks) produce an identical payload. Skip the redundant
    // JSON serialization and JS-bridge dispatch when nothing changed.
    if (metadata == lastEmittedMetadata) return
    lastEmittedMetadata = metadata
    listener?.onMetadata(metadata)
  }

  fun resyncUiState() {
    lastEmittedMetadata = null
    forceNextPlayingStateUpdate = true
    mediaController?.let { controller ->
      // Re-announcing puts the UI into loading, which only emitMetadata clears.
      if (hasMeaningfulPlayerState(controller)) {
        activePlaybackSession?.let { emitPlaybackSession(it) }
      }
      emitMetadata(controller)
      notifyPlayingState(effectiveIsPlaying(controller))
    }
    sendCommand(resyncSleepTimerCommand, Bundle.EMPTY, null)
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


  fun currentPosition(): Long {
    val mediaController = mediaController ?: return 0L
    return computeAbsolutePosition(mediaController)
  }

  fun bufferedPosition(): Long {
    val mediaController = mediaController ?: return 0L
    val session = activePlaybackSession ?: return mediaController.bufferedPosition
    return PlaybackPositionModel.bookAbsoluteMsFor(
      session,
      currentMediaItemIndex(),
      mediaController.bufferedPosition
    )
  }

  fun isPlaying(): Boolean = mediaController?.isPlaying ?: false

  fun hasLoadedQueue(): Boolean = (mediaController?.mediaItemCount ?: 0) > 0

  fun currentMediaItemIndex(): Int {
    val mediaController = mediaController
      ?: return activePlaybackSession?.getCurrentTrackIndex() ?: 0
    return positionModel(mediaController)?.trackIndex()
      ?: mediaController.currentMediaItemIndex
  }

  private fun effectiveIsPlaying(player: Player): Boolean {
    if (player.isPlaying) return true
    return player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
  }

  private fun executeWithController(onControllerReady: (MediaController) -> Unit) {
    val mediaController = mediaController
    if (mediaController != null) {
      onControllerReady(mediaController)
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

  private fun positionModel(mediaController: MediaController): PlaybackPositionModel? =
    activePlaybackSession?.let { PlaybackPositionModel(it, mediaController) }

  private fun computeAbsolutePosition(mediaController: MediaController): Long =
    positionModel(mediaController)?.bookAbsoluteMsOrNull()
      ?: mediaController.currentPosition.coerceAtLeast(0L)

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
    if (isOnMainThread) {
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
    private const val TAG = "PlaybackController"
    private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
    private const val CONNECTION_TIMEOUT_SEC = 2L

    private val DEFAULT_SUCCESS_RESULT = SessionResult(SessionResult.RESULT_SUCCESS)
    private val UNKNOWN_ERROR_RESULT = SessionResult(SessionError.ERROR_UNKNOWN)
  }
}
