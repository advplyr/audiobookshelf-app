package com.audiobookshelf.app.player.media3

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.audiobookshelf.app.R
import kotlin.math.abs

/**
 * Manages playback speed cycling and creates command buttons for speed control in notifications.
 * Supports predefined speed steps with corresponding icons and labels.
 */
class Media3PlaybackSpeedButtonProvider(
  private val cyclePlaybackSpeedCommand: SessionCommand,
  private val displaySpeedKey: String
) {
    private data class SpeedEntry(
        val speed: Float,
        val iconRes: Int,
        val iconConstant: Int
    )

    private val speedEntries = listOf(
        SpeedEntry(0.5f, R.drawable.ic_play_speed_0_5x, CommandButton.ICON_PLAYBACK_SPEED_0_5),
        SpeedEntry(1.0f, R.drawable.ic_play_speed_1_0x, CommandButton.ICON_PLAYBACK_SPEED_1_0),
        SpeedEntry(1.2f, R.drawable.ic_play_speed_1_2x, CommandButton.ICON_PLAYBACK_SPEED_1_2),
        SpeedEntry(1.5f, R.drawable.ic_play_speed_1_5x, CommandButton.ICON_PLAYBACK_SPEED_1_5),
        SpeedEntry(2.0f, R.drawable.ic_play_speed_2_0x, CommandButton.ICON_PLAYBACK_SPEED_2_0),
        SpeedEntry(3.0f, R.drawable.ic_play_speed_3_0x, CommandButton.ICON_PLAYBACK_SPEED)
    )

    private val defaultEntry = speedEntries[1] // 1.0x

  private var playbackSpeedIndex =
      speedEntries.indexOfFirst { abs(it.speed - 1.0f) < 0.01f }.coerceAtLeast(0)

  fun alignTo(speed: Float) {
      val speedIndex = speedEntries.indexOfFirst { abs(it.speed - speed) < 0.01f }
    if (speedIndex >= 0) {
      playbackSpeedIndex = speedIndex
    }
  }

    fun currentSpeed(): Float = speedEntries.getOrElse(playbackSpeedIndex) { defaultEntry }.speed

  fun cycleSpeed(): Float {
      playbackSpeedIndex = (playbackSpeedIndex + 1) % speedEntries.size
      return speedEntries[playbackSpeedIndex].speed
  }

  @OptIn(UnstableApi::class)
  fun createButton(speed: Float): CommandButton {
    val label = CustomMediaNotificationProvider.formatSpeedLabel(speed)
      val entry = speedEntries.firstOrNull { abs(it.speed - speed) < 0.01f } ?: defaultEntry

      return CommandButton.Builder(entry.iconConstant)
      .setSessionCommand(cyclePlaybackSpeedCommand)
      .setDisplayName(label)
      .setExtras(Bundle().apply { putFloat(displaySpeedKey, speed) })
      .setSlots(CommandButton.SLOT_OVERFLOW)
          .setCustomIconResId(entry.iconRes)
      .build()
  }
}
