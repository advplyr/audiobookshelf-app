package com.audiobookshelf.app.player

import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.audiobookshelf.app.R
import com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands
import com.google.common.collect.ImmutableList
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@UnstableApi
class CustomMediaNotificationProvider(
  context: Context,
  channelId: String,
  channelNameResId: Int,
  notificationId: Int
) : DefaultMediaNotificationProvider(
  context,
  { _: MediaSession -> notificationId },
  channelId,
  channelNameResId
) {

  private val appContext = context.applicationContext

  init {
    setSmallIcon(R.drawable.icon_monochrome)
  }

  override fun addNotificationActions(
    mediaSession: MediaSession,
    mediaButtons: ImmutableList<CommandButton>,
    builder: NotificationCompat.Builder,
    actionFactory: MediaNotification.ActionFactory
  ): IntArray {
    // Log incoming mediaButtons for debugging which commands the provider received
    val btnSummary = mediaButtons.joinToString(", ") { btn ->
      val kind = if (btn.sessionCommand != null) "session" else "player"
      val cmd = when (btn.playerCommand) {
        Player.COMMAND_SEEK_BACK -> "SEEK_BACK"
        Player.COMMAND_SEEK_FORWARD -> "SEEK_FORWARD"
        Player.COMMAND_PLAY_PAUSE -> "PLAY_PAUSE"
        Player.COMMAND_SEEK_TO_NEXT -> "SEEK_TO_NEXT"
        Player.COMMAND_SEEK_TO_PREVIOUS -> "SEEK_TO_PREVIOUS"
        else -> btn.playerCommand.toString()
      }
      "$kind:$cmd"
    }
    android.util.Log.d("Media3Notif", "addNotificationActions mediaButtons=[$btnSummary]")

    val playPause = mediaButtons.firstOrNull { it.playerCommand == Player.COMMAND_PLAY_PAUSE }
    // Always create explicit SEEK_BACK/SEEK_FORWARD actions to avoid surfaces substituting prev/next
    val backAction = actionFactory.createMediaAction(
      mediaSession,
      IconCompat.createWithResource(appContext, R.drawable.exo_icon_rewind),
      "Back 10s",
      Player.COMMAND_SEEK_BACK
    )
    val forwardAction = actionFactory.createMediaAction(
      mediaSession,
      IconCompat.createWithResource(appContext, R.drawable.exo_icon_fastforward),
      "Forward 30s",
      Player.COMMAND_SEEK_FORWARD
    )

    var actionIndex = 0
    val compact = intArrayOf(-1, -1, -1)

    android.util.Log.d("Media3Notif", "Creating forced SEEK actions + play/pause")

    // Back
    builder.addAction(backAction)
    compact[0] = actionIndex
    actionIndex += 1

    // Play/Pause
    if (playPause != null) {
      val pp = actionFactory.createMediaAction(
        mediaSession,
        IconCompat.createWithResource(appContext, playPause.iconResId),
        playPause.displayName,
        playPause.playerCommand
      )
      builder.addAction(pp)
      compact[1] = actionIndex
      actionIndex += 1
    }

    // Forward
    builder.addAction(forwardAction)
    compact[2] = actionIndex
    actionIndex += 1

    // Append remaining custom/session actions (e.g., speed), skipping duplicates
    mediaButtons.forEach { button ->
      val isDup = button.playerCommand == Player.COMMAND_SEEK_BACK ||
                  button.playerCommand == Player.COMMAND_SEEK_FORWARD ||
                  button.playerCommand == Player.COMMAND_PLAY_PAUSE
      if (!isDup) {
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

    for (i in compact.indices) {
      if (compact[i] == -1 && actionIndex > 0) {
        compact[i] = minOf(i, actionIndex - 1)
      }
    }

    return compact
  }

  override fun getMediaButtons(
    session: MediaSession,
    playerCommands: Player.Commands,
    mediaButtonPreferences: ImmutableList<CommandButton>,
    showPauseButton: Boolean
  ): ImmutableList<CommandButton> {
    val customButtons = mutableListOf<CommandButton>()

    val rewindCommand = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
      .setDisplayName("Back 10s")
      .setSessionCommand(
        SessionCommand(
          CustomCommands.SEEK_BACK_INCREMENT,
          Bundle.EMPTY
        )
      )
      .setCustomIconResId(R.drawable.exo_icon_rewind)
      .build()

    val forwardCommand = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
      .setDisplayName("Forward 30s")
      .setSessionCommand(
        SessionCommand(
          CustomCommands.SEEK_FORWARD_INCREMENT,
          Bundle.EMPTY
        )
      )
      .setCustomIconResId(R.drawable.exo_icon_fastforward)
      .build()

    val playPauseCommand = mediaButtonPreferences.firstOrNull {
      it.playerCommand == Player.COMMAND_PLAY_PAUSE
    }

    if (playerCommands.contains(Player.COMMAND_SEEK_BACK)) customButtons.add(rewindCommand)
    if (playPauseCommand != null) customButtons.add(playPauseCommand)
    if (playerCommands.contains(Player.COMMAND_SEEK_FORWARD)) customButtons.add(forwardCommand)

    return ImmutableList.copyOf(customButtons)
  }



  companion object {
    fun formatSpeedLabel(speed: Float): String {
      val decimalFormat = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
      val formatted = decimalFormat.format(speed.toDouble())
      return "${formatted}x"
    }
  }
}
