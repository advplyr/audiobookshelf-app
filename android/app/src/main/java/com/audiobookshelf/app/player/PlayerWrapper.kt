package com.audiobookshelf.app.player

import com.audiobookshelf.app.player.PlayerMediaItem

/**
 * Small, focused player abstraction used as a migration seam between ExoPlayer and Media3.
 * The interface currently includes a few ExoPlayer-specific types to keep the migration
 * incremental and low-risk. Media3 implementations should provide appropriate behavior or
 * no-op stubs until fully implemented.
 */
interface PlayerWrapper {
  fun prepare()
  fun play()
  fun pause()
  fun release()

  fun setPlayWhenReady(playWhenReady: Boolean)
  fun seekTo(positionMs: Long)
  fun seekTo(windowIndex: Int, positionMs: Long)

  fun setMediaItems(items: List<PlayerMediaItem>, startIndex: Int = 0, startPositionMs: Long = 0)
  fun addMediaItems(items: List<PlayerMediaItem>)

  fun getCurrentPosition(): Long
  fun getMediaItemCount(): Int
  fun getCurrentMediaItemIndex(): Int
  fun getBufferedPosition(): Long

  fun setPlaybackSpeed(speed: Float)
  fun isPlaying(): Boolean

  fun setVolume(volume: Float)
  fun clearMediaItems()
  fun stop()

  fun seekToPrevious()
  fun seekToNext()

  fun getDuration(): Long
  fun getPlaybackState(): Int
  fun isLoading(): Boolean
  fun getPlaybackSpeed(): Float
}
