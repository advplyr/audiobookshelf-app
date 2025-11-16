package com.audiobookshelf.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.media.AudioManager
// Removed legacy compat imports
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.managers.SleepTimerManager
import com.audiobookshelf.app.managers.SleepTimerHost
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlin.jvm.Volatile
import kotlin.math.abs
import kotlin.math.max

/**
 * Media3 playback service following MediaLibraryService architecture.
 * Handles local playback, session management, and native Media3 notifications.
 * Cast playback remains in legacy PlayerNotificationService until Phase 4.
 */
@UnstableApi
class Media3PlaybackService : MediaLibraryService() {
  private val tag = "Media3PlaybackService"
  
  private var mediaSession: MediaLibraryService.MediaLibrarySession? = null
  private var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
  private var sessionPlayer: Player? = null
  private var skipForwardingPlayer: SkipCommandForwardingPlayer? = null
  // Removed compatSession property
  private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
  
  private var currentPlaybackSession: PlaybackSession? = null
  private var sleepTimerManager: SleepTimerManager? = null
  private var sleepTimerShakeController: SleepTimerShakeController? = null
  private lateinit var rewindCommandButton: CommandButton
  private lateinit var forwardCommandButton: CommandButton
  private var playbackSpeedCommandButton: CommandButton? = null

  private val sleepTimerHost = object : SleepTimerHost {
    override val context: android.content.Context
      get() = this@Media3PlaybackService

    override fun currentTimeMs(): Long {
      return sessionPlayer?.currentPosition ?: 0L
    }

    override fun durationMs(): Long {
      val playerDuration = sessionPlayer?.duration ?: C.TIME_UNSET
      return if (playerDuration != C.TIME_UNSET && playerDuration >= 0) {
        playerDuration
      } else {
        currentPlaybackSession?.totalDurationMs ?: 0L
      }
    }

    override fun isPlaying(): Boolean {
      return sessionPlayer?.isPlaying ?: false
    }

    override fun playbackSpeed(): Float {
      return sessionPlayer?.playbackParameters?.speed ?: 1f
    }

    override fun setVolume(volume: Float) {
      sessionPlayer?.volume = volume.coerceIn(0f, 1f)
    }

    override fun pause() {
      sessionPlayer?.pause()
    }

    override fun play() {
      sessionPlayer?.play()
    }

    override fun seekBackward(amountMs: Long) {
      sessionPlayer?.let { player ->
        val target = max(player.currentPosition - amountMs, 0L)
        player.seekTo(target)
      }
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

  private fun ensureShakeController() {
    if (sleepTimerShakeController == null) {
      sleepTimerShakeController = SleepTimerShakeController(
        this,
        SLEEP_TIMER_WAKE_UP_EXPIRATION
      ) {
        sleepTimerManager?.handleShake()
      }
    }
  }

  private fun ensureSleepTimerManager(): SleepTimerManager {
    if (sleepTimerManager == null) {
      ensureShakeController()
      sleepTimerManager = SleepTimerManager(sleepTimerHost)
    }
    return sleepTimerManager!!
  }
  
  // Jump increments applied to the ExoPlayer builder for skip actions
  private var jumpBackwardMs: Long = 10000L
  private var jumpForwardMs: Long = 30000L
  

  
  // Notification constants
  companion object {
    const val NOTIFICATION_CHANNEL_ID = "media3_playback_channel"
    const val NOTIFICATION_ID = 100
    internal const val CUSTOM_COMMAND_CYCLE_PLAYBACK_SPEED = "com.audiobookshelf.player.CYCLE_PLAYBACK_SPEED"
    internal const val EXTRA_DISPLAY_SPEED = "display_speed"

    const val CUSTOM_COMMAND_SET_SLEEP_TIMER = "com.audiobookshelf.player.SET_SLEEP_TIMER"
    const val CUSTOM_COMMAND_CANCEL_SLEEP_TIMER = "com.audiobookshelf.player.CANCEL_SLEEP_TIMER"
    const val CUSTOM_COMMAND_ADJUST_SLEEP_TIMER = "com.audiobookshelf.player.ADJUST_SLEEP_TIMER"
    const val CUSTOM_COMMAND_GET_SLEEP_TIMER_TIME = "com.audiobookshelf.player.GET_SLEEP_TIMER_TIME"
    const val CUSTOM_COMMAND_CHECK_AUTO_SLEEP_TIMER = "com.audiobookshelf.player.CHECK_AUTO_SLEEP_TIMER"

    const val EXTRA_SLEEP_TIMER_TIME_MS = "sleep_timer_time_ms"
    const val EXTRA_SLEEP_TIMER_IS_CHAPTER = "sleep_timer_is_chapter"
    const val EXTRA_SLEEP_TIMER_SESSION_ID = "sleep_timer_session_id"
    const val EXTRA_SLEEP_TIMER_ADJUST_DELTA = "sleep_timer_adjust_delta"
    const val EXTRA_SLEEP_TIMER_ADJUST_INCREASE = "sleep_timer_adjust_increase"

    @Volatile private var sleepTimerUiNotifier: SleepTimerUiNotifier? = null

    fun registerSleepTimerNotifier(notifier: SleepTimerUiNotifier?) {
      sleepTimerUiNotifier = notifier
    }
  }

  private lateinit var notificationProvider: androidx.media3.session.MediaNotification.Provider
  private val playbackSpeedSteps = floatArrayOf(0.5f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f)
  private var playbackSpeedIndex: Int = playbackSpeedSteps.indexOfFirst { abs(it - 1.0f) < 0.01f }.let { if (it >= 0) it else 0 }
  private val playbackSpeedCommand = SessionCommand(CUSTOM_COMMAND_CYCLE_PLAYBACK_SPEED, Bundle.EMPTY)
  private val setSleepTimerCommand = SessionCommand(CUSTOM_COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY)
  private val cancelSleepTimerCommand = SessionCommand(CUSTOM_COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY)
  private val adjustSleepTimerCommand = SessionCommand(CUSTOM_COMMAND_ADJUST_SLEEP_TIMER, Bundle.EMPTY)
  private val getSleepTimerTimeCommand = SessionCommand(CUSTOM_COMMAND_GET_SLEEP_TIMER_TIME, Bundle.EMPTY)
  private val checkAutoSleepTimerCommand = SessionCommand(CUSTOM_COMMAND_CHECK_AUTO_SLEEP_TIMER, Bundle.EMPTY)

  override fun onCreate() {
    super.onCreate()
    Log.d(tag, "onCreate: Initializing Media3 playback service")
    
    // Create notification channel
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channelName = "Media Playback"
      val importance = NotificationManager.IMPORTANCE_DEFAULT
      val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, importance).apply {
        description = "Playback controls and progress"
        setShowBadge(false)
      }
      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
      Log.d(tag, "Notification channel created: $NOTIFICATION_CHANNEL_ID")
    }
    
    val notificationProvider = CustomMediaNotificationProvider(
      this,
      NOTIFICATION_CHANNEL_ID,
      androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID,
      NOTIFICATION_ID
    )
    this.notificationProvider = notificationProvider
    setMediaNotificationProvider(notificationProvider)
    Log.d(tag, "CustomMediaNotificationProvider configured")

      // Initialize ExoPlayer and wrap it with a delegating Player so base skip actions stay present
    val corePlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this)
      .setSeekBackIncrementMs(jumpBackwardMs)
      .setSeekForwardIncrementMs(jumpForwardMs)
      .setDeviceVolumeControlEnabled(true)
      .build()
    exoPlayer = corePlayer
    val delegatingPlayer = SkipCommandForwardingPlayer(corePlayer, this)
    skipForwardingPlayer = delegatingPlayer
    sessionPlayer = delegatingPlayer
    delegatingPlayer.addListener(PlayerEventListener())

    // Create MediaLibrarySession with callback
    val sessionId = "AudiobookshelfMedia3_${System.currentTimeMillis()}"

    rewindCommandButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
      .setPlayerCommand(Player.COMMAND_SEEK_BACK)
      .setDisplayName("Back 10s")
      .setCustomIconResId(R.drawable.exo_icon_rewind)
      .setSlots(CommandButton.SLOT_BACK)
      .build()
    forwardCommandButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
      .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
      .setDisplayName("Forward 30s")
      .setCustomIconResId(R.drawable.exo_icon_fastforward)
      .setSlots(CommandButton.SLOT_FORWARD)
      .build()
    val playbackSpeedButton = createPlaybackSpeedButton(currentPlaybackSpeed())
    playbackSpeedCommandButton = playbackSpeedButton

    val playerInstance = sessionPlayer ?: error("Player not initialised")
    
    // ...existing code...
    
    // Create session activity intent before building Media3 session
    val sessionActivityFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val sessionActivityIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      },
      sessionActivityFlags
    )

    // ...existing code...
    
    // Now build the Media3 session with the compat session token
    mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, playerInstance, Media3SessionCallback())
      .setId(sessionId)
      .setSessionActivity(sessionActivityIntent)
      .setMediaButtonPreferences(ImmutableList.of(rewindCommandButton, forwardCommandButton, playbackSpeedButton))
      .build()
      
    // Initial state/metadata handled via Media3 session

    // Set session extras to reserve prev/next slots so system doesn't add default buttons
    val sessionExtras = Bundle().apply {
      // Do NOT reserve prev/next slots so system surfaces (e.g., Wear OS)
      // can advertise/use their default previous/next controls.
      // Our phone notification provider still forces SEEK_BACK/SEEK_FORWARD explicitly.
      putBoolean(androidx.media3.session.MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, false)
      putBoolean(androidx.media3.session.MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, false)
    }
    mediaSession?.setSessionExtras(sessionExtras)

    Log.d(tag, "MediaLibrarySession created: $sessionId")

    ensureSleepTimerManager()
  }
  
  // Removed updateCompatPlaybackState and updateCompatMetadataFromPlayer methods
  
  override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
    Log.d(tag, "onUpdateNotification: foreground=$startInForegroundRequired, session=${session.id}, isPlaying=${session.player.isPlaying}, state=${session.player.playbackState}")
    super.onUpdateNotification(session, startInForegroundRequired)
  }
  
  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
    return mediaSession
  }

  override fun onDestroy() {
    mediaSession?.run {
      release()
      mediaSession = null
    }
    exoPlayer?.release()
    exoPlayer = null
    sessionPlayer = null
    // Removed compatSession cleanup
    sleepTimerShakeController?.destroy()
    sleepTimerShakeController = null
    sleepTimerManager = null
    registerSleepTimerNotifier(null)
    super.onDestroy()
    Log.d(tag, "onDestroy: Media3 service destroyed")
  }

  /**
   * MediaLibrarySession callback handling transport controls and custom commands.
   */
    private inner class Media3SessionCallback : MediaLibraryService.MediaLibrarySession.Callback {
    override fun onPlayerInteractionFinished(
      session: MediaSession,
      controllerInfo: MediaSession.ControllerInfo,
      playerCommands: Player.Commands
    ) {
      try {
        val pkg = controllerInfo.packageName ?: ""
        if (pkg.contains("wear", ignoreCase = true) || pkg.contains("com.google.android.apps.wear", ignoreCase = true)) {
          // Map NEXT/PREV or SEEK commands sent from Wear controllers to seek increments
          if (playerCommands.contains(Player.COMMAND_SEEK_BACK) || playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) {
            val p = exoPlayer?.currentPosition ?: sessionPlayer?.currentPosition ?: 0L
            val target = (p - jumpBackwardMs).coerceAtLeast(0L)
            Log.d(tag, "Wear interaction SEEK_BACK -> seekTo=$target")
            mainHandler.post { exoPlayer?.seekTo(target) }
          }
          if (playerCommands.contains(Player.COMMAND_SEEK_FORWARD) || playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) {
            val p = exoPlayer?.currentPosition ?: sessionPlayer?.currentPosition ?: 0L
            val dur = exoPlayer?.duration ?: sessionPlayer?.duration ?: Long.MAX_VALUE
            val target = (p + jumpForwardMs).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
            Log.d(tag, "Wear interaction SEEK_FORWARD -> seekTo=$target")
            mainHandler.post { exoPlayer?.seekTo(target) }
          }
          // Volume commands should be exposed via Player device-volume APIs now
          // (ExoPlayer created with device-volume control enabled). If further
          // mapping to AudioManager becomes necessary we can add it after
          // observing runtime behavior on device.
        }
      } catch (t: Throwable) {
        Log.w(tag, "onPlayerInteractionFinished handling error: ${t.message}")
      }
    }
    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
      // Build player commands - keep SEEK_BACK/FORWARD but remove track navigation
      val availablePlayerCommands = session.player.availableCommands
      val isWear = controller.packageName.contains("wear", ignoreCase = true)
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
        // Let Wear keep NEXT/PREV visible, but map them to jumps at player layer.
        skipForwardingPlayer?.preferSeekOverSkip = true
        Log.d(tag, "Wear controller connected; mapping NEXT/PREV to seek increments")
      }
      val playerCommands = builder.build()

      fun cmd(c: Int) = if (playerCommands.contains(c)) "Y" else "N"
      Log.d(tag, "Controller connected pkg=${controller.packageName}. cmds: BACK=${cmd(Player.COMMAND_SEEK_BACK)} FWD=${cmd(Player.COMMAND_SEEK_FORWARD)} SEEK_IN_ITEM=${cmd(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)} PREV=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS)} NEXT=${cmd(Player.COMMAND_SEEK_TO_NEXT)} PREV_ITEM=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)} NEXT_ITEM=${cmd(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)} VOL_GET=${cmd(Player.COMMAND_GET_DEVICE_VOLUME)} VOL_SET=${cmd(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)} VOL_ADJ=${cmd(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)}")

      val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        .buildUpon()
        .add(playbackSpeedCommand)
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
      Log.d(tag, "Post-connect: controller=${controller.packageName}")
    }

    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      // For now, return empty; will implement session restoration later
      return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0))
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle
    ): ListenableFuture<SessionResult> {
      return when (customCommand.customAction) {
        CUSTOM_COMMAND_CYCLE_PLAYBACK_SPEED -> {
          val newSpeed = cyclePlaybackSpeed()
          Log.d(tag, "Playback speed command from controller. New speed=$newSpeed")
          Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        CUSTOM_COMMAND_SET_SLEEP_TIMER -> {
          val timeMs = args.getLong(EXTRA_SLEEP_TIMER_TIME_MS, 0L)
          val isChapter = args.getBoolean(EXTRA_SLEEP_TIMER_IS_CHAPTER, false)
          val sessionId = args.getString(EXTRA_SLEEP_TIMER_SESSION_ID)
          val manager = ensureSleepTimerManager()
          val resolvedSessionId = sessionId ?: currentPlaybackSession?.id ?: ""
          val success = manager.setManualSleepTimer(resolvedSessionId, timeMs, isChapter)
          val resultCode = if (success) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_BAD_VALUE
          Futures.immediateFuture(SessionResult(resultCode))
        }
        CUSTOM_COMMAND_CANCEL_SLEEP_TIMER -> {
          sleepTimerManager?.cancelSleepTimer()
          Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        CUSTOM_COMMAND_ADJUST_SLEEP_TIMER -> {
          val delta = args.getLong(EXTRA_SLEEP_TIMER_ADJUST_DELTA, 0L)
          val increase = args.getBoolean(EXTRA_SLEEP_TIMER_ADJUST_INCREASE, true)
          val manager = sleepTimerManager
          if (manager == null || delta <= 0L) {
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
          } else {
            if (increase) {
              manager.increaseSleepTime(delta)
            } else {
              manager.decreaseSleepTime(delta)
            }
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
          }
        }
        CUSTOM_COMMAND_GET_SLEEP_TIMER_TIME -> {
          val bundle = Bundle().apply {
            putLong(EXTRA_SLEEP_TIMER_TIME_MS, sleepTimerManager?.getSleepTimerTime() ?: 0L)
          }
          Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundle))
        }
        CUSTOM_COMMAND_CHECK_AUTO_SLEEP_TIMER -> {
          sleepTimerManager?.checkAutoSleepTimer()
          Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        else -> super.onCustomCommand(session, controller, customCommand, args)
      }
    }

    override fun onGetLibraryRoot(
      session: MediaLibraryService.MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
      return Futures.immediateFuture(
        LibraryResult.ofItem(
          MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(
              androidx.media3.common.MediaMetadata.Builder()
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
      session: MediaLibraryService.MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: MediaLibraryService.LibraryParams?
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
      val cmds = mutableListOf<String>()
      if (availableCommands.contains(Player.COMMAND_SEEK_BACK)) cmds.add("SEEK_BACK")
      if (availableCommands.contains(Player.COMMAND_SEEK_FORWARD)) cmds.add("SEEK_FORWARD")
      if (availableCommands.contains(Player.COMMAND_PLAY_PAUSE)) cmds.add("PLAY_PAUSE")
      if (availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) cmds.add("SEEK_IN_ITEM")
      if (availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) cmds.add("SEEK_TO_PREVIOUS")
      if (availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) cmds.add("SEEK_TO_NEXT")
      if (availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) cmds.add("PREV_ITEM")
      if (availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) cmds.add("NEXT_ITEM")
      if (availableCommands.contains(Player.COMMAND_GET_DEVICE_VOLUME)) cmds.add("GET_DEV_VOL")
      if (availableCommands.contains(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)) cmds.add("SET_DEV_VOL")
      if (availableCommands.contains(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) cmds.add("ADJ_DEV_VOL")
      Log.d(tag, "AvailableCommandsChanged: ${cmds.joinToString(",")}")
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      Log.d(tag, "onIsPlayingChanged: $isPlaying")
      // MediaController layer now emits playback state to UI
      // No legacy compat updates required
      mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      Log.d(tag, "onPlaybackStateChanged: $playbackState")
      when (playbackState) {
        Player.STATE_READY -> Unit
        Player.STATE_ENDED -> {
          Log.d(tag, "Playback ended")
        }
      }
      // No legacy compat updates required
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
      Log.e(tag, "Player error: ${error.message}")
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      updatePlaybackSpeedButton(playbackParameters.speed)
    }

    override fun onVolumeChanged(volume: Float) {
      Log.d(tag, "onVolumeChanged: $volume (player volume updated)")
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      Log.d(tag, "onMediaItemTransition: reason=$reason")
    }
  }

  /**
   * Update MediaSession metadata for notification display.
   */
  fun updateSessionMetadata(playbackSession: PlaybackSession?) {
    playbackSession?.let { session ->
      currentPlaybackSession = session
      Log.d(tag, "Session metadata updated: ${session.displayTitle}")
    }
  }

  private fun cyclePlaybackSpeed(): Float {
    if (playbackSpeedSteps.isEmpty()) return 1.0f
    playbackSpeedIndex = (playbackSpeedIndex + 1) % playbackSpeedSteps.size
    val newSpeed = playbackSpeedSteps[playbackSpeedIndex]
    sessionPlayer?.setPlaybackSpeed(newSpeed)
    updatePlaybackSpeedButton(newSpeed)
    return newSpeed
  }

  private fun currentPlaybackSpeed(): Float {
    val playerSpeed = sessionPlayer?.playbackParameters?.speed
    return playerSpeed ?: playbackSpeedSteps.getOrElse(playbackSpeedIndex) { 1.0f }
  }

  private fun createPlaybackSpeedButton(speed: Float): CommandButton {
    val label = CustomMediaNotificationProvider.formatSpeedLabel(speed)
    
    // Map speed to custom icon resource - we have icons for all our speed steps
    val customIconRes = when {
      abs(speed - 0.5f) < 0.01f -> R.drawable.ic_play_speed_0_5x
      abs(speed - 1.0f) < 0.01f -> R.drawable.ic_play_speed_1_0x
      abs(speed - 1.2f) < 0.01f -> R.drawable.ic_play_speed_1_2x
      abs(speed - 1.5f) < 0.01f -> R.drawable.ic_play_speed_1_5x
      abs(speed - 2.0f) < 0.01f -> R.drawable.ic_play_speed_2_0x
      abs(speed - 3.0f) < 0.01f -> R.drawable.ic_play_speed_3_0x
      else -> R.drawable.ic_play_speed_1_0x // Fallback to 1.0x
    }
    
    // Use closest predefined Media3 icon constant as base
    val iconConstant = when {
      abs(speed - 0.5f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
      abs(speed - 1.0f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
      abs(speed - 1.2f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
      abs(speed - 1.5f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
      abs(speed - 2.0f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
      abs(speed - 3.0f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_2_0 // Closest available
      else -> CommandButton.ICON_PLAYBACK_SPEED
    }
    
    // Explicit overflow slot keeps speed in secondary/ellipsis menu, not primary transport row
    return CommandButton.Builder(iconConstant)
      .setSessionCommand(playbackSpeedCommand)
      .setDisplayName(label)
      .setExtras(Bundle().apply { putFloat(EXTRA_DISPLAY_SPEED, speed) })
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
      if (::rewindCommandButton.isInitialized && ::forwardCommandButton.isInitialized) {
        session.setMediaButtonPreferences(
          ImmutableList.of(rewindCommandButton, forwardCommandButton, speedButton)
        )
      }
    }
  }

}
