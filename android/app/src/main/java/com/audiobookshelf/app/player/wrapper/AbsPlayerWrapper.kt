package com.audiobookshelf.app.player.wrapper

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Player wrapper that keeps next/previous useful for audiobooks: skip commands fall back to
 * seekForward/seekBack when there is no adjacent media item, so single-file books still respond
 * to hardware next/previous buttons (BT headsets, car controls) instead of doing nothing.
 *
 * Per-controller behavior (e.g. Wear mapping skips to seeks) is handled at the session layer,
 * not here. Device volume is handled natively by ExoPlayer (deviceVolumeControlEnabled) and
 * CastPlayer, so this wrapper deliberately does not touch it.
 */
@UnstableApi
class AbsPlayerWrapper(player: Player) : ForwardingPlayer(player) {

  override fun getAvailableCommands(): Player.Commands {
    return super.getAvailableCommands().buildUpon()
      .add(COMMAND_SEEK_BACK)
      .add(COMMAND_SEEK_FORWARD)
      .add(COMMAND_SEEK_TO_PREVIOUS)
      .add(COMMAND_SEEK_TO_NEXT)
      .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
      .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
      .build()
  }

  // Report next/previous as always available so controllers keep the buttons enabled;
  // the seek fallback below gives them meaning when no adjacent item exists.
  override fun hasNextMediaItem(): Boolean = true

  override fun hasPreviousMediaItem(): Boolean = true

  override fun seekToNextMediaItem() = handleNext { super.seekToNextMediaItem() }

  override fun seekToNext() = handleNext { super.seekToNext() }

  override fun seekToPreviousMediaItem() = handlePrevious { super.seekToPreviousMediaItem() }

  override fun seekToPrevious() = handlePrevious { super.seekToPrevious() }

  private fun handleNext(skipToNextAction: () -> Unit) {
    if (super.hasNextMediaItem()) skipToNextAction() else seekForward()
  }

  private fun handlePrevious(skipToPreviousAction: () -> Unit) {
    if (super.hasPreviousMediaItem()) skipToPreviousAction() else seekBack()
  }
}
