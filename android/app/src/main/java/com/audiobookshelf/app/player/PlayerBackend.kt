package com.audiobookshelf.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.PlaybackMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PlayerState
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.player.core.NetworkMonitor
import com.audiobookshelf.app.player.media3.PlaybackController

/**
 * Backend-agnostic command surface used by the AbsAudioPlayer JS bridge.
 *
 * The bridge holds exactly one implementation, chosen once at load time from
 * BuildConfig.USE_MEDIA3. Signatures are framework-free (PlaybackSession +
 * primitives + callbacks) so the bridge never touches ExoPlayer v2 or Media3
 * types directly. Removing the ExoPlayer v2 backend later is a single-class
 * deletion.
 */
interface PlayerBackend {
  /** Wire up event emitters. May complete asynchronously (ExoPlayer v2 waits for its foreground service). */
  fun initialize()

  fun getDeviceInfo(): DeviceInfo
  fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload

  /**
   * Stop/close the current playback session and invoke [onStopped] once the old
   * session's final progress sync has completed, so a new session can safely start.
   */
  fun stopPlayback(onStopped: () -> Unit)

  fun preparePlayback(session: PlaybackSession, playWhenReady: Boolean, playbackRate: Float)

  fun currentTimeSeconds(): Double
  fun bufferedTimeSeconds(): Double

  fun play()
  fun pause()
  /** @return true when the toggle resulted in playing state */
  fun playPause(): Boolean
  fun seekTo(timeMs: Long)
  fun seekForward(amountMs: Long)
  fun seekBackward(amountMs: Long)
  fun setPlaybackSpeed(speed: Float)
  fun closePlayback()

  fun setSleepTimer(timeMs: Long, isChapterTime: Boolean, onResult: (Boolean) -> Unit)
  fun getSleepTimerTime(onResult: (Long) -> Unit)
  fun increaseSleepTimer(timeMs: Long)
  fun decreaseSleepTimer(timeMs: Long)
  fun cancelSleepTimer()

  /** Re-sync playback state to the web UI after the app returns to the foreground. */
  fun onAppResume()
  fun onDestroy()

  /** ExoPlayer v2 needs its service for Cast session transfer; Media3 handles Cast internally. */
  fun castSessionService(): PlayerNotificationService?

  companion object {
    /** Delay before pushing state to the WebView so it has fully resumed. */
    const val RESUME_SYNC_DELAY_MS = 100L
  }
}

/**
 * ExoPlayer v2 backend delegating to [PlayerNotificationService]. The service is
 * created by MainActivity and arrives asynchronously via the plugin callback.
 */
class ExoV2PlayerBackend(
  private val mainActivity: MainActivity,
  private val clientEventEmitter: PlayerNotificationService.ClientEventEmitter
) : PlayerBackend {
  companion object {
    private const val TAG = "ExoV2PlayerBackend"
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private lateinit var playerNotificationService: PlayerNotificationService
  private val isServiceReady get() = ::playerNotificationService.isInitialized

  override fun initialize() {
    mainActivity.pluginCallback = {
      playerNotificationService = mainActivity.foregroundService
      playerNotificationService.clientEventEmitter = clientEventEmitter
      MediaEventManager.clientEventEmitter = clientEventEmitter

      playerNotificationService.setExternalPlaybackState(null)
      SleepTimerNotificationCenter.unregister()
    }
  }

  override fun getDeviceInfo(): DeviceInfo = playerNotificationService.getDeviceInfo()

  override fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload =
    playerNotificationService.getPlayItemRequestPayload(forceTranscode)

  override fun stopPlayback(onStopped: () -> Unit) {
    playerNotificationService.mediaProgressSyncer.stop { onStopped() }
  }

  override fun preparePlayback(session: PlaybackSession, playWhenReady: Boolean, playbackRate: Float) {
    if (session.isLocal) {
      playerNotificationService.mediaProgressSyncer.reset()
    } else {
      PlayerListener.lazyIsPlaying = false
    }
    playerNotificationService.preparePlayer(session, playWhenReady, playbackRate)
  }

  override fun currentTimeSeconds(): Double = playerNotificationService.getCurrentTimeSeconds()

  override fun bufferedTimeSeconds(): Double = playerNotificationService.getBufferedTimeSeconds()

  override fun play() = playerNotificationService.play()

  override fun pause() = playerNotificationService.pause()

  override fun playPause(): Boolean = playerNotificationService.playPause()

  override fun seekTo(timeMs: Long) = playerNotificationService.seekPlayer(timeMs)

  override fun seekForward(amountMs: Long) = playerNotificationService.seekForward(amountMs)

  override fun seekBackward(amountMs: Long) = playerNotificationService.seekBackward(amountMs)

  override fun setPlaybackSpeed(speed: Float) = playerNotificationService.setPlaybackSpeed(speed)

  override fun closePlayback() = playerNotificationService.closePlayback()

  override fun setSleepTimer(timeMs: Long, isChapterTime: Boolean, onResult: (Boolean) -> Unit) {
    val playbackSession = playerNotificationService.mediaProgressSyncer.currentPlaybackSession
      ?: playerNotificationService.currentPlaybackSession
    onResult(
      playerNotificationService.setManualSleepTimer(playbackSession?.id ?: "", timeMs, isChapterTime)
    )
  }

  override fun getSleepTimerTime(onResult: (Long) -> Unit) {
    onResult(playerNotificationService.getSleepTimerTime())
  }

  override fun increaseSleepTimer(timeMs: Long) = playerNotificationService.increaseSleepTimer(timeMs)

  override fun decreaseSleepTimer(timeMs: Long) = playerNotificationService.decreaseSleepTimer(timeMs)

  override fun cancelSleepTimer() = playerNotificationService.cancelSleepTimer()

  override fun onAppResume() {
    if (!isServiceReady || playerNotificationService.currentPlaybackSession == null) return
    mainHandler.postDelayed({
      playerNotificationService.sendClientMetadata(PlayerState.READY)
      playerNotificationService.sendCurrentSleepTimerState()
      playerNotificationService.mediaProgressSyncer.currentLocalMediaProgress?.let {
        playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
      }
    }, PlayerBackend.RESUME_SYNC_DELAY_MS)
  }

  override fun onDestroy() {
    // Foreground service lifecycle is owned by MainActivity
  }

  override fun castSessionService(): PlayerNotificationService? {
    if (!isServiceReady) {
      Log.w(TAG, "castSessionService: foreground service not ready")
      return null
    }
    return playerNotificationService
  }
}

/**
 * Media3 backend delegating to [PlaybackController]. Owns the Media3-side session
 * bookkeeping (active session, current media player id, widget snapshots) and
 * relays controller events to the shared client event emitter.
 */
@UnstableApi
class Media3PlayerBackend(
  private val context: Context,
  private val clientEventEmitter: PlayerNotificationService.ClientEventEmitter,
  private val sleepTimerNotifier: SleepTimerUiNotifier
) : PlayerBackend, PlaybackController.Listener {
  companion object {
    private const val TAG = "Media3PlayerBackend"
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val playbackController = PlaybackController(context)
  private var networkStateListener: NetworkMonitor.Listener? = null

  private var activePlaybackSession: PlaybackSession? = null
  private var lastKnownMediaPlayer: String? = null

  override fun getDeviceInfo(): DeviceInfo = PlaybackConstants.buildDeviceInfo(context)

  override fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    return PlayItemRequestPayload(
      mediaPlayer = PLAYER_MEDIA3,
      forceDirectPlay = !forceTranscode,
      forceTranscode = forceTranscode,
      deviceInfo = getDeviceInfo()
    )
  }

  override fun initialize() {
    MediaEventManager.clientEventEmitter = clientEventEmitter

    NetworkMonitor.initialize(context)
    if (networkStateListener == null) {
      val listener = NetworkMonitor.Listener { state ->
        clientEventEmitter.onNetworkMeteredChanged(state.isUnmetered)
      }
      networkStateListener = listener
      NetworkMonitor.addListener(listener)
    }

    playbackController.listener = this
    SleepTimerNotificationCenter.register(sleepTimerNotifier)
  }

  override fun stopPlayback(onStopped: () -> Unit) {
    // Await the close: the service syncs the old session to the server and tears down
    // (ending in stopSelf) before a new session starts, avoiding a race where the
    // deferred stop kills the freshly prepared playback.
    if (activePlaybackSession != null) {
      playbackController.closePlayback { onStopped() }
    } else {
      onStopped()
    }
  }

  override fun preparePlayback(session: PlaybackSession, playWhenReady: Boolean, playbackRate: Float) {
    playbackController.preparePlayback(session, playWhenReady, playbackRate)
  }

  override fun currentTimeSeconds(): Double = playbackController.currentPosition() / 1000.0

  override fun bufferedTimeSeconds(): Double = playbackController.bufferedPosition() / 1000.0

  override fun play() {
    playbackController.forceNextPlayingStateDispatch()
    playbackController.play()
  }

  override fun pause() {
    playbackController.forceNextPlayingStateDispatch()
    playbackController.pause()
  }

  override fun playPause(): Boolean {
    playbackController.forceNextPlayingStateDispatch()
    return playbackController.playPause()
  }

  override fun seekTo(timeMs: Long) = playbackController.seekTo(timeMs)

  override fun seekForward(amountMs: Long) = playbackController.seekBy(amountMs)

  override fun seekBackward(amountMs: Long) = playbackController.seekBy(-amountMs)

  override fun setPlaybackSpeed(speed: Float) = playbackController.setPlaybackSpeed(speed)

  override fun closePlayback() {
    playbackController.closePlayback { success ->
      if (!success) {
        Log.w(TAG, "closePlayback command returned failure")
      }
    }
  }

  override fun setSleepTimer(timeMs: Long, isChapterTime: Boolean, onResult: (Boolean) -> Unit) {
    val playbackSessionId = activePlaybackSession?.id
      ?: DeviceManager.getLastPlaybackSession()?.id
    playbackController.setSleepTimer(timeMs, isChapterTime, playbackSessionId, onResult)
  }

  override fun getSleepTimerTime(onResult: (Long) -> Unit) {
    playbackController.getSleepTimerTime(onResult)
  }

  override fun increaseSleepTimer(timeMs: Long) = playbackController.increaseSleepTimer(timeMs)

  override fun decreaseSleepTimer(timeMs: Long) = playbackController.decreaseSleepTimer(timeMs)

  override fun cancelSleepTimer() = playbackController.cancelSleepTimer()

  override fun onAppResume() {
    mainHandler.postDelayed({
      playbackController.resyncUiState()
    }, PlayerBackend.RESUME_SYNC_DELAY_MS)
  }

  override fun onDestroy() {
    try {
      playbackController.stopAndDisconnect()
    } catch (_: Exception) {
    }
    SleepTimerNotificationCenter.unregister()
    networkStateListener?.let { NetworkMonitor.removeListener(it) }
    networkStateListener = null
  }

  override fun castSessionService(): PlayerNotificationService? = null

  /* ======== PlaybackController.Listener (controller -> web UI / widget) ======== */

  override fun onPlaybackSession(session: PlaybackSession) {
    lastKnownMediaPlayer?.let { session.mediaPlayer = it }
    activePlaybackSession = session
    DeviceManager.setLastPlaybackSession(session)
    clientEventEmitter.onPlaybackSession(session)
    notifyWidgetState()
  }

  override fun onPlayingUpdate(isPlaying: Boolean) {
    clientEventEmitter.onPlayingUpdate(isPlaying)
    // Auto-rewind after a pause is handled by Media3PlaybackService.handlePlaybackResumed;
    // it must not also happen here or resumes would rewind twice.
    notifyWidgetState()
  }

  override fun onMetadata(metadata: PlaybackMetadata) {
    clientEventEmitter.onMetadata(metadata)
  }

  override fun onPlaybackSpeedChanged(speed: Float) {
    clientEventEmitter.onPlaybackSpeedChanged(speed)
  }

  override fun onPlaybackClosed() {
    lastKnownMediaPlayer = null
    clientEventEmitter.onPlaybackClosed()
    notifyWidgetState(isClosed = true)
  }

  override fun onMediaPlayerChanged(mediaPlayer: String) {
    lastKnownMediaPlayer = mediaPlayer
    activePlaybackSession?.let { session ->
      session.mediaPlayer = mediaPlayer
      clientEventEmitter.onPlaybackSession(session)
    }
    clientEventEmitter.onMediaPlayerChanged(mediaPlayer)
  }

  override fun onPlaybackFailed(errorMessage: String) {
    clientEventEmitter.onPlaybackFailed(errorMessage)
  }

  override fun onPlaybackEnded() {
    notifyWidgetState(isClosed = true)
    activePlaybackSession = null
    clientEventEmitter.onPlaybackClosed()
  }

  override fun onSeekCompleted(positionMs: Long, mediaItemIndex: Int) {
  }

  /* ======== Widget ======== */

  private fun notifyWidgetState(isClosed: Boolean = false) {
    val updater = DeviceManager.widgetUpdater ?: return
    val session = activePlaybackSession ?: return
    val snapshot = session.toWidgetSnapshot(
      context = context,
      isPlaying = playbackController.isPlaying(),
      isClosed = isClosed,
      positionOverrideMs = currentAbsolutePositionMs(session)
    )
    updater.onPlayerChanged(snapshot)
    if (isClosed) {
      updater.onPlayerClosed()
    }
  }

  private fun currentAbsolutePositionMs(session: PlaybackSession): Long {
    val trackIndex = playbackController.currentMediaItemIndex()
    val offsetMs = session.getTrackStartOffsetMs(trackIndex)
    return playbackController.currentPosition() + offsetMs
  }
}
