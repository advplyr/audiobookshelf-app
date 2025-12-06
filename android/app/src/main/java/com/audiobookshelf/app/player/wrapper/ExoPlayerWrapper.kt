package com.audiobookshelf.app.player.wrapper

import android.os.Looper
import com.audiobookshelf.app.player.PlayerEvents
import com.audiobookshelf.app.player.PlayerMediaItem
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ui.PlayerNotificationManager

/**
 * Lightweight wrapper around ExoPlayer/Player so calling code can depend on PlayerWrapper.
 * This intentionally keeps to a small surface needed for migration.
 */
class ExoPlayerWrapper(private val player: Player) : PlayerWrapper {
  private val listeners = mutableSetOf<PlayerEvents>()
  private var forwardingListenerAttached = false
  private var lastKnownPosition: Long = 0L

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
    } catch (_: Exception) {
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
      .setMediaId(dto.mediaId)
    dto.tag?.let { builder.setTag(it) }
    dto.mimeType?.let { builder.setMimeType(it) }
    return builder.build()
  }

  fun toExoMediaItems(items: List<PlayerMediaItem>): List<MediaItem> = items.map { toExoMediaItem(it) }

  // Safe snapshot accessor (now primary). Updates cache only on main thread.
  override fun getCurrentPosition(): Long {
    return if (Looper.myLooper() == Looper.getMainLooper()) {
      lastKnownPosition = player.currentPosition
      lastKnownPosition
    } else {
      lastKnownPosition
    }
  }

  // Live accessor MUST be main thread.
  override fun getCurrentPositionLive(): Long {
    val pos = player.currentPosition
    if (Looper.myLooper() == Looper.getMainLooper()) {
      lastKnownPosition = pos
    }
    return pos
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

  // Internal listener that forwards ExoPlayer v2 events to our neutral PlayerEvents
  private val exoForwardingListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      listeners.forEach { it.onPlaybackStateChanged(playbackState) }
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }
    override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
      listeners.forEach { it.onPlayerError(error.message ?: "Unknown error", error.errorCode) }
    }
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int
    ) {
      val isSeek = reason == Player.DISCONTINUITY_REASON_SEEK
      listeners.forEach { it.onPositionDiscontinuity(isSeek) }
    }
  }

  // Notification / session attachments (managed by wrapper to avoid service-level
  // conditionals between Exo and Media3 player types).
  private var notificationManagerRef: PlayerNotificationManager? = null
  private var mediaSessionConnectorRef: MediaSessionConnector? = null

  override fun attachNotificationManager(playerNotificationManager: Any?) {
    try {
      notificationManagerRef = playerNotificationManager as? PlayerNotificationManager
      notificationManagerRef?.setPlayer(player)
    } catch (e: Exception) {
      // best-effort
    }
  }

  override fun attachMediaSessionConnector(mediaSessionConnector: Any?) {
    try {
      mediaSessionConnectorRef = mediaSessionConnector as? MediaSessionConnector
      mediaSessionConnectorRef?.setPlayer(player)
    } catch (e: Exception) {
      // best-effort
    }
  }

  override fun setActivePlayerForNotification(activePlayer: Any?) {
    try {
      if (activePlayer != null) {
        val p = activePlayer as? Player
        notificationManagerRef?.setPlayer(p)
        mediaSessionConnectorRef?.setPlayer(p)
      } else {
        // restore underlying player
        notificationManagerRef?.setPlayer(player)
        mediaSessionConnectorRef?.setPlayer(player)
      }
    } catch (e: Exception) {
      // ignore
    }
  }

  override fun addListener(listener: PlayerEvents) {
    listeners.add(listener)
    if (!forwardingListenerAttached) {
      player.addListener(exoForwardingListener)
      forwardingListenerAttached = true
    }
  }

  override fun removeListener(listener: PlayerEvents) {
    listeners.remove(listener)
    if (listeners.isEmpty() && forwardingListenerAttached) {
      try { player.removeListener(exoForwardingListener) } catch (_: Exception) {}
      forwardingListenerAttached = false
    }
  }
}
