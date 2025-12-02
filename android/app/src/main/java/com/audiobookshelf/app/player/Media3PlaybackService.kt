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
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.BookChapter
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
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder
import com.audiobookshelf.app.player.media3.CustomMediaNotificationProvider
import com.audiobookshelf.app.player.media3.Media3AutoLibraryCoordinator
import com.audiobookshelf.app.player.media3.Media3BrowseTree
import com.audiobookshelf.app.player.media3.Media3PlaybackSpeedButtonProvider
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import com.audiobookshelf.app.server.ApiHandler
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
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
  private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  companion object {
    private const val APP_PREFIX = "com.audiobookshelf.app.player"
    val TAG: String = Media3PlaybackService::class.java.simpleName
    private const val RESOLVED_CACHE_TTL_MS = 5_000L
    private const val RESOLVED_CACHE_LIMIT = 6
    private const val CHAPTER_RESTART_THRESHOLD_MS = 4_000L
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
      const val EXTRA_CHAPTER_START_MS = "chapter_start_ms"
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

  private var mediaSession: MediaLibrarySession? = null

  private var currentPlaybackSession: PlaybackSession? = null
  private var sleepTimerShakeController: SleepTimerShakeController? = null

  private lateinit var seekBackButton: CommandButton
  private lateinit var seekForwardButton: CommandButton
  private lateinit var playbackSpeedButtonProvider: Media3PlaybackSpeedButtonProvider
  private var playbackSpeedCommandButton: CommandButton? = null

  private lateinit var localPlayer: AbsPlayerWrapper
  private lateinit var castPlayer: AbsPlayerWrapper
  private lateinit var activePlayer: Player

  @Volatile
  private var playerInitialized = false
  private val hasActivePlayer: Boolean
    get() = playerInitialized && this::activePlayer.isInitialized
  private val playerInitializationSignal = CompletableDeferred<Unit>()

  @Volatile
  private var consecutiveErrorCount = 0

  @Volatile
  private var lastErrorTimeMs = 0L
  private var errorRetryJob: Job? = null
  private val playerSwitchMutex = Mutex()
  private var closePlaybackSignal: CompletableDeferred<Unit>? = null

  private val speechAudioAttributes = androidx.media3.common.AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
    .build()


  private val playerListener = PlayerEventListener()
  private val playbackMetrics = PlaybackMetricsRecorder()

  @Volatile
  private var lastKnownIsPlaying: Boolean = false

  @Volatile
  private var listenedSinceLastSaveSec: Long = 0

  @Volatile
  private var progressSyncInFlight: Boolean = false
  private var positionUpdateJob: Job? = null
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

  private inline fun debugLog(crossinline message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(TAG, message())
  }

  private lateinit var apiHandler: ApiHandler
  private lateinit var mediaManager: MediaManager
  private lateinit var browseTree: Media3BrowseTree
  private lateinit var autoLibraryCoordinator: Media3AutoLibraryCoordinator
  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  private data class CachedResolvedPlayable(
    val key: String,
    val resolved: Media3BrowseTree.ResolvedPlayable,
    val timestampMs: Long
  )

  private val resolvedPlayableCache = ArrayDeque<CachedResolvedPlayable>()
  private val resolvedCacheMutex = Mutex()
  private fun resolvedPlayableCacheKey(mediaId: String, preferCastUris: Boolean): String {
    return "$mediaId|cast=$preferCastUris"
  }

  private suspend fun cleanupResolvedCache(nowMs: Long = System.currentTimeMillis()) {
    resolvedCacheMutex.withLock {
      while (resolvedPlayableCache.isNotEmpty()) {
        val head = resolvedPlayableCache.firstOrNull() ?: break
        if (nowMs - head.timestampMs > RESOLVED_CACHE_TTL_MS) {
          resolvedPlayableCache.removeFirst()
        } else {
          break
        }
      }
    }
  }

  private suspend fun getCachedResolvedPlayable(
    mediaId: String,
    preferCastUris: Boolean
  ): Media3BrowseTree.ResolvedPlayable? {
    cleanupResolvedCache()
    val key = resolvedPlayableCacheKey(mediaId, preferCastUris)
    return resolvedCacheMutex.withLock {
      val cached = resolvedPlayableCache.firstOrNull { it.key == key } ?: return@withLock null
      cached.resolved.copy(session = cached.resolved.session.clone())
    }
  }

  private suspend fun storeResolvedPlayable(
    mediaId: String,
    preferCastUris: Boolean,
    resolvedPlayable: Media3BrowseTree.ResolvedPlayable
  ) {
    cleanupResolvedCache()
    val key = resolvedPlayableCacheKey(mediaId, preferCastUris)
    resolvedCacheMutex.withLock {
      resolvedPlayableCache.removeAll { it.key == key }
      resolvedPlayableCache.addLast(
        CachedResolvedPlayable(
          key,
          resolvedPlayable.copy(session = resolvedPlayable.session.clone()),
          System.currentTimeMillis()
        )
      )
      while (resolvedPlayableCache.size > RESOLVED_CACHE_LIMIT) {
        resolvedPlayableCache.removeFirst()
      }
    }
  }

  private suspend fun resolvePlayableWithCache(
    mediaId: String,
    preferCastUris: Boolean
  ): Media3BrowseTree.ResolvedPlayable? {
    getCachedResolvedPlayable(mediaId, preferCastUris)?.let { return it }
    val resolved = browseTree.resolvePlayableItem(
      mediaId = mediaId,
      payload = getPlayItemRequestPayload(forceTranscode = false),
      preferServerUrisForCast = preferCastUris
    )
    if (resolved != null) {
      storeResolvedPlayable(mediaId, preferCastUris, resolved)
    }
    return resolved
  }

  private fun currentMediaPlayerId(): String {
    val isCastActive =
      this::castPlayer.isInitialized &&
        this::activePlayer.isInitialized &&
        activePlayer === castPlayer
    return if (isCastActive) PLAYER_CAST else PLAYER_MEDIA3
  }

  private fun notifyWidgetState(isClosed: Boolean = false) {
    val updater = DeviceManager.widgetUpdater ?: return
    if (isClosed) {
      updater.onPlayerClosed()
      return
    }
    buildWidgetSnapshot(isClosed)?.let { updater.onPlayerChanged(it) }
  }

  private fun assignPlaybackSession(session: PlaybackSession) {
    currentPlaybackSession = session
    if (this::mediaManager.isInitialized) {
      mediaManager.updateLatestServerItemFromSession(session)
    }
    session.mediaPlayer = currentMediaPlayerId()
    playbackMetrics.begin(session.mediaPlayer, session.mediaItemId)
    notifyWidgetState()
  }

  private suspend fun switchPlayer(to: Player) {
    playerSwitchMutex.withLock {
      if (activePlayer === to) return

      val fromPlayer = activePlayer
      val toPlayer = to

      // Snapshot state atomically before we change active player
      val itemCount = fromPlayer.mediaItemCount
      val startIndex = fromPlayer.currentMediaItemIndex
      val startPosition = fromPlayer.currentPosition.coerceAtLeast(0)
      val playWhenReady = fromPlayer.playWhenReady

      activePlayer = toPlayer
      mediaSession?.player = toPlayer
      currentPlaybackSession?.mediaPlayer = currentMediaPlayerId()
      notifyWidgetState()
      updateMediaPlayerExtra()

      if (itemCount > 0) {
        toPlayer.setMediaItems(
          List(itemCount) { fromPlayer.getMediaItemAt(it) },
          startIndex,
          startPosition
        )
      }
      toPlayer.playWhenReady = playWhenReady
      toPlayer.prepare()

      if (fromPlayer !== localPlayer) {
        fromPlayer.stop()
        fromPlayer.clearMediaItems()
      }

      debugLog {
        "Switched active player from ${fromPlayer.javaClass.simpleName} to ${toPlayer.javaClass.simpleName}"
      }
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

  private lateinit var sleepTimerManager: SleepTimerManager

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

  private fun setupPlaybackPipeline() {
    initializeLocalPlayer()
    configureNotificationProvider()
    createNotificationChannel()
    ensureForegroundNotification()
    configureCommandButtons()

    val sessionId = "AudiobookshelfMedia3_${System.currentTimeMillis()}"
    val sessionActivityIntent = createSessionActivityIntent()
    buildMediaLibrarySession(sessionId, sessionActivityIntent)

    initializeCastPlayer()
    registerBecomingNoisyReceiver()
    ensureSleepTimerManager()

    playerInitializationSignal.complete(Unit)
  }

  private fun setupMediaManagers() {
    apiHandler = ApiHandler(this)
    mediaManager = MediaManager(apiHandler, this)
    browseTree = Media3BrowseTree(this, mediaManager)
    autoLibraryCoordinator = Media3AutoLibraryCoordinator(mediaManager, browseTree, serviceScope)
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

  private fun ensureSleepTimerManager(): SleepTimerManager {
    if (!::sleepTimerManager.isInitialized) {
      ensureShakeController()
      sleepTimerManager = SleepTimerManager(sleepTimerHost, serviceScope)
    }
    return sleepTimerManager
  }

  private var jumpBackwardMs: Long = 10000L
  private var jumpForwardMs: Long = 10000L

  private fun applyJumpIncrementsFromDeviceSettings() {
    val settings = deviceSettings
    jumpBackwardMs = settings.jumpBackwardsTimeMs
    jumpForwardMs = settings.jumpForwardTimeMs
    debugLog {
      "Jump increments set from device settings: back=${jumpBackwardMs}ms forward=${jumpForwardMs}ms"
    }
  }

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

  private lateinit var notificationProvider: MediaNotification.Provider

  @Volatile
  private var foregroundStarted = false
  private val cyclePlaybackSpeedCommand =
    SessionCommand(CustomCommands.CYCLE_PLAYBACK_SPEED, Bundle.EMPTY)
  private val setSleepTimerCommand = SessionCommand(SleepTimer.ACTION_SET, Bundle.EMPTY)
  private val cancelSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CANCEL, Bundle.EMPTY)
  private val adjustSleepTimerCommand = SessionCommand(SleepTimer.ACTION_ADJUST, Bundle.EMPTY)
  private val getSleepTimerTimeCommand = SessionCommand(SleepTimer.ACTION_GET_TIME, Bundle.EMPTY)
  private val checkAutoSleepTimerCommand =
    SessionCommand(SleepTimer.ACTION_CHECK_AUTO, Bundle.EMPTY)
  private val seekBackIncrementCommand =
    SessionCommand(CustomCommands.SEEK_BACK_INCREMENT, Bundle.EMPTY)
  private val seekForwardIncrementCommand =
    SessionCommand(CustomCommands.SEEK_FORWARD_INCREMENT, Bundle.EMPTY)
  private val previousChapterCommand =
    SessionCommand(CustomCommands.SEEK_TO_PREVIOUS_CHAPTER, Bundle.EMPTY)
  private val nextChapterCommand =
    SessionCommand(CustomCommands.SEEK_TO_NEXT_CHAPTER, Bundle.EMPTY)
  private val seekToChapterCommand =
    SessionCommand(CustomCommands.SEEK_TO_CHAPTER, Bundle.EMPTY)

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


  override fun onCreate() {
    super.onCreate()
    debugLog { "onCreate: Initializing Media3 playback service" }
    DbManager.initialize(this)
    DeviceManager.initializeWidgetUpdater(this)
    applyJumpIncrementsFromDeviceSettings()
    setupPlaybackPipeline()
    setupMediaManagers()
  }

  private fun initializeLocalPlayer() {
    val extractorsFactory = DefaultExtractorsFactory().apply {
      if (deviceSettings.enableMp3IndexSeeking) {
        setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
      }
    }
    val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)

    val coreExoPlayer = ExoPlayer.Builder(this)
      .setMediaSourceFactory(mediaSourceFactory)
      .setAudioAttributes(speechAudioAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .setSeekBackIncrementMs(jumpBackwardMs)
      .setSeekForwardIncrementMs(jumpForwardMs)
      .setDeviceVolumeControlEnabled(true)
      .build()
    localPlayer = AbsPlayerWrapper(coreExoPlayer, this).apply { addListener(playerListener) }
    activePlayer = localPlayer
    updateMediaPlayerExtra()
    playerInitialized = true
    debugLog { "Local player initialized." }
  }

  private fun initializeCastPlayer() {
    try {
      val castContext = CastContext.getSharedInstance(this@Media3PlaybackService)
      val coreCastPlayer = CastPlayer(castContext).apply {
        setAudioAttributes(speechAudioAttributes, true)
      }
      castPlayer = AbsPlayerWrapper(coreCastPlayer, this@Media3PlaybackService).apply {
        addListener(playerListener)
      }

      coreCastPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
          serviceScope.launch {
            playerInitializationSignal.await()
            debugLog { "Cast session available. Switching to CastPlayer." }
            pauseLocalPlaybackForCasting()
            switchPlayer(to = castPlayer)
          }
        }

          override fun onCastSessionUnavailable() {
            serviceScope.launch {
              playerInitializationSignal.await()
              debugLog { "Cast session unavailable. Switching back to local player." }
              switchPlayer(to = localPlayer)
            }
          }
        })
      debugLog { "Cast player initialized and listener attached." }

    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize CastContext. Cast feature will be unavailable.", e)
    }
  }


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

  private fun updateMediaPlayerExtra() {
    if (!this::activePlayer.isInitialized) return
    val mediaPlayerId = currentMediaPlayerId()
    mediaSession?.let { session ->
      val extras = session.sessionExtras
      extras.putString(Extras.MEDIA_PLAYER, mediaPlayerId)
      session.sessionExtras = extras
    }
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

  private fun resolveTrackIndexForPosition(session: PlaybackSession, positionMs: Long): Int {
    val tracks = session.audioTracks
    if (tracks.isEmpty()) return 0
    val track =
      tracks.firstOrNull { positionMs in it.startOffsetMs until it.endOffsetMs } ?: tracks.last()
    return tracks.indexOf(track)
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

  private fun updatePlaybackSessionTime(positionMs: Long) {
    currentPlaybackSession?.currentTime = positionMs / 1000.0
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

  fun closePlayback() {
    debugLog { "closePlayback: user requested stop" }
    // Cancel any pending retry attempts
    errorRetryJob?.cancel()
    errorRetryJob = null
    val session = currentPlaybackSession
    if (session != null) {
      // Create/replace a completion signal for callers awaiting cleanup
      val signal = CompletableDeferred<Unit>()
      closePlaybackSignal = signal
      stopPositionUpdates()
      if (playerInitialized && activePlayer.isPlaying) {
        activePlayer.pause()
      }
      updateCurrentPosition()
      maybeSyncProgress("close", force = true) { result ->
        MediaEventManager.stopEvent(session, result)
        serviceScope.launch(Dispatchers.Main) {
          if (playerInitialized) {
            activePlayer.stop()
            activePlayer.clearMediaItems()
          }
          currentPlaybackSession = null
          listenedSinceLastSaveSec = 0
          lastKnownIsPlaying = false
          notifyWidgetState(isClosed = true)
          // Signal completion to any waiter (onTaskRemoved, etc.)
          signal.complete(Unit)
        }
      }
    } else {
      // Nothing to close; ensure any waiter proceeds
      closePlaybackSignal?.complete(Unit)
    }
  }

  private fun maybeSyncProgress(
    reason: String,
    force: Boolean = false,
    onComplete: ((SyncResult?) -> Unit)? = null
  ) {
    val session = currentPlaybackSession ?: return
    if (progressSyncInFlight && !force) return

    val hasNetwork = DeviceManager.checkConnectivity(applicationContext)
    val shouldAttemptServer = hasNetwork && (!session.isLocal)

    updateCurrentPosition()

    if (!force && listenedSinceLastSaveSec < SAVE_SYNC_INTERVAL_SECONDS) return

    val timeListened = if (force && listenedSinceLastSaveSec <= 0L) 1L else listenedSinceLastSaveSec
    val durationSec = session.getTotalDuration()
    val currentTimeSec = session.currentTime

    if (!shouldAttemptServer) {
      val result = SyncResult(false, null, null)
      MediaEventManager.saveEvent(session, result)
      listenedSinceLastSaveSec = 0
      onComplete?.let { it(result) }
      return
    }

    progressSyncInFlight = true
    val syncData = MediaProgressSyncData(timeListened, durationSec, currentTimeSec)
    session.syncData(syncData)

    serviceScope.launch {
      var attempt = 1
      val maxAttempts = 3
      var backoffMs = 500L
      var finalSuccess = false
      var errMsg: String? = null

      while (attempt <= maxAttempts) {
        val deferred = CompletableDeferred<Pair<Boolean, String?>>()
        apiHandler.sendProgressSync(session.id, syncData) { success, errorMsg ->
          deferred.complete(success to errorMsg)
        }
        val (success, error) = deferred.await()
        if (success) {
          finalSuccess = true
          errMsg = null
          break
        } else {
          errMsg = error
          debugLog { "Progress sync failed (attempt $attempt/$maxAttempts): ${error ?: "unknown"}" }
          if (attempt < maxAttempts) {
            kotlinx.coroutines.delay(backoffMs)
            backoffMs *= 2
          }
          attempt += 1
        }
      }

      progressSyncInFlight = false
      val result = SyncResult(true, finalSuccess, errMsg)
      MediaEventManager.saveEvent(session, result)
      if (finalSuccess) {
        listenedSinceLastSaveSec = 0
      }
      onComplete?.let { it(result) }
    }
  }

  private fun configureCommandButtons() {
    seekBackButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
      .setSessionCommand(seekBackIncrementCommand)
      .setDisplayName("Back ${jumpBackwardMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_rewind)
      .setSlots(CommandButton.SLOT_BACK)
      .build()

    seekForwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
      .setSessionCommand(seekForwardIncrementCommand)
      .setDisplayName("Forward ${jumpForwardMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_fastforward)
      .setSlots(CommandButton.SLOT_FORWARD)
      .build()

    playbackSpeedButtonProvider =
      Media3PlaybackSpeedButtonProvider(cyclePlaybackSpeedCommand, Extras.DISPLAY_SPEED)
    playbackSpeedButtonProvider.alignTo(currentPlaybackSpeed())
    playbackSpeedCommandButton =
      playbackSpeedButtonProvider.createButton(playbackSpeedButtonProvider.currentSpeed())
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

  private fun buildMediaLibrarySession(sessionId: String, sessionActivityIntent: PendingIntent) {
    val playbackButton = playbackSpeedCommandButton
      ?: playbackSpeedButtonProvider.createButton(playbackSpeedButtonProvider.currentSpeed()).also {
        playbackSpeedCommandButton = it
      }

    mediaSession = MediaLibrarySession.Builder(this, activePlayer, Media3SessionCallback())
      .setId(sessionId)
      .setSessionActivity(sessionActivityIntent)
      .setMediaButtonPreferences(
        ImmutableList.of(
          playbackButton,
          seekBackButton,
          seekForwardButton
        )
      )
      .build()

    if (!::localPlayer.isInitialized) {
      throw IllegalStateException("Fatal: localPlayer could not be initialized.")
    }

    localPlayer.mapSkipToSeek = true
    playerInitialized = true

    mediaSession?.sessionExtras = Bundle().apply {
      putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, false)
      putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, false)
    }
    updateMediaPlayerExtra()

    debugLog { "MediaLibrarySession created: $sessionId" }
  }

  private fun registerBecomingNoisyReceiver() {
    registerReceiver(
      becomingNoisyReceiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    )
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
    // Request close and await its completion instead of using a fixed delay
    if (currentPlaybackSession != null) {
      closePlayback()
    }
    serviceScope.launch {
      try {
        // Wait up to a reasonable bound to avoid hanging the task removal
        val signal = closePlaybackSignal
        if (signal != null) {
          withTimeout(TASK_REMOVAL_CLOSE_TIMEOUT_MS) { signal.await() }
        }
      } catch (_: Exception) {
        // Timeout or cancellation; proceed to stop service
      } finally {
        stopSelf()
      }
    }
  }

  /**
   * MediaLibrarySession callback handling transport controls and custom commands.
   */
  private inner class Media3SessionCallback : MediaLibrarySession.Callback {

    override fun onPlayerInteractionFinished(
      session: MediaSession,
      controllerInfo: MediaSession.ControllerInfo,
      playerCommands: Player.Commands
    ) {
      try {
        val pkg = controllerInfo.packageName
        if (pkg.contains("wear", ignoreCase = true) || pkg.contains(
            "com.google.android.apps.wear",
            ignoreCase = true
          )
        ) {
          if (playerCommands.contains(Player.COMMAND_SEEK_BACK) || playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) {
            val p = activePlayer.currentPosition
            val target = (p - jumpBackwardMs).coerceAtLeast(0L)
            debugLog { "Wear interaction SEEK_BACK -> seekTo=$target" }
            activePlayer.seekTo(target)
          }
          if (playerCommands.contains(Player.COMMAND_SEEK_FORWARD) || playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) {
            val p = activePlayer.currentPosition
            val dur = activePlayer.duration
            val target = (p + jumpForwardMs).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
            debugLog { "Wear interaction SEEK_FORWARD -> seekTo=$target" }
            activePlayer.seekTo(target)
          }
        }
      } catch (t: Throwable) {
        Log.w(TAG, "onPlayerInteractionFinished handling error: ${t.message}")
      }
    }

    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val availablePlayerCommands = session.player.availableCommands
        val isWear = controller.packageName.contains("wear", ignoreCase = true)
        val isAppController = controller.packageName == this@Media3PlaybackService.packageName
        (session.player as? AbsPlayerWrapper)?.mapSkipToSeek = isWear

        val builder = Player.Commands.Builder().addAll(availablePlayerCommands)
          .add(Player.COMMAND_SEEK_BACK)
          .add(Player.COMMAND_SEEK_FORWARD)
          .add(Player.COMMAND_PLAY_PAUSE)
        builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        builder.add(Player.COMMAND_GET_DEVICE_VOLUME)
        builder.add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
        builder.add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)

        if (!isAppController && !deviceSettings.allowSeekingOnMediaControls) {
          builder.remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
      if (isWear) {
        debugLog { "Wear controller connected; removing default PREV/NEXT to allow custom buttons" }

        builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
        builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        builder.remove(Player.COMMAND_SEEK_TO_NEXT)
        builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
        builder.remove(Player.COMMAND_SEEK_TO_NEXT)
      }

      val playerCommands = builder.build()

      fun cmd(c: Int) = if (playerCommands.contains(c)) "Y" else "N"
      debugLog {
        "Controller connected pkg=${controller.packageName}. cmd: BACK=${cmd(Player.COMMAND_SEEK_BACK)} " +
          "FWD=${cmd(Player.COMMAND_SEEK_FORWARD)} SEEK_IN_ITEM=${cmd(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)} " +
          "PREV=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS)} NEXT=${cmd(Player.COMMAND_SEEK_TO_NEXT)} " +
          "PREV_ITEM=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)} NEXT_ITEM=${cmd(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)} " +
          "VOL_GET=${cmd(Player.COMMAND_GET_DEVICE_VOLUME)} VOL_SET=${cmd(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)} " +
          "VOL_ADJ=${cmd(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)}"
      }

      val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        .buildUpon()
        .add(cyclePlaybackSpeedCommand)
        .add(seekBackIncrementCommand)
        .add(seekForwardIncrementCommand)
        .add(setSleepTimerCommand)
        .add(cancelSleepTimerCommand)
        .add(adjustSleepTimerCommand)
        .add(getSleepTimerTimeCommand)
        .add(checkAutoSleepTimerCommand)
        .add(previousChapterCommand)
        .add(nextChapterCommand)
        .add(seekToChapterCommand)
        .build()

      return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        .setAvailableSessionCommands(sessionCommands)
        .setAvailablePlayerCommands(playerCommands)
        .build()
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
      super.onPostConnect(session, controller)
      debugLog { "Post-connect: controller=${controller.packageName}" }
    }


    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      debugLog { "onPlaybackResumption: Controller '${controller.packageName}' requested to resume playback." }

      return serviceScope.future {
        val preferServerUrisForCast = this@Media3PlaybackService::castPlayer.isInitialized &&
          activePlayer === castPlayer

        val latestServerItem = mediaManager.latestServerItemInProgress

        if (latestServerItem != null) {
          val mediaId = latestServerItem.episode?.id ?: latestServerItem.libraryItemWrapper.id
          val resolved = browseTree.resolvePlayableItem(
            mediaId = mediaId,
            payload = getPlayItemRequestPayload(forceTranscode = false),
            preferServerUrisForCast = preferServerUrisForCast
          )

          if (resolved != null) {
            assignPlaybackSession(resolved.session)
            debugLog {
              "onPlaybackResumption: Resolved server in-progress item=${resolved.session.id} " +
                "startIndex=${resolved.startIndex} startPos=${resolved.startPositionMs}"
            }
            return@future MediaSession.MediaItemsWithStartPosition(
              resolved.mediaItems,
              resolved.startIndex,
              resolved.startPositionMs
            )
          } else {
            Log.w(TAG, "onPlaybackResumption: Failed to resolve server in-progress item '$mediaId'. Falling back to last local session.")
          }
        }

        val lastSession = DeviceManager.getLastPlaybackSession()
        if (lastSession == null) {
          Log.w(TAG, "onPlaybackResumption: No last playback session found. Returning empty.")
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }

        debugLog {
          "onPlaybackResumption: Found last session for item '${lastSession.libraryItemId}' at ${lastSession.currentTimeMs}ms."
        }

        val preferServerUris = preferServerUrisForCast && lastSession.isLocal
        val mediaItems = lastSession.toPlayerMediaItems(
          this@Media3PlaybackService,
          preferServerUrisForCast = preferServerUris
        )
          .mapIndexed { index, playerMediaItem ->
            val mediaId = "${lastSession.id}_${index}"
            MediaItem.Builder()
              .setUri(playerMediaItem.uri)
              .setMediaId(mediaId)
              .setMediaMetadata(
                MediaMetadata.Builder()
                  .setTitle(lastSession.displayTitle)
                  .setArtist(lastSession.displayAuthor)
                  .setArtworkUri(playerMediaItem.artworkUri)
                  .build()
              )
              .build()
          }

        if (mediaItems.isEmpty()) {
          Log.e(TAG, "onPlaybackResumption: Failed to create MediaItems from last session.")
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        } else {
          val serverProgressMs = mediaManager.serverUserMediaProgress.find {
            it.libraryItemId == lastSession.libraryItemId && it.episodeId == lastSession.episodeId
          }?.currentTime?.times(1000)?.toLong() ?: 0L

          val resumeMs = maxOf(serverProgressMs, lastSession.currentTimeMs)
          val startIndex = resolveTrackIndexForPosition(lastSession, resumeMs)
            .coerceIn(0, mediaItems.lastIndex)
          val trackStartOffsetMs = lastSession.getTrackStartOffsetMs(startIndex)
          val startPositionMs = (resumeMs - trackStartOffsetMs).coerceAtLeast(0L)

          debugLog {
            "onPlaybackResumption: Resuming at index $startIndex with position ${startPositionMs}ms."
          }

          assignPlaybackSession(lastSession)
          MediaSession.MediaItemsWithStartPosition(
            mediaItems,
            startIndex,
            startPositionMs
          )
        }
      }
    }


    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle
    ): ListenableFuture<SessionResult> {
      when (customCommand.customAction) {
        CustomCommands.CYCLE_PLAYBACK_SPEED -> {
          cyclePlaybackSpeed()
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        CustomCommands.SEEK_BACK_INCREMENT -> {
          activePlayer.seekBack()
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        CustomCommands.SEEK_FORWARD_INCREMENT -> {
          activePlayer.seekForward()
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        CustomCommands.SEEK_TO_PREVIOUS_CHAPTER -> {
          val success = handleSeekToChapter(previous = true)
          val resultCode =
            if (success) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE
          return Futures.immediateFuture(SessionResult(resultCode))
        }

        CustomCommands.SEEK_TO_NEXT_CHAPTER -> {
          val success = handleSeekToChapter(previous = false)
          val resultCode =
            if (success) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE
          return Futures.immediateFuture(SessionResult(resultCode))
        }

        CustomCommands.SEEK_TO_CHAPTER -> {
          val chapterStartMs = args.getLong(CustomCommands.EXTRA_CHAPTER_START_MS, Long.MIN_VALUE)
          if (chapterStartMs >= 0L) {
            seekToAbsolutePosition(chapterStartMs)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
          }
          return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        }

        CustomCommands.SYNC_PROGRESS_FORCE -> {
          if (playerInitialized && activePlayer.isPlaying) {
            activePlayer.pause()
            updateCurrentPosition()
          }
          maybeSyncProgress("switch", force = true, onComplete = null)
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        SleepTimer.ACTION_SET -> {
          val timeMs = args.getLong(SleepTimer.EXTRA_TIME_MS, 0L)
          val isChapter = args.getBoolean(SleepTimer.EXTRA_IS_CHAPTER, false)
          val sessionId = args.getString(SleepTimer.EXTRA_SESSION_ID)
          val success = ensureSleepTimerManager().setManualSleepTimer(
            sessionId ?: currentPlaybackSession?.id ?: "", timeMs, isChapter
          )
          val resultCode =
            if (success) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE
          return Futures.immediateFuture(SessionResult(resultCode))
        }

        SleepTimer.ACTION_CANCEL -> {
          if (::sleepTimerManager.isInitialized) sleepTimerManager.cancelSleepTimer()
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        SleepTimer.ACTION_ADJUST -> {
          val delta = args.getLong(SleepTimer.EXTRA_ADJUST_DELTA, 0L)
          val increase = args.getBoolean(SleepTimer.EXTRA_ADJUST_INCREASE, true)
          if (!::sleepTimerManager.isInitialized || delta <= 0L) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
          }
          if (increase) sleepTimerManager.increaseSleepTime(delta) else sleepTimerManager.decreaseSleepTime(
            delta
          )
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        SleepTimer.ACTION_GET_TIME -> {
          val time =
            if (::sleepTimerManager.isInitialized) sleepTimerManager.getSleepTimerTime() else 0L
          val resultBundle = Bundle().apply { putLong(SleepTimer.EXTRA_TIME_MS, time) }
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
        }

        SleepTimer.ACTION_CHECK_AUTO -> {
          if (::sleepTimerManager.isInitialized) sleepTimerManager.checkAutoSleepTimer()
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        CustomCommands.CLOSE_PLAYBACK -> {
          closePlayback()
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
      }
      return super.onCustomCommand(session, controller, customCommand, args)
    }

    private fun handleSeekToChapter(previous: Boolean): Boolean {
      val session = currentPlaybackSession ?: return false
      val absPosition = currentAbsolutePositionMs() ?: return false
      if (session.chapters.isEmpty()) {
        if (previous) activePlayer.seekBack() else activePlayer.seekForward()
        return true
      }
      val targetChapter =
        if (previous) resolvePreviousChapter(session, absPosition) else
          session.getNextChapterForTime(absPosition)
      if (targetChapter != null) {
        seekToAbsolutePosition(targetChapter.startMs)
        return true
      }
      if (previous) activePlayer.seekBack() else activePlayer.seekForward()
      return true
    }

    private fun resolvePreviousChapter(session: PlaybackSession, currentMs: Long): BookChapter? {
      val chapters = session.chapters
      if (chapters.isEmpty()) return null
      val currentChapter = session.getChapterForTime(currentMs)
      if (currentChapter == null) {
        return chapters.firstOrNull()
      }
      val currentIndex = chapters.indexOf(currentChapter).coerceAtLeast(0)
      val nearStart = currentMs - currentChapter.startMs <= CHAPTER_RESTART_THRESHOLD_MS
      return when {
        nearStart && currentIndex > 0 -> chapters[currentIndex - 1]
        else -> currentChapter
      }
    }

    override fun onAddMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> {
      return serviceScope.future {
        val requested = mediaItems.firstOrNull()
        if (requested == null) {
          debugLog { "onAddMediaItems: empty request from ${controller.packageName}" }
          return@future mutableListOf<MediaItem>()
        }

        val isPlayableRequest =
          requested.localConfiguration != null || requested.requestMetadata.mediaUri != null

        if (isPlayableRequest) {
          debugLog { "onAddMediaItems: passthrough playable request '${requested.mediaId}'" }
          if (!isPassthroughRequestAllowed(requested.mediaId, controller)) {
            debugLog { "onAddMediaItems: rejecting passthrough request for id=${requested.mediaId}" }
            return@future mutableListOf<MediaItem>()
          }
          return@future mediaItems
        }

        val mediaId = requested.mediaId
        val preferServerUrisForCast = this@Media3PlaybackService::castPlayer.isInitialized &&
          activePlayer === castPlayer

        val resolved = resolvePlayableWithCache(mediaId, preferServerUrisForCast)

        if (resolved == null) {
          debugLog { "onAddMediaItems: unable to resolve mediaId=$mediaId" }
          return@future mutableListOf<MediaItem>()
        }

        assignPlaybackSession(resolved.session)
        debugLog {
          "onAddMediaItems: resolved ${resolved.mediaItems.size} items for session=${resolved.session.id} " +
            "startIndex=${resolved.startIndex} startPos=${resolved.startPositionMs}"
        }
        activePlayer.setMediaItems(
          resolved.mediaItems,
          resolved.startIndex.coerceIn(0, resolved.mediaItems.lastIndex),
          resolved.startPositionMs
        )
        activePlayer.prepare()
        return@future resolved.mediaItems.toMutableList()
      }
    }

    override fun onSetMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      return serviceScope.future {
        val playablePassthrough = mediaItems.filter {
          it.localConfiguration != null || it.requestMetadata.mediaUri != null
        }
        if (playablePassthrough.isNotEmpty()) {
          debugLog { "onSetMediaItems: passthrough items=${playablePassthrough.size}" }
          if (playablePassthrough.any { !isPassthroughRequestAllowed(it.mediaId, controller) }) {
            debugLog { "onSetMediaItems: rejecting passthrough set; ids do not match current session" }
            return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
          }
          return@future MediaSession.MediaItemsWithStartPosition(
            playablePassthrough,
            startIndex,
            startPositionMs
          )
        }

        val requested = mediaItems.firstOrNull()
        if (requested == null) {
          debugLog { "onSetMediaItems: empty request from ${controller.packageName}" }
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }

        val mediaId = requested.mediaId
        val preferServerUrisForCast = this@Media3PlaybackService::castPlayer.isInitialized &&
          activePlayer === castPlayer

        val resolved = resolvePlayableWithCache(mediaId, preferServerUrisForCast)

        if (resolved == null) {
          debugLog { "onSetMediaItems: unable to resolve mediaId=$mediaId" }
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }

        assignPlaybackSession(resolved.session)
        debugLog {
          "onSetMediaItems: resolved ${resolved.mediaItems.size} items for session=${resolved.session.id} " +
            "startIndex=${resolved.startIndex} startPos=${resolved.startPositionMs}"
        }
        MediaSession.MediaItemsWithStartPosition(
          resolved.mediaItems,
          resolved.startIndex,
          resolved.startPositionMs
        )
      }
    }

    /**
     * This is the entry point for Android Auto. It should be fast and synchronous.
     * It simply provides the static root node of the browse tree.
     */
    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
      debugLog { "onGetLibraryRoot requested by ${browser.packageName}" }
      return Futures.immediateFuture(LibraryResult.ofItem(browseTree.getRootItem(), params))
    }

    /**
     * This is the CORE of the browsing logic. It is called on-demand by Android Auto
     * whenever the user taps on a browsable item.
     */

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      Log.d(TAG, "onGetChildren requested for parentId: '$parentId'")
      return autoLibraryCoordinator.requestChildren(parentId, params)
    }

    override fun onGetItem(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
      debugLog { "onGetItem: Resolving '$mediaId' via browseTree for ${browser.packageName}" }
      return serviceScope.future {
        val mediaItem = browseTree.getItem(mediaId)

        if (mediaItem == null) {
          debugLog { "onGetItem: browseTree.getItem failed to resolve '$mediaId'" }
          return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        LibraryResult.ofItem(mediaItem, null)
      }
    }
  }

  /**
   * Player event listener to forward state changes to UI.
   */
  private inner class PlayerEventListener : Player.Listener {
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
      debugLog { "AvailableCommandsChanged: ${cmd.joinToString(",")}" }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      debugLog { "onIsPlayingChanged: $isPlaying" }

      if (lastKnownIsPlaying == isPlaying) {
        return
      }

      val session = currentPlaybackSession
      if (session != null) {
        updateCurrentPosition()

        if (isPlaying) {
          // Cancel any pending error retry when playback resumes
          errorRetryJob?.cancel()
          errorRetryJob = null
          consecutiveErrorCount = 0
          MediaEventManager.playEvent(session)
          ensureSleepTimerManager().handleMediaPlayEvent(session.id)
          if (playerInitialized) {
            activePlayer.volume = 1f
          }
          stopPositionUpdates()
          startPositionUpdates()
        } else {
          stopPositionUpdates()
          maybeSyncProgress("pause", force = true) { result ->
            MediaEventManager.pauseEvent(session, result)
          }
        }
      }

      lastKnownIsPlaying = isPlaying
      notifyWidgetState()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      debugLog { "onPlaybackStateChanged: $playbackState" }
      when (playbackState) {
        Player.STATE_READY -> playbackMetrics.recordFirstReadyIfUnset()
        Player.STATE_BUFFERING -> playbackMetrics.recordBuffer()
        Player.STATE_ENDED -> {
          debugLog { "Playback ended" }
          playbackMetrics.logSummary()
          currentPlaybackSession?.let { session ->
            updateCurrentPosition()
            maybeSyncProgress("ended", force = true) { result ->
              MediaEventManager.stopEvent(session, result)
            }
          }
          notifyWidgetState()
        }
      }

    }

    override fun onPlayerError(error: PlaybackException) {
      Log.e(TAG, "Player error: ${error.message}", error)
      playbackMetrics.recordError()

      // Determine if error is recoverable
      val isRecoverable = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true

        else -> false
      }

      if (!isRecoverable) {
        debugLog { "Non-recoverable error: ${error.errorCodeName}" }
        consecutiveErrorCount = 0
        errorRetryJob?.cancel()
        errorRetryJob = null
        return
      }

      val now = System.currentTimeMillis()
      // Reset counter if errors are spaced out (> 30s)
      if (now - lastErrorTimeMs > ERROR_RESET_WINDOW_MS) {
        consecutiveErrorCount = 0
      }
      lastErrorTimeMs = now
      consecutiveErrorCount++

      val maxRetries = 3
      if (consecutiveErrorCount > maxRetries) {
        Log.w(TAG, "Max retries ($maxRetries) exceeded for recoverable error")
        return
      }

      // Exponential backoff: 1s, 2s, 4s
      val backoffMs =
        (RETRY_BACKOFF_STEP_MS * (1 shl (consecutiveErrorCount - 1))).coerceAtMost(4 * RETRY_BACKOFF_STEP_MS)
      debugLog { "Recoverable error (attempt $consecutiveErrorCount/$maxRetries), retrying in ${backoffMs}ms" }

      errorRetryJob?.cancel()
      errorRetryJob = serviceScope.launch {
        kotlinx.coroutines.delay(backoffMs)
        if (playerInitialized && currentPlaybackSession != null) {
          debugLog { "Retrying playback after error..." }
          activePlayer.prepare()
          if (lastKnownIsPlaying) {
            activePlayer.play()
          }
        }
      }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      updatePlaybackSpeedButton(playbackParameters.speed)
    }

    override fun onVolumeChanged(volume: Float) {
      debugLog { "onVolumeChanged: $volume (player volume updated)" }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      debugLog { "onMediaItemTransition: reason=$reason" }
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int
    ) {
      debugLog { "onPositionDiscontinuity: reason=$reason, oldPos=${oldPosition.positionMs}, newPos=${newPosition.positionMs}" }
      if (reason == Player.DISCONTINUITY_REASON_SEEK ||
        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
      ) {
        currentPlaybackSession?.let { session ->
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
    mediaSession?.let { session ->
      if (::seekBackButton.isInitialized && ::seekForwardButton.isInitialized) {
        session.setMediaButtonPreferences(
          ImmutableList.of(speedButton, seekBackButton, seekForwardButton)
        )
      }
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
}
