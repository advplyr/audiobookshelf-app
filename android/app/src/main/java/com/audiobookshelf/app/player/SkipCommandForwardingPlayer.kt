package com.audiobookshelf.app.player

import android.content.Context
import android.media.AudioManager
import android.util.Log
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
  private val tag = "SkipFwdPlayer"
  private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)
  // When true, map NEXT/PREV commands to seekForward/seekBack instead of track changes
  var preferSeekOverSkip: Boolean = false

  override fun getAvailableCommands(): Player.Commands {
    val builder = super.getAvailableCommands().buildUpon()

    builder.add(Player.COMMAND_SEEK_BACK)
    builder.add(Player.COMMAND_SEEK_FORWARD)

    if (!preferSeekOverSkip) {
      builder.add(Player.COMMAND_SEEK_TO_NEXT)
      builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
      builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
      builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    }

    builder.add(Player.COMMAND_GET_DEVICE_VOLUME)
    builder.add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
    builder.add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)

    return builder.build()
  }


  // Skip/seek fallbacks
  override fun seekBack() {
    val currentPos = currentPosition
    val increment = seekBackIncrement
    Log.d(tag, "seekBack called - current: ${currentPos}ms, increment: ${increment}ms, target: ${currentPos - increment}ms")
    super.seekBack()
  }

  override fun seekForward() {
    val currentPos = currentPosition
    val increment = seekForwardIncrement
    Log.d(tag, "seekForward called - current: ${currentPos}ms, increment: ${increment}ms, target: ${currentPos + increment}ms")
    super.seekForward()
  }

  override fun hasNextMediaItem(): Boolean {
    // Report "has next" so controllers (e.g., Wear) enable the Next button
    return if (preferSeekOverSkip) true else super.hasNextMediaItem()
  }

  override fun hasPreviousMediaItem(): Boolean {
    // Report "has previous" so controllers enable the Previous button
    return if (preferSeekOverSkip) true else super.hasPreviousMediaItem()
  }

  override fun seekToNextMediaItem() {
    if (preferSeekOverSkip) {
      Log.d(tag, "seekToNextMediaItem -> mapped to seekForward() due to preferSeekOverSkip")
      seekForward()
    } else if (super.hasNextMediaItem()) {
      Log.d(tag, "seekToNextMediaItem -> has next, performing track skip")
      super.seekToNextMediaItem()
    } else {
      Log.d(tag, "seekToNextMediaItem -> no next, falling back to seekForward()")
      // Use our override so we log and use configured increment
      seekForward()
    }
  }

  override fun seekToNext() {
    if (preferSeekOverSkip) {
      Log.d(tag, "seekToNext -> mapped to seekForward() due to preferSeekOverSkip")
      seekForward()
    } else if (super.hasNextMediaItem()) {
      Log.d(tag, "seekToNext -> has next, performing track skip")
      super.seekToNext()
    } else {
      Log.d(tag, "seekToNext -> no next, falling back to seekForward()")
      seekForward()
    }
  }

  override fun seekToPreviousMediaItem() {
    if (preferSeekOverSkip) {
      Log.d(tag, "seekToPreviousMediaItem -> mapped to seekBack() due to preferSeekOverSkip")
      seekBack()
    } else if (super.hasPreviousMediaItem()) {
      Log.d(tag, "seekToPreviousMediaItem -> has previous, performing track skip")
      super.seekToPreviousMediaItem()
    } else {
      Log.d(tag, "seekToPreviousMediaItem -> no previous, falling back to seekBack()")
      seekBack()
    }
  }

  override fun seekToPrevious() {
    if (preferSeekOverSkip) {
      Log.d(tag, "seekToPrevious -> mapped to seekBack() due to preferSeekOverSkip")
      seekBack()
    } else if (super.hasPreviousMediaItem()) {
      Log.d(tag, "seekToPrevious -> has previous, performing track skip")
      super.seekToPrevious()
    } else {
      Log.d(tag, "seekToPrevious -> no previous, falling back to seekBack()")
      seekBack()
    }
  }

  // Device volume bridge
  private fun maxVol(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  override fun getDeviceVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
  override fun isDeviceMuted(): Boolean = getDeviceVolume() == 0
  override fun setDeviceVolume(volume: Int) {
    val clamped = volume.coerceIn(0, maxVol())
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
    Log.d(tag, "setDeviceVolume -> $clamped")
  }
  override fun increaseDeviceVolume() {
    val before = getDeviceVolume()
    val v = (before + 1).coerceAtMost(maxVol())
    setDeviceVolume(v)
    Log.d(tag, "increaseDeviceVolume $before -> $v")
  }
  override fun decreaseDeviceVolume() {
    val before = getDeviceVolume()
    val v = (before - 1).coerceAtLeast(0)
    setDeviceVolume(v)
    Log.d(tag, "decreaseDeviceVolume $before -> $v")
  }
  override fun setDeviceMuted(muted: Boolean) {
    if (muted) {
      setDeviceVolume(0)
    } else if (isDeviceMuted()) {
      // Restore to a reasonable level (33% of max) if unmuting from zero
      val restore = (maxVol() / 3).coerceAtLeast(1)
      setDeviceVolume(restore)
    }
    Log.d(tag, "setDeviceMuted -> $muted")
  }
}
