package com.audiobookshelf.app.player.media3

import android.app.PendingIntent
import android.content.*
import android.os.*
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

@UnstableApi
class Media3PlaybackService : MediaLibraryService(), PlaybackEventSink, PlaybackCommandTarget,
  PlaybackStateHost, BrowseApi {
  companion object {
    val TAG: String = Media3PlaybackService::class.java.simpleName

    // Resolved item cache
    private const val RESOLVED_CACHE_TTL_MS = 5_000L
    private const val RESOLVED_CACHE_LIMIT = 6

    // Shutdown timeouts
    private const val TASK_REMOVAL_CLOSE_TIMEOUT_MS = 5_000L
    private const val FINAL_SYNC_TIMEOUT_MS = 500L
    private const val DESTROY_FINAL_SYNC_TIMEOUT_SEC = 1L
  }

  // Service lifecycle
  private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  // Media3 player and session
  private var mediaSession: MediaLibrarySession? = null
  private lateinit var player: Player
  private var playbackPipeline: PlaybackPipeline? = null

  // Playback collaborators
  private lateinit var apiHandler: ApiHandler
  private lateinit var mediaManager: MediaManager
  private lateinit var browseTree: Media3BrowseTree
  private lateinit var autoLibraryCoordinator: Media3AutoLibraryCoordinator
  private lateinit var progressSync: Media3ProgressSyncCoordinator
  private lateinit var media3SessionManager: Media3SessionManager
  private lateinit var media3NotificationManager: Media3NotificationManager
  private val sleepTimerCoordinator = SleepTimerCoordinator(serviceScope)
  private var networkStateListener: NetworkMonitor.Listener? = null

  // Event and progress state
  private val eventPipeline = Media3EventPipeline()
  override val playbackMetrics = PlaybackMetricsRecorder()
  private val currentPlaybackSession: PlaybackSession?
    get() = media3SessionManager.currentPlaybackSession

  // Player lifecycle state
  @Volatile
  override var isPlayerInitialized = false
  private val hasActivePlayer: Boolean
    get() = isPlayerInitialized && this::player.isInitialized

  private val notificationMetadata = NotificationMetadataUpdater()
  private val queueManager by lazy { PlaybackQueueManager(this) }
  private val commandPolicy by lazy {
    MediaSessionCommandPolicy(
      logTag = TAG,
      allowSeekingOnMediaControls = { deviceSettings.allowSeekingOnMediaControls },
      refreshNotificationButtons = {
        media3NotificationManager.refreshMediaButtonPreferences(mediaSession)
      }
    )
  }
  private val isCastActive: Boolean
    get() {
      if (!this::player.isInitialized) return false
      return player.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
    }

  private val errorHandler by lazy {
    PlaybackErrorHandler(
      scope = serviceScope,
      requestTranscodeSession = { libraryItemId, episodeId ->
        requestPlaybackSession(libraryItemId, episodeId, forceTranscode = true)
      },
      playFallbackSession = { session ->
        prepareAndPlaySession(
          session,
          playWhenReady = true,
          playbackSpeed = currentPlaybackSpeed(),
          syncOnSwitch = false
        )
      },
      failPlayback = { message ->
        MediaEventManager.clientEventEmitter?.onPlaybackFailed(message)
        closePlayback(calledOnError = true)
      }
    )
  }

  private val lifecycleHandler by lazy {
    PlaybackLifecycleHandler(
      appContext = applicationContext,
      scope = serviceScope,
      apiHandler = apiHandler,
      mediaManager = mediaManager,
      autoRewindDisabled = { deviceSettings.disableAutoRewind },
      isAndroidAutoConnected = ::isAndroidAutoControllerConnected,
      playItemRequestPayload = ::getPlayItemRequestPayload,
      currentPlaybackSpeed = ::currentPlaybackSpeed,
      seekToSessionPosition = ::seekToSessionPosition,
      seekBackwardWithinSession = ::seekBackwardWithinSession,
      prepareAndPlaySession = { session, speed ->
        prepareAndPlaySession(session, playWhenReady = true, playbackSpeed = speed)
      },
      startNewSessionFromServer = ::startNewPlaybackSessionFromServer,
      resumeProgressSync = { session -> progressSync.play(session) },
      closePlayback = { closePlayback() }
    )
  }

  // Audio configuration
  private val speechAudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
    .build()

  // Playback controls
  private var jumpBackwardMs: Long = 10000L
  private var jumpForwardMs: Long = 10000L
  private val closePlaybackSignal: CompletableDeferred<Unit>?
    get() = media3SessionManager.closePlaybackSignalSnapshot

  private val widgetPresenter by lazy { WidgetPresenter(this) }

  // Session commands
  private val cyclePlaybackSpeedCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED)
  private val seekBackIncrementCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_BACK_INCREMENT)
  private val seekForwardIncrementCommand =
    PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT)

  // Browse cache and device settings
  private val resolvedCache = ResolvedPlayableCache(
    RESOLVED_CACHE_TTL_MS,
    RESOLVED_CACHE_LIMIT
  )
  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  private val playerListener = Media3PlayerEventListener(this, eventPipeline)

  override fun onCreate() {
    super.onCreate()
    playbackMetrics.noteServiceStart()

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
      val alreadyClosing =
        this::media3SessionManager.isInitialized && media3SessionManager.terminalSyncClaimed
      if (session != null && !alreadyClosing && this::progressSync.isInitialized && isPlayerInitialized) {
        media3SessionManager.claimTerminalSync()
        progressSync.syncOnDestroy(session, DESTROY_FINAL_SYNC_TIMEOUT_SEC) {
          updateCurrentPosition(session)
        }
      }
    } catch (_: Exception) {
    }

    super.onDestroy()
    if (this::progressSync.isInitialized) {
      progressSync.cleanup()
    }
    serviceScope.cancel()
    cleanupPlaybackResources()
    networkStateListener?.let { NetworkMonitor.removeListener(it) }
    notifyWidgetState(isPlaybackClosed = true)
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

  /** Android Auto can start the service before the UI restores the last server connection. */
  private fun restoreServerConnectionConfigIfNeeded() {
    if (DeviceManager.serverConnectionConfig != null) return
    val lastConfig = DeviceManager.deviceData.getLastServerConnectionConfig()
    if (lastConfig != null) {
      DeviceManager.serverConnectionConfig = lastConfig
    }
  }

  private fun setupMediaManagers() {
    apiHandler = ApiHandler(this)
    mediaManager = MediaManager(apiHandler, this)
    browseTree = Media3BrowseTree(this, mediaManager)
    autoLibraryCoordinator = Media3AutoLibraryCoordinator(
      mediaManager,
      browseTree,
      serviceScope,
      onAutoDataLoaded = ::notifyRootChildrenChanged
    )
    NetworkMonitor.initialize(applicationContext)

    progressSync = Media3ProgressSyncCoordinator(applicationContext, apiHandler)
    progressSync.attach(
      Media3ProgressSyncer(
        stateHost = this,
        progressApi = apiHandler
      ) { event, session, result ->
        when (event) {
          SyncReason.SAVE -> eventPipeline.emitSaveEvent(session, result)
          SyncReason.PAUSE -> eventPipeline.emitPauseEvent(session, result)
          SyncReason.CLOSE, SyncReason.STOP -> eventPipeline.emitStopEvent(session, result)
          SyncReason.FINISHED -> eventPipeline.emitFinishedEvent(session, result)
        }
      }
    )
  }

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

  override fun currentSession(): PlaybackSession? = currentPlaybackSession

  override fun playerOrNull(): Player? = if (this::player.isInitialized) player else null

  override fun progressSyncPlay(session: PlaybackSession) {
    progressSync.play(session)
  }

  override fun startProgressSyncIfPlaying(session: PlaybackSession) {
    if (isEffectivelyPlaying()) {
      progressSync.play(session)
    }
  }

  override fun progressSyncPause() {
    val closeSignal = closePlaybackSignal
    val skipReason =
      when {
        closeSignal != null && !closeSignal.isCompleted -> "closePlayback is already in progress"
        this::media3SessionManager.isInitialized && media3SessionManager.terminalSyncClaimed ->
          "terminal sync is already in progress"
        else -> null
      }
    progressSync.pause(skipReason)
  }

  override fun resetProgressSyncState() {
    progressSync.reset()
  }

  override fun closeSessionOnServer(sessionId: String) {
    progressSync.closeSessionOnServer(sessionId)
  }

  override fun onPlayStarted(sessionId: String) {
    ensureSleepTimerStarted()
    sleepTimerCoordinator.handlePlayStarted(sessionId)
    val sessionAssignTimestampMs = media3SessionManager.sessionAssignTimestampMs
    if (sessionAssignTimestampMs > 0L) {
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
    debugLog(TAG, message)
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

  private fun registerNetworkMonitor() {
    val listener = NetworkMonitor.Listener { state ->
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
      currentPlaybackSpeedProvider = { currentPlaybackSpeed() ?: 1.0f }
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
    val pipeline = playbackPipeline ?: PlaybackPipeline(this).also { playbackPipeline = it }

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
    return if (deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE) {
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

    // Push the queue to the receiver rather than waiting for CastPlayer to move it on its own,
    // which leaves playback silent for ~14s after the receiver is already connected.
    val session = currentPlaybackSession
    if (session != null && isCast) {
      reloadQueueForCast(session)
    }

  }

  private fun reloadQueueForCast(session: PlaybackSession) {
    val wasPlaying = player.isPlaying
    // CastPlayer has already swapped in the receiver by the time this runs, so the player
    // reports position 0 rather than null and the session holds the real position.
    val playerPosition = currentAbsolutePositionMs() ?: 0L
    val currentPosition = if (playerPosition > 0L) playerPosition else session.currentTimeMs
    queueManager.reloadForCast(player, session, currentPosition, wasPlaying)
  }

  private fun switchPlaybackSession(
    session: PlaybackSession,
    syncPreviousSession: Boolean = true
  ) {
    val isNewSession = currentPlaybackSession?.id != session.id
    if (isNewSession) {
      errorHandler.resetFallbackAttempt()
    }
    media3SessionManager.switchPlaybackSession(session, syncPreviousSession)
    updateTrackNavigationButtons()
    if (isNewSession) {
      applySavedPlaybackSpeed()
    }
  }

  override fun closePlayback(calledOnError: Boolean, onPlaybackStopped: (() -> Unit)?) {
    media3SessionManager.closePlayback(calledOnError = calledOnError) {
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
      debugLog(TAG) { "Allowing passthrough request from host app despite session mismatch" }
      media3SessionManager.syncSessionFromHostController()
      return true
    }
    return false
  }

  private fun positionModel(session: PlaybackSession): PlaybackPositionModel =
    PlaybackPositionModel(session, if (isPlayerInitialized) player else null)

  override fun currentAbsolutePositionMs(): Long? {
    if (!isPlayerInitialized) return null
    val session = currentPlaybackSession ?: return null
    return positionModel(session).bookAbsoluteMsOrNull()
  }

  private fun getCurrentBookChapter(): BookChapter? {
    val session = currentPlaybackSession ?: return null
    return positionModel(session).currentChapter()
  }

  private fun getNextBookChapter(): BookChapter? {
    val session = currentPlaybackSession ?: return null
    return positionModel(session).nextChapter()
  }

  override fun updateCurrentPosition(session: PlaybackSession) {
    if (hasActivePlayer) {
      val writeBack = positionModel(session).writeBackToSession() ?: return
      notificationMetadata.syncIfNeeded(
        player = player,
        session = session,
        currentPosMs = writeBack.positionMs,
        trackIndex = writeBack.trackIndex,
        isCastActive = isCastActive
      )
    }
  }

  override fun maybeSyncProgress(
    reason: String,
    force: Boolean,
    targetSession: PlaybackSession?,
    onSyncComplete: ((SyncResult?) -> Unit)?
  ) {
    progressSync.sync(
      session = targetSession ?: currentPlaybackSession,
      reason = reason,
      force = force,
      beforeSync = ::updateCurrentPosition,
      onSyncComplete = onSyncComplete
    )
  }

  override fun claimTerminalSync(): Boolean = media3SessionManager.claimTerminalSync()

  override fun handlePlaybackError(playbackError: PlaybackException) {
    errorHandler.handleError(currentPlaybackSession)
  }

  override fun handleFatalPlaybackError(message: String) {
    errorHandler.handleFatalError(message)
  }

  override fun handlePlaybackEnded(session: PlaybackSession) {
    lifecycleHandler.handlePlaybackEnded(session)
  }

  override fun handlePlaybackResumed(pauseDurationMs: Long) {
    lifecycleHandler.handlePlaybackResumed(currentPlaybackSession, pauseDurationMs)
  }

  private fun seekToSessionPosition(session: PlaybackSession) {
    if (!hasActivePlayer) return
    val target = positionModel(session).seekTargetForSessionTime()
    player.seekTo(target.trackIndex, target.positionInTrackMs)
  }

  private fun seekBackwardWithinSession(amountMs: Long, session: PlaybackSession) {
    if (amountMs <= 0) return
    updateCurrentPosition(session)
    // Clamp to the current chapter start so the rewind never crosses into the previous chapter
    val chapterStartMs = getCurrentBookChapter()?.startMs ?: 0L
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
    val loaded = queueManager.loadSession(
      player = player,
      session = session,
      playWhenReady = playWhenReady,
      playbackSpeed = playbackSpeed ?: mediaManager.getSavedPlaybackRate(),
      isCastActive = isCastActive
    )
    if (!loaded) return

    // Reset so the new session's first tick always pushes fresh metadata
    notificationMetadata.reset()
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

  private fun ensureSleepTimerStarted() {
    sleepTimerCoordinator.start(sleepTimerHostAdapter)
  }

  private val sleepTimerHostAdapter = object : SleepTimerHostAdapter {
    override val context: Context
      get() = this@Media3PlaybackService

    override fun currentTimeMs(): Long {
      return currentAbsolutePositionMs() ?: 0L
    }

    override fun durationMs(): Long {
      return currentPlaybackSession?.totalDurationMs ?: 0L
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
      val session = currentPlaybackSession ?: return
      val target = ((currentAbsolutePositionMs() ?: session.currentTimeMs) - amountMs).coerceAtLeast(0L)
      session.currentTime = target / 1000.0
      seekToSessionPosition(session)
    }

    override fun endTimeOfChapterOrTrack(): Long? =
      getCurrentBookChapter()?.endMs ?: currentPlaybackSession?.getCurrentTrackEndTime()

    override fun endTimeOfNextChapterOrTrack(): Long? =
      getNextBookChapter()?.endMs ?: currentPlaybackSession?.getNextTrackEndTime()

    override fun notifySleepTimerSet(secondsRemaining: Int, isAuto: Boolean) {
      SleepTimerNotificationCenter.notifySet(secondsRemaining, isAuto)
    }

    override fun notifySleepTimerEnded(currentPosition: Long) {
      SleepTimerNotificationCenter.notifyEnded(currentPosition)
    }

    override fun getCurrentSessionId(): String? = currentPlaybackSession?.id
  }

  /** Invalidates shelves that Auto may have cached before server data loaded. */
  private fun notifyRootChildrenChanged() {
    val session = mediaSession ?: return
    serviceScope.launch {
      runCatching {
        val nodesToRefresh = listOf(
          Media3BrowseTree.ROOT_ID,
          Media3BrowseTree.CONTINUE_LISTENING_ID,
          Media3BrowseTree.RECENTLY_ROOT,
          Media3BrowseTree.LIBRARIES_ROOT
        )
        val counts = nodesToRefresh.associateWith { browseTree.getChildren(it).size }
        session.connectedControllers.forEach { controllerInfo ->
          counts.forEach { (parentId, childCount) ->
            session.notifyChildrenChanged(controllerInfo, parentId, childCount, null)
          }
        }
      }.onFailure { debugLog(TAG) { "notifyRootChildrenChanged failed: ${it.message}" } }
    }
  }

  private fun createSessionCallback(): Media3SessionCallback {
    val seekConfig = SeekConfig(
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
      awaitFinalSync = { progressSync.awaitFinalSync(FINAL_SYNC_TIMEOUT_MS) },
      sessionController = sessionController
    )
  }

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

    media3NotificationManager.refreshMediaButtonPreferences(mediaSession)
  }

  private fun updateMediaSessionPlaybackActions() {
    commandPolicy.applyTo(mediaSession, if (this::player.isInitialized) player else null)
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
    media3NotificationManager.refreshMediaButtonPreferences(mediaSession)
    return newSpeed
  }

  override fun updatePlaybackSpeedButton(speed: Float) {
    mediaManager.setSavedPlaybackRate(speed)
    media3NotificationManager.updatePlaybackSpeedButton(speed)
    media3NotificationManager.refreshMediaButtonPreferences(mediaSession)
  }

  private fun updateTrackNavigationButtons() {
    val session = currentPlaybackSession
    val hasMultipleTracks = (session?.audioTracks?.size ?: 0) > 1
    media3NotificationManager.setTrackNavigationEnabled(hasMultipleTracks && !isCastActive)
    runCatching {
      media3NotificationManager.refreshMediaButtonPreferences(
        mediaSession
      )
    }
  }

  override fun notifyWidgetState(
    isPlaybackClosed: Boolean,
    isPlayingOverride: Boolean?
  ) {
    val session = currentPlaybackSession
    widgetPresenter.notifyState(
      session = session,
      positionMs = session?.let { positionModel(it).bookAbsoluteMs() } ?: 0L,
      isPlaying = isPlayingOverride ?: isEffectivelyPlaying(),
      isPlaybackClosed = isPlaybackClosed
    )
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

  private fun jumpBySession(deltaMs: Long) {
    val session = currentPlaybackSession ?: return
    val current = positionModel(session).bookAbsoluteMs()
    val target = (current + deltaMs).coerceIn(0L, session.totalDurationMs)
    session.currentTime = target / 1000.0
    seekToSessionPosition(session)
  }

  override fun jumpBackward() = jumpBySession(-jumpBackwardMs)

  override fun jumpForward() = jumpBySession(jumpForwardMs)

  private fun seekFromWidget(forward: Boolean) {
    jumpBySession(if (forward) jumpForwardMs else jumpBackwardMs)
    notifyWidgetState()
  }

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
