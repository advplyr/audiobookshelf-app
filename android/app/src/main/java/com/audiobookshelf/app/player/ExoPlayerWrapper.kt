package com.audiobookshelf.app.player

import com.audiobookshelf.app.player.PlayerMediaItem
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player

/**
 * Lightweight wrapper around ExoPlayer/Player so calling code can depend on PlayerWrapper.
 * This intentionally keeps to a small surface needed for migration.
 */
class ExoPlayerWrapper(private val player: Player) : PlayerWrapper {

  override fun prepare() {
    try {
      player.prepare()
    } catch (e: Exception) {
      // best effort
    }
  }

  override fun play() {
    player.play()
  }

  override fun pause() {
    player.pause()
  }

  override fun release() {
    try {
      if (player is ExoPlayer) player.release()
    } catch (e: Exception) {
      // ignore
    }
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    player.playWhenReady = playWhenReady
  }

  override fun seekTo(positionMs: Long) {
    player.seekTo(positionMs)
  }

  override fun setMediaItems(items: List<PlayerMediaItem>, startIndex: Int, startPositionMs: Long) {
    val mediaItems = toExoMediaItems(items)
    if (player is ExoPlayer) {
      player.setMediaItems(mediaItems, startIndex, startPositionMs)
    } else {
      player.clearMediaItems()
      if (mediaItems.isNotEmpty()) player.addMediaItem(mediaItems.first())
    }
  }

  override fun addMediaItems(items: List<PlayerMediaItem>) {
    val mediaItems = toExoMediaItems(items)
    player.addMediaItems(mediaItems)
  }

  /**
   * Convert a PlayerMediaItem to an Exo MediaItem.
   * Exposed here so callers that need native Exo types (cast, media-source builders)
   * can obtain them from the Exo-specific implementation rather than building them
   * in service-level code.
   */
  fun toExoMediaItem(dto: PlayerMediaItem): MediaItem {
    val builder = MediaItem.Builder().setUri(dto.uri)
    dto.tag?.let { builder.setTag(it) }
    dto.mimeType?.let { builder.setMimeType(it) }
    return builder.build()
  }

  fun toExoMediaItems(items: List<PlayerMediaItem>): List<MediaItem> = items.map { toExoMediaItem(it) }

  override fun getCurrentPosition(): Long {
    return player.currentPosition
  }

  override fun getMediaItemCount(): Int {
    return player.mediaItemCount
  }
  override fun setPlaybackSpeed(speed: Float) {
    try {
      if (player is ExoPlayer) {
        player.setPlaybackSpeed(speed)
      }
    } catch (e: Exception) {
      // ignore
    }
  }

  override fun isPlaying(): Boolean {
    return try {
      player.isPlaying
    } catch (e: Exception) {
      false
    }
  }

  // Exo-specific APIs removed from public wrapper; conversion happens in this wrapper.

  override fun seekTo(windowIndex: Int, positionMs: Long) {
    player.seekTo(windowIndex, positionMs)
  }

  override fun getCurrentMediaItemIndex(): Int {
    return player.currentMediaItemIndex
  }

  override fun getBufferedPosition(): Long {
    return player.bufferedPosition
  }

  override fun setVolume(volume: Float) {
    player.volume = volume
  }

  override fun clearMediaItems() {
    player.clearMediaItems()
  }

  override fun stop() {
    player.stop()
  }

  override fun seekToPrevious() {
    player.seekToPrevious()
  }

  override fun seekToNext() {
    player.seekToNext()
  }

  override fun getDuration(): Long {
    return try { player.duration } catch (e: Exception) { 0L }
  }

  override fun getPlaybackState(): Int {
    return player.playbackState
  }

  override fun isLoading(): Boolean {
    return player.isLoading
  }
  override fun getPlaybackSpeed(): Float {
    return try { player.playbackParameters.speed } catch (e: Exception) { 1f }
  }
}
