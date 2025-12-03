package com.audiobookshelf.app.player.media3

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

interface SleepTimerApi {
  fun set(sessionId: String, timeMs: Long, isChapter: Boolean)
  fun cancel()
  fun adjust(deltaMs: Long, increase: Boolean)
  fun getTime(): Long
  fun checkAuto()
}

interface PlaybackControlApi {
  fun sync(reason: String, force: Boolean)
  fun close(afterStop: (() -> Unit)?)
}

@UnstableApi
class SessionController(
  private val context: Context,
  val transportSessionCommands: SessionCommands,
  val customSessionCommands: SessionCommands,
  val availableSessionCommands: SessionCommands,
  val playbackSpeedCommandButton: CommandButton?,
  // Custom commands and helpers
  private val cyclePlaybackSpeedCommand: SessionCommand?,
  private val seekBackIncrementCommand: SessionCommand?,
  private val seekForwardIncrementCommand: SessionCommand?,
  private val setSleepTimerCommand: SessionCommand?,
  private val cancelSleepTimerCommand: SessionCommand?,
  private val adjustSleepTimerCommand: SessionCommand?,
  private val getSleepTimerTimeCommand: SessionCommand?,
  private val checkAutoSleepTimerCommand: SessionCommand?,
  private val sleepTimerApi: SleepTimerApi,
  private val previousChapterCommand: SessionCommand?,
  private val nextChapterCommand: SessionCommand?,
  private val seekToChapterCommand: SessionCommand?,
  private val syncProgressForceCommand: SessionCommand?,
  private val sleepExtraTimeMsKey: String?,
  private val sleepExtraIsChapterKey: String?,
  private val sleepExtraSessionIdKey: String?,
  private val sleepExtraAdjustDeltaKey: String?,
  private val sleepExtraAdjustIncreaseKey: String?,
  private val extraChapterStartMsKey: String?,
  private val cyclePlaybackSpeed: (() -> Unit)?,
  private val getCurrentSession: (() -> com.audiobookshelf.app.data.PlaybackSession?)?,
  private val currentAbsolutePositionMs: (() -> Long?)?,
  private val playbackControlApi: PlaybackControlApi,
  private val logger: (String) -> Unit,
  private val playerProvider: () -> Player?
) {
  fun onPlay() {
    playerProvider()?.play()
  }

  fun onPause() {
    playerProvider()?.pause()
  }

  fun onStop() {
    playerProvider()?.stop()
  }

  // Lightweight secondary constructor: builds default command sets internally
  constructor(
    context: Context,
    playbackSpeedCommandButton: CommandButton?,
    sleepTimerApi: SleepTimerApi,
    cyclePlaybackSpeed: (() -> Unit)?,
    getCurrentSession: (() -> com.audiobookshelf.app.data.PlaybackSession?)?,
    currentAbsolutePositionMs: (() -> Long?)?,
    playbackControlApi: PlaybackControlApi,
    logger: (String) -> Unit,
    playerProvider: () -> Player?
  ) : this(
    context = context,
    transportSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
    customSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
    availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
    playbackSpeedCommandButton = playbackSpeedCommandButton,
    cyclePlaybackSpeedCommand = SessionCommand(CustomCommands.CYCLE_PLAYBACK_SPEED, Bundle.EMPTY),
    seekBackIncrementCommand = SessionCommand(CustomCommands.SEEK_BACK_INCREMENT, Bundle.EMPTY),
    seekForwardIncrementCommand = SessionCommand(
      CustomCommands.SEEK_FORWARD_INCREMENT,
      Bundle.EMPTY
    ),
    setSleepTimerCommand = SessionCommand(SleepTimer.ACTION_SET, Bundle.EMPTY),
    cancelSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CANCEL, Bundle.EMPTY),
    adjustSleepTimerCommand = SessionCommand(SleepTimer.ACTION_ADJUST, Bundle.EMPTY),
    getSleepTimerTimeCommand = SessionCommand(SleepTimer.ACTION_GET_TIME, Bundle.EMPTY),
    checkAutoSleepTimerCommand = SessionCommand(SleepTimer.ACTION_CHECK_AUTO, Bundle.EMPTY),
    previousChapterCommand = SessionCommand(CustomCommands.SEEK_TO_PREVIOUS_CHAPTER, Bundle.EMPTY),
    nextChapterCommand = SessionCommand(CustomCommands.SEEK_TO_NEXT_CHAPTER, Bundle.EMPTY),
    seekToChapterCommand = SessionCommand(CustomCommands.SEEK_TO_CHAPTER, Bundle.EMPTY),
    syncProgressForceCommand = SessionCommand(CustomCommands.SYNC_PROGRESS_FORCE, Bundle.EMPTY),
    sleepExtraTimeMsKey = SleepTimer.EXTRA_TIME_MS,
    sleepExtraIsChapterKey = SleepTimer.EXTRA_IS_CHAPTER,
    sleepExtraSessionIdKey = SleepTimer.EXTRA_SESSION_ID,
    sleepExtraAdjustDeltaKey = SleepTimer.EXTRA_ADJUST_DELTA,
    sleepExtraAdjustIncreaseKey = SleepTimer.EXTRA_ADJUST_INCREASE,
    extraChapterStartMsKey = CustomCommands.EXTRA_CHAPTER_START_MS,
    cyclePlaybackSpeed = cyclePlaybackSpeed,
    sleepTimerApi = sleepTimerApi,
    getCurrentSession = getCurrentSession,
    currentAbsolutePositionMs = currentAbsolutePositionMs,
    playbackControlApi = playbackControlApi,
    logger = logger,
    playerProvider = playerProvider
  )

  private object CustomCommands {
    const val CYCLE_PLAYBACK_SPEED = "com.audiobookshelf.app.player.CYCLE_PLAYBACK_SPEED"
    const val SEEK_BACK_INCREMENT = "com.audiobookshelf.app.player.SEEK_BACK_INCREMENT"
    const val SEEK_FORWARD_INCREMENT = "com.audiobookshelf.app.player.SEEK_FORWARD_INCREMENT"
    const val SEEK_TO_PREVIOUS_CHAPTER = "com.audiobookshelf.app.player.SEEK_TO_PREVIOUS_CHAPTER"
    const val SEEK_TO_NEXT_CHAPTER = "com.audiobookshelf.app.player.SEEK_TO_NEXT_CHAPTER"
    const val SEEK_TO_CHAPTER = "com.audiobookshelf.app.player.SEEK_TO_CHAPTER"
    const val SYNC_PROGRESS_FORCE = "com.audiobookshelf.app.player.SYNC_PROGRESS_FORCE"
    const val EXTRA_CHAPTER_START_MS = "chapter_start_ms"
  }

  private object SleepTimer {
    const val ACTION_SET = "com.audiobookshelf.app.player.SET_SLEEP_TIMER"
    const val ACTION_CANCEL = "com.audiobookshelf.app.player.CANCEL_SLEEP_TIMER"
    const val ACTION_ADJUST = "com.audiobookshelf.app.player.ADJUST_SLEEP_TIMER"
    const val ACTION_GET_TIME = "com.audiobookshelf.app.player.GET_SLEEP_TIMER_TIME"
    const val ACTION_CHECK_AUTO = "com.audiobookshelf.app.player.CHECK_AUTO_SLEEP_TIMER"
    const val EXTRA_TIME_MS = "sleep_timer_time_ms"
    const val EXTRA_IS_CHAPTER = "sleep_timer_is_chapter"
    const val EXTRA_SESSION_ID = "sleep_timer_session_id"
    const val EXTRA_ADJUST_DELTA = "sleep_timer_adjust_delta"
    const val EXTRA_ADJUST_INCREASE = "sleep_timer_adjust_increase"
  }

  fun onSeekTo(positionMs: Long) {
    playerProvider()?.seekTo(positionMs)
  }

  fun onSkipToNext() {
    playerProvider()?.seekToNext()
  }

  fun onSkipToPrevious() {
    playerProvider()?.seekToPrevious()
  }

  fun onSetShuffleModeEnabled(enabled: Boolean) {
    playerProvider()?.let { it.shuffleModeEnabled = enabled }
  }

  fun onSetRepeatMode(repeatMode: Int) {
    playerProvider()?.let { it.repeatMode = repeatMode }
  }

  fun onCustomCommand(command: SessionCommand, data: Bundle?): SessionResult {
    val action = command.customAction
    if (syncProgressForceCommand != null && action == syncProgressForceCommand.customAction) {
      val player = playerProvider()
      if (player != null && player.isPlaying) {
        player.pause()
      }
      playbackControlApi.sync("switch", true)
      return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (cyclePlaybackSpeedCommand != null && action == cyclePlaybackSpeedCommand.customAction) {
      cyclePlaybackSpeed?.invoke(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (seekBackIncrementCommand != null && action == seekBackIncrementCommand.customAction) {
      playerProvider()?.seekBack(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (seekForwardIncrementCommand != null && action == seekForwardIncrementCommand.customAction) {
      playerProvider()?.seekForward(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (previousChapterCommand != null && action == previousChapterCommand.customAction) {
      val session = getCurrentSession?.invoke()
      val absPos = currentAbsolutePositionMs?.invoke()
      if (session != null && absPos != null) {
        val target = resolvePreviousChapter(session, absPos)
        if (target != null) {
          // Use absolute seek via player
          playerProvider()?.let { p -> p.seekTo(target.startMs) }
          return SessionResult(SessionResult.RESULT_SUCCESS)
        }
      }
      playerProvider()?.seekBack(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (nextChapterCommand != null && action == nextChapterCommand.customAction) {
      val session = getCurrentSession?.invoke()
      val absPos = currentAbsolutePositionMs?.invoke()
      if (session != null && absPos != null) {
        val target = session.getNextChapterForTime(absPos)
        if (target != null) {
          playerProvider()?.let { p -> p.seekTo(target.startMs) }
          return SessionResult(SessionResult.RESULT_SUCCESS)
        }
      }
      playerProvider()?.seekForward(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (seekToChapterCommand != null && action == seekToChapterCommand.customAction) {
      val startMs = data?.getLong(extraChapterStartMsKey ?: "", Long.MIN_VALUE) ?: Long.MIN_VALUE
      if (startMs >= 0L) {
        playerProvider()?.seekTo(startMs)
        return SessionResult(SessionResult.RESULT_SUCCESS)
      }
      return SessionResult(SessionError.ERROR_BAD_VALUE)
    }
    if (setSleepTimerCommand != null && action == setSleepTimerCommand.customAction) {
      val timeMs = data?.getLong(sleepExtraTimeMsKey ?: "", 0L) ?: 0L
      val isChapter = data?.getBoolean(sleepExtraIsChapterKey ?: "", false) ?: false
      val sessionId = data?.getString(sleepExtraSessionIdKey ?: "") ?: ""
      sleepTimerApi.set(sessionId, timeMs, isChapter)
      return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (cancelSleepTimerCommand != null && action == cancelSleepTimerCommand.customAction) {
      sleepTimerApi.cancel(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (adjustSleepTimerCommand != null && action == adjustSleepTimerCommand.customAction) {
      val delta = data?.getLong(sleepExtraAdjustDeltaKey ?: "", 0L) ?: 0L
      val inc = data?.getBoolean(sleepExtraAdjustIncreaseKey ?: "", true) ?: true
      if (delta <= 0L) return SessionResult(SessionError.ERROR_BAD_VALUE)
      sleepTimerApi.adjust(delta, inc); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (getSleepTimerTimeCommand != null && action == getSleepTimerTimeCommand.customAction) {
      val time = sleepTimerApi.getTime()
      return SessionResult(
        SessionResult.RESULT_SUCCESS,
        Bundle().apply { putLong(sleepExtraTimeMsKey ?: "sleep_time_ms", time) })
    }
    if (checkAutoSleepTimerCommand != null && action == checkAutoSleepTimerCommand.customAction) {
      sleepTimerApi.checkAuto(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (action.contains("CLOSE_PLAYBACK")) {
      playbackControlApi.close(null); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    // Default success
    return SessionResult(SessionResult.RESULT_SUCCESS)
  }

  private fun resolvePreviousChapter(
    session: com.audiobookshelf.app.data.PlaybackSession,
    currentMs: Long
  ): com.audiobookshelf.app.data.BookChapter? {
    val chapters = session.chapters
    if (chapters.isEmpty()) return null
    val currentChapter = session.getChapterForTime(currentMs) ?: return chapters.firstOrNull()
    val currentIndex = chapters.indexOf(currentChapter).coerceAtLeast(0)
    val nearStart = currentMs - currentChapter.startMs <= 3_000L
    return if (nearStart && currentIndex > 0) chapters[currentIndex - 1] else currentChapter
  }

  fun closePlayback(afterStop: (() -> Unit)?) = playbackControlApi.close(afterStop)
  fun debugLog(message: String) = logger(message)
  fun getContext(): Context = context
  fun currentPlayer(): Player? = playerProvider()

  fun buildPlayerCommands(
    controllerInfo: MediaSession.ControllerInfo,
    allowSeekingOnMediaControls: Boolean,
    appPackageName: String
  ): Player.Commands {
    val player = playerProvider() ?: return Player.Commands.EMPTY
    val available = player.availableCommands
    val isWear = controllerInfo.packageName.contains("wear", ignoreCase = true)
    val isAppController = controllerInfo.packageName == appPackageName

    val builder = Player.Commands.Builder().addAll(available)
      .add(Player.COMMAND_SEEK_BACK)
      .add(Player.COMMAND_SEEK_FORWARD)
      .add(Player.COMMAND_PLAY_PAUSE)
      .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
      .add(Player.COMMAND_GET_DEVICE_VOLUME)
      .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
      .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)

    if (!isAppController && !allowSeekingOnMediaControls) {
      builder.remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }
    if (isWear) {
      // Allow custom buttons for wear
      builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
      builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
      builder.remove(Player.COMMAND_SEEK_TO_NEXT)
      builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }
    return builder.build()
  }
}
