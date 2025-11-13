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

  /**
   * Attach the provided notification manager to the underlying player instance.
   * Use Any to avoid compile-time coupling in the service; implementations should
   * cast to the concrete notification/session types they expect.
   */
  fun attachNotificationManager(playerNotificationManager: Any?)

  /**
   * Attach the provided media session connector to the underlying player instance.
   * Implementations may no-op if they do not support the connector type provided.
   */
  fun attachMediaSessionConnector(mediaSessionConnector: Any?)

  /**
   * Switch the active player used for notification/session presentation. For example
   * when casting is active the service will pass the cast player here so the
   * notification/session reflect the cast state. Pass null to restore the wrapper's
   * underlying player as the active player.
   */
  fun setActivePlayerForNotification(activePlayer: Any?)

  /**
   * Add a player listener to receive playback events. The listener object should be
   * compatible with the underlying player framework (ExoPlayer v2 or Media3).
   * Use Any to avoid compile-time coupling; implementations should cast appropriately.
   */
  fun addListener(listener: Any?)

  /**
   * Remove a previously added player listener.
   */
  fun removeListener(listener: Any?)
}
