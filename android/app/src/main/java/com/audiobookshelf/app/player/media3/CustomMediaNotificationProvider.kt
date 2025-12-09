package com.audiobookshelf.app.player.media3

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlaybackConstants
import com.google.common.collect.ImmutableList
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@UnstableApi
/**
 * Custom Media3 notification provider extending DefaultMediaNotificationProvider.
 * Adds custom command buttons and handles speed control, sleep timer, and chapter navigation.
 */
class CustomMediaNotificationProvider(
  context: Context,
  channelId: String,
  channelNameResourceId: Int,
  notificationId: Int
) : DefaultMediaNotificationProvider(
  context,
  { _: MediaSession -> notificationId },
  channelId,
  channelNameResourceId
) {

  private val appContext = context.applicationContext

  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  private val defaultIcon = R.drawable.icon_monochrome

  init {
    setSmallIcon(defaultIcon)
  }

  override fun addNotificationActions(
    mediaSession: MediaSession,
    mediaButtons: ImmutableList<CommandButton>,
    builder: NotificationCompat.Builder,
    actionFactory: MediaNotification.ActionFactory
  ): IntArray {

    val playPauseButton = mediaButtons.firstOrNull { it.playerCommand == Player.COMMAND_PLAY_PAUSE }
    val seekBackLabel = "Back ${deviceSettings.jumpBackwardsTimeMs / 1000}s"
    val seekForwardLabel = "Forward ${deviceSettings.jumpForwardTimeMs / 1000}s"
    val seekBackAction = actionFactory.createMediaAction(
      mediaSession,
      IconCompat.createWithResource(appContext, R.drawable.exo_icon_rewind),
      seekBackLabel,
      Player.COMMAND_SEEK_BACK
    )
    val seekForwardAction = actionFactory.createMediaAction(
      mediaSession,
      IconCompat.createWithResource(appContext, R.drawable.exo_icon_fastforward),
      seekForwardLabel,
      Player.COMMAND_SEEK_FORWARD
    )

    var actionIndex = 0
    val compactViewActionIndices = intArrayOf(-1, -1, -1)

    // Back
    builder.addAction(seekBackAction)
    compactViewActionIndices[0] = 0
    actionIndex += 1

    // Play/Pause
    if (playPauseButton != null) {
      val iconRes = if (playPauseButton.iconResId != 0) playPauseButton.iconResId else defaultIcon
      val display = playPauseButton.displayName
      val playPauseAction = actionFactory.createMediaAction(
        mediaSession,
        IconCompat.createWithResource(appContext, iconRes),
        display,
        playPauseButton.playerCommand
      )
      builder.addAction(playPauseAction)
      compactViewActionIndices[1] = actionIndex
      actionIndex += 1
    }

    // Forward
    builder.addAction(seekForwardAction)
    compactViewActionIndices[2] = actionIndex
    actionIndex += 1

    // Append remaining custom/session actions (e.g., speed), skipping duplicates
    mediaButtons.forEach { button ->
      val isDuplicatePlayerCommand = button.playerCommand == Player.COMMAND_SEEK_BACK ||
                  button.playerCommand == Player.COMMAND_SEEK_FORWARD ||
                  button.playerCommand == Player.COMMAND_PLAY_PAUSE
      if (!isDuplicatePlayerCommand) {
        val action = if (button.sessionCommand != null) {
          actionFactory.createCustomActionFromCustomCommandButton(mediaSession, button)
        } else {
          actionFactory.createMediaAction(
            mediaSession,
            IconCompat.createWithResource(appContext, if (button.iconResId != 0) button.iconResId else R.drawable.icon_monochrome),
            button.displayName,
            button.playerCommand
          )
        }
        builder.addAction(action)
      }
    }

    for (i in compactViewActionIndices.indices) {
      if (compactViewActionIndices[i] == -1 && actionIndex > 0) {
        compactViewActionIndices[i] = minOf(i, actionIndex - 1)
      }
    }

    return compactViewActionIndices
  }

  override fun getMediaButtons(
    mediaSession: MediaSession,
    playerCommands: Player.Commands,
    mediaButtonPreferences: ImmutableList<CommandButton>,
    showPauseButton: Boolean
  ): ImmutableList<CommandButton> {
    // Build canonical service buttons (back, forward, speed)
    val builtButtons = mutableListOf<CommandButton>()
    val seekBackCmd = if (playerCommands.contains(Player.COMMAND_SEEK_BACK))
      CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
        .setDisplayName("Back ${deviceSettings.jumpBackwardsTimeMs / 1000}s")
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setCustomIconResId(R.drawable.exo_icon_rewind)
        .build()
    else
      CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
        .setDisplayName("Back ${deviceSettings.jumpBackwardsTimeMs / 1000}s")
        .setSessionCommand(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_BACK_INCREMENT))
        .setCustomIconResId(R.drawable.exo_icon_rewind)
        .build()

    val seekFwdCmd = if (playerCommands.contains(Player.COMMAND_SEEK_FORWARD))
      CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
        .setDisplayName("Forward ${deviceSettings.jumpForwardTimeMs / 1000}s")
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setCustomIconResId(R.drawable.exo_icon_fastforward)
        .build()
    else
      CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
        .setDisplayName("Forward ${deviceSettings.jumpForwardTimeMs / 1000}s")
        .setSessionCommand(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT))
        .setCustomIconResId(R.drawable.exo_icon_fastforward)
        .build()

    val speedCmd = CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
      .setSessionCommand(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED))
      .setDisplayName("Speed")
      .build()

    builtButtons.add(seekBackCmd)
    builtButtons.add(seekFwdCmd)
    builtButtons.add(speedCmd)

    // If session provided preferences are empty, return built defaults
    if (mediaButtonPreferences.isEmpty()) {
      return ImmutableList.copyOf(builtButtons)
    }

    // Merge: start with built buttons keyed by session customAction or playerCommand, allow session prefs to override
    val map = linkedMapOf<String, CommandButton>()
    builtButtons.forEach { btn -> keyOf(btn)?.let { map[it] = btn } }
    mediaButtonPreferences.forEach { btn ->
      val k = keyOf(btn)
      if (k != null) map[k] = btn else map[btn.displayName as String] = btn
    }

    return ImmutableList.copyOf(map.values)
  }


  companion object {
    fun formatSpeedLabel(speed: Float): String {
      val decimalFormat = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
      val formattedSpeed = decimalFormat.format(speed.toDouble())
      return "${formattedSpeed}x"
    }

    fun keyOf(btn: CommandButton): String? {
      btn.sessionCommand?.let { return it.customAction }
      if (btn.playerCommand != 0) return btn.playerCommand.toString()
      return null
    }
  }
}
