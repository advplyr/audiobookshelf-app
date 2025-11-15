package com.audiobookshelf.app.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * ForwardingPlayer wrapper that keeps skip commands exposed even when the session hosts
 * a single media item. Android surfaces expect SEEK_TO_NEXT/SEEK_TO_PREVIOUS to be present
 * for transport controls; we fall back to the configured seek increments whenever the queue
 * lacks adjacent items.
 */
class SkipCommandForwardingPlayer(basePlayer: ExoPlayer) : ForwardingPlayer(basePlayer) {
  override fun getAvailableCommands(): Player.Commands {
    return super.getAvailableCommands().buildUpon()
      .add(Player.COMMAND_SEEK_TO_NEXT)
      .add(Player.COMMAND_SEEK_TO_PREVIOUS)
      .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
      .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
      .build()
  }

  override fun seekToNextMediaItem() {
    if (super.hasNextMediaItem()) {
      super.seekToNextMediaItem()
    } else {
      super.seekForward()
    }
  }

  override fun seekToNext() {
    if (super.hasNextMediaItem()) {
      super.seekToNext()
    } else {
      super.seekForward()
    }
  }

  override fun seekToPreviousMediaItem() {
    if (super.hasPreviousMediaItem()) {
      super.seekToPreviousMediaItem()
    } else {
      super.seekBack()
    }
  }

  override fun seekToPrevious() {
    if (super.hasPreviousMediaItem()) {
      super.seekToPrevious()
    } else {
      super.seekBack()
    }
  }
}
