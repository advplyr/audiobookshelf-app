package com.audiobookshelf.app.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * A generic Player wrapper that applies Audiobookshelf's custom logic.
 *
 * This wrapper can wrap any Media3 `Player` (like ExoPlayer or CastPlayer) and:
 * 1.  Provides a fallback for seek/skip commands, ensuring "next" and "previous"
 *     buttons work even with a single-item queue by mapping them to seek operations.
 * 2.  Bridges Media3's device volume commands to Android's AudioManager, allowing
 *     remote controllers (like wearables) to adjust the device's stream volume.
 */
class AbsPlayerWrapper(
  player: Player, // Changed from ExoPlayer to the generic Player interface
  private val appContext: Context
) : ForwardingPlayer(player) {

  companion object {
    private const val TAG = "AbsPlayerWrapper"
  }

  private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)

  /** When true, map NEXT/PREV commands to seekForward/seekBack instead of track changes. */
  var mapSkipToSeek: Boolean = false

  override fun getAvailableCommands(): Player.Commands {
    val builder = super.getAvailableCommands().buildUpon()
      // Always add seek commands
      .add(Player.COMMAND_SEEK_BACK)
      .add(Player.COMMAND_SEEK_FORWARD)

    // Only add track skipping if not mapping skip to seek
    if (!mapSkipToSeek) {
      builder
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }

    // Always add device volume commands to bridge to AudioManager
    builder
      .add(Player.COMMAND_GET_DEVICE_VOLUME)
      .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
      .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)

    return builder.build()
  }

  // region ===== Skip/Seek Fallbacks ==========================================================
  override fun seekBack() {
    Log.d(TAG, "seekBack() called.")
    super.seekBack()
  }

  override fun seekForward() {
    Log.d(TAG, "seekForward() called.")
    super.seekForward()
  }

  override fun hasNextMediaItem(): Boolean {
    // If we map skip to seek, always report "true" so the 'next' button is enabled.
    return if (mapSkipToSeek) true else super.hasNextMediaItem()
  }

  override fun hasPreviousMediaItem(): Boolean {
    // If we map skip to seek, always report "true" so the 'previous' button is enabled.
    return if (mapSkipToSeek) true else super.hasPreviousMediaItem()
  }

  override fun seekToNextMediaItem() = handleNext { super.seekToNextMediaItem() }

  override fun seekToNext() = handleNext { super.seekToNext() }

  override fun seekToPreviousMediaItem() = handlePrevious { super.seekToPreviousMediaItem() }

  override fun seekToPrevious() = handlePrevious { super.seekToPrevious() }

  private fun handleNext(skipToNextAction: () -> Unit) {
    if (mapSkipToSeek) {
      Log.d(TAG, "Next command -> mapped to seekForward() due to mapSkipToSeek.")
      seekForward()
    } else if (super.hasNextMediaItem()) {
      Log.d(TAG, "Next command -> has next item, performing track skip.")
      skipToNextAction()
    } else {
      Log.d(TAG, "Next command -> no next item, falling back to seekForward().")
      seekForward()
    }
  }

  private fun handlePrevious(skipToPreviousAction: () -> Unit) {
    if (mapSkipToSeek) {
      Log.d(TAG, "Previous command -> mapped to seekBack() due to mapSkipToSeek.")
      seekBack()
    } else if (super.hasPreviousMediaItem()) {
      Log.d(TAG, "Previous command -> has previous item, performing track skip.")
      skipToPreviousAction()
    } else {
      Log.d(TAG, "Previous command -> no previous item, falling back to seekBack().")
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
