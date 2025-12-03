package com.audiobookshelf.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.SleepTimerHost
import com.audiobookshelf.app.managers.SleepTimerManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder
import com.audiobookshelf.app.player.media3.CustomMediaNotificationProvider
import com.audiobookshelf.app.player.media3.Media3AutoLibraryCoordinator
import com.audiobookshelf.app.player.media3.Media3BrowseTree
import com.audiobookshelf.app.player.media3.Media3PlaybackSpeedButtonProvider
import com.audiobookshelf.app.player.media3.PlaybackPipeline
import com.audiobookshelf.app.player.media3.ProgressSyncManager
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import com.audiobookshelf.app.server.ApiHandler
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.math.max


/**
 * Media3 playback service following MediaLibraryService architecture.
 * Handles local playback, session management, and native Media3 notifications.
 * Cast playback is handled via Media3 CastPlayer within this service.
 */

// Media3 cast/session/notification APIs are annotated @UnstableApi; opt-in is required here.
@UnstableApi
class Media3PlaybackService : MediaLibraryService() {
  companion object {
    private const val APP_PREFIX = "com.audiobookshelf.app.player"
    val TAG: String = Media3PlaybackService::class.java.simpleName
    private const val RESOLVED_CACHE_TTL_MS = 5_000L
    private const val RESOLVED_CACHE_LIMIT = 6
    private const val POSITION_UPDATE_INTERVAL_MS = 1_000L
    private const val SAVE_SYNC_INTERVAL_SECONDS = 15L
    private const val TASK_REMOVAL_CLOSE_TIMEOUT_MS = 5_000L
    private const val ERROR_RESET_WINDOW_MS = 30_000L
    private const val RETRY_BACKOFF_STEP_MS = 1_000L

    object Notification {
      const val CHANNEL_ID = "media3_playback_channel"
      const val ID = 100
    }

    object CustomCommands {
      const val CYCLE_PLAYBACK_SPEED = "$APP_PREFIX.CYCLE_PLAYBACK_SPEED"
      const val SEEK_BACK_INCREMENT = "$APP_PREFIX.SEEK_BACK_INCREMENT"
      const val SEEK_FORWARD_INCREMENT = "$APP_PREFIX.SEEK_FORWARD_INCREMENT"
      const val SEEK_TO_PREVIOUS_CHAPTER = "$APP_PREFIX.SEEK_TO_PREVIOUS_CHAPTER"
      const val SEEK_TO_NEXT_CHAPTER = "$APP_PREFIX.SEEK_TO_NEXT_CHAPTER"
      const val SEEK_TO_CHAPTER = "$APP_PREFIX.SEEK_TO_CHAPTER"
      const val CLOSE_PLAYBACK = "$APP_PREFIX.CLOSE_PLAYBACK"
      const val SYNC_PROGRESS_FORCE = "$APP_PREFIX.SYNC_PROGRESS_FORCE"
    }

    object SleepTimer {
      const val ACTION_SET = "$APP_PREFIX.SET_SLEEP_TIMER"
      const val ACTION_CANCEL = "$APP_PREFIX.CANCEL_SLEEP_TIMER"
      const val ACTION_ADJUST = "$APP_PREFIX.ADJUST_SLEEP_TIMER"
      const val ACTION_GET_TIME = "$APP_PREFIX.GET_SLEEP_TIMER_TIME"
      const val ACTION_CHECK_AUTO = "$APP_PREFIX.CHECK_AUTO_SLEEP_TIMER"

      const val EXTRA_TIME_MS = "sleep_timer_time_ms"
      const val EXTRA_IS_CHAPTER = "sleep_timer_is_chapter"
      const val EXTRA_SESSION_ID = "sleep_timer_session_id"
      const val EXTRA_ADJUST_DELTA = "sleep_timer_adjust_delta"
      const val EXTRA_ADJUST_INCREASE = "sleep_timer_adjust_increase"
    }

    object Extras {
      const val DISPLAY_SPEED = "display_speed"
      const val MEDIA_PLAYER = "media_player"
    }
  }

  // Core service infrastructure
  private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  // Media session and managers
  private var mediaSession: MediaLibrarySession? = null
  private lateinit var apiHandler: ApiHandler
  private lateinit var mediaManager: MediaManager
  private lateinit var browseTree: Media3BrowseTree
  private lateinit var autoLibraryCoordinator: Media3AutoLibraryCoordinator
  private lateinit var progressSyncManager: ProgressSyncManager

  // Playback session state
  private var currentPlaybackSession: PlaybackSession? = null
  private val playbackMetrics = PlaybackMetricsRecorder()

  // Player instances and state
  private lateinit var localPlayer: AbsPlayerWrapper
  private lateinit var castPlayer: AbsPlayerWrapper
  private lateinit var activePlayer: Player
  private var playbackPipeline: PlaybackPipeline? = null
  private var castCoordinator: com.audiobookshelf.app.player.media3.Media3CastCoordinator? = null

  @Volatile
  private var playerInitialized = false
  private val hasActivePlayer: Boolean
    get() = playerInitialized && this::activePlayer.isInitialized
  private val playerInitializationSignal = CompletableDeferred<Unit>()
  private val playerSwitchMutex = Mutex()

  private val speechAudioAttributes = androidx.media3.common.AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
    .build()

  // Position tracking and progress sync
  @Volatile
  private var lastKnownIsPlaying: Boolean = false
  @Volatile
  private var listenedSinceLastSaveSec: Long = 0
  @Volatile
  private var progressSyncInFlight: Boolean = false
  private var positionUpdateJob: Job? = null

  // Error handling
  private var errorRetryJob: Job? = null

  // Playback controls
  private var jumpBackwardMs: Long = 10000L
  private var jumpForwardMs: Long = 10000L
  private var closePlaybackSignal: CompletableDeferred<Unit>? = null

  // Sleep timer
  private lateinit var sleepTimerManager: SleepTimerManager
  private var sleepTimerShakeController: SleepTimerShakeController? = null

  // Notification and UI
  private lateinit var notificationProvider: MediaNotification.Provider
  private lateinit var playbackSpeedButtonProvider: Media3PlaybackSpeedButtonProvider
  private var playbackSpeedCommandButton: CommandButton? = null

  @Volatile
  private var foregroundStarted = false

  // Command definitions
  private val cyclePlaybackSpeedCommand =
    SessionCommand(CustomCommands.CYCLE_PLAYBACK_SPEED, Bundle.EMPTY)
  private val seekBackIncrementCommand =
    SessionCommand(CustomCommands.SEEK_BACK_INCREMENT, Bundle.EMPTY)
  private val seekForwardIncrementCommand =
    SessionCommand(CustomCommands.SEEK_FORWARD_INCREMENT, Bundle.EMPTY)

  // Audio focus handling
  private val becomingNoisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (
        intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
        playerInitialized && this@Media3PlaybackService::activePlayer.isInitialized &&
        activePlayer.isPlaying
      ) {
        debugLog { "Audio becoming noisy; pausing player" }
        activePlayer.pause()
      }
    }
  }

  // Cache for resolved playables
  private val resolvedCache = com.audiobookshelf.app.player.media3.ResolvedPlayableCache(
    RESOLVED_CACHE_TTL_MS,
    RESOLVED_CACHE_LIMIT
  )

  // Device settings accessor
  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  // Player event listener
  private val playerListener = com.audiobookshelf.app.player.media3.Media3PlayerEventListener(
    api = object : com.audiobookshelf.app.player.media3.ListenerApi {
      override val tag: String = TAG
      override val playbackMetrics: PlaybackMetricsRecorder =
        this@Media3PlaybackService.playbackMetrics

      override fun currentSession(): PlaybackSession? = currentPlaybackSession
      override fun activePlayer(): Player = activePlayer
      override fun isPlayerInitialized(): Boolean = playerInitialized
      override fun lastKnownIsPlaying(): Boolean = lastKnownIsPlaying
      override fun setLastKnownIsPlaying(value: Boolean) {
        lastKnownIsPlaying = value
      }

      override fun updateCurrentPosition() {
        this@Media3PlaybackService.updateCurrentPosition()
      }

      override fun maybeSyncProgress(
        reason: String,
        force: Boolean,
        onComplete: ((SyncResult?) -> Unit)?
      ) {
        this@Media3PlaybackService.maybeSyncProgress(reason, force, onComplete)
      }

      override fun onPlayStarted(sessionId: String) {
        ensureSleepTimerManager().handleMediaPlayEvent(sessionId)
      }

      override fun startPositionUpdates() {
        this@Media3PlaybackService.startPositionUpdates()
      }

      override fun stopPositionUpdates() {
        this@Media3PlaybackService.stopPositionUpdates()
      }

      override fun notifyWidgetState() {
        this@Media3PlaybackService.notifyWidgetState()
      }

      override fun updatePlaybackSpeedButton(speed: Float) {
        this@Media3PlaybackService.updatePlaybackSpeedButton(speed)
      }

      override fun getErrorRetryJob(): Job? = errorRetryJob
      override fun setErrorRetryJob(job: Job?) {
        errorRetryJob = job
      }

      override val serviceScope: CoroutineScope = this@Media3PlaybackService.serviceScope
      override val errorResetWindowMs: Long = ERROR_RESET_WINDOW_MS
      override val retryBackoffStepMs: Long = RETRY_BACKOFF_STEP_MS
      override fun debug(msg: () -> String) {
        this@Media3PlaybackService.debugLog(msg)
      }
    }
  )

  // ========================================
  // Lifecycle Methods
  // ========================================

  override fun onCreate() {
    super.onCreate()
    debugLog { "onCreate: Initializing Media3 playback service" }
    DbManager.initialize(this)
    DeviceManager.initializeWidgetUpdater(this)
    applyJumpIncrementsFromDeviceSettings()
    setupMediaManagers()
    setupPlaybackPipeline()
  }

  override fun onDestroy() {
    super.onDestroy()
    if (foregroundStarted) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      foregroundStarted = false
    }
    errorRetryJob?.cancel()
    errorRetryJob = null
    stopPositionUpdates()
    serviceScope.cancel()
    cleanupPlaybackResources()
    notifyWidgetState(isClosed = true)
    debugLog { "onDestroy: Media3 service destroyed" }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    if (currentPlaybackSession != null) {
      closePlayback()
    }
    serviceScope.launch {
      try {
        val signal = closePlaybackSignal
        if (signal != null) {
          withTimeout(TASK_REMOVAL_CLOSE_TIMEOUT_MS) { signal.await() }
        }
      } catch (_: Exception) {
      } finally {
        stopSelf()
      }
    }
  }

  override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
    debugLog {
      "onUpdateNotification: foreground=$startInForegroundRequired, session=${session.id}, " +
        "isPlaying=${session.player.isPlaying}, state=${session.player.playbackState}"
    }
    super.onUpdateNotification(session, startInForegroundRequired)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
    return mediaSession
  }

  // ========================================
  // Setup & Initialization
  // ========================================

  private fun setupMediaManagers() {
    apiHandler = ApiHandler(this)
    mediaManager = MediaManager(apiHandler, this)
    browseTree = Media3BrowseTree(this, mediaManager)
    autoLibraryCoordinator = Media3AutoLibraryCoordinator(mediaManager, browseTree, serviceScope)
    progressSyncManager = ProgressSyncManager(
      apiHandler = apiHandler,
      serviceScope = serviceScope,
      getCurrentSession = { currentPlaybackSession },
      updateCurrentPosition = { updateCurrentPosition() },
      getListenedSinceLastSaveSec = { listenedSinceLastSaveSec },
      setListenedSinceLastSaveSec = { v -> listenedSinceLastSaveSec = v },
      getProgressSyncInFlight = { progressSyncInFlight },
      setProgressSyncInFlight = { v -> progressSyncInFlight = v },
      hasNetworkConnectivity = { DeviceManager.checkConnectivity(applicationContext) },
      saveSyncEvent = { session, result -> MediaEventManager.saveEvent(session, result) },
      saveIntervalSeconds = SAVE_SYNC_INTERVAL_SECONDS,
      debugLog = { msg -> debugLog(msg) }
    )
  }

  private fun setupPlaybackPipeline() {
    initializeLocalPlayer()
    createNotificationChannel()
    configureNotificationProvider()
    configureCommandButtons()

    val sessionId = "AudiobookshelfMedia3_${System.currentTimeMillis()}"
    val sessionActivityIntent = createSessionActivityIntent()
    buildMediaLibrarySession(sessionId, sessionActivityIntent)
    ensureForegroundNotification()

    initializeCastPlayer()
    registerBecomingNoisyReceiver()
    ensureSleepTimerManager()

    playerInitializationSignal.complete(Unit)
  }

  private fun applyJumpIncrementsFromDeviceSettings() {
    val settings = deviceSettings
    jumpBackwardMs = settings.jumpBackwardsTimeMs
    jumpForwardMs = settings.jumpForwardTimeMs
    debugLog {
      "Jump increments set from device settings: back=${jumpBackwardMs}ms forward=${jumpForwardMs}ms"
    }
  }

  private fun cleanupPlaybackResources() {
    unregisterReceiver(becomingNoisyReceiver)
    mediaSession?.run {
      release()
      mediaSession = null
    }
    if (playerInitialized) {
      localPlayer.release()
      if (::castPlayer.isInitialized) {
        castPlayer.release()
      }
      playerInitialized = false
    }
    sleepTimerShakeController?.release()
    sleepTimerShakeController = null
    SleepTimerNotificationCenter.unregister()
  }

  // ========================================
  // Player Initialization
  // ========================================

  private fun initializeLocalPlayer() {
    val pipeline = playbackPipeline ?: PlaybackPipeline(
      context = this,
      scope = serviceScope,
      speechAudioAttributes = speechAudioAttributes,
      onSwitchToCast = { },
      onSwitchToLocal = { },
      pauseLocalForCasting = { },
      debug = { msg -> debugLog(msg) }
    ).also { playbackPipeline = it }

    pipeline.initializeLocalPlayer(
      enableMp3IndexSeeking = deviceSettings.enableMp3IndexSeeking,
      speechAttributes = speechAudioAttributes,
      jumpBackwardMs = jumpBackwardMs,
      jumpForwardMs = jumpForwardMs,
      onPlayerReady = { lp ->
        localPlayer = lp
        activePlayer = lp
        updateMediaPlayerExtra()
        playerInitialized = true
      },
      buildListener = { playerListener }
    )
    debugLog { "Local player initialized via pipeline." }
  }

  private fun initializeCastPlayer() {
    playbackPipeline = PlaybackPipeline(
      context = this,
      scope = serviceScope,
      speechAudioAttributes = speechAudioAttributes,
      onSwitchToCast = { wrapper ->
        serviceScope.launch {
          playerInitializationSignal.await()
          castPlayer = wrapper.apply { addListener(playerListener) }
          switchPlayer(to = castPlayer)
        }
      },
      onSwitchToLocal = {
        serviceScope.launch {
          playerInitializationSignal.await()
          switchPlayer(to = localPlayer)
        }
      },
      pauseLocalForCasting = { pauseLocalPlaybackForCasting() },
      debug = { msg -> debugLog(msg) }
    )
    castCoordinator = playbackPipeline?.initializeCast()
  }

  // ========================================
  // Player Switching & Coordination
  // ========================================

  private suspend fun switchPlayer(to: Player) {
    playerSwitchMutex.withLock {
      if (activePlayer === to) return

      val fromPlayer = activePlayer

      val itemCount = fromPlayer.mediaItemCount
      val startIndex = fromPlayer.currentMediaItemIndex
      val startPosition = fromPlayer.currentPosition.coerceAtLeast(0)
      val playWhenReady = fromPlayer.playWhenReady

      activePlayer = to
      mediaSession?.player = to
      currentPlaybackSession?.mediaPlayer = currentMediaPlayerId()
      notifyWidgetState()
      updateMediaPlayerExtra()

      if (itemCount > 0) {
        to.setMediaItems(
          List(itemCount) { fromPlayer.getMediaItemAt(it) },
          startIndex,
          startPosition
        )
      }
      to.playWhenReady = playWhenReady
      to.prepare()

      if (fromPlayer !== localPlayer) {
        fromPlayer.stop()
        fromPlayer.clearMediaItems()
      }

      debugLog { "Switched active player from ${fromPlayer.javaClass.simpleName} to ${to.javaClass.simpleName}" }
    }
  }

  private fun pauseLocalPlaybackForCasting() {
    if (!this::localPlayer.isInitialized) return
    if (!this::activePlayer.isInitialized) return
    if (activePlayer !== localPlayer) return
    if (localPlayer.isPlaying) {
      localPlayer.pause()
    }
  }

  private fun currentMediaPlayerId(): String {
    val isCastActive =
      this::castPlayer.isInitialized &&
        this::activePlayer.isInitialized &&
        activePlayer === castPlayer
    return if (isCastActive) PLAYER_CAST else PLAYER_MEDIA3
  }

  // ========================================
  // Playback Session Management
  // ========================================

  private fun assignPlaybackSession(session: PlaybackSession) {
    currentPlaybackSession = session
    if (this::mediaManager.isInitialized) {
      mediaManager.updateLatestServerItemFromSession(session)
    }
    session.mediaPlayer = currentMediaPlayerId()
    playbackMetrics.begin(session.mediaPlayer, session.mediaItemId)
    notifyWidgetState()
  }

  fun closePlayback() {
    debugLog { "closePlayback: user requested stop" }
    errorRetryJob?.cancel(); errorRetryJob = null
    val session = currentPlaybackSession
    if (session != null) {
      val signal = CompletableDeferred<Unit>()
      closePlaybackSignal = signal
      stopPositionUpdates()
      if (playerInitialized && activePlayer.isPlaying) activePlayer.pause()
      updateCurrentPosition()
      maybeSyncProgress("close", force = true) { result ->
        MediaEventManager.stopEvent(session, result)
        serviceScope.launch(Dispatchers.Main) {
          if (playerInitialized) {
            activePlayer.stop()
            activePlayer.clearMediaItems()
            playerInitialized = false
          }
          currentPlaybackSession = null
          listenedSinceLastSaveSec = 0
          lastKnownIsPlaying = false
          notifyWidgetState(isClosed = true)
          signal.complete(Unit)
        }
      }
    } else {
      closePlaybackSignal?.complete(Unit)
    }
  }

  private fun isHostController(controller: MediaSession.ControllerInfo?): Boolean {
    return controller?.packageName == packageName
  }

  private fun syncSessionFromHostController() {
    val latest = DeviceManager.getLastPlaybackSession() ?: return
    val currentId = currentPlaybackSession?.id
    if (currentId == latest.id) return
    assignPlaybackSession(latest)
  }

  private fun isPassthroughRequestAllowed(
    mediaId: String?,
    controller: MediaSession.ControllerInfo?
  ): Boolean {
    if (mediaId.isNullOrBlank()) return false

    var sessionId = currentPlaybackSession?.id
    if (sessionId == null && isHostController(controller)) {
      syncSessionFromHostController()
      sessionId = currentPlaybackSession?.id
    }

    if (sessionId == null) return false
    if (mediaId.startsWith(sessionId)) return true

    if (isHostController(controller)) {
      debugLog { "Allowing passthrough request from host app despite session mismatch" }
      syncSessionFromHostController()
      return true
    }
    return false
  }

  // ========================================
  // Position Tracking & Seeking
  // ========================================

  private fun startPositionUpdates() {
    if (positionUpdateJob != null) return
    positionUpdateJob = serviceScope.launch {
      while (playerInitialized && lastKnownIsPlaying) {
        updateCurrentPosition()
        listenedSinceLastSaveSec += 1
        if (listenedSinceLastSaveSec >= SAVE_SYNC_INTERVAL_SECONDS) {
          maybeSyncProgress("interval")
        }
        kotlinx.coroutines.delay(POSITION_UPDATE_INTERVAL_MS)
      }
    }
  }

  private fun stopPositionUpdates() {
    positionUpdateJob?.cancel()
    positionUpdateJob = null
  }

  private fun updateCurrentPosition() {
    val session = currentPlaybackSession ?: return
    if (!this::activePlayer.isInitialized) return

    val trackIndex = activePlayer.currentMediaItemIndex
    val positionInTrack = activePlayer.currentPosition
    val trackStartOffset = session.getTrackStartOffsetMs(trackIndex)
    val absolutePositionMs = trackStartOffset + positionInTrack

    session.currentTime = absolutePositionMs / 1000.0
  }

  private fun currentAbsolutePositionMs(): Long? {
    if (!playerInitialized) return null
    val session = currentPlaybackSession ?: return null
    val mediaItemCount = activePlayer.mediaItemCount
    if (mediaItemCount <= 0) return activePlayer.currentPosition.coerceAtLeast(0L)
    val trackIndex = activePlayer.currentMediaItemIndex.coerceIn(0, mediaItemCount - 1)
    val offset = session.getTrackStartOffsetMs(trackIndex)
    return (activePlayer.currentPosition + offset).coerceAtLeast(0L)
  }

  private fun seekToAbsolutePosition(targetMs: Long) {
    val session = currentPlaybackSession ?: return
    if (!this::activePlayer.isInitialized) return
    val clampedMs = targetMs.coerceIn(0L, session.totalDurationMs)
    updatePlaybackSessionTime(clampedMs)
    serviceScope.launch(Dispatchers.Main) {
      if (!playerInitialized) return@launch
      val trackCount = activePlayer.mediaItemCount
      if (trackCount <= 0) {
        activePlayer.seekTo(clampedMs)
        return@launch
      }
      val trackIndex = resolveTrackIndexForPosition(session, clampedMs)
        .coerceIn(0, trackCount - 1)
      val trackStartOffset = session.getTrackStartOffsetMs(trackIndex)
      val positionInTrack = (clampedMs - trackStartOffset).coerceAtLeast(0L)
      activePlayer.seekTo(trackIndex, positionInTrack)
    }
  }

  private fun resolveTrackIndexForPosition(session: PlaybackSession, positionMs: Long): Int {
    val tracks = session.audioTracks
    if (tracks.isEmpty()) return 0
    val track =
      tracks.firstOrNull { positionMs in it.startOffsetMs until it.endOffsetMs } ?: tracks.last()
    return tracks.indexOf(track)
  }

  private fun updatePlaybackSessionTime(positionMs: Long) {
    currentPlaybackSession?.currentTime = positionMs / 1000.0
  }

  // ========================================
  // Progress Sync
  // ========================================

  private fun maybeSyncProgress(
    reason: String,
    force: Boolean = false,
    onComplete: ((SyncResult?) -> Unit)? = null
  ) {
    progressSyncManager.maybeSyncProgress(reason, force, onComplete)
  }

  // ========================================
  // Sleep Timer
  // ========================================

  private val sleepTimerHost = object : SleepTimerHost {
    override val context: Context
      get() = this@Media3PlaybackService

    override fun currentTimeMs(): Long {
      return if (hasActivePlayer) activePlayer.currentPosition else 0L
    }

    override fun durationMs(): Long {
      val playerDuration = if (hasActivePlayer) activePlayer.duration else C.TIME_UNSET
      return if (playerDuration != C.TIME_UNSET && playerDuration >= 0) {
        playerDuration
      } else {
        currentPlaybackSession?.totalDurationMs ?: 0L
      }
    }

    override fun isPlaying(): Boolean {
      return if (hasActivePlayer) activePlayer.isPlaying else false
    }

    override fun playbackSpeed(): Float {
      return if (hasActivePlayer) activePlayer.playbackParameters.speed else 1f
    }

    override fun setVolume(volume: Float) {
      if (hasActivePlayer) activePlayer.volume = volume.coerceIn(0f, 1f)
    }

    override fun pause() {
      if (hasActivePlayer) activePlayer.pause()
    }

    override fun play() {
      if (hasActivePlayer) activePlayer.play()
    }

    override fun seekBackward(amountMs: Long) {
      if (!hasActivePlayer) return
      val target = max(activePlayer.currentPosition - amountMs, 0L)
      activePlayer.seekTo(target)
    }

    override fun endTimeOfChapterOrTrack(): Long? {
      val session = currentPlaybackSession ?: return null
      val current = currentTimeMs()
      return session.getChapterForTime(current)?.endMs ?: session.getCurrentTrackEndTime()
    }

    override fun endTimeOfNextChapterOrTrack(): Long? {
      val session = currentPlaybackSession ?: return null
      val current = currentTimeMs()
      return session.getNextChapterForTime(current)?.endMs ?: session.getNextTrackEndTime()
    }

    override fun notifySleepTimerSet(secondsRemaining: Int, isAuto: Boolean) {
      SleepTimerNotificationCenter.notifySet(secondsRemaining, isAuto)
    }

    override fun notifySleepTimerEnded(currentPosition: Long) {
      SleepTimerNotificationCenter.notifyEnded(currentPosition)
    }

    override fun registerSensor() {
      ensureShakeController()
      sleepTimerShakeController?.register()
    }

    override fun unregisterSensor() {
      ensureShakeController()
      sleepTimerShakeController?.scheduleUnregister()
    }
  }

  private fun ensureSleepTimerManager(): SleepTimerManager {
    if (!::sleepTimerManager.isInitialized) {
      ensureShakeController()
      sleepTimerManager = SleepTimerManager(sleepTimerHost, serviceScope)
    }
    return sleepTimerManager
  }

  private fun ensureShakeController() {
    if (sleepTimerShakeController == null) {
      sleepTimerShakeController = SleepTimerShakeController(
        this,
        SLEEP_TIMER_WAKE_UP_EXPIRATION,
        serviceScope
      ) {
        if (::sleepTimerManager.isInitialized) {
          sleepTimerManager.handleShake()
        }
      }
    }
  }

  // ========================================
  // Session Callback & Controller
  // ========================================

  private fun createSessionCallback(): com.audiobookshelf.app.player.media3.Media3SessionCallback {
    val sleepTimerApi = object : com.audiobookshelf.app.player.media3.SleepTimerApi {
      override fun set(sessionId: String, timeMs: Long, isChapter: Boolean) {
        ensureSleepTimerManager().setManualSleepTimer(sessionId, timeMs, isChapter)
      }

      override fun cancel() {
        if (::sleepTimerManager.isInitialized) sleepTimerManager.cancelSleepTimer()
      }

      override fun adjust(deltaMs: Long, increase: Boolean) {
        if (::sleepTimerManager.isInitialized) {
          if (increase) sleepTimerManager.increaseSleepTime(deltaMs) else sleepTimerManager.decreaseSleepTime(
            deltaMs
          )
        }
      }

      override fun getTime(): Long {
        return if (::sleepTimerManager.isInitialized) sleepTimerManager.getSleepTimerTime() else 0L
      }

      override fun checkAuto() {
        if (::sleepTimerManager.isInitialized) sleepTimerManager.checkAutoSleepTimer()
      }
    }
    val playbackControlApi = object : com.audiobookshelf.app.player.media3.PlaybackControlApi {
      override fun sync(reason: String, force: Boolean) {
        maybeSyncProgress(reason, force, null)
      }

      override fun close(afterStop: (() -> Unit)?) {
        closePlayback(); afterStop?.invoke()
      }
    }
    val sessionController = com.audiobookshelf.app.player.media3.SessionController(
      context = this,
      playbackSpeedCommandButton = playbackSpeedCommandButton,
      sleepTimerApi = sleepTimerApi,
      cyclePlaybackSpeed = { cyclePlaybackSpeed() },
      getCurrentSession = { currentPlaybackSession },
      currentAbsolutePositionMs = { currentAbsolutePositionMs() },
      playbackControlApi = playbackControlApi,
      logger = { msg -> debugLog { msg } },
      playerProvider = { activePlayer }
    )
    val seekConfig = com.audiobookshelf.app.player.media3.SeekConfig(
      jumpBackwardMs = jumpBackwardMs,
      jumpForwardMs = jumpForwardMs,
      allowSeekingOnMediaControls = deviceSettings.allowSeekingOnMediaControls
    )
    val browseApi = object : com.audiobookshelf.app.player.media3.BrowseApi {
      override fun getPayload(forceTranscode: Boolean): PlayItemRequestPayload {
        return getPlayItemRequestPayload(forceTranscode)
      }

      override suspend fun resolve(
        mediaId: String,
        preferCast: Boolean
      ): Media3BrowseTree.ResolvedPlayable? {
        return resolvePlayableWithCache(mediaId, preferCast)
      }

      override fun assignSession(session: PlaybackSession) {
        assignPlaybackSession(session)
      }

      override fun passthroughAllowed(
        mediaId: String?,
        controller: MediaSession.ControllerInfo?
      ): Boolean {
        return isPassthroughRequestAllowed(mediaId, controller)
      }
    }
    return com.audiobookshelf.app.player.media3.Media3SessionCallback(
      logTag = TAG,
      appPackageName = packageName,
      scope = serviceScope,
      appContext = this,
      browseTree = browseTree,
      autoLibraryCoordinator = autoLibraryCoordinator,
      mediaManager = mediaManager,
      playerProvider = { activePlayer },
      isCastActive = { this::castPlayer.isInitialized && this::activePlayer.isInitialized && activePlayer === castPlayer },
      seekConfig = seekConfig,
      browseApi = browseApi,
      getCurrentSession = { currentPlaybackSession },
      currentAbsolutePositionMs = { currentAbsolutePositionMs() },
      seekToAbsolutePosition = { pos -> seekToAbsolutePosition(pos) },
      resolveTrackIndexForPosition = { session, pos -> resolveTrackIndexForPosition(session, pos) },
      debug = { msg -> debugLog(msg) },
      sessionController = sessionController
    )
  }

  // ========================================
  // Media Session & Buttons
  // ========================================

  private fun buildMediaLibrarySession(sessionId: String, sessionActivityIntent: PendingIntent) {
    playbackSpeedCommandButton
      ?: playbackSpeedButtonProvider.createButton(playbackSpeedButtonProvider.currentSpeed()).also {
        playbackSpeedCommandButton = it
      }

    mediaSession = MediaLibrarySession.Builder(this, activePlayer, createSessionCallback())
      .setId(sessionId)
      .setSessionActivity(sessionActivityIntent)
      .build()

    if (!::localPlayer.isInitialized) {
      throw IllegalStateException("Fatal: localPlayer could not be initialized.")
    }

    localPlayer.mapSkipToSeek = true
    playerInitialized = true

    mediaSession?.sessionExtras = Bundle().apply {
      // This lets our custom Back/Forward take precedence on Wear/notifications
      putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, false)
      putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, false)
    }
    updateMediaPlayerExtra()

    debugLog { "MediaLibrarySession created: $sessionId" }

    // Set initial media button preferences so notifications have custom actions immediately
    runCatching {
      val prefs = ImmutableList.copyOf(buildServiceMediaButtons())
      mediaSession?.setMediaButtonPreferences(prefs)
      debugLog { "Initial media button preferences applied: ${prefs.size}" }
    }.onFailure { t ->
      debugLog { "Failed to apply initial media button preferences: ${t.message}" }
    }
  }

  private fun createSessionActivityIntent(): PendingIntent {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      },
      flags
    )
  }

  private fun configureCommandButtons() {
    playbackSpeedButtonProvider =
      Media3PlaybackSpeedButtonProvider(cyclePlaybackSpeedCommand, Extras.DISPLAY_SPEED)
    playbackSpeedButtonProvider.alignTo(currentPlaybackSpeed())
    playbackSpeedCommandButton = null
  }

  private fun buildServiceMediaButtons(): List<CommandButton> {
    val buttons = mutableListOf<CommandButton>()

    // Always include Back/Forward regardless of allowSeekingOnMediaControls for notifications
    val backMs = jumpBackwardMs.coerceAtLeast(1_000L)
    val fwdMs = jumpForwardMs.coerceAtLeast(1_000L)
    val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
      .setSessionCommand(seekBackIncrementCommand)
      .setDisplayName("Back ${backMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_rewind)
      .setSlots(CommandButton.SLOT_BACK)
      .build()
    val fwd = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
      .setSessionCommand(seekForwardIncrementCommand)
      .setDisplayName("Forward ${fwdMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_fastforward)
      .setSlots(CommandButton.SLOT_FORWARD)
      .build()
    buttons.add(back)
    buttons.add(fwd)

    val speedBtn = playbackSpeedCommandButton ?: run {
      CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
        .setSessionCommand(cyclePlaybackSpeedCommand)
        .setDisplayName("Speed")
        .build()
    }
    buttons.add(speedBtn)

    return buttons
  }

  private fun cyclePlaybackSpeed(): Float {
    val newSpeed = playbackSpeedButtonProvider.cycleSpeed()
    activePlayer.setPlaybackSpeed(newSpeed)
    updatePlaybackSpeedButton(newSpeed)
    return newSpeed
  }

  private fun currentPlaybackSpeed(): Float {
    val playerSpeed = activePlayer.playbackParameters.speed
    return playerSpeed
  }

  private fun updatePlaybackSpeedButton(speed: Float) {
    playbackSpeedButtonProvider.alignTo(speed)
    val speedButton = playbackSpeedButtonProvider.createButton(speed)
    playbackSpeedCommandButton = speedButton
    // Refresh media button preferences so controllers/notifications get updated icon/label
    runCatching {
      val prefs = ImmutableList.copyOf(buildServiceMediaButtons())
      mediaSession?.setMediaButtonPreferences(prefs)
      debugLog { "Updated media button preferences after speed change: ${speed}x" }
    }.onFailure { t ->
      debugLog { "Failed to update media button preferences after speed change: ${t.message}" }
    }
  }

  private fun registerBecomingNoisyReceiver() {
    registerReceiver(
      becomingNoisyReceiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    )
  }

  // ========================================
  // Notification & Foreground Management
  // ========================================

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channelName = "Media Playback"
      val importance = NotificationManager.IMPORTANCE_LOW
      val channel = NotificationChannel(Notification.CHANNEL_ID, channelName, importance).apply {
        description = "Playback controls and progress"
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
      }
      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
      debugLog { "Notification channel created: ${Notification.CHANNEL_ID}" }
    }
  }

  private fun configureNotificationProvider() {
    val provider = CustomMediaNotificationProvider(
      this,
      Notification.CHANNEL_ID,
      DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID,
      Notification.ID
    )
    this.notificationProvider = provider
    setMediaNotificationProvider(provider)
    debugLog { "CustomMediaNotificationProvider configured" }
  }

  private fun ensureForegroundNotification() {
    if (foregroundStarted) return
    val notification =
      NotificationCompat.Builder(this, Notification.CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_preparing_playback))
        .setSmallIcon(R.drawable.icon_monochrome)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
    startForeground(Notification.ID, notification)
    foregroundStarted = true
  }

  // ========================================
  // Widget Integration
  // ========================================

  private fun notifyWidgetState(isClosed: Boolean = false) {
    val updater = DeviceManager.widgetUpdater ?: return
    if (isClosed) {
      updater.onPlayerClosed()
      return
    }
    buildWidgetSnapshot(isClosed)?.let { updater.onPlayerChanged(it) }
  }

  private fun buildWidgetSnapshot(isClosed: Boolean): WidgetPlaybackSnapshot? {
    val session = currentPlaybackSession ?: return null
    val isPlaying = this::activePlayer.isInitialized && activePlayer.isPlaying
    var absolutePosition = session.currentTimeMs
    if (playerInitialized) {
      val trackIndex = activePlayer.currentMediaItemIndex
      val trackOffset = session.getTrackStartOffsetMs(trackIndex)
      absolutePosition = (activePlayer.currentPosition + trackOffset).coerceAtLeast(0L)
    }
    return WidgetPlaybackSnapshot(
      title = session.displayTitle,
      author = session.displayAuthor,
      coverUri = session.getCoverUri(this),
      positionMs = absolutePosition,
      durationMs = session.totalDurationMs,
      isPlaying = isPlaying,
      isClosed = isClosed
    )
  }

  // ========================================
  // Utility Helpers
  // ========================================

  private inline fun debugLog(crossinline message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(TAG, message())
  }

  private fun getDeviceInfo(): DeviceInfo {
    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    return DeviceInfo(
      deviceId,
      Build.MANUFACTURER,
      Build.MODEL,
      Build.VERSION.SDK_INT,
      BuildConfig.VERSION_NAME
    )
  }

  private fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    val mediaPlayerId = currentMediaPlayerId()
    return PlayItemRequestPayload(
      mediaPlayerId,
      forceDirectPlay = !forceTranscode,
      forceTranscode = forceTranscode,
      deviceInfo = getDeviceInfo()
    )
  }

  private suspend fun resolvePlayableWithCache(
    mediaId: String,
    preferCastUris: Boolean
  ): Media3BrowseTree.ResolvedPlayable? {
    resolvedCache.get(mediaId, preferCastUris)?.let { return it }
    val resolved = browseTree.resolvePlayableItem(
      mediaId = mediaId,
      payload = getPlayItemRequestPayload(forceTranscode = false),
      preferServerUrisForCast = preferCastUris
    )
    if (resolved != null) {
      resolvedCache.put(mediaId, preferCastUris, resolved)
    }
    return resolved
  }

  private fun updateMediaPlayerExtra() {
    if (!this::activePlayer.isInitialized) return
    val mediaPlayerId = currentMediaPlayerId()
    mediaSession?.let { session ->
      val extras = session.sessionExtras
      extras.putString(Extras.MEDIA_PLAYER, mediaPlayerId)
      session.sessionExtras = extras
    }
  }
}
