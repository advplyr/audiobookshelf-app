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
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.SleepTimerHost
import com.audiobookshelf.app.managers.SleepTimerManager
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.guava.future
import kotlin.math.abs
import kotlin.math.max


/**
 * Media3 playback service following MediaLibraryService architecture.
 * Handles local playback, session management, and native Media3 notifications.
 * Cast playback remains in legacy PlayerNotificationService until Phase 4.
 */

@UnstableApi
class Media3PlaybackService : MediaLibraryService() {
  private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  private var mediaSession: MediaLibrarySession? = null

  private var currentPlaybackSession: PlaybackSession? = null
  private var sleepTimerShakeController: SleepTimerShakeController? = null
  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

  private lateinit var seekBackButton: CommandButton
  private lateinit var seekForwardButton: CommandButton
  private var playbackSpeedCommandButton: CommandButton? = null

  private lateinit var localPlayer: AbsPlayerWrapper
  private lateinit var castPlayer: AbsPlayerWrapper
  private lateinit var activePlayer: Player
  private var playerInitialized = false


  private val playerListener = PlayerEventListener()

  /**
   * Switches the active player and transfers playback state.
   * This is the new, unified method for handling player switching.
   */
  private fun switchPlayer(to: Player) {
    if (activePlayer === to) return

    val oldPlayer = activePlayer

    // 1. Capture the state from the currently active player.
    val mediaItems = List(oldPlayer.mediaItemCount) { oldPlayer.getMediaItemAt(it) }
    val startIndex = oldPlayer.currentMediaItemIndex
    val startPosition = oldPlayer.currentPosition.coerceAtLeast(0)
    val playWhenReady = oldPlayer.isPlaying

    // 2. Stop the old player.
    oldPlayer.stop()
    oldPlayer.clearMediaItems()

    // 3. Set the new player on the session.
    activePlayer = to
    mediaSession?.player = to

    // 4. If there are items to play, load them into the new player.
    if (mediaItems.isNotEmpty()) {
      activePlayer.setMediaItems(mediaItems, startIndex, startPosition)
      activePlayer.playWhenReady = playWhenReady
      activePlayer.prepare()
    }

    Log.d(TAG, "Switched active player from ${oldPlayer.javaClass.simpleName} to ${to.javaClass.simpleName}")
  }

  private val sleepTimerHost = object : SleepTimerHost {
    override val context: Context
      get() = this@Media3PlaybackService

    override fun currentTimeMs(): Long {
      return if (playerInitialized) activePlayer.currentPosition else 0L
    }

    override fun durationMs(): Long {
      val playerDuration = if (playerInitialized) activePlayer.duration else C.TIME_UNSET
      return if (playerDuration != C.TIME_UNSET && playerDuration >= 0) {
        playerDuration
      } else {
        currentPlaybackSession?.totalDurationMs ?: 0L
      }
    }

    override fun isPlaying(): Boolean {
      return if (playerInitialized) activePlayer.isPlaying else false
    }

    override fun playbackSpeed(): Float {
      return if (playerInitialized) activePlayer.playbackParameters.speed else 1f
    }

    override fun setVolume(volume: Float) {
      if (playerInitialized) activePlayer.volume = volume.coerceIn(0f, 1f)
    }

    override fun pause() {
      if (playerInitialized) activePlayer.pause()
    }

    override fun play() {
      if (playerInitialized) activePlayer.play()
    }

    override fun seekBackward(amountMs: Long) {
      if (!playerInitialized) return
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
      sleepTimerUiNotifier?.onSleepTimerSet(secondsRemaining, isAuto)
    }

    override fun notifySleepTimerEnded(currentPosition: Long) {
      sleepTimerUiNotifier?.onSleepTimerEnded(currentPosition)
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

  private fun ensureSleepTimerManager(): SleepTimerManager {
    if (!::sleepTimerManager.isInitialized) {
      ensureShakeController()
      // Pass the serviceScope as the second argument
      sleepTimerManager = SleepTimerManager(sleepTimerHost, serviceScope)
    }
    return sleepTimerManager
  }


  // Jump increments applied to the ExoPlayer builder for skip actions
  private var jumpBackwardMs: Long = 10000L
  private var jumpForwardMs: Long = 30000L

  private val becomingNoisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && activePlayer.isPlaying) {
        Log.d(TAG, "Audio becoming noisy; pausing player")
        activePlayer.pause()
      }
    }
  }



  // Notification constants
  companion object {
    private const val APP_PREFIX = "com.audiobookshelf.app.player"
    val TAG: String = Media3PlaybackService::class.java.simpleName

    object Notification {
      const val CHANNEL_ID = "media3_playback_channel"
      const val ID = 100
    }

    object CustomCommands {
      const val CYCLE_PLAYBACK_SPEED = "$APP_PREFIX.CYCLE_PLAYBACK_SPEED"
      const val SEEK_BACK_INCREMENT = "$APP_PREFIX.SEEK_BACK_INCREMENT"
      const val SEEK_FORWARD_INCREMENT = "$APP_PREFIX.SEEK_FORWARD_INCREMENT"
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
    }

    @Volatile private var sleepTimerUiNotifier: SleepTimerUiNotifier? = null

    fun registerSleepTimerNotifier(notifier: SleepTimerUiNotifier?) {
      sleepTimerUiNotifier = notifier
    }
  }

  private lateinit var notificationProvider: MediaNotification.Provider
  private val playbackSpeedSteps = floatArrayOf(0.5f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f)
  private var playbackSpeedIndex: Int = playbackSpeedSteps.indexOfFirst { abs(it - 1.0f) < 0.01f }.let { if (it >= 0) it else 0 }
  private val cyclePlaybackSpeedCommand = SessionCommand(CustomCommands.CYCLE_PLAYBACK_SPEED, Bundle.EMPTY)
  private val setSleepTimerCommand = SessionCommand(SleepTimer.ACTION_SET, Bundle.EMPTY)
  private val cancelSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CANCEL, Bundle.EMPTY)
  private val adjustSleepTimerCommand = SessionCommand(SleepTimer.ACTION_ADJUST, Bundle.EMPTY)
  private val getSleepTimerTimeCommand = SessionCommand(SleepTimer.ACTION_GET_TIME, Bundle.EMPTY)
  private val checkAutoSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CHECK_AUTO, Bundle.EMPTY)
  private val seekBackIncrementCommand = SessionCommand(CustomCommands.SEEK_BACK_INCREMENT, Bundle.EMPTY)
  private val seekForwardIncrementCommand = SessionCommand(CustomCommands.SEEK_FORWARD_INCREMENT, Bundle.EMPTY)


  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "onCreate: Initializing Media3 playback service")

    initializeLocalPlayer()
    configureNotificationProvider()
    createNotificationChannel()
    configureCommandButtons()

    val sessionId = "AudiobookshelfMedia3_${System.currentTimeMillis()}"
    val sessionActivityIntent = createSessionActivityIntent()
    buildMediaLibrarySession(sessionId, sessionActivityIntent)

    initializeCastPlayerInBackground()

    registerBecomingNoisyReceiver()
    ensureSleepTimerManager()
  }

  private fun initializeLocalPlayer() {
    val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
      .build()

    val coreExoPlayer = ExoPlayer.Builder(this)
      .setAudioAttributes(audioAttributes,true)
      .setHandleAudioBecomingNoisy(true)
      .setSeekBackIncrementMs(jumpBackwardMs)
      .setSeekForwardIncrementMs(jumpForwardMs)
      .setDeviceVolumeControlEnabled(true)
      .build()
    localPlayer = AbsPlayerWrapper(coreExoPlayer, this).apply { addListener(playerListener) }
    activePlayer = localPlayer // Start with the local player as active
    playerInitialized = true
    Log.d(TAG, "Local player initialized.")
  }

  private fun initializeCastPlayerInBackground() {
    // Launch a coroutine on the main thread.
    serviceScope.launch {
      try {
        // 1. Get the CastContext. This MUST be called on the main thread.
        val castContext = CastContext.getSharedInstance(this@Media3PlaybackService)

        // If we get here, the context was successfully retrieved.
        Log.d(TAG, "CastContext obtained successfully.")

        // 2. Now, create and configure the CastPlayer, also on the main thread.
        val coreCastPlayer = CastPlayer(castContext)
        castPlayer = AbsPlayerWrapper(coreCastPlayer, this@Media3PlaybackService).apply {
          addListener(playerListener)
        }

        // 3. Set up the listener to automatically switch players.
        coreCastPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
          override fun onCastSessionAvailable() {
            Log.d(TAG, "Cast session available. Switching to CastPlayer.")
            switchPlayer(to = castPlayer)
          }

          override fun onCastSessionUnavailable() {
            Log.d(TAG, "Cast session unavailable. Switching back to local player.")
            switchPlayer(to = localPlayer)
          }
        })
        Log.d(TAG, "Cast player initialized and listener attached.")

      } catch (e: Exception) {
        // This will catch any exceptions from getSharedInstance if Play Services are missing.
        Log.e(TAG, "Failed to initialize CastContext. Cast feature will be unavailable.", e)
      }
    }
  }


  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channelName = "Media Playback"
      val importance = NotificationManager.IMPORTANCE_DEFAULT
      val channel = NotificationChannel(Notification.CHANNEL_ID, channelName, importance).apply {
        description = "Playback controls and progress"
        setShowBadge(false)
      }
      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
      Log.d(TAG, "Notification channel created: ${Notification.CHANNEL_ID}")
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
    Log.d(TAG, "CustomMediaNotificationProvider configured")
  }

  private fun configureCommandButtons() {
    seekBackButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
      .setSessionCommand(seekBackIncrementCommand)
      .setDisplayName("Back ${jumpBackwardMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_rewind)
      .setSlots(CommandButton.SLOT_BACK)
      .build()

    seekForwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
      .setSessionCommand(seekForwardIncrementCommand)
      .setDisplayName("Forward ${jumpForwardMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_fastforward)
      .setSlots(CommandButton.SLOT_FORWARD)
      .build()

    playbackSpeedCommandButton = createPlaybackSpeedButton(currentPlaybackSpeed())
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
    val playbackButton =
      playbackSpeedCommandButton ?: createPlaybackSpeedButton(currentPlaybackSpeed()).also { playbackSpeedCommandButton = it }

    mediaSession = MediaLibrarySession.Builder(this, activePlayer, Media3SessionCallback())
      .setId(sessionId)
      .setSessionActivity(sessionActivityIntent)
      .setMediaButtonPreferences(ImmutableList.of(playbackButton, seekBackButton, seekForwardButton))
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

    Log.d(TAG, "MediaLibrarySession created: $sessionId")
  }

  private fun registerBecomingNoisyReceiver() {
    registerReceiver(
      becomingNoisyReceiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    )
  }


  override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
    Log.d(TAG, "onUpdateNotification: foreground=$startInForegroundRequired, session=${session.id}, isPlaying=${session.player.isPlaying}, state=${session.player.playbackState}")
    super.onUpdateNotification(session, startInForegroundRequired)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
    return mediaSession
  }

  override fun onDestroy() {
    super.onDestroy()
    serviceScope.cancel()
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


    registerSleepTimerNotifier(null)
    Log.d(TAG, "onDestroy: Media3 service destroyed")
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
        if (pkg.contains("wear", ignoreCase = true) || pkg.contains("com.google.android.apps.wear", ignoreCase = true)) {
          // Map NEXT/PREV or SEEK commands sent from Wear controllers to seek increments
        if (playerCommands.contains(Player.COMMAND_SEEK_BACK) || playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) {
            val p = activePlayer.currentPosition
            val target = (p - jumpBackwardMs).coerceAtLeast(0L)
            Log.d(TAG, "Wear interaction SEEK_BACK -> seekTo=$target")
            mainHandler.post { activePlayer.seekTo(target) }
          }
          if (playerCommands.contains(Player.COMMAND_SEEK_FORWARD) || playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) {
            val p = activePlayer.currentPosition
            val dur = activePlayer.duration
            val target = (p + jumpForwardMs).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
            Log.d(TAG, "Wear interaction SEEK_FORWARD -> seekTo=$target")
            mainHandler.post { activePlayer.seekTo(target) }
          }
          // Volume commands should be exposed via Player device-volume APIs now
          // (ExoPlayer created with device-volume control enabled). If further
          // mapping to AudioManager becomes necessary we can add it after
          // observing runtime behavior on device.
        }
      } catch (t: Throwable) {
        Log.w(TAG, "onPlayerInteractionFinished handling error: ${t.message}")
      }
    }
    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
      // Build player commands - keep SEEK_BACK/FORWARD but remove track navigation
      val availablePlayerCommands = session.player.availableCommands
      val isWear = controller.packageName.contains("wear", ignoreCase = true)
      (session.player as? AbsPlayerWrapper)?.mapSkipToSeek = isWear

      val builder = Player.Commands.Builder().addAll(availablePlayerCommands)
        // Explicitly ensure core seek commands are present
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_PLAY_PAUSE)
      // Ensure device volume commands are advertised so remote controllers (Wear)
      // can show crown and send device-volume requests to the Media3 session.
      // We'll detect and log these commands and forward actual volume adjustments
      // to AudioManager in `onPlayerCommandRequest` where possible.
      builder.add(Player.COMMAND_GET_DEVICE_VOLUME)
      builder.add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
      builder.add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
      if (isWear) {
        Log.d(TAG, "Wear controller connected; removing default PREV/NEXT to allow custom buttons")

        builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
        builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        builder.remove(Player.COMMAND_SEEK_TO_NEXT)
        builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
        builder.remove(Player.COMMAND_SEEK_TO_NEXT)
      }

      val playerCommands = builder.build()

      fun cmd(c: Int) = if (playerCommands.contains(c)) "Y" else "N"
      Log.d(TAG, "Controller connected pkg=${controller.packageName}. cmd: BACK=${cmd(Player.COMMAND_SEEK_BACK)} FWD=${cmd(Player.COMMAND_SEEK_FORWARD)} SEEK_IN_ITEM=${cmd(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)} PREV=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS)} NEXT=${cmd(Player.COMMAND_SEEK_TO_NEXT)} PREV_ITEM=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)} NEXT_ITEM=${cmd(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)} VOL_GET=${cmd(Player.COMMAND_GET_DEVICE_VOLUME)} VOL_SET=${cmd(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)} VOL_ADJ=${cmd(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)}")

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
        .build()

      return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        .setAvailableSessionCommands(sessionCommands)
        .setAvailablePlayerCommands(playerCommands)
        .build()
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
      super.onPostConnect(session, controller)
      Log.d(TAG, "Post-connect: controller=${controller.packageName}")
    }


    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      // This is the implementation for session restoration.
      Log.d(TAG, "onPlaybackResumption: Controller '${controller.packageName}' requested to resume playback.")

      // Use the coroutine scope from the service to do this work asynchronously.
      // The 'future' builder from kotlinx-coroutines-guava is perfect for this.
      return serviceScope.future {
        // STEP 1: Load the last known playback session from your persistent storage.
        val lastSession = DeviceManager.getLastPlaybackSession()
        if (lastSession == null) {
          Log.w(TAG, "onPlaybackResumption: No last playback session found. Returning empty.")
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }

        Log.d(
          TAG,
          "onPlaybackResumption: Found last session for item '${lastSession.libraryItemId}' at ${lastSession.currentTimeMs}ms."
        )

        val mediaItems = lastSession.toPlayerMediaItems(this@Media3PlaybackService)
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

          val startIndex = lastSession.getCurrentTrackIndex().coerceIn(0, mediaItems.lastIndex)
          val trackStartOffsetMs = startIndex.let { lastSession.getTrackStartOffsetMs(it) }
          val startPositionMs =
            trackStartOffsetMs.let { (lastSession.currentTimeMs.minus(it)).coerceAtLeast(0L) }

          Log.d(
            TAG,
            "onPlaybackResumption: Resuming at index $startIndex with position ${startPositionMs}ms."
          )

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
          // Use immediateVoidFuture for simple success actions
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
        SleepTimer.ACTION_SET -> {
          val timeMs = args.getLong(SleepTimer.EXTRA_TIME_MS, 0L)
          val isChapter = args.getBoolean(SleepTimer.EXTRA_IS_CHAPTER, false)
          val sessionId = args.getString(SleepTimer.EXTRA_SESSION_ID)
          val success = ensureSleepTimerManager().setManualSleepTimer(
            sessionId ?: currentPlaybackSession?.id ?: "", timeMs, isChapter
          )
          val resultCode = if (success) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_BAD_VALUE
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
          if (increase) sleepTimerManager.increaseSleepTime(delta) else sleepTimerManager.decreaseSleepTime(delta)
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        SleepTimer.ACTION_GET_TIME -> {
          val time = if (::sleepTimerManager.isInitialized) sleepTimerManager.getSleepTimerTime() else 0L
          val resultBundle = Bundle().apply { putLong(SleepTimer.EXTRA_TIME_MS, time) }
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
        }
        SleepTimer.ACTION_CHECK_AUTO -> {
          if (::sleepTimerManager.isInitialized) sleepTimerManager.checkAutoSleepTimer()
          return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
      }
      return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
      return Futures.immediateFuture(
        LibraryResult.ofItem(
          MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(
              MediaMetadata.Builder()
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setTitle("Audiobookshelf")
                .build()
            )
            .build(),
          params
        )
      )
    }

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      // Return empty list for now; can populate with current playback item if needed
      return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
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
      Log.d(TAG, "AvailableCommandsChanged: ${cmd.joinToString(",")}")
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      Log.d(TAG, "onIsPlayingChanged: $isPlaying")
      // MediaController layer now emits playback state to UI
      // No legacy compat updates required
      mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      Log.d(TAG, "onPlaybackStateChanged: $playbackState")
      when (playbackState) {
        Player.STATE_READY -> Unit
        Player.STATE_ENDED -> {
          Log.d(TAG, "Playback ended")
        }
      }

    }

    override fun onPlayerError(error: PlaybackException) {
      Log.e(TAG, "Player error: ${error.message}")
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      updatePlaybackSpeedButton(playbackParameters.speed)
    }

    override fun onVolumeChanged(volume: Float) {
      Log.d(TAG, "onVolumeChanged: $volume (player volume updated)")
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      Log.d(TAG, "onMediaItemTransition: reason=$reason")
    }
  }

  private fun cyclePlaybackSpeed(): Float {
    if (playbackSpeedSteps.isEmpty()) return 1.0f
    playbackSpeedIndex = (playbackSpeedIndex + 1) % playbackSpeedSteps.size
    val newSpeed = playbackSpeedSteps[playbackSpeedIndex]
    activePlayer.setPlaybackSpeed(newSpeed)
    updatePlaybackSpeedButton(newSpeed)
    return newSpeed
  }

  private fun currentPlaybackSpeed(): Float {
    val playerSpeed = activePlayer.playbackParameters.speed
    return playerSpeed
  }

  private fun createPlaybackSpeedButton(speed: Float): CommandButton {
    val label = CustomMediaNotificationProvider.formatSpeedLabel(speed)
    val normalizedSpeed = playbackSpeedSteps.firstOrNull { abs(it - speed) < 0.01f } ?: 1.0f

    // Map speed to custom icon resource - we have icons for all our supported steps
    val customIconRes = when (normalizedSpeed) {
      0.5f -> R.drawable.ic_play_speed_0_5x
      1.0f -> R.drawable.ic_play_speed_1_0x
      1.2f -> R.drawable.ic_play_speed_1_2x
      1.5f -> R.drawable.ic_play_speed_1_5x
      2.0f -> R.drawable.ic_play_speed_2_0x
      3.0f -> R.drawable.ic_play_speed_3_0x
      else -> R.drawable.ic_play_speed_1_0x
    }

    // Use closest predefined Media3 icon constant as base
    val iconConstant = when (normalizedSpeed) {
      0.5f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
      1.0f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
      1.2f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
      1.5f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
      2.0f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
      3.0f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
      else -> CommandButton.ICON_PLAYBACK_SPEED
    }

    // Explicit overflow slot keeps speed in secondary/ellipsis menu, not primary transport row
    return CommandButton.Builder(iconConstant)
      .setSessionCommand(cyclePlaybackSpeedCommand)
      .setDisplayName(label)
      .setExtras(Bundle().apply { putFloat(Extras.DISPLAY_SPEED, speed) })
      .setSlots(CommandButton.SLOT_OVERFLOW)
      .setCustomIconResId(customIconRes)
      .build()
  }

  private fun updatePlaybackSpeedButton(speed: Float) {
    val index = playbackSpeedSteps.indexOfFirst { abs(it - speed) < 0.01f }
    if (index >= 0) playbackSpeedIndex = index
    val speedButton = createPlaybackSpeedButton(speed)
    playbackSpeedCommandButton = speedButton
    mediaSession?.let { session ->
      if (::seekBackButton.isInitialized && ::seekForwardButton.isInitialized) {
        session.setMediaButtonPreferences(
          ImmutableList.of(speedButton, seekBackButton, seekForwardButton)
        )
      }
    }
  }

}
