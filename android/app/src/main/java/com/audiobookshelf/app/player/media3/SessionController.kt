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
  private val setSleepTimer: (sessionId: String, timeMs: Long, isChapter: Boolean) -> Unit,
  private val cancelSleepTimer: () -> Unit,
  private val adjustSleepTimer: (deltaMs: Long, increase: Boolean) -> Unit,
  private val getSleepTimerTime: () -> Long,
  private val resyncSleepTimerState: () -> Unit,
  private val cyclePlaybackSpeed: (() -> Unit)?,
  private val getCurrentSession: (() -> PlaybackSession?)?,
  private val currentAbsolutePositionMs: (() -> Long?)?,
  private val syncProgress: (reason: String, force: Boolean, onComplete: (() -> Unit)?) -> Unit,
  private val closePlaybackCallback: (afterStop: (() -> Unit)?) -> Unit,
  private val playerProvider: () -> Player?
) {
  fun onCustomCommand(command: SessionCommand, commandData: Bundle?): SessionResult {
      val action = command.customAction
      val success = SessionResult(SessionResult.RESULT_SUCCESS)

      return when (action) {
          PlaybackConstants.Commands.SYNC_PROGRESS_FORCE -> {
              playerProvider()?.takeIf { it.isPlaying }?.pause()
              val latch = java.util.concurrent.CountDownLatch(1)
              syncProgress("switch", true) { latch.countDown() }
              latch.await(SYNC_LATCH_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
              success
          }

          PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED -> {
              cyclePlaybackSpeed?.invoke()
              success
          }

          PlaybackConstants.Commands.SEEK_BACK_INCREMENT -> {
              playerProvider()?.seekBack()
              success
          }

          PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT -> {
              playerProvider()?.seekForward()
              success
          }

          PlaybackConstants.Commands.SEEK_TO_PREVIOUS_TRACK -> {
              playerProvider()?.seekToPreviousMediaItem()
              success
          }

          PlaybackConstants.Commands.SEEK_TO_NEXT_TRACK -> {
              playerProvider()?.seekToNextMediaItem()
              success
          }

          PlaybackConstants.Commands.SEEK_TO_PREVIOUS_CHAPTER -> {
              val session = getCurrentSession?.invoke()
              val absolutePositionMs = currentAbsolutePositionMs?.invoke()
              if (session!=null && absolutePositionMs!=null) {
                  val targetChapter = resolvePreviousChapter(session, absolutePositionMs)
                  if (targetChapter!=null) {
                      playerProvider()?.seekTo(targetChapter.startMs)
                      return success
                  }
              }
              playerProvider()?.seekBack()
              success
          }

          PlaybackConstants.Commands.SEEK_TO_NEXT_CHAPTER -> {
              val session = getCurrentSession?.invoke()
              val absolutePositionMs = currentAbsolutePositionMs?.invoke()
              if (session!=null && absolutePositionMs!=null) {
                  val targetChapter = session.getNextChapterForTime(absolutePositionMs)
                  if (targetChapter!=null) {
                      playerProvider()?.seekTo(targetChapter.startMs)
                      return success
                  }
              }
              playerProvider()?.seekForward()
              success
          }

          PlaybackConstants.Commands.SEEK_TO_CHAPTER -> {
              val chapterStartMs =
                  commandData?.getLong(KEY_CHAPTER_START_MS, Long.MIN_VALUE) ?: Long.MIN_VALUE
              if (chapterStartMs >= 0L) {
                  playerProvider()?.seekTo(chapterStartMs)
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
              setSleepTimer(sessionId, timeMs, isChapter)
              success
          }

          PlaybackConstants.SleepTimer.ACTION_CANCEL -> {
              cancelSleepTimer()
              success
          }

          PlaybackConstants.SleepTimer.ACTION_ADJUST -> {
              val deltaMs = commandData?.getLong(PlaybackConstants.SleepTimer.EXTRA_ADJUST_DELTA, 0L)
                  ?: 0L
              val increase = commandData?.getBoolean(PlaybackConstants.SleepTimer.EXTRA_ADJUST_INCREASE, true)
                  ?: true
              if (deltaMs <= 0L) return SessionResult(SessionError.ERROR_BAD_VALUE)
              adjustSleepTimer(deltaMs, increase)
              success
          }

          PlaybackConstants.SleepTimer.ACTION_GET_TIME -> {
              val remainingSleepTimeMs = getSleepTimerTime()
              SessionResult(
                  SessionResult.RESULT_SUCCESS,
                  Bundle().apply { putLong(PlaybackConstants.SleepTimer.EXTRA_TIME_MS, remainingSleepTimeMs) }
              )
          }

          PlaybackConstants.Commands.RESYNC_SLEEP_TIMER -> {
              resyncSleepTimerState()
              success
          }

          PlaybackConstants.Commands.CLOSE_PLAYBACK -> {
              closePlaybackCallback(null)
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

  fun closePlayback(afterStop: (() -> Unit)?): Unit = closePlaybackCallback(afterStop)

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
    private const val CHAPTER_START_THRESHOLD_MS = 3_000L
    private const val SYNC_LATCH_TIMEOUT_SEC = 2L

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
