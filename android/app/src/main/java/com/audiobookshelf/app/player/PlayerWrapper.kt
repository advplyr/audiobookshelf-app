package com.audiobookshelf.app.player

import android.net.Uri
import com.google.android.exoplayer2.MediaItem

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

  fun setMediaItems(uris: List<Uri>, startIndex: Int = 0, startPositionMs: Long = 0)
  fun addMediaItems(uris: List<Uri>)

  // ExoPlayer-specific convenience for incremental migration
  fun addExoMediaItems(items: List<MediaItem>)

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
