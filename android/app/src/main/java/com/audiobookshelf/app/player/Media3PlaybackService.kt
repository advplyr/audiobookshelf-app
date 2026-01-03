package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.audiobookshelf.app.*
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.media.*
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.*
import com.audiobookshelf.app.player.media3.*
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import com.audiobookshelf.app.server.ApiHandler
import kotlinx.coroutines.*
import kotlin.math.*


/**
 * Media3 playback service following MediaLibraryService architecture.
 * Handles local playback, session management, and native Media3 notifications.
 * Cast playback is handled via Media3 CastPlayer within this service.
 */

@UnstableApi
class Media3PlaybackService : MediaLibraryService() {
  companion object {
    val TAG: String = Media3PlaybackService::class.java.simpleName

    // Cache settings
    private const val RESOLVED_CACHE_TTL_MS = 5_000L
    private const val RESOLVED_CACHE_LIMIT = 6

    // Sync & timeout settings
    private const val TASK_REMOVAL_CLOSE_TIMEOUT_MS = 5_000L
    private const val FINAL_SYNC_TIMEOUT_MS = 500L
    private const val ONBOARDING_SYNC_TIMEOUT_SEC = 1L

    // Playback recheck settings
    private const val PAUSE_LEN_BEFORE_RECHECK_MS = 30_000L

    // Player identifiers - must match values in PlayerNotificationService for server compatibility
    private const val PLAYER_MEDIA3 = "media3-exoplayer"
    private const val PLAYER_CAST = "cast-player"

    // Bundle keys
    private const val KEY_IS_APP_UI_CONTROLLER = "isAppUiController"

  }

  // Lifecycle & Scope
  private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  // Media3 Core Components
  private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: Player
  private var playbackPipeline: PlaybackPipeline? = null

  // Media3 Managers & Coordinators
  private lateinit var apiHandler: ApiHandler
  private lateinit var mediaManager: MediaManager
  private lateinit var browseTree: Media3BrowseTree
  private lateinit var autoLibraryCoordinator: Media3AutoLibraryCoordinator
  private lateinit var unifiedProgressSyncer: UnifiedMediaProgressSyncer
  private lateinit var media3SessionManager: Media3SessionManager
  private lateinit var media3NotificationManager: Media3NotificationManager
  private val sleepTimerCoordinator = SleepTimerCoordinator(serviceScope)
  private var networkStateListener: NetworkMonitor.Listener? = null

  // Pipelines & State Trackers
  private val eventPipeline = Media3EventPipeline()
  private val playbackMetrics = PlaybackMetricsRecorder()
  private val currentPlaybackSession: PlaybackSession?
    get() = media3SessionManager.currentPlaybackSession

  // Player State & Synchronization
  @Volatile
  private var playerInitialized = false
  private val hasActivePlayer: Boolean
      get() = playerInitialized && this::player.isInitialized
  private val isCastActive: Boolean
      get() {
          if (!this::player.isInitialized) return false
          return player.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
      }
  private var finalSyncBarrier: CompletableDeferred<SyncResult?>? = null
  @Volatile
  private var suppressFinalServerSync: Boolean = false

  private var transcodeFallbackAttemptedSessionId: String? = null

  // Public Player Access (for other components)
  val isPlayerActive: Boolean
    get() = hasActivePlayer
    val currentPlayer: Player
        get() = player

  // Audio Configuration
  private val speechAudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
    .build()

  // Playback Controls
  private var jumpBackwardMs: Long = 10000L
  private var jumpForwardMs: Long = 10000L
  private val closePlaybackSignal: CompletableDeferred<Unit>?
    get() = media3SessionManager.getClosePlaybackSignal()

  // Notification Components
  private lateinit var notificationProvider: MediaNotification.Provider
  private var playbackSpeedCommandButton: CommandButton? = null

  // Widget state cache
  private var lastWidgetSnapshot: WidgetPlaybackSnapshot? = null

  // Session Commands
  private val cyclePlaybackSpeedCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED)
  private val seekBackIncrementCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_BACK_INCREMENT)
  private val seekForwardIncrementCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT)
  private val seekPreviousTrackCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_PREVIOUS_TRACK)
  private val seekNextTrackCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_NEXT_TRACK)

  // Caching & Settings
  private val resolvedCache = ResolvedPlayableCache(
    RESOLVED_CACHE_TTL_MS,
    RESOLVED_CACHE_LIMIT
  )
  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  private val playerListener = Media3PlayerEventListener(
      serviceCallbacks = PlayerListenerBridge(),
      playerEventPipeline = eventPipeline
  )

    private inner class PlayerListenerBridge : ListenerApi {
        override val tag: String = TAG
        override val playbackMetrics: PlaybackMetricsRecorder =
            this@Media3PlaybackService.playbackMetrics

        override fun currentSession(): PlaybackSession? = currentPlaybackSession
        override fun activePlayer(): Player = this@Media3PlaybackService.player
        override fun isPlayerInitialized(): Boolean = playerInitialized
        override fun lastKnownIsPlaying(): Boolean = isEffectivelyPlaying()

        override fun updateCurrentPosition(sessionToUpdate: PlaybackSession?) {
            this@Media3PlaybackService.updateCurrentPosition(sessionToUpdate ?: return)
        }

        override fun maybeSyncProgress(
            changeReason: String,
            forceSync: Boolean,
            sessionToUpdate: PlaybackSession?,
            onSyncComplete: ((SyncResult?) -> Unit)?
        ) {
            this@Media3PlaybackService.maybeSyncProgress(
                changeReason,
                forceSync,
                sessionToUpdate,
                onSyncComplete
            )
        }

        override fun progressSyncPlay(currentSession: PlaybackSession) {
            if (this@Media3PlaybackService::unifiedProgressSyncer.isInitialized) {
                unifiedProgressSyncer.play(currentSession)
            }
        }

        override fun progressSyncPause() {
            if (this@Media3PlaybackService::unifiedProgressSyncer.isInitialized) {
                unifiedProgressSyncer.pause {}
            }
        }

        override fun onPlayStarted(currentSessionId: String) {
            ensureSleepTimerStarted()
            sleepTimerCoordinator.handlePlayStarted(currentSessionId)
        }

        override fun notifyWidgetState() {
            this@Media3PlaybackService.notifyWidgetState()
        }

        override fun updatePlaybackSpeedButton(speed: Float) {
            this@Media3PlaybackService.updatePlaybackSpeedButton(speed)
        }

        override fun debug(message: () -> String) {
            this@Media3PlaybackService.debugLog(message)
        }

        override fun currentMediaPlayerId(): String {
            return this@Media3PlaybackService.currentMediaPlayerId()
        }

        override fun getPlaybackSessionAssignTimestampMs(): Long {
            return media3SessionManager.sessionAssignTimestampMs
        }

        override fun resetPlaybackSessionAssignTimestamp() {
            media3SessionManager.resetSessionAssignTimestamp()
        }

        override fun handlePlaybackError(playbackError: PlaybackException) {
            this@Media3PlaybackService.handlePlaybackError(playbackError)
        }

        override fun onPlaybackEnded(session: PlaybackSession) {
            this@Media3PlaybackService.handlePlaybackEnded(session)
        }

        override fun onPlaybackResumed(pauseDurationMs: Long) {
            this@Media3PlaybackService.handlePlaybackResumed(pauseDurationMs)
        }

        override fun onCastDeviceChanged(isCast: Boolean) {
            this@Media3PlaybackService.handleCastDeviceChanged(isCast)
        }
    }


  /* ========================================
   * Lifecycle Methods
   * ======================================== */
  override fun onCreate() {
    super.onCreate()
    playbackMetrics.noteServiceStart()
    debugLog { "onCreate: Initializing Media3 playback service" }

    DbManager.initialize(this)
    DeviceManager.initializeWidgetUpdater(this)
    applyJumpIncrementsFromDeviceSettings()
    setupMediaManagers()
    registerNetworkMonitor()

    initializeMedia3NotificationManager()
    notificationProvider = media3NotificationManager.createNotificationProvider()
    setMediaNotificationProvider(notificationProvider)

    initializeMedia3SessionManager()
    setupPlaybackPipeline()
  }

  override fun onDestroy() {
    try {
      val session = currentPlaybackSession
      if (session != null && this::unifiedProgressSyncer.isInitialized && playerInitialized) {
          updateCurrentPosition(session)
        val latch = java.util.concurrent.CountDownLatch(1)
        unifiedProgressSyncer.syncNow(
          "stop",
          session.clone(),
          shouldSyncServer = true
        ) { latch.countDown() }
        latch.await(ONBOARDING_SYNC_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)

        // Close session on server if not local
        if (!session.isLocal && session.id.isNotEmpty()) {
          apiHandler.closePlaybackSession(
            session.id,
            DeviceManager.serverConnectionConfig
          ) { success ->
            debugLog { "onDestroy: Closed playback session ${session.id} on server: $success" }
          }
        }
      }
    } catch (_: Exception) {
    }

    super.onDestroy()
    if (this::unifiedProgressSyncer.isInitialized) {
      unifiedProgressSyncer.cleanup()
    }
    serviceScope.cancel()
    cleanupPlaybackResources()
    networkStateListener?.let { NetworkMonitor.removeListener(it) }
    notifyWidgetState(isPlaybackClosed = true)
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

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
    return mediaSession
  }


  /* ========================================
   * Setup & Initialization
   * ======================================== */
  private fun setupMediaManagers() {
    apiHandler = ApiHandler(this)
    mediaManager = MediaManager(apiHandler, this)
    browseTree = Media3BrowseTree(this, mediaManager)
    autoLibraryCoordinator = Media3AutoLibraryCoordinator(mediaManager, browseTree, serviceScope)
    NetworkMonitor.initialize(applicationContext)
    val telemetryHost = object : PlaybackTelemetryHost {
      override val appContext = applicationContext
      override val isUnmeteredNetwork: Boolean
        get() = NetworkMonitor.isUnmeteredNetwork

      override fun isPlayerActive(): Boolean {
          return hasActivePlayer && player.isPlaying
      }

      override fun getCurrentTimeSeconds(): Double {
        val session = currentPlaybackSession ?: return 0.0
          updateCurrentPosition(session)
        return session.currentTime
      }

      override fun alertSyncSuccess() {
        MediaEventManager.clientEventEmitter?.onProgressSyncSuccess()
      }

      override fun alertSyncFailing() {
        MediaEventManager.clientEventEmitter?.onProgressSyncFailing()
      }

      override fun notifyLocalProgressUpdate(localMediaProgress: LocalMediaProgress) {
        MediaEventManager.clientEventEmitter?.onLocalMediaProgressUpdate(localMediaProgress)
      }

      override fun isSleepTimerActive(): Boolean {
        return sleepTimerCoordinator.isStarted()
      }

      override fun checkAutoSleepTimer() {
        sleepTimerCoordinator.checkAutoTimerIfNeeded()
      }
    }

    unifiedProgressSyncer = UnifiedMediaProgressSyncer(
      playbackTelemetryProvider = telemetryHost,
      progressApi = apiHandler
    ) { event, session, result ->
      when (event) {
        "save" -> eventPipeline.emitSaveEvent(session, result)
        "pause" -> eventPipeline.emitPauseEvent(session, result)
        "stop" -> eventPipeline.emitStopEvent(session, result)
        "finished" -> eventPipeline.emitFinishedEvent(session, result)
      }
    }
  }

  private fun registerNetworkMonitor() {
    val listener = NetworkMonitor.Listener { state ->
      debugLog {
        "Network state changed. hasNetworkConnectivity=${state.hasConnectivity} | isUnmeteredNetwork=${state.isUnmetered}"
      }
      if (state.hasConnectivity && mediaManager.serverLibraries.isEmpty()) {
        serviceScope.launch { runCatching { autoLibraryCoordinator.awaitAutoDataLoaded() } }
      }
    }
    networkStateListener = listener
    NetworkMonitor.addListener(listener)
  }

  private fun initializeMedia3NotificationManager() {
    media3NotificationManager = Media3NotificationManager(
      context = this,
      cyclePlaybackSpeedCommand = cyclePlaybackSpeedCommand,
      seekBackIncrementCommand = seekBackIncrementCommand,
      seekForwardIncrementCommand = seekForwardIncrementCommand,
      jumpBackwardMsProvider = { jumpBackwardMs },
      jumpForwardMsProvider = { jumpForwardMs },
        currentPlaybackSpeedProvider = { if (hasActivePlayer) player.playbackParameters.speed else 1.0f },
      debugLog = { lazyMessage -> debugLog { lazyMessage } }
    )
    media3NotificationManager.createNotificationChannel()
  }

  private fun initializeMedia3SessionManager() {
    media3SessionManager = Media3SessionManager(
      serviceScope = serviceScope,
      mediaManager = mediaManager,
      playbackMetrics = playbackMetrics,
      currentMediaPlayerIdProvider = { currentMediaPlayerId() },
        updateCurrentPosition = { session -> updateCurrentPosition(session) },
      maybeSyncProgress = { reason, force, session, onSyncComplete ->
        val currentSession = session ?: currentPlaybackSession
        if (currentSession != null) {
            updateCurrentPosition(currentSession)
          unifiedProgressSyncer.syncNow(reason, currentSession, force, onSyncComplete ?: {})
        } else {
          onSyncComplete?.invoke(null)
        }
      },
      notifyWidgetState = { isPlaybackClosed -> notifyWidgetState(isPlaybackClosed) },
      isPlayerInitialized = { playerInitialized },
        stopPlayer = { if (playerInitialized) player.stop() },
        clearPlayerMediaItems = { if (playerInitialized) player.clearMediaItems() },
      setPlayerNotInitialized = { playerInitialized = false },
      setPlayerInitialized = { playerInitialized = true },
      closeSessionOnServer = { sessionId ->
        apiHandler.closePlaybackSession(
          sessionId,
          DeviceManager.serverConnectionConfig
        ) { success ->
          debugLog { "Closed playback session $sessionId on server: $success" }
        }
      }
    )
  }

  private fun setupPlaybackPipeline() {
      initializePlayer()
    configureCommandButtons()

    val sessionId = "AudiobookshelfMedia3_${System.currentTimeMillis()}"
    val sessionActivityIntent = createSessionActivityIntent()
    buildMediaLibrarySession(sessionId, sessionActivityIntent)

    playbackMetrics.recordServiceReady()
  }

  private fun applyJumpIncrementsFromDeviceSettings() {
    val settings = deviceSettings
    jumpBackwardMs = settings.jumpBackwardsTimeMs
    jumpForwardMs = settings.jumpForwardTimeMs
  }

  private fun cleanupPlaybackResources() {
    mediaSession?.run {
      release()
      mediaSession = null
    }
      if (playerInitialized && this::player.isInitialized) {
          player.release()
      playerInitialized = false
    }
    sleepTimerCoordinator.release()
    SleepTimerNotificationCenter.unregister()
  }


    private fun initializePlayer() {
    val pipeline = playbackPipeline ?: PlaybackPipeline(
      context = this,
      log = { msg -> debugLog(msg) }
    ).also { playbackPipeline = it }

        pipeline.initializePlayer(
      enableMp3IndexSeeking = deviceSettings.enableMp3IndexSeeking,
      speechAttributes = speechAudioAttributes,
      seekBackIncrementMs = jumpBackwardMs,
      seekForwardIncrementMs = jumpForwardMs,
            onPlayerReady = { playerWrapper ->
                this@Media3PlaybackService.player = playerWrapper
        updateMediaPlayerExtra()
        playerInitialized = true
                applySavedPlaybackSpeed(playerWrapper)
      },
      buildListener = { playerListener }
    )
  }


    private fun currentMediaPlayerId(): String {
        if (!this::player.isInitialized) return PLAYER_MEDIA3
        val deviceInfo = player.deviceInfo
        return if (deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            PLAYER_CAST
        } else {
            PLAYER_MEDIA3
        }
    }

    private fun handleCastDeviceChanged(isCast: Boolean) {
        val newPlayerId = if (isCast) PLAYER_CAST else PLAYER_MEDIA3

        currentPlaybackSession?.mediaPlayer = newPlayerId
        playbackMetrics.updatePlayerId(newPlayerId)

        MediaEventManager.clientEventEmitter?.onMediaPlayerChanged(newPlayerId)
        notifyWidgetState()
        updateMediaPlayerExtra()
        updateTrackNavigationButtons()

        val session = currentPlaybackSession
        if (session != null && isCast && session.isLocal) {
            reloadQueueForCast(session)
    }

        debugLog { "Cast device changed: isCast=$isCast, newPlayerId=$newPlayerId" }
  }

    private fun reloadQueueForCast(session: PlaybackSession) {
        val wasPlaying = player.isPlaying
        val currentPosition = currentAbsolutePositionMs() ?: session.currentTimeMs

        updateCurrentPosition(session)

        val mediaItems = session.toMedia3MediaItems(
            this,
            preferServerUrisForCast = true
        )
        if (mediaItems.isEmpty()) return

        val trackIndex = resolveTrackIndexForPlayer(session, player)
            .coerceIn(0, mediaItems.lastIndex)
        val trackStartOffsetMs = session.getTrackStartOffsetMs(trackIndex)
        val positionInTrack = (currentPosition - trackStartOffsetMs).coerceAtLeast(0L)

        player.setMediaItems(mediaItems, trackIndex, positionInTrack)
        player.prepare()
        player.playWhenReady = wasPlaying

        debugLog { "Reloaded queue with cast-friendly URIs at track=$trackIndex, position=${positionInTrack}ms" }
  }

  private fun switchPlaybackSession(
    session: PlaybackSession,
    syncPreviousSession: Boolean = true
  ) {
    val isNewSession = currentPlaybackSession?.id != session.id
    if (isNewSession) {
      transcodeFallbackAttemptedSessionId = null
    }
    media3SessionManager.switchPlaybackSession(session, syncPreviousSession)
    updateTrackNavigationButtons()
    if (isNewSession) {
      applySavedPlaybackSpeed()
    }
  }

  fun closePlayback(onPlaybackStopped: (() -> Unit)? = null) {
    media3SessionManager.closePlayback {
      // After session manager completes, stop the service
      media3NotificationManager.setTrackNavigationEnabled(false)
      onPlaybackStopped?.invoke()
      stopSelf()
    }
  }

  private fun isHostController(controllerInfo: MediaSession.ControllerInfo?): Boolean {
    return controllerInfo?.packageName == packageName
  }

  private fun syncSessionFromHostController() {
    media3SessionManager.syncSessionFromHostController()
  }

  private fun isPassthroughRequestAllowed(
    requestedMediaId: String?,
    controllerInfo: MediaSession.ControllerInfo?
  ): Boolean {
    if (requestedMediaId.isNullOrBlank()) return false

    var sessionId = currentPlaybackSession?.id
    if (sessionId == null && isHostController(controllerInfo)) {
      syncSessionFromHostController()
      sessionId = currentPlaybackSession?.id
    }

    if (sessionId == null) return false
    if (requestedMediaId.startsWith(sessionId)) return true

    if (isHostController(controllerInfo)) {
      debugLog { "Allowing passthrough request from host app despite session mismatch" }
      syncSessionFromHostController()
      return true
    }
    return false
  }


  /* ========================================
   * Position Tracking & Seeking
   * ======================================== */
  private fun currentAbsolutePositionMs(): Long? {
    if (!playerInitialized) return null
    val session = currentPlaybackSession ?: return null
      val currentPlayer = this@Media3PlaybackService.player
      val mediaItemCount = currentPlayer.mediaItemCount
      if (mediaItemCount <= 0) return currentPlayer.currentPosition.coerceAtLeast(0L)
    val trackIndex =
        resolveTrackIndexForPlayer(session, currentPlayer).coerceIn(0, mediaItemCount - 1)
    val offset = session.getTrackStartOffsetMs(trackIndex)
      return (currentPlayer.currentPosition + offset).coerceAtLeast(0L)
  }

    private fun updateCurrentPosition(session: PlaybackSession) {
        if (isPlayerActive) {
            val player = currentPlayer
            val trackIndex = resolveTrackIndexForPlayer(session, player)
            val positionInTrack = player.currentPosition
            val trackStartOffset = session.getTrackStartOffsetMs(trackIndex)
            val absolutePositionMs = trackStartOffset + positionInTrack

            session.currentTime = (absolutePositionMs / 1000.0)
        }
  }

    private fun resolveTrackIndexForPlayer(session: PlaybackSession, player: Player): Int {
        val tracks = session.audioTracks
        if (tracks.isEmpty()) return 0

        val mediaId = player.currentMediaItem?.mediaId
        if (!mediaId.isNullOrEmpty()) {
            tracks.forEachIndexed { index, track ->
                if (mediaId=="${session.id}_${track.stableId}") {
                    return index
                }
            }
        }

        val playerIndex = player.currentMediaItemIndex
        if (playerIndex in tracks.indices) {
            return playerIndex
        }

        return session.getCurrentTrackIndex().coerceIn(0, tracks.lastIndex)
  }

  private suspend fun awaitFinalSyncBarrier() {
    val barrier = finalSyncBarrier ?: return
    if (barrier.isCompleted) return

    try {
      withTimeout(FINAL_SYNC_TIMEOUT_MS) { barrier.await() }
    } catch (_: Exception) {
    }
  }


  /* ========================================
   * Progress Sync
   * ======================================== */
  private fun maybeSyncProgress(
    reason: String,
    force: Boolean = false,
    targetSession: PlaybackSession? = null,
    onSyncComplete: ((SyncResult?) -> Unit)? = null
  ) {
    val session = targetSession ?: currentPlaybackSession
    if (!this::unifiedProgressSyncer.isInitialized || session == null) {
      onSyncComplete?.invoke(null)
      return
    }

    // Create barrier for critical sync points (pause/ended/close) to ensure completion before service cleanup
    // This prevents progress loss if service terminates immediately after these events
    val shouldCreateBarrier = (reason == "pause" || reason == "ended" || reason == "close")
    val barrier = if (shouldCreateBarrier && finalSyncBarrier == null) {
      CompletableDeferred<SyncResult?>().also { finalSyncBarrier = it }
    } else finalSyncBarrier

    // Determine server sync necessity: critical events always sync, others check connectivity
    val shouldSyncServerOverride =
      if (shouldCreateBarrier && suppressFinalServerSync) false else null
    val shouldSyncServer = when (reason) {
      "pause", "ended", "close" -> shouldSyncServerOverride ?: true
      else -> null ?: (force || DeviceManager.checkConnectivity(
        applicationContext
      ))
    }

    // Suppression flag allows emergency cancellation (e.g., user force-stops app)
    if (suppressFinalServerSync && !shouldSyncServer) {
      debugLog { "Skipping progress sync due to suppression: $reason" }
      val suppressedResult = SyncResult(false, null, "Suppressed")
      barrier?.let { if (!it.isCompleted) it.complete(suppressedResult) }
      finalSyncBarrier = null
      suppressFinalServerSync = false
      onSyncComplete?.invoke(suppressedResult)
      return
    }
    val completion: (SyncResult?) -> Unit = { syncResult ->
      barrier?.let { if (!it.isCompleted) it.complete(syncResult) }
      onSyncComplete?.invoke(syncResult)
      if (shouldCreateBarrier && finalSyncBarrier === barrier && (barrier?.isCompleted == true)) {
        finalSyncBarrier = null
        suppressFinalServerSync = false
      }
    }
      updateCurrentPosition(session)
    unifiedProgressSyncer.syncNow(reason, session, shouldSyncServer, completion)
  }


  /* ========================================
   * Playback Recovery Helpers
   * ======================================== */
  private fun handlePlaybackError(playbackError: PlaybackException) {
    val session = currentPlaybackSession ?: return
    if (!session.isDirectPlay || session.isLocal) return
    if (transcodeFallbackAttemptedSessionId == session.id) return

    transcodeFallbackAttemptedSessionId = session.id
    serviceScope.launch {
        try {
            val fallbackSession = requestPlaybackSession(
                libraryItemId = session.libraryItemId ?: return@launch,
                episodeId = session.episodeId,
                forceTranscode = true
            )
            if (fallbackSession==null) {
                Log.w(
                    TAG,
                    "handlePlaybackError: transcode fallback failed for session=${session.id}"
                )
                MediaEventManager.clientEventEmitter?.onPlaybackFailed("Unable to play this item")
                return@launch
            }
        val currentSpeed = if (hasActivePlayer) player.playbackParameters.speed else null
            prepareAndPlaySession(
                fallbackSession,
                playWhenReady = true,
                playbackSpeed = currentSpeed,
                syncOnSwitch = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "handlePlaybackError: Exception during transcode fallback", e)
            MediaEventManager.clientEventEmitter?.onPlaybackFailed("Unable to play this item")
        }
    }
  }

  private fun handlePlaybackEnded(session: PlaybackSession) {
    if (!session.isPodcastEpisode) {
      closePlayback()
      return
    }

    if (!isAndroidAutoControllerConnected()) return
    val libraryItem = session.libraryItem ?: return
      val currentSpeed = if (hasActivePlayer) player.playbackParameters.speed else null

    mediaManager.loadServerUserMediaProgress {
      val podcast = libraryItem.media as? Podcast ?: return@loadServerUserMediaProgress
      val nextEpisode = podcast.getNextUnfinishedEpisode(libraryItem.id, mediaManager)
        ?: return@loadServerUserMediaProgress

      val payload = getPlayItemRequestPayload(forceTranscode = session.isHLS)
      mediaManager.play(libraryItem, nextEpisode, payload) { nextSession ->
        if (nextSession != null) {
          serviceScope.launch(Dispatchers.Main) {
            prepareAndPlaySession(nextSession, playWhenReady = true, playbackSpeed = currentSpeed)
          }
        }
      }
    }
  }

  private fun handlePlaybackResumed(pauseDurationMs: Long) {
    if (pauseDurationMs < PAUSE_LEN_BEFORE_RECHECK_MS) return
    if (!DeviceManager.checkConnectivity(applicationContext)) return
    val session = currentPlaybackSession ?: return
    val seekBackTimeMs =
      if (deviceSettings.disableAutoRewind) 0L else calcPauseSeekBackTime(pauseDurationMs)

    if (session.isLocal) {
      val serverConfig = DeviceManager.getServerConnectionConfig(session.serverConnectionConfigId)
        ?: return
      apiHandler.getMediaProgress(
        session.libraryItemId ?: return,
        session.episodeId,
        serverConfig
      ) { mediaProgress ->
        if (mediaProgress != null &&
          mediaProgress.lastUpdate > session.updatedAt &&
          mediaProgress.currentTime != session.currentTime
        ) {
          serviceScope.launch(Dispatchers.Main) {
            session.currentTime = mediaProgress.currentTime
            seekToSessionPosition(session)
            if (seekBackTimeMs > 0) {
              seekBackwardWithinSession(seekBackTimeMs, session)
            }
            if (this@Media3PlaybackService::unifiedProgressSyncer.isInitialized) {
              unifiedProgressSyncer.play(session)
            }
          }
        } else if (seekBackTimeMs > 0) {
          serviceScope.launch(Dispatchers.Main) {
            seekBackwardWithinSession(
              seekBackTimeMs,
              session
            )
          }
        }
      }
    } else {
      apiHandler.getPlaybackSession(session.id) {
        if (it == null) {
          serviceScope.launch(Dispatchers.Main) { startNewPlaybackSessionFromServer(session) }
        } else if (seekBackTimeMs > 0) {
          serviceScope.launch(Dispatchers.Main) {
            seekBackwardWithinSession(
              seekBackTimeMs,
              session
            )
          }
        }
      }
    }
  }

  private fun seekToSessionPosition(session: PlaybackSession) {
    if (!hasActivePlayer) return
    val trackIndex = session.getCurrentTrackIndex().coerceIn(0, session.audioTracks.lastIndex)
    val trackOffsetMs = session.getTrackStartOffsetMs(trackIndex)
    val positionInTrack = (session.currentTimeMs - trackOffsetMs).coerceAtLeast(0L)
      player.seekTo(trackIndex, positionInTrack)
  }

  private fun seekBackwardWithinSession(amountMs: Long, session: PlaybackSession) {
    if (amountMs <= 0) return
    val targetPosition = (session.currentTimeMs - amountMs).coerceAtLeast(0L)
    session.currentTime = targetPosition / 1000.0
    seekToSessionPosition(session)
  }

  private suspend fun requestPlaybackSession(
    libraryItemId: String,
    episodeId: String?,
    forceTranscode: Boolean
  ): PlaybackSession? {
    val deferred = CompletableDeferred<PlaybackSession?>()
    apiHandler.playLibraryItem(
      libraryItemId,
      episodeId,
      getPlayItemRequestPayload(forceTranscode)
    ) { newSession -> deferred.complete(newSession) }
    return deferred.await()
  }

  private fun prepareAndPlaySession(
    session: PlaybackSession,
    playWhenReady: Boolean,
    playbackSpeed: Float? = null,
    syncOnSwitch: Boolean = true
  ) {
    switchPlaybackSession(session, syncOnSwitch)
    val mediaItems = session.toMedia3MediaItems(
      this,
      preferServerUrisForCast = isCastActive
    )
    if (mediaItems.isEmpty()) return

    val trackIndex = session.getCurrentTrackIndex().coerceIn(0, mediaItems.lastIndex)
    val trackStartOffsetMs = session.getTrackStartOffsetMs(trackIndex)
    val positionInTrack = (session.currentTimeMs - trackStartOffsetMs).coerceAtLeast(0L)

      player.setMediaItems(mediaItems, trackIndex, positionInTrack)
      player.setPlaybackSpeed(playbackSpeed ?: mediaManager.getSavedPlaybackRate())
      player.prepare()
      player.playWhenReady = playWhenReady
    updateTrackNavigationButtons()

    notifyWidgetState(isPlayingOverride = playWhenReady)

    MediaEventManager.clientEventEmitter?.onMediaPlayerChanged(currentMediaPlayerId())
  }

  private fun startNewPlaybackSessionFromServer(session: PlaybackSession) {
    val libraryItemId = session.libraryItemId ?: return
    serviceScope.launch {
      val newSession = requestPlaybackSession(
        libraryItemId = libraryItemId,
        episodeId = session.episodeId,
        forceTranscode = session.isHLS
      ) ?: return@launch
        val currentSpeed = if (hasActivePlayer) player.playbackParameters.speed else null
      prepareAndPlaySession(
        newSession,
        playWhenReady = true,
        playbackSpeed = currentSpeed,
        syncOnSwitch = false
      )
    }
  }

  private fun calcPauseSeekBackTime(pauseDuration: Long): Long {
    return when {
      pauseDuration < 10_000 -> 0L
      pauseDuration < 60_000 -> 3_000L
      pauseDuration < 300_000 -> 10_000L
      pauseDuration < 1_800_000 -> 20_000L
      else -> 29_500L
    }
  }


  /* ========================================
   * Sleep Timer
   * ======================================== */
  private fun ensureSleepTimerStarted() {
    if (!sleepTimerCoordinator.isStarted()) {
      synchronized(this) {
        if (!sleepTimerCoordinator.isStarted()) {
          sleepTimerCoordinator.start(sleepTimerHostAdapter)
        }
      }
    }
  }

  private val sleepTimerHostAdapter = object : SleepTimerHostAdapter {
    override val context: Context
      get() = this@Media3PlaybackService

    override fun currentTimeMs(): Long {
        return if (hasActivePlayer) player.currentPosition else 0L
    }

    override fun durationMs(): Long {
        val playerDuration = if (hasActivePlayer) player.duration else C.TIME_UNSET
      return if (playerDuration != C.TIME_UNSET && playerDuration >= 0) {
        playerDuration
      } else {
        currentPlaybackSession?.totalDurationMs ?: 0L
      }
    }

    override fun isPlaying(): Boolean {
      return isEffectivelyPlaying()
    }

    override fun playbackSpeed(): Float {
        return if (hasActivePlayer) player.playbackParameters.speed else 1f
    }

    override fun setVolume(volume: Float) {
        if (hasActivePlayer) player.volume = volume.coerceIn(0f, 1f)
    }

    override fun pause() {
        if (hasActivePlayer) player.pause()
    }

    override fun play() {
        if (hasActivePlayer) player.play()
    }

    override fun seekBackward(amountMs: Long) {
      if (!hasActivePlayer) return
        val target = max(player.currentPosition - amountMs, 0L)
        player.seekTo(target)
    }

    override fun endTimeOfChapterOrTrack(): Long? {
      val session = currentPlaybackSession ?: return null
      val currentTimeMs = currentTimeMs()
      return session.getChapterForTime(currentTimeMs)?.endMs ?: session.getCurrentTrackEndTime()
    }

    override fun endTimeOfNextChapterOrTrack(): Long? {
      val session = currentPlaybackSession ?: return null
      val currentTimeMs = currentTimeMs()
      return session.getNextChapterForTime(currentTimeMs)?.endMs ?: session.getNextTrackEndTime()
    }

    override fun notifySleepTimerSet(secondsRemaining: Int, isAuto: Boolean) {
      SleepTimerNotificationCenter.notifySet(secondsRemaining, isAuto)
    }

    override fun notifySleepTimerEnded(currentPosition: Long) {
      SleepTimerNotificationCenter.notifyEnded(currentPosition)
    }

    override fun getCurrentSessionId(): String? = currentPlaybackSession?.id
  }


  /* ========================================
   * Session Callback & Controller
   * ======================================== */
  private fun createSessionCallback(): Media3SessionCallback {
    val seekConfig = SeekConfig(
      jumpBackwardMs = jumpBackwardMs,
      jumpForwardMs = jumpForwardMs,
      allowSeekingOnMediaControls = deviceSettings.allowSeekingOnMediaControls
    )
    val browseApi = object : BrowseApi {
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
        switchPlaybackSession(session)
      }

      override fun passthroughAllowed(
        mediaId: String?,
        controller: MediaSession.ControllerInfo?
      ): Boolean {
        return isPassthroughRequestAllowed(mediaId, controller)
      }
    }
    val sessionController = SessionController(
      context = this,
      availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
      setSleepTimer = { sessionId, timeMs, isChapter ->
        sleepTimerCoordinator.setManualTimer(
          sessionId,
          timeMs,
          isChapter
        )
      },
      cancelSleepTimer = { sleepTimerCoordinator.cancelTimer() },
      adjustSleepTimer = { deltaMs, increase ->
        if (increase) sleepTimerCoordinator.increaseTimer(
          deltaMs
        ) else sleepTimerCoordinator.decreaseTimer(deltaMs)
      },
      getSleepTimerTime = { sleepTimerCoordinator.getTimerTimeMs() },
      cyclePlaybackSpeed = { cyclePlaybackSpeed() },
      getCurrentSession = { currentPlaybackSession },
      currentAbsolutePositionMs = { currentAbsolutePositionMs() },
      syncProgress = { reason, force, onSyncComplete ->
        maybeSyncProgress(
          reason,
          force
        ) { onSyncComplete?.invoke() }
      },
      closePlaybackCallback = { onPlaybackStopped -> closePlayback(onPlaybackStopped) },
        playerProvider = { if (this::player.isInitialized) player else null }
    )

    return Media3SessionCallback(
      logTag = TAG,
      scope = serviceScope,
      browseTree = browseTree,
      autoLibraryCoordinator = autoLibraryCoordinator,
      mediaManager = mediaManager,
        playerProvider = { player },
      isCastActive = { isCastActive },
      seekConfig = seekConfig,
      browseApi = browseApi,
      awaitFinalSync = { awaitFinalSyncBarrier() },
      markNextPlaybackEventSourceUi = {
        if (this::unifiedProgressSyncer.isInitialized) {
          unifiedProgressSyncer.markNextPlaybackEventSource(PlaybackEventSource.UI)
        }
      },
      debug = { msg -> debugLog(msg) },
      sessionController = sessionController
    )
  }


  /* ========================================
   * Media Session & Buttons
   * ======================================== */
  private fun buildMediaLibrarySession(sessionId: String, sessionActivityIntent: PendingIntent) {
      mediaSession = MediaLibrarySession.Builder(this, player, createSessionCallback())
      .setId(sessionId)
      .setSessionActivity(sessionActivityIntent)
      .build()

      if (!::player.isInitialized) {
          throw IllegalStateException("Fatal: player could not be initialized.")
    }

      val currentPlayer = player
      if (currentPlayer is AbsPlayerWrapper) {
          currentPlayer.mapSkipToSeek = true
      }
    playerInitialized = true

    mediaSession?.sessionExtras = Bundle().apply {
      putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, false)
      putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, false)
    }
    updateMediaPlayerExtra()

    debugLog { "MediaLibrarySession created: $sessionId" }

    media3NotificationManager.applyInitialMediaButtonPreferences(mediaSession)
  }

  /**
   * Update available player commands for connected controllers and refresh notification buttons.
   * Ensures the notification's seek behaviour reflects `deviceSettings.allowSeekingOnMediaControls`.
   */
  fun updateMediaSessionPlaybackActions() {
    runCatching {
      val allowSeekingOnMediaControls = deviceSettings.allowSeekingOnMediaControls
      val sessionCommands = SessionCommands.Builder()
        .add(cyclePlaybackSpeedCommand)
        .add(seekBackIncrementCommand)
        .add(seekForwardIncrementCommand)
        .add(seekPreviousTrackCommand)
        .add(seekNextTrackCommand)
        .add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CLOSE_PLAYBACK))
        .build()

      val connected = mediaSession?.connectedControllers ?: emptyList()
      connected.forEach { controllerInfo ->
        runCatching {
            val player = if (this::player.isInitialized) this.player else null
          val isAppUiController =
            controllerInfo.connectionHints.getBoolean(KEY_IS_APP_UI_CONTROLLER, false)
          val effectiveAllowSeeking = isAppUiController || allowSeekingOnMediaControls

          val playerCommands =
            SessionController.buildBasePlayerCommands(
              player,
              effectiveAllowSeeking
            )
          mediaSession?.setAvailableCommands(controllerInfo, sessionCommands, playerCommands)
        }.onFailure { t ->
          Log.w(
            TAG,
            "updateMediaSessionPlaybackActions: failed for controller=${controllerInfo.packageName}: ${t.message}"
          )
        }
      }

      runCatching {
        media3NotificationManager.updateMediaButtonPreferencesAfterSpeedChange(
          mediaSession
        )
      }.onFailure { t ->
        Log.w(
          TAG,
          "updateMediaSessionPlaybackActions: failed to refresh notification buttons: ${t.message}"
        )
      }
    }.onFailure { t ->
      Log.w(TAG, "updateMediaSessionPlaybackActions: ${t.message}")
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      "UPDATE_COMMANDS" -> {
        updateMediaSessionPlaybackActions()
        return START_STICKY
      }

      PlaybackConstants.WidgetActions.PLAY_PAUSE,
      PlaybackConstants.WidgetActions.FAST_FORWARD,
      PlaybackConstants.WidgetActions.REWIND -> {
        handleWidgetCommand(intent.action)
        return START_STICKY
      }
    }
    return super.onStartCommand(intent, flags, startId)
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
    media3NotificationManager.configureCommandButtons()
    playbackSpeedCommandButton = media3NotificationManager.getPlaybackSpeedCommandButton()
  }


  private fun cyclePlaybackSpeed(): Float {
    val newSpeed = media3NotificationManager.cyclePlaybackSpeed()
      player.setPlaybackSpeed(newSpeed)
    mediaManager.setSavedPlaybackRate(newSpeed)
    playbackSpeedCommandButton = media3NotificationManager.getPlaybackSpeedCommandButton()
    media3NotificationManager.updateMediaButtonPreferencesAfterSpeedChange(mediaSession)
    return newSpeed
  }

    private fun updatePlaybackSpeedButton(speed: Float) {
    mediaManager.setSavedPlaybackRate(speed)
    media3NotificationManager.updatePlaybackSpeedButton(speed)
    playbackSpeedCommandButton = media3NotificationManager.getPlaybackSpeedCommandButton()
    media3NotificationManager.updateMediaButtonPreferencesAfterSpeedChange(mediaSession)
  }

  private fun updateTrackNavigationButtons() {
    val session = currentPlaybackSession
      val hasMultipleTracks = (session?.audioTracks?.size ?: 0) > 1
    media3NotificationManager.setTrackNavigationEnabled(hasMultipleTracks && !isCastActive)
    runCatching {
      media3NotificationManager.updateMediaButtonPreferencesAfterSpeedChange(
        mediaSession
      )
    }
  }


    /* ========================================
       * Widget Integration
       * ======================================== */
  private fun notifyWidgetState(
    isPlaybackClosed: Boolean = false,
    isPlayingOverride: Boolean? = null
  ) {
        val updater = DeviceManager.widgetUpdater ?: return
        if (isPlaybackClosed) {
      lastWidgetSnapshot = null
      updater.onPlayerClosed()
      return
    }
    buildWidgetSnapshot(isPlaybackClosed, isPlayingOverride)?.let { snapshot ->
      if (snapshot.hasMeaningfulChangesFrom(lastWidgetSnapshot)) {
        lastWidgetSnapshot = snapshot
        updater.onPlayerChanged(snapshot)
      }
    }
  }

  private fun handleWidgetCommand(action: String?) {
    when (action) {
      PlaybackConstants.WidgetActions.PLAY_PAUSE -> togglePlayPauseFromWidget()
      PlaybackConstants.WidgetActions.FAST_FORWARD -> seekFromWidget(forward = true)
      PlaybackConstants.WidgetActions.REWIND -> seekFromWidget(forward = false)
    }
  }

  private fun togglePlayPauseFromWidget() {
    if (!hasActivePlayer) return
      val targetPlaying = !player.isPlaying
      if (player.isPlaying) {
          player.pause()
    } else {
          player.play()
    }
    notifyWidgetState(isPlayingOverride = targetPlaying)
  }

  private fun seekFromWidget(forward: Boolean) {
    if (!hasActivePlayer) return
    val delta = if (forward) jumpForwardMs else jumpBackwardMs
      val currentPosition = player.currentPosition
    val target = if (forward) {
        val duration = player.duration
      val desiredPosition = currentPosition + delta
      if (duration != C.TIME_UNSET && duration > 0) min(
        desiredPosition,
        duration
      ) else desiredPosition
    } else {
      max(currentPosition - delta, 0L)
    }
      player.seekTo(target)
    notifyWidgetState()
  }

  private fun buildWidgetSnapshot(
    isPlaybackClosed: Boolean,
    isPlayingOverride: Boolean?
  ): WidgetPlaybackSnapshot? {
    val session = currentPlaybackSession ?: return null
    val isPlaying = isPlayingOverride ?: isEffectivelyPlaying()
    var absolutePosition = session.currentTimeMs
    if (playerInitialized) {
        val trackIndex = player.currentMediaItemIndex
      val trackOffset = session.getTrackStartOffsetMs(trackIndex)
        absolutePosition = (player.currentPosition + trackOffset).coerceAtLeast(0L)
    }
    return WidgetPlaybackSnapshot(
      title = session.displayTitle,
      author = session.displayAuthor,
      coverUri = session.getCoverUri(this),
      positionMs = absolutePosition,
      durationMs = session.totalDurationMs,
      isPlaying = isPlaying,
      isClosed = isPlaybackClosed
    )
  }


  /* ========================================
   * Utility Helpers
   * ======================================== */
  private fun isEffectivelyPlaying(): Boolean {
    if (!hasActivePlayer) return false

      val player = player
    return player.isPlaying ||
      (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
  }

  private fun applySavedPlaybackSpeed(target: Player? = null) {
      val player = target ?: if (this::player.isInitialized) player else return
    val savedSpeed = mediaManager.getSavedPlaybackRate()
    runCatching { player.setPlaybackSpeed(savedSpeed) }
    if (this::media3NotificationManager.isInitialized) {
      updatePlaybackSpeedButton(savedSpeed)
    }
  }

  private fun isAndroidAutoControllerConnected(): Boolean {
    val controllers = mediaSession?.connectedControllers ?: return false
    return controllers.any { info ->
      val pkg = info.packageName.lowercase()
      pkg.contains("gearhead") || pkg.contains("android.auto") || pkg.contains("android.automotive")
    }
  }

  private inline fun debugLog(crossinline lazyMessage: () -> String) {
    if (BuildConfig.DEBUG) Log.d(TAG, lazyMessage())
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
    requestedMediaId: String,
    preferCastPlayerUris: Boolean
  ): Media3BrowseTree.ResolvedPlayable? {
    resolvedCache.get(requestedMediaId, preferCastPlayerUris)?.let { return it }
    val resolved = browseTree.resolvePlayableItem(
      mediaId = requestedMediaId,
      playRequestPayload = getPlayItemRequestPayload(forceTranscode = false),
      preferServerUrisForCast = preferCastPlayerUris
    )
    if (resolved != null) {
      resolvedCache.put(requestedMediaId, preferCastPlayerUris, resolved)
    }
    return resolved
  }

  private fun updateMediaPlayerExtra() {
      if (!this::player.isInitialized) return
    val mediaPlayerId = currentMediaPlayerId()
    mediaSession?.let { session ->
      val extras = session.sessionExtras
      extras.putString(PlaybackConstants.MEDIA_PLAYER, mediaPlayerId)
      session.sessionExtras = extras
    }
  }

}
