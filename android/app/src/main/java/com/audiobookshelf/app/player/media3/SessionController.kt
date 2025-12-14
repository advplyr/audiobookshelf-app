package com.audiobookshelf.app.player.media3

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

@UnstableApi
/**
 * Handles custom Media3 session commands including sleep timer, chapter navigation, and playback speed control.
 * Processes command execution and provides callbacks for various playback operations.
 */
class SessionController(
  private val context: Context,
  val availableSessionCommands: SessionCommands,
  private val setSleepTimer: (sessionId: String, timeMs: Long, isChapter: Boolean) -> Unit,
  private val cancelSleepTimer: () -> Unit,
  private val adjustSleepTimer: (deltaMs: Long, increase: Boolean) -> Unit,
  private val getSleepTimerTime: () -> Long,
  private val cyclePlaybackSpeed: (() -> Unit)?,
  private val getCurrentSession: (() -> com.audiobookshelf.app.data.PlaybackSession?)?,
  private val currentAbsolutePositionMs: (() -> Long?)?,
  private val syncProgress: (reason: String, force: Boolean, onComplete: (() -> Unit)?) -> Unit,
  private val closePlaybackCallback: (afterStop: (() -> Unit)?) -> Unit,
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

  fun onCustomCommand(command: SessionCommand, commandData: Bundle?): SessionResult {
    val customAction = command.customAction
    val syncProgressForceCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.Commands.SYNC_PROGRESS_FORCE,
      Bundle.EMPTY
    )
    val cyclePlaybackSpeedCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED,
      Bundle.EMPTY
    )
    val seekBackIncrementCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.Commands.SEEK_BACK_INCREMENT,
      Bundle.EMPTY
    )
    val seekForwardIncrementCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT,
      Bundle.EMPTY
    )
    val previousChapterCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.Commands.SEEK_TO_PREVIOUS_CHAPTER,
      Bundle.EMPTY
    )
    val nextChapterCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.Commands.SEEK_TO_NEXT_CHAPTER,
      Bundle.EMPTY
    )
    val seekToChapterCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.Commands.SEEK_TO_CHAPTER,
      Bundle.EMPTY
    )
    val setSleepTimerCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.ACTION_SET,
      Bundle.EMPTY
    )
    val cancelSleepTimerCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.ACTION_CANCEL,
      Bundle.EMPTY
    )
    val adjustSleepTimerCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.ACTION_ADJUST,
      Bundle.EMPTY
    )
    val getSleepTimerTimeCommand = SessionCommand(
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.ACTION_GET_TIME,
      Bundle.EMPTY
    )
    val sleepExtraTimeMsKey =
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.EXTRA_TIME_MS
    val sleepExtraIsChapterKey =
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.EXTRA_IS_CHAPTER
    val sleepExtraSessionIdKey =
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.EXTRA_SESSION_ID
    val sleepExtraAdjustDeltaKey =
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.EXTRA_ADJUST_DELTA
    val sleepExtraAdjustIncreaseKey =
      com.audiobookshelf.app.player.PlaybackConstants.SleepTimer.EXTRA_ADJUST_INCREASE
    val extraChapterStartMsKey = "chapter_start_ms"

    if (customAction == syncProgressForceCommand.customAction) {
      playerProvider()?.takeIf { it.isPlaying }?.pause()
      val progressSyncLatch = java.util.concurrent.CountDownLatch(1)
      syncProgress("switch", true) { progressSyncLatch.countDown() }
      progressSyncLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
      return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == cyclePlaybackSpeedCommand.customAction) {
      cyclePlaybackSpeed?.invoke(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == seekBackIncrementCommand.customAction) {
      playerProvider()?.seekBack(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == seekForwardIncrementCommand.customAction) {
      playerProvider()?.seekForward(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == previousChapterCommand.customAction) {
      val session = getCurrentSession?.invoke()
      val absolutePositionMs = currentAbsolutePositionMs?.invoke()
      if (session != null && absolutePositionMs != null) {
        val targetChapter = resolvePreviousChapter(session, absolutePositionMs)
        if (targetChapter != null) {
          playerProvider()?.seekTo(targetChapter.startMs)
          return SessionResult(SessionResult.RESULT_SUCCESS)
        }
      }
      playerProvider()?.seekBack(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == nextChapterCommand.customAction) {
      val session = getCurrentSession?.invoke()
      val absolutePositionMs = currentAbsolutePositionMs?.invoke()
      if (session != null && absolutePositionMs != null) {
        val targetChapter = session.getNextChapterForTime(absolutePositionMs)
        if (targetChapter != null) {
          playerProvider()?.seekTo(targetChapter.startMs)
          return SessionResult(SessionResult.RESULT_SUCCESS)
        }
      }
      playerProvider()?.seekForward(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == seekToChapterCommand.customAction) {
      val chapterStartMs =
        commandData?.getLong(extraChapterStartMsKey, Long.MIN_VALUE) ?: Long.MIN_VALUE
      if (chapterStartMs >= 0L) {
        playerProvider()?.seekTo(chapterStartMs)
        return SessionResult(SessionResult.RESULT_SUCCESS)
      }
      return SessionResult(SessionError.ERROR_BAD_VALUE)
    }
    if (customAction == setSleepTimerCommand.customAction) {
      val timeMs = commandData?.getLong(sleepExtraTimeMsKey, 0L) ?: 0L
      val isChapter = commandData?.getBoolean(sleepExtraIsChapterKey, false) ?: false
      val sessionId = commandData?.getString(sleepExtraSessionIdKey) ?: ""
      setSleepTimer(sessionId, timeMs, isChapter)
      return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == cancelSleepTimerCommand.customAction) {
      cancelSleepTimer(); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == adjustSleepTimerCommand.customAction) {
      val deltaMs = commandData?.getLong(sleepExtraAdjustDeltaKey, 0L) ?: 0L
      val increase = commandData?.getBoolean(sleepExtraAdjustIncreaseKey, true) ?: true
      if (deltaMs <= 0L) return SessionResult(SessionError.ERROR_BAD_VALUE)
      adjustSleepTimer(deltaMs, increase); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    if (customAction == getSleepTimerTimeCommand.customAction) {
      val remainingSleepTimeMs = getSleepTimerTime()
      return SessionResult(
        SessionResult.RESULT_SUCCESS,
        Bundle().apply { putLong(sleepExtraTimeMsKey, remainingSleepTimeMs) })
    }
    if (customAction.contains("CLOSE_PLAYBACK")) {
      closePlaybackCallback(null); return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    return SessionResult(SessionResult.RESULT_SUCCESS)
  }

  private fun resolvePreviousChapter(
    session: com.audiobookshelf.app.data.PlaybackSession,
    currentPositionMs: Long
  ): com.audiobookshelf.app.data.BookChapter? {
    val chapters = session.chapters
    if (chapters.isEmpty()) return null
    val currentChapter =
      session.getChapterForTime(currentPositionMs) ?: return chapters.firstOrNull()
    val currentIndex = chapters.indexOf(currentChapter).coerceAtLeast(0)
    val isNearChapterStart = currentPositionMs - currentChapter.startMs <= 3_000L
    return if (isNearChapterStart && currentIndex > 0) chapters[currentIndex - 1] else currentChapter
  }

  fun closePlayback(afterStop: (() -> Unit)?): Unit = closePlaybackCallback(afterStop)
  fun getContext(): Context = context

  fun buildPlayerCommands(
    controllerInfo: MediaSession.ControllerInfo,
    allowSeekingOnMediaControls: Boolean
  ): Player.Commands {

    val player = playerProvider()
    if (player == null) {
      val fallbackCommands = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .add(Player.COMMAND_GET_DEVICE_VOLUME)
        .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
        .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)

      return fallbackCommands.build()
    }

    val isAppUiController = controllerInfo.connectionHints.getBoolean("isAppUiController", false)
    val effectiveAllowSeeking = isAppUiController || allowSeekingOnMediaControls

    val baseCommands = buildBasePlayerCommands(player, effectiveAllowSeeking)
    val isWearController = controllerInfo.packageName.contains("wear", ignoreCase = true)

    val builder = Player.Commands.Builder().addAll(baseCommands)

    if (isWearController) {
      builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
      builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
      builder.remove(Player.COMMAND_SEEK_TO_NEXT)
      builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }
    return builder.build()
  }

  companion object {
    fun buildBasePlayerCommands(player: Player?, allowSeeking: Boolean): Player.Commands {
      val availablePlayerCommands = player?.availableCommands
      val builder = Player.Commands.Builder()
      if (availablePlayerCommands != null) builder.addAll(availablePlayerCommands)
      builder.add(Player.COMMAND_SEEK_BACK)
      builder.add(Player.COMMAND_SEEK_FORWARD)
      builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
      builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
      builder.add(Player.COMMAND_SEEK_TO_NEXT)
      builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
      builder.add(Player.COMMAND_PLAY_PAUSE)
      builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
      builder.add(Player.COMMAND_GET_DEVICE_VOLUME)
      builder.add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
      builder.add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
      if (!allowSeeking) {
        builder.remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
      }
      return builder.build()
    }
  }
}
