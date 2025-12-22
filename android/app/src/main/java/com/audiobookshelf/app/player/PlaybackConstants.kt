package com.audiobookshelf.app.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

object PlaybackConstants {
  // Intent/command extras used when sending playback commands or building media items
  const val DISPLAY_SPEED = "display_speed"
  const val MEDIA_PLAYER = "media_player"
  const val MEDIA3_NOTIFICATION_CHANNEL_ID = "media3_playback_channel"

  object Commands {
    const val CYCLE_PLAYBACK_SPEED = "com.audiobookshelf.app.player.CYCLE_PLAYBACK_SPEED"
    const val SEEK_BACK_INCREMENT = "com.audiobookshelf.app.player.SEEK_BACK_INCREMENT"
    const val SEEK_FORWARD_INCREMENT = "com.audiobookshelf.app.player.SEEK_FORWARD_INCREMENT"
    const val SEEK_TO_PREVIOUS_CHAPTER = "com.audiobookshelf.app.player.SEEK_TO_PREVIOUS_CHAPTER"
    const val SEEK_TO_NEXT_CHAPTER = "com.audiobookshelf.app.player.SEEK_TO_NEXT_CHAPTER"
    const val SEEK_TO_CHAPTER = "com.audiobookshelf.app.player.SEEK_TO_CHAPTER"
    const val SEEK_TO_PREVIOUS_TRACK = "com.audiobookshelf.app.player.SEEK_TO_PREVIOUS_TRACK"
    const val SEEK_TO_NEXT_TRACK = "com.audiobookshelf.app.player.SEEK_TO_NEXT_TRACK"
    const val CLOSE_PLAYBACK = "com.audiobookshelf.app.player.CLOSE_PLAYBACK"
    const val SYNC_PROGRESS_FORCE = "com.audiobookshelf.app.player.SYNC_PROGRESS_FORCE"
    const val MARK_UI_PLAYBACK_EVENT = "com.audiobookshelf.app.player.MARK_UI_PLAYBACK_EVENT"
  }

  object WidgetActions {
    const val PLAY_PAUSE = "com.audiobookshelf.app.widget.PLAY_PAUSE"
    const val FAST_FORWARD = "com.audiobookshelf.app.widget.FAST_FORWARD"
    const val REWIND = "com.audiobookshelf.app.widget.REWIND"
  }

  object SleepTimer {
    const val ACTION_SET = "com.audiobookshelf.app.player.SET_SLEEP_TIMER"
    const val ACTION_CANCEL = "com.audiobookshelf.app.player.CANCEL_SLEEP_TIMER"
    const val ACTION_ADJUST = "com.audiobookshelf.app.player.ADJUST_SLEEP_TIMER"
    const val ACTION_GET_TIME = "com.audiobookshelf.app.player.GET_SLEEP_TIMER_TIME"

    const val EXTRA_TIME_MS = "sleep_timer_time_ms"
    const val EXTRA_IS_CHAPTER = "sleep_timer_is_chapter"
    const val EXTRA_SESSION_ID = "sleep_timer_session_id"
    const val EXTRA_ADJUST_DELTA = "sleep_timer_adjust_delta"
    const val EXTRA_ADJUST_INCREASE = "sleep_timer_adjust_increase"
  }

  /**
   * Convenience helper to create a SessionCommand for the given action using an empty Bundle.
   */
  fun sessionCommand(action: String): SessionCommand = SessionCommand(action, Bundle.EMPTY)
}
