package com.audiobookshelf.app.player.media3

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
import com.audiobookshelf.app.player.*
import com.audiobookshelf.app.player.core.*
import com.audiobookshelf.app.server.ApiHandler
import kotlinx.coroutines.*
import kotlin.math.*


/**
 * Media3 playback service following MediaLibraryService architecture.
 * Handles local playback, session management, and native Media3 notifications.
 * Cast playback is handled via Media3 CastPlayer within this service.
 *
 * Implements [Media3ServiceHost] (surface for the player listener, session manager and
 * session controller), [PlaybackTelemetryHost] (progress sync telemetry) and [BrowseApi]
 * (session callback browse/resolve) directly rather than through bridge objects.
 */
@UnstableApi
class Media3PlaybackService : MediaLibraryService(), Media3ServiceHost, PlaybackTelemetryHost,
    BrowseApi {
  companion object {
    val TAG: String = Media3PlaybackService::class.java.simpleName

    // Cache settings
    private const val RESOLVED_CACHE_TTL_MS = 5_000L
    private const val RESOLVED_CACHE_LIMIT = 6

    // Sync & timeout settings
    private const val TASK_REMOVAL_CLOSE_TIMEOUT_MS = 5_000L
    private const val FINAL_SYNC_TIMEOUT_MS = 500L
    private const val DESTROY_FINAL_SYNC_TIMEOUT_SEC = 1L

    // Playback recheck settings
    private const val PAUSE_LEN_BEFORE_RECHECK_MS = 30_000L
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
  override val playbackMetrics = PlaybackMetricsRecorder()
  private val currentPlaybackSession: PlaybackSession?
    get() = media3SessionManager.currentPlaybackSession

  // Player State & Synchronization
  @Volatile
  override var isPlayerInitialized = false
  private val hasActivePlayer: Boolean
      get() = isPlayerInitialized && this::player.isInitialized

  // Last (trackIndex, chapterTitle) synced into the now-playing metadata; lets ticks short-circuit.
  private var lastSyncedTrackIndex = -1
  private var lastSyncedChapterTitle: String? = null
  private val isCastActive: Boolean
      get() {
          if (!this::player.isInitialized) return false
          return player.deviceInfo.playbackType==androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
      }
    private val finalSyncBarrier = FinalSyncBarrier()

  private var transcodeFallbackAttemptedSessionId: String? = null

  // Audio Configuration
  private val speechAudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
    .build()

  // Playback Controls
  private var jumpBackwardMs: Long = 10000L
  private var jumpForwardMs: Long = 10000L
  private val closePlaybackSignal: CompletableDeferred<Unit>?
      get() = media3SessionManager.closePlaybackSignalSnapshot

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

  private val playerListener = Media3PlayerEventListener(this, eventPipeline)


  /* ========================================
   * Lifecycle Methods
   * ======================================== */
  override fun onCreate() {
    super.onCreate()
    playbackMetrics.noteServiceStart()
    debugLog { "onCreate: Initializing Media3 playback service" }

    DbManager.initialize(this)
      restoreServerConnectionConfigIfNeeded()
    DeviceManager.initializeWidgetUpdater(this)
    applyJumpIncrementsFromDeviceSettings()
    setupMediaManagers()
    registerNetworkMonitor()

    initializeMedia3NotificationManager()
      setMediaNotificationProvider(media3NotificationManager.createNotificationProvider())

    media3SessionManager = Media3SessionManager(serviceScope, mediaManager, this)
    setupPlaybackPipeline()
  }

  override fun onDestroy() {
    try {
      val session = currentPlaybackSession
      if (session != null && this::unifiedProgressSyncer.isInitialized && isPlayerInitialized) {
          updateCurrentPosition(session)
        val latch = java.util.concurrent.CountDownLatch(1)
        unifiedProgressSyncer.syncNow(
          "stop",
          session.clone(),
            shouldSyncServer = true,
            callbackOnMainThread = false
        ) { latch.countDown() }
        latch.await(DESTROY_FINAL_SYNC_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)

        if (!session.isLocal && session.id.isNotEmpty()) {
          closeSessionOnServer(session.id)
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
    /** Restore server connection config from persisted data when the service starts before the UI. */
    private fun restoreServerConnectionConfigIfNeeded() {
        if (DeviceManager.serverConnectionConfig!=null) return
        val lastConfig = DeviceManager.deviceData.getLastServerConnectionConfig()
        if (lastConfig!=null) {
            DeviceManager.serverConnectionConfig = lastConfig
            Log.d(TAG, "Restored server connection config: ${lastConfig.name}")
        }
    }

  private fun setupMediaManagers() {
    apiHandler = ApiHandler(this)
    mediaManager = MediaManager(apiHandler, this)
    browseTree = Media3BrowseTree(this, mediaManager)
    autoLibraryCoordinator = Media3AutoLibraryCoordinator(mediaManager, browseTree, serviceScope)
    NetworkMonitor.initialize(applicationContext)

    unifiedProgressSyncer = UnifiedMediaProgressSyncer(
      playbackTelemetryProvider = this,
      progressApi = apiHandler
    ) { event, session, result ->
      when (event) {
        "save" -> eventPipeline.emitSaveEvent(session, result)
        "pause" -> eventPipeline.emitPauseEvent(session, result)
          "close" -> eventPipeline.emitStopEvent(session, result)
        "stop" -> eventPipeline.emitStopEvent(session, result)
        "finished" -> eventPipeline.emitFinishedEvent(session, result)
      }
    }
  }

  /* ========================================
   * PlaybackTelemetryHost implementation
   * ======================================== */
  override val appContext: Context
    get() = applicationContext

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

  /* ========================================
   * Media3ServiceHost implementation
   * (methods shared with collaborators; see also the overrides further down)
   * ======================================== */
  override fun currentSession(): PlaybackSession? = currentPlaybackSession

  override fun playerOrNull(): Player? = if (this::player.isInitialized) player else null

  override fun progressSyncPlay(session: PlaybackSession) {
    if (this::unifiedProgressSyncer.isInitialized) {
      unifiedProgressSyncer.play(session)
    }
  }

  override fun progressSyncPause() {
    val closeSignal = closePlaybackSignal
    if (closeSignal != null && !closeSignal.isCompleted) {
      debugLog { "Skipping pause sync because closePlayback is already in progress" }
      return
    }
    if (this::unifiedProgressSyncer.isInitialized) {
      unifiedProgressSyncer.pause {}
    }
  }

  override fun resetProgressSyncState() {
    if (this::unifiedProgressSyncer.isInitialized) {
      unifiedProgressSyncer.reset()
    }
  }

  override fun closeSessionOnServer(sessionId: String) {
    apiHandler.closePlaybackSession(sessionId, DeviceManager.serverConnectionConfig) { success ->
      debugLog { "Closed playback session $sessionId on server: $success" }
    }
  }

  override fun onPlayStarted(sessionId: String) {
    ensureSleepTimerStarted()
    sleepTimerCoordinator.handlePlayStarted(sessionId)
    val sessionAssignTimestampMs = media3SessionManager.sessionAssignTimestampMs
    if (sessionAssignTimestampMs > 0L) {
      debugLog {
        "Ready latency after session assign: ${System.currentTimeMillis() - sessionAssignTimestampMs}ms"
      }
      media3SessionManager.resetSessionAssignTimestamp()
    }
  }

  override fun setSleepTimer(sessionId: String, timeMs: Long, isChapter: Boolean) {
    ensureSleepTimerStarted()
    sleepTimerCoordinator.setManualTimer(sessionId, timeMs, isChapter)
  }

  override fun cancelSleepTimer() {
    ensureSleepTimerStarted()
    sleepTimerCoordinator.cancelTimer()
  }

  override fun adjustSleepTimer(deltaMs: Long, increase: Boolean) {
    ensureSleepTimerStarted()
    if (increase) sleepTimerCoordinator.increaseTimer(deltaMs)
    else sleepTimerCoordinator.decreaseTimer(deltaMs)
  }

  override fun getSleepTimerTimeMs(): Long {
    ensureSleepTimerStarted()
    return sleepTimerCoordinator.getTimerTimeMs()
  }

  override fun resyncSleepTimerState() {
    ensureSleepTimerStarted()
    sleepTimerCoordinator.sendCurrentSleepTimerState()
  }

  override fun debug(message: () -> String) {
    debugLog(message)
  }

  /* ========================================
   * BrowseApi implementation
   * ======================================== */
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

  private fun registerNetworkMonitor() {
    val listener = NetworkMonitor.Listener { state ->
      debugLog {
        "Network state changed. hasNetworkConnectivity=${state.hasConnectivity} | isUnmeteredNetwork=${state.isUnmetered}"
      }
        if (state.hasConnectivity && !mediaManager.isAutoDataLoaded) {
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
        currentPlaybackSpeedProvider = { currentPlaybackSpeed() ?: 1.0f },
      debugLog = { lazyMessage -> debugLog { lazyMessage } }
    )
    media3NotificationManager.createNotificationChannel()
  }

  private fun setupPlaybackPipeline() {
      initializePlayer()
      media3NotificationManager.configureCommandButtons()

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
      if (isPlayerInitialized && this::player.isInitialized) {
          player.release()
      isPlayerInitialized = false
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
        isPlayerInitialized = true
                applySavedPlaybackSpeed(playerWrapper)
      },
      buildListener = { playerListener }
    )
  }

    override fun currentMediaPlayerId(): String {
        if (!this::player.isInitialized) return PLAYER_MEDIA3
        val deviceInfo = player.deviceInfo
        return if (deviceInfo.playbackType==androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            PLAYER_CAST
        } else {
            PLAYER_MEDIA3
        }
    }

    override fun handleCastDeviceChanged(isCast: Boolean) {
        val newPlayerId = if (isCast) PLAYER_CAST else PLAYER_MEDIA3

        currentPlaybackSession?.mediaPlayer = newPlayerId
        playbackMetrics.updatePlayerId(newPlayerId)

        MediaEventManager.clientEventEmitter?.onMediaPlayerChanged(newPlayerId)
        notifyWidgetState()
        updateMediaPlayerExtra()
        updateTrackNavigationButtons()

        val session = currentPlaybackSession
        if (session!=null && isCast && session.isLocal) {
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

  override fun closePlayback(calledOnError: Boolean, onPlaybackStopped: (() -> Unit)?) {
    media3SessionManager.closePlayback(calledOnError = calledOnError) {
      // After session manager completes, stop the service
      media3NotificationManager.setTrackNavigationEnabled(false)
      onPlaybackStopped?.invoke()
      stopSelf()
    }
  }

  private fun isHostController(controllerInfo: MediaSession.ControllerInfo?): Boolean {
    return controllerInfo?.packageName == packageName
  }

  private fun isPassthroughRequestAllowed(
    requestedMediaId: String?,
    controllerInfo: MediaSession.ControllerInfo?
  ): Boolean {
    if (requestedMediaId.isNullOrBlank()) return false

    var sessionId = currentPlaybackSession?.id
    if (sessionId == null && isHostController(controllerInfo)) {
        media3SessionManager.syncSessionFromHostController()
      sessionId = currentPlaybackSession?.id
    }

    if (sessionId == null) return false
    if (requestedMediaId.startsWith(sessionId)) return true

    if (isHostController(controllerInfo)) {
      debugLog { "Allowing passthrough request from host app despite session mismatch" }
        media3SessionManager.syncSessionFromHostController()
      return true
    }
    return false
  }


  /* ========================================
   * Position Tracking & Seeking
   * ======================================== */
  override fun currentAbsolutePositionMs(): Long? {
    if (!isPlayerInitialized) return null
    val session = currentPlaybackSession ?: return null
      val mediaItemCount = player.mediaItemCount
      if (mediaItemCount <= 0) return player.currentPosition.coerceAtLeast(0L)
    val trackIndex =
        resolveTrackIndexForPlayer(session, player).coerceIn(0, mediaItemCount - 1)
      val offset = session.getTrackStartOffsetMs(trackIndex)
      return (player.currentPosition + offset).coerceAtLeast(0L)
  }

    override fun updateCurrentPosition(session: PlaybackSession) {
        if (hasActivePlayer) {
            val trackIndex = resolveTrackIndexForPlayer(session, player)
            val trackStartOffset = session.getTrackStartOffsetMs(trackIndex)
            val absolutePosMs = trackStartOffset + player.currentPosition
            session.currentTime = (absolutePosMs / 1000.0)

            syncChapterMetadataIfNeeded(session, absolutePosMs, trackIndex)
        }
    }

    private fun syncChapterMetadataIfNeeded(session: PlaybackSession, currentPosMs: Long, trackIndex: Int) {
        val chapterTitle = session.getChapterForTime(currentPosMs)?.title

        if (trackIndex == lastSyncedTrackIndex && chapterTitle == lastSyncedChapterTitle) return

        val currentItem = player.currentMediaItem ?: return

        val author = session.displayAuthor ?: ""
        val trackLabel = session.trackLabelForIndex(trackIndex)

        val artistLine = when {
            chapterTitle != null && trackLabel != null -> "$chapterTitle ($trackLabel) • $author"
            chapterTitle != null -> "$chapterTitle • $author"
            trackLabel != null -> "$trackLabel • $author"
            else -> author
        }

        lastSyncedTrackIndex = trackIndex
        lastSyncedChapterTitle = chapterTitle

        // Only update if the string has actually changed to avoid notification flickering
        if (currentItem.mediaMetadata.artist != artistLine) {
            val newMetadata = currentItem.mediaMetadata.buildUpon()
                .setArtist(artistLine)
                .build()
            // Replace at the player's own index: currentItem came from the player, and the
            // session-resolved trackIndex could disagree with the queue position (e.g. cast reload)
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentItem.buildUpon().setMediaMetadata(newMetadata).build()
            )
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

  /* ========================================
   * Progress Sync
   * ======================================== */
  override fun maybeSyncProgress(
    reason: String,
    force: Boolean,
    targetSession: PlaybackSession?,
    onSyncComplete: ((SyncResult?) -> Unit)?
  ) {
    val session = targetSession ?: currentPlaybackSession
    if (!this::unifiedProgressSyncer.isInitialized || session == null) {
      onSyncComplete?.invoke(null)
      return
    }

      val barrier = finalSyncBarrier.armIfCritical(reason)
    val shouldSyncServer = when (reason) {
        "pause", "ended", "close" -> true
        else -> force || DeviceManager.checkConnectivity(applicationContext)
    }

    val completion: (SyncResult?) -> Unit = { syncResult ->
        finalSyncBarrier.complete(syncResult, barrier)
      onSyncComplete?.invoke(syncResult)
    }
      updateCurrentPosition(session)
      unifiedProgressSyncer.syncNow(reason, session, shouldSyncServer, onComplete = completion)
  }


  /* ========================================
   * Playback Recovery Helpers
   * ======================================== */
  override fun handlePlaybackError(playbackError: PlaybackException) {
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
                closePlayback(calledOnError = true)
                return@launch
            }
            val currentSpeed = currentPlaybackSpeed()
            prepareAndPlaySession(
                fallbackSession,
                playWhenReady = true,
                playbackSpeed = currentSpeed,
                syncOnSwitch = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "handlePlaybackError: Exception during transcode fallback", e)
            MediaEventManager.clientEventEmitter?.onPlaybackFailed("Unable to play this item")
            closePlayback(calledOnError = true)
        }
    }
  }

  override fun handleFatalPlaybackError(message: String) {
    MediaEventManager.clientEventEmitter?.onPlaybackFailed(message)
    closePlayback(calledOnError = true)
  }

  override fun handlePlaybackEnded(session: PlaybackSession) {
    if (!session.isPodcastEpisode) {
      closePlayback()
      return
    }

    if (!isAndroidAutoControllerConnected()) return
    val libraryItem = session.libraryItem ?: return
      val currentSpeed = currentPlaybackSpeed()

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

  override fun handlePlaybackResumed(pauseDurationMs: Long) {
    val session = currentPlaybackSession ?: return
    val seekBackTimeMs =
      if (deviceSettings.disableAutoRewind) 0L else calcPauseSeekBackTime(pauseDurationMs)

    // Short pause or offline: apply the auto-rewind locally without a server progress recheck
    if (pauseDurationMs < PAUSE_LEN_BEFORE_RECHECK_MS ||
      !DeviceManager.checkConnectivity(applicationContext)
    ) {
      if (seekBackTimeMs > 0) {
        seekBackwardWithinSession(seekBackTimeMs, session)
      }
      return
    }

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
    updateCurrentPosition(session)
    // Clamp to the current chapter start so the rewind never crosses into the previous chapter
    val chapterStartMs = session.getChapterForTime(session.currentTimeMs)?.startMs ?: 0L
    val targetPosition = (session.currentTimeMs - amountMs).coerceAtLeast(chapterStartMs)
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
    // Reset metadata sync cache so the new session's first tick always pushes fresh metadata
    lastSyncedTrackIndex = -1
    lastSyncedChapterTitle = null
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
        val currentSpeed = currentPlaybackSpeed()
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
        return currentPlaybackSpeed() ?: 1f
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
    val sessionController = SessionController(
      availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
      host = this
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
      browseApi = this,
        awaitFinalSync = { finalSyncBarrier.await(FINAL_SYNC_TIMEOUT_MS) },
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
            controllerInfo.connectionHints.getBoolean(PlaybackConstants.KEY_IS_APP_UI_CONTROLLER, false)
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

  override fun cyclePlaybackSpeed(): Float {
      val newSpeed = media3NotificationManager.cyclePlaybackSpeed()
      player.setPlaybackSpeed(newSpeed)
    mediaManager.setSavedPlaybackRate(newSpeed)
    media3NotificationManager.updateMediaButtonPreferencesAfterSpeedChange(mediaSession)
    return newSpeed
  }

    override fun updatePlaybackSpeedButton(speed: Float) {
    mediaManager.setSavedPlaybackRate(speed)
    media3NotificationManager.updatePlaybackSpeedButton(speed)
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
  override fun notifyWidgetState(
    isPlaybackClosed: Boolean,
    isPlayingOverride: Boolean?
  ) {
        val updater = DeviceManager.widgetUpdater ?: return
        if (isPlaybackClosed) {
      lastWidgetSnapshot = null
      updater.onPlayerClosed()
      return
    }
        buildWidgetSnapshot(isPlayingOverride)?.let { snapshot ->
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

    private fun buildWidgetSnapshot(isPlayingOverride: Boolean?): WidgetPlaybackSnapshot? {
    val session = currentPlaybackSession ?: return null
    val isPlaying = isPlayingOverride ?: isEffectivelyPlaying()
    var absolutePosition = session.currentTimeMs
    if (isPlayerInitialized) {
        val trackIndex = resolveTrackIndexForPlayer(session, player)
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
        isClosed = false
    )
  }


  /* ========================================
   * Utility Helpers
   * ======================================== */
  override fun isEffectivelyPlaying(): Boolean {
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

    private fun currentPlaybackSpeed(): Float? =
        if (hasActivePlayer) player.playbackParameters.speed else null

  private inline fun debugLog(crossinline lazyMessage: () -> String) {
    if (BuildConfig.DEBUG) Log.d(TAG, lazyMessage())
  }

  private fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    val mediaPlayerId = currentMediaPlayerId()
    return PlayItemRequestPayload(
      mediaPlayerId,
      forceDirectPlay = !forceTranscode,
      forceTranscode = forceTranscode,
      deviceInfo = PlaybackConstants.buildDeviceInfo(this)
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
