package com.audiobookshelf.app.player.media3

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * A ForwardingPlayer that filters COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM from availableCommands
 * based on the allowSeekingOnMediaControls setting. This ensures the notification seek bar
 * respects the user's preference while the app's own UI can still seek.
 */
@OptIn(UnstableApi::class)
class NotificationCommandFilteringPlayer
  (
  player: Player,
  private val allowSeekingOnMediaControls: () -> Boolean
) : ForwardingPlayer(player) {

  override fun getAvailableCommands(): Player.Commands {
    val baseCommands = super.getAvailableCommands()

    return if (allowSeekingOnMediaControls()) {
      baseCommands
    } else {
      // Remove COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM to disable notification seek bar
      Player.Commands.Builder()
        .addAll(baseCommands)
        .remove(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .build()
    }
  }
}
