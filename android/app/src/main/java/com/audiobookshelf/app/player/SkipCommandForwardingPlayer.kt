package com.audiobookshelf.app.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer


/**
 * ForwardingPlayer wrapper that keeps skip commands exposed even when the session hosts
 * a single media item. Android surfaces expect SEEK_TO_NEXT/SEEK_TO_PREVIOUS to be present
 * for transport controls; we fall back to the configured seek increments whenever the queue
 * lacks adjacent items.
 * Additionally bridges device volume commands to the local STREAM_MUSIC so wearables
 * and other controllers can adjust phone volume through Media3 device volume APIs.
 */
class SkipCommandForwardingPlayer(
  basePlayer: ExoPlayer,
  private val appContext: Context
) : ForwardingPlayer(basePlayer) {

  companion object {
    private const val TAG = "SkipFwdPlayer"
  }

  private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)

  /** When true, map NEXT/PREV commands to seekForward/seekBack instead of track changes. */
  var mapSkipToSeek: Boolean = false

  override fun getAvailableCommands(): Player.Commands {
    val builder = super.getAvailableCommands().buildUpon()
      .add(Player.COMMAND_SEEK_BACK)
      .add(Player.COMMAND_SEEK_FORWARD)
      .add(Player.COMMAND_GET_DEVICE_VOLUME)
      .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
      .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)

    if (!mapSkipToSeek) {
      builder
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }

    return builder.build()
  }

  // region ===== Skip/Seek Fallbacks ==========================================================
  override fun seekBack() {
    val currentPos = currentPosition
    val increment = seekBackIncrement
    Log.d(TAG, "seekBack called - current: ${currentPos}ms, increment: ${increment}ms, target: ${currentPos - increment}ms")
    super.seekBack()
  }

  override fun seekForward() {
    val currentPos = currentPosition
    val increment = seekForwardIncrement
    Log.d(TAG, "seekForward called - current: ${currentPos}ms, increment: ${increment}ms, target: ${currentPos + increment}ms")
    super.seekForward()
  }

  override fun hasNextMediaItem(): Boolean {
    // Report "has next" so controllers (e.g., Wear) enable the Next button.
    return if (mapSkipToSeek) true else super.hasNextMediaItem()
  }

  override fun hasPreviousMediaItem(): Boolean {
    // Report "has previous" so controllers enable the Previous button.
    return if (mapSkipToSeek) true else super.hasPreviousMediaItem()
  }

  override fun seekToNextMediaItem() = handleNext { super.seekToNextMediaItem() }

  override fun seekToNext() = handleNext { super.seekToNext() }

  override fun seekToPreviousMediaItem() = handlePrevious { super.seekToPreviousMediaItem() }

  override fun seekToPrevious() = handlePrevious { super.seekToPrevious() }

  /**
   * Handles a "next" command, either by seeking forward or skipping to the next track.
   * @param skipToNextAction The action to perform if a track skip is appropriate.
   */
  private fun handleNext(skipToNextAction: () -> Unit) {
    if (mapSkipToSeek) {
      Log.d(TAG, "Next command -> mapped to seekForward() due to mapSkipToSeek")
      seekForward()
    } else if (super.hasNextMediaItem()) {
      Log.d(TAG, "Next command -> has next, performing track skip")
      skipToNextAction()
    } else {
      Log.d(TAG, "Next command -> no next, falling back to seekForward()")
      seekForward()
    }
  }

  /**
   * Handles a "previous" command, either by seeking backward or skipping to the previous track.
   * @param skipToPreviousAction The action to perform if a track skip is appropriate.
   */
  private fun handlePrevious(skipToPreviousAction: () -> Unit) {
    if (mapSkipToSeek) {
      Log.d(TAG, "Previous command -> mapped to seekBack() due to mapSkipToSeek")
      seekBack()
    } else if (super.hasPreviousMediaItem()) {
      Log.d(TAG, "Previous command -> has previous, performing track skip")
      skipToPreviousAction()
    } else {
      Log.d(TAG, "Previous command -> no previous, falling back to seekBack()")
      seekBack()
    }
  }
  // endregion

  // region ===== Device Volume Bridge ========================================================
  private fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

  override fun getDeviceVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

  @RequiresApi(Build.VERSION_CODES.M)
  override fun isDeviceMuted(): Boolean {
    return audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
  }

  override fun setDeviceVolume(volume: Int) {
    val clamped = volume.coerceIn(0, getMaxVolume())
    Log.d(TAG, "setDeviceVolume -> $clamped")
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
  }

  override fun increaseDeviceVolume() {
    Log.d(TAG, "increaseDeviceVolume")
    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
  }

  override fun decreaseDeviceVolume() {
    Log.d(TAG, "decreaseDeviceVolume")
    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
  }

  @RequiresApi(Build.VERSION_CODES.M)
  override fun setDeviceMuted(muted: Boolean) {
    Log.d(TAG, "setDeviceMuted -> $muted")
    val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
  }
  // endregion
}
