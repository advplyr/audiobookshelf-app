package com.audiobookshelf.app.player

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
  { notificationId },
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
    val seekBack = mediaButtons.firstOrNull { it.playerCommand == Player.COMMAND_SEEK_BACK }
    val playPause = mediaButtons.firstOrNull { it.playerCommand == Player.COMMAND_PLAY_PAUSE }
    val seekForward = mediaButtons.firstOrNull { it.playerCommand == Player.COMMAND_SEEK_FORWARD }

    if (seekBack == null || playPause == null || seekForward == null) {
      return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
    }

    val prioritized = ImmutableList.builder<CommandButton>().apply {
      add(seekBack)
      add(playPause)
      add(seekForward)
      mediaButtons.forEach { button ->
        if (button !== seekBack && button !== playPause && button !== seekForward) {
          add(button)
        }
      }
    }.build()

    var actionIndex = 0
    val compact = intArrayOf(-1, -1, -1)

    prioritized.forEach { button ->
      val action = when {
        button.sessionCommand != null -> actionFactory.createCustomActionFromCustomCommandButton(mediaSession, button)
        else -> {
          // Use the button's iconResId which contains either the custom icon or fallback from icon constant
          // The CommandButton.iconResId field is automatically populated from either:
          // 1. setCustomIconResId() - if explicitly set
          // 2. The icon constant via getIconResIdForIconConstant() - as fallback
          val iconRes = if (button.iconResId != 0) button.iconResId else when (button.playerCommand) {
            Player.COMMAND_SEEK_BACK -> R.drawable.exo_icon_rewind
            Player.COMMAND_SEEK_FORWARD -> R.drawable.exo_icon_fastforward
            else -> R.drawable.exo_icon_rewind // Fallback (should never reach here)
          }
          actionFactory.createMediaAction(
            mediaSession,
            IconCompat.createWithResource(appContext, iconRes),
            button.displayName,
            button.playerCommand
          )
        }
      }

      builder.addAction(action)

      if (compact[0] == -1 && button.playerCommand == Player.COMMAND_SEEK_BACK) {
        compact[0] = actionIndex
      } else if (compact[1] == -1 && button.playerCommand == Player.COMMAND_PLAY_PAUSE) {
        compact[1] = actionIndex
      } else if (compact[2] == -1 && button.playerCommand == Player.COMMAND_SEEK_FORWARD) {
        compact[2] = actionIndex
      }

      actionIndex += 1
    }

    for (i in compact.indices) {
      if (compact[i] == -1 && actionIndex > 0) {
        compact[i] = minOf(i, actionIndex - 1)
      }
    }

    return compact
  }

  companion object {
    fun formatSpeedLabel(speed: Float): String {
      val decimalFormat = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
      val formatted = decimalFormat.format(speed.toDouble())
      return "${formatted}x"
    }
  }
}
