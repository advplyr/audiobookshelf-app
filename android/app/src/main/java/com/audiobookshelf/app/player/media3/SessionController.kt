package com.audiobookshelf.app.player.media3

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobookshelf.app.data.BookChapter
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.player.PlaybackConstants

/**
 * Handles custom Media3 session commands including sleep timer, chapter navigation, and playback speed control.
 * Processes command execution and provides callbacks for various playback operations.
 */
@UnstableApi
class SessionController(
  val availableSessionCommands: SessionCommands,
  private val host: Media3ServiceHost
) {
  fun onCustomCommand(command: SessionCommand, commandData: Bundle?): SessionResult {
    val action = command.customAction
    val success = SessionResult(SessionResult.RESULT_SUCCESS)

    return when (action) {
      PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED -> {
        host.cyclePlaybackSpeed()
        success
      }

      PlaybackConstants.Commands.SEEK_BACK_INCREMENT -> {
        host.jumpBackward()
        success
      }

      PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT -> {
        host.jumpForward()
        success
      }

      PlaybackConstants.Commands.SEEK_TO_PREVIOUS_TRACK -> {
        host.playerOrNull()?.seekToPreviousMediaItem()
        success
      }

      PlaybackConstants.Commands.SEEK_TO_NEXT_TRACK -> {
        host.playerOrNull()?.seekToNextMediaItem()
        success
      }

      PlaybackConstants.Commands.SEEK_TO_PREVIOUS_CHAPTER -> {
        val session = host.currentSession()
        val absolutePositionMs = host.currentAbsolutePositionMs()
        if (session != null && absolutePositionMs != null) {
          val targetChapter = resolvePreviousChapter(session, absolutePositionMs)
          if (targetChapter != null) {
            host.playerOrNull()?.seekTo(targetChapter.startMs)
            return success
          }
        }
        host.playerOrNull()?.seekBack()
        success
      }

      PlaybackConstants.Commands.SEEK_TO_NEXT_CHAPTER -> {
        val session = host.currentSession()
        val absolutePositionMs = host.currentAbsolutePositionMs()
        if (session != null && absolutePositionMs != null) {
          val targetChapter = session.getNextChapterForTime(absolutePositionMs)
          if (targetChapter != null) {
            host.playerOrNull()?.seekTo(targetChapter.startMs)
            return success
          }
        }
        host.playerOrNull()?.seekForward()
        success
      }

      PlaybackConstants.Commands.SEEK_TO_CHAPTER -> {
        val chapterStartMs =
          commandData?.getLong(KEY_CHAPTER_START_MS, Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (chapterStartMs >= 0L) {
          host.playerOrNull()?.seekTo(chapterStartMs)
          success
        } else {
          SessionResult(SessionError.ERROR_BAD_VALUE)
        }
      }

      PlaybackConstants.SleepTimer.ACTION_SET -> {
        val timeMs = commandData?.getLong(PlaybackConstants.SleepTimer.EXTRA_TIME_MS, 0L)
          ?: 0L
        val isChapter = commandData?.getBoolean(PlaybackConstants.SleepTimer.EXTRA_IS_CHAPTER, false)
          ?: false
        val sessionId = commandData?.getString(PlaybackConstants.SleepTimer.EXTRA_SESSION_ID)
          ?: ""
        host.setSleepTimer(sessionId, timeMs, isChapter)
        success
      }

      PlaybackConstants.SleepTimer.ACTION_CANCEL -> {
        host.cancelSleepTimer()
        success
      }

      PlaybackConstants.SleepTimer.ACTION_ADJUST -> {
        val deltaMs = commandData?.getLong(PlaybackConstants.SleepTimer.EXTRA_ADJUST_DELTA, 0L)
          ?: 0L
        val increase = commandData?.getBoolean(PlaybackConstants.SleepTimer.EXTRA_ADJUST_INCREASE, true)
          ?: true
        if (deltaMs <= 0L) return SessionResult(SessionError.ERROR_BAD_VALUE)
        host.adjustSleepTimer(deltaMs, increase)
        success
      }

      PlaybackConstants.SleepTimer.ACTION_GET_TIME -> {
        val remainingSleepTimeMs = host.getSleepTimerTimeMs()
        SessionResult(
          SessionResult.RESULT_SUCCESS,
          Bundle().apply { putLong(PlaybackConstants.SleepTimer.EXTRA_TIME_MS, remainingSleepTimeMs) }
        )
      }

      PlaybackConstants.Commands.RESYNC_SLEEP_TIMER -> {
        host.resyncSleepTimerState()
        success
      }

      PlaybackConstants.Commands.CLOSE_PLAYBACK -> {
        host.closePlayback()
        success
      }

      else -> success
    }
  }

  private fun resolvePreviousChapter(
    session: PlaybackSession,
    currentPositionMs: Long
  ): BookChapter? {
    val chapters = session.chapters
    if (chapters.isEmpty()) return null
    val currentChapter =
      session.getChapterForTime(currentPositionMs) ?: return chapters.firstOrNull()
    val currentIndex = chapters.indexOf(currentChapter).coerceAtLeast(0)
    val isNearChapterStart =
      currentPositionMs - currentChapter.startMs <= CHAPTER_START_THRESHOLD_MS
    return if (isNearChapterStart && currentIndex > 0) chapters[currentIndex - 1] else currentChapter
  }

  fun closePlayback(afterStop: (() -> Unit)?): Unit = host.closePlayback(onPlaybackStopped = afterStop)

  /**
   * Pauses playback (if playing) and forces a progress sync for the current session.
   * Asynchronous: [onComplete] fires once the sync has finished, so callers can sequence
   * a new session behind it without blocking the session callback thread.
   */
  fun forceSyncProgress(onComplete: () -> Unit) {
    host.playerOrNull()?.takeIf { it.isPlaying }?.pause()
    host.maybeSyncProgress("switch", true) { onComplete() }
  }

  fun buildPlayerCommands(
    controllerInfo: MediaSession.ControllerInfo,
    allowSeekingOnMediaControls: Boolean
  ): Player.Commands {

    val player = host.playerOrNull()
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

    val isAppUiController =
      controllerInfo.connectionHints.getBoolean(PlaybackConstants.KEY_IS_APP_UI_CONTROLLER, false)
    val effectiveAllowSeeking = isAppUiController || allowSeekingOnMediaControls

    val baseCommands = buildBasePlayerCommands(player, effectiveAllowSeeking)
    val isWearController = PlaybackConstants.isWearController(controllerInfo.packageName)

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
    private const val CHAPTER_START_THRESHOLD_MS = 3_000L

    // Bundle keys
    private const val KEY_CHAPTER_START_MS = "chapter_start_ms"

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
