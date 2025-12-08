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
  private val playbackSpeedSteps = floatArrayOf(0.5f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f)
  private var playbackSpeedIndex =
    playbackSpeedSteps.indexOfFirst { abs(it - 1.0f) < 0.01f }.let { if (it >= 0) it else 0 }

  fun alignTo(speed: Float) {
    val speedIndex = playbackSpeedSteps.indexOfFirst { abs(it - speed) < 0.01f }
    if (speedIndex >= 0) {
      playbackSpeedIndex = speedIndex
    }
  }

  fun currentSpeed(): Float = playbackSpeedSteps.getOrElse(playbackSpeedIndex) { 1.0f }

  fun cycleSpeed(): Float {
    if (playbackSpeedSteps.isEmpty()) return 1.0f
    playbackSpeedIndex = (playbackSpeedIndex + 1) % playbackSpeedSteps.size
    return playbackSpeedSteps[playbackSpeedIndex]
  }

  @OptIn(UnstableApi::class) // CommandButton icons sourced via Media3 notification provider (unstable)
  fun createButton(speed: Float): CommandButton {
    val label = CustomMediaNotificationProvider.formatSpeedLabel(speed)
    val normalizedSpeed = playbackSpeedSteps.firstOrNull { abs(it - speed) < 0.01f } ?: 1.0f

    val customIconRes = when (normalizedSpeed) {
      0.5f -> R.drawable.ic_play_speed_0_5x
      1.0f -> R.drawable.ic_play_speed_1_0x
      1.2f -> R.drawable.ic_play_speed_1_2x
      1.5f -> R.drawable.ic_play_speed_1_5x
      2.0f -> R.drawable.ic_play_speed_2_0x
      3.0f -> R.drawable.ic_play_speed_3_0x
      else -> R.drawable.ic_play_speed_1_0x
    }

    val iconConstant = when (normalizedSpeed) {
      0.5f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
      1.0f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
      1.2f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
      1.5f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
      2.0f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
      3.0f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
      else -> CommandButton.ICON_PLAYBACK_SPEED
    }

    return CommandButton.Builder(iconConstant)
      .setSessionCommand(cyclePlaybackSpeedCommand)
      .setDisplayName(label)
      .setExtras(Bundle().apply { putFloat(displaySpeedKey, speed) })
      .setSlots(CommandButton.SLOT_OVERFLOW)
      .setCustomIconResId(customIconRes)
      .build()
  }
}
