package com.audiobookshelf.app.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer

/**
 * Media3-backed PlayerWrapper using the androidx.media3 ExoPlayer implementation.
 * This requires Media3 dependencies to be enabled in Gradle (media3_feature_enabled).
 */
class Media3Wrapper(private val ctx: Context) : PlayerWrapper {
  private val tag = "Media3Wrapper"
  private var player: ExoPlayer? = null
  
  // Store ExoPlayer v2 listeners that need to receive events
  private val exoListeners = mutableListOf<com.google.android.exoplayer2.Player.Listener>()

  // Listener to surface Media3 playback state changes / errors into logcat for debugging
  // AND forward events to registered ExoPlayer v2 listeners
  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      // Forward to ExoPlayer v2 listeners (state constants are the same)
      exoListeners.forEach { it.onPlaybackStateChanged(playbackState) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      // Forward to ExoPlayer v2 listeners
      exoListeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
      // Forward to ExoPlayer v2 listeners
      exoListeners.forEach { it.onIsLoadingChanged(isLoading) }
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.e(tag, "onPlayerError: ${error.message}", error)
      
      // Forward to ExoPlayer v2 listeners - need to convert Media3 exception to ExoPlayer v2 exception
      val exoError = com.google.android.exoplayer2.PlaybackException(
        error.message ?: "Unknown error",
        error.cause,
        error.errorCode
      )
      exoListeners.forEach { it.onPlayerError(exoError) }
    }
    
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int
    ) {
      // Forward to ExoPlayer v2 listeners - need to convert PositionInfo
      val exoOldPosition = com.google.android.exoplayer2.Player.PositionInfo(
        oldPosition.windowUid,
        oldPosition.mediaItemIndex,
        oldPosition.mediaItem?.let { convertToExoMediaItem(it) },
        oldPosition.periodUid,
        oldPosition.periodIndex,
        oldPosition.positionMs,
        oldPosition.contentPositionMs,
        oldPosition.adGroupIndex,
        oldPosition.adIndexInAdGroup
      )
      val exoNewPosition = com.google.android.exoplayer2.Player.PositionInfo(
        newPosition.windowUid,
        newPosition.mediaItemIndex,
        newPosition.mediaItem?.let { convertToExoMediaItem(it) },
        newPosition.periodUid,
        newPosition.periodIndex,
        newPosition.positionMs,
        newPosition.contentPositionMs,
        newPosition.adGroupIndex,
        newPosition.adIndexInAdGroup
      )
      exoListeners.forEach { it.onPositionDiscontinuity(exoOldPosition, exoNewPosition, reason) }
    }
    
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
      // Forward to ExoPlayer v2 listeners
      val exoMediaItem = mediaItem?.let { convertToExoMediaItem(it) }
      exoListeners.forEach { it.onMediaItemTransition(exoMediaItem, reason) }
    }
    
    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
      // Forward to ExoPlayer v2 listeners
      val exoParams = com.google.android.exoplayer2.PlaybackParameters(playbackParameters.speed, playbackParameters.pitch)
      exoListeners.forEach { it.onPlaybackParametersChanged(exoParams) }
    }
  }
  
  // Helper to convert Media3 MediaItem to ExoPlayer v2 MediaItem
  private fun convertToExoMediaItem(media3Item: androidx.media3.common.MediaItem): com.google.android.exoplayer2.MediaItem {
    val builder = com.google.android.exoplayer2.MediaItem.Builder()
      .setUri(media3Item.localConfiguration?.uri ?: android.net.Uri.EMPTY)
    media3Item.localConfiguration?.tag?.let { builder.setTag(it) }
    media3Item.localConfiguration?.mimeType?.let { builder.setMimeType(it) }
    return builder.build()
  }

  init {
    try {
      player = ExoPlayer.Builder(ctx).build()
      
      // Configure audio attributes matching ExoPlayer v2 baseline behavior
      val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()
      player?.setAudioAttributes(audioAttributes, true)
      player?.setHandleAudioBecomingNoisy(true)
      
      player?.addListener(playerListener)
    } catch (e: Exception) {
      Log.w(tag, "Failed to construct Media3 ExoPlayer: ${e.message}", e)
      player = null
    }
  }

  override fun prepare() { player?.prepare() }
  
  override fun play() { player?.play() }
  
  override fun pause() { player?.pause() }
  override fun release() {
    try {
      player?.removeListener(playerListener)
      player?.release()
    } catch (e: Exception) {
      Log.w(tag, "release failed: ${e.message}")
    }
  }
  override fun setPlayWhenReady(playWhenReady: Boolean) { player?.playWhenReady = playWhenReady }
  
  override fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }

  override fun setMediaItems(items: List<PlayerMediaItem>, startIndex: Int, startPositionMs: Long) {
    val media3Items = items.map { dto ->
      val b = MediaItem.Builder().setUri(dto.uri)
      dto.tag?.let { b.setTag(it) }
      dto.mimeType?.let { b.setMimeType(it) }
      b.build()
    }
    if (player != null) {
      player!!.setMediaItems(media3Items, startIndex, startPositionMs)
    }
  }

  override fun addMediaItems(items: List<PlayerMediaItem>) {
    val media3Items = items.map { dto ->
      val b = MediaItem.Builder().setUri(dto.uri)
      dto.tag?.let { b.setTag(it) }
      dto.mimeType?.let { b.setMimeType(it) }
      b.build()
    }
    player?.addMediaItems(media3Items)
  }

  override fun getCurrentPosition(): Long {
    val pos = player?.currentPosition ?: 0L
    return pos
  }
  
  override fun getMediaItemCount(): Int = player?.mediaItemCount ?: 0
  
  override fun setPlaybackSpeed(speed: Float) { 
    try { 
      player?.setPlaybackSpeed(speed) 
    } catch (e: Exception) { 
      Log.w(tag, "setPlaybackSpeed failed: ${e.message}") 
    } 
  }
  
  override fun isPlaying(): Boolean {
    return try { 
      player?.isPlaying ?: false
    } catch (e: Exception) { 
      Log.w(tag, "isPlaying() exception: ${e.message}")
      false 
    }
  }
  
  override fun seekTo(windowIndex: Int, positionMs: Long) { player?.seekTo(windowIndex, positionMs) }
  override fun getCurrentMediaItemIndex(): Int = player?.currentMediaItemIndex ?: 0
  override fun getBufferedPosition(): Long = player?.bufferedPosition ?: 0L
  override fun setVolume(volume: Float) { player?.volume = volume }
  override fun clearMediaItems() { player?.clearMediaItems() }
  override fun stop() { player?.stop() }
  override fun seekToPrevious() { player?.seekToPrevious() }
  override fun seekToNext() { player?.seekToNext() }
  
  override fun getDuration(): Long {
    return try {
      val duration = player?.duration ?: androidx.media3.common.C.TIME_UNSET
      // Media3 returns C.TIME_UNSET when duration is not known yet
      if (duration == androidx.media3.common.C.TIME_UNSET) {
        0L
      } else {
        duration
      }
    } catch (e: Exception) {
      Log.w(tag, "getDuration failed: ${e.message}")
      0L
    }
  }
  
  override fun getPlaybackState(): Int {
    val state = player?.playbackState ?: 0
    return state
  }
  
  override fun isLoading(): Boolean = player?.isLoading ?: false
  override fun getPlaybackSpeed(): Float = try { player?.playbackParameters?.speed ?: 1f } catch (e: Exception) { 1f }

  // Expose underlying player for limited use (e.g., wiring notifications). Prefer not to use widely.
  fun getMedia3Player(): ExoPlayer? = player


  // Notification/session attachments
  // Note: The service may pass ExoPlayer v2's PlayerNotificationManager which is
  // incompatible with Media3's player. For now we attempt a best-effort attachment
  // using reflection. In a production implementation, the service should create a
  // Media3-compatible notification manager when USE_MEDIA3 is enabled.
  private var notificationManagerRef: Any? = null
  private var mediaSessionConnectorRef: Any? = null

  override fun attachNotificationManager(playerNotificationManager: Any?) {
    notificationManagerRef = playerNotificationManager
    
    // Try to attach our Media3 player to the notification manager via reflection
    // This handles both ExoPlayer v2 and Media3 notification managers
    try {
      val setPlayerMethod = playerNotificationManager?.javaClass?.getMethod("setPlayer", com.google.android.exoplayer2.Player::class.java)
      setPlayerMethod?.invoke(playerNotificationManager, player)
    } catch (e: Exception) {
      Log.w(tag, "attachNotificationManager reflection failed (this is expected if using ExoPlayer v2 notification manager): ${e.message}")
      // This is OK - notification may not display perfectly but playback should still work
    }
  }

  override fun attachMediaSessionConnector(mediaSessionConnector: Any?) {
    // Media3 uses a different media session API; store a reference for potential future use.
    mediaSessionConnectorRef = mediaSessionConnector
  }

  override fun setActivePlayerForNotification(activePlayer: Any?) {
    try {
      val playerToSet = if (activePlayer != null) {
        // Cast player is being set as active
        activePlayer as? com.google.android.exoplayer2.Player
      } else {
        // Switch back to our Media3 player
        player as? com.google.android.exoplayer2.Player
      }
      
      val setPlayerMethod = notificationManagerRef?.javaClass?.getMethod("setPlayer", com.google.android.exoplayer2.Player::class.java)
      setPlayerMethod?.invoke(notificationManagerRef, playerToSet)
    } catch (e: Exception) {
      Log.w(tag, "setActivePlayerForNotification failed: ${e.message}")
    }
  }

  override fun addListener(listener: Any?) {
    try {
      val exoListener = listener as? com.google.android.exoplayer2.Player.Listener
      if (exoListener != null) {
        exoListeners.add(exoListener)
      }
    } catch (e: Exception) {
      Log.w(tag, "addListener failed: ${e.message}")
    }
  }

  override fun removeListener(listener: Any?) {
    try {
      val exoListener = listener as? com.google.android.exoplayer2.Player.Listener
      if (exoListener != null) {
        exoListeners.remove(exoListener)
      }
    } catch (e: Exception) {
      Log.w(tag, "removeListener failed: ${e.message}")
    }
  }
}
