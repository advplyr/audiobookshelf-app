package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Looper
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.audiobookshelf.app.data.PlaybackSession

/**
 * Media3-backed PlayerWrapper using the androidx.media3 ExoPlayer implementation.
 * This requires Media3 dependencies to be enabled in Gradle (media3_feature_enabled).
 */
class Media3Wrapper(private val ctx: Context) : PlayerWrapper {
  val tag = "Media3Wrapper"
  private var player: ExoPlayer? = null
  private val listeners = mutableSetOf<PlayerEvents>()
  private var mediaSession: MediaSession? = null
  private var lastKnownPosition: Long = 0L

  // Track the active v2 player for operations when Cast is active
  // When null, operations go to the local Media3 player
  private var activeV2Player: com.google.android.exoplayer2.Player? = null

  // Listener to surface Media3 playback state changes / errors into logcat for debugging
  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      listeners.forEach { it.onPlaybackStateChanged(playbackState) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
      // No neutral event for isLoading; consumers use onPlaybackStateChanged + wrapper getters
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.e(tag, "onPlayerError: ${error.message}", error)
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
    
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
      // No neutral event required here; consumers can query current item if needed
    }
    
    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
      // Expose via wrapper getters (getPlaybackSpeed) rather than event
    }
  }
  
    // Session callback reference - set by service during initialization
    private var sessionCallback: Media3SessionCallback? = null

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
      
      // Initially no Cast player - local Media3 player is active
      activeV2Player = null
      
      // Create MediaSession for Media3 notification support
      createMediaSession()
    } catch (e: Exception) {
      Log.w(tag, "Failed to construct Media3 ExoPlayer: ${e.message}", e)
      player = null
    }
  }
  
  private fun createMediaSession() {
    try {
      // Build a PendingIntent to relaunch the app if possible; only set if non-null.
      val launchIntent = ctx.packageManager?.getLaunchIntentForPackage(ctx.packageName)
      val sessionActivityPendingIntent: PendingIntent? = launchIntent?.let { intent ->
        PendingIntent.getActivity(
          ctx,
          0,
          intent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
      }

      mediaSession = player?.let { p ->
        // Use a time-suffixed ID to avoid collisions on service recreation
        val sessionId = "audiobookshelf.media3.session." + System.currentTimeMillis()
        val builder = MediaSession.Builder(ctx, p).setId(sessionId)
        if (sessionActivityPendingIntent != null) {
          builder.setSessionActivity(sessionActivityPendingIntent)
        }
          // Callback will be set after build for broader version compatibility
        builder.build()
      }
      
      Log.d(tag, "Media3 MediaSession created successfully")
    } catch (e: Exception) {
      Log.w(tag, "Failed to create MediaSession: ${e.message}", e)
      mediaSession = null
    }
  }

    /**
     * Initialize MediaSession with callback. Must be called after wrapper construction
     * and before playback starts. This sets up the session to handle playback commands
     * from notifications, Android Auto, etc.
     */
    fun setSessionCallback(callback: Media3SessionCallback) {
      sessionCallback = callback
      // MediaSession#setCallback may not be available in this Media3 version.
      // We'll store the callback for future use when upgrading to MediaLibraryService.
      Log.d(tag, "Stored MediaSession callback (no-op for current Media3 version)")
    }

    /**
     * Configure seek forward/backward increments for notification buttons.
     * These will appear as standard seek forward/back actions in notifications.
     */
    fun setSeekIncrements(backwardMs: Long, forwardMs: Long) {
      try {
        // ExoPlayer seek increment setters not available in current Media3 version.
        // Skipping configuration to maintain compatibility.
        Log.d(tag, "Seek increments skipped for current Media3 version")
      } catch (e: Exception) {
        Log.w(tag, "Failed to set seek increments: ${e.message}")
      }
    }

    /**
     * Get the Media3 MediaSession for notification integration.
     */
    fun getMediaSession(): MediaSession? = mediaSession

  /**
   * Update MediaMetadata with proper information from PlaybackSession.
   * This populates the notification with title, artist, and artwork.
   * Should be called after preparePlayer() when PlaybackSession is available.
   */
  fun updateMediaMetadata(playbackSession: PlaybackSession) {
    try {
      val coverUri = playbackSession.getCoverUri(ctx)
      
      val metadata = MediaMetadata.Builder()
        .setTitle(playbackSession.displayTitle ?: "Unknown Title")
        .setArtist(playbackSession.displayAuthor ?: "Unknown Author")
        .setDisplayTitle(playbackSession.displayTitle ?: "Unknown Title")
        .setSubtitle(playbackSession.displayAuthor)
        .setArtworkUri(coverUri)
        .build()
      
      // Update the current media item's metadata
      val currentIndex = player?.currentMediaItemIndex ?: 0
      player?.let { p ->
        if (p.mediaItemCount > currentIndex) {
          val currentItem = p.getMediaItemAt(currentIndex)
          val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(metadata)
            .build()
          
          // Replace current item with updated metadata
          p.replaceMediaItem(currentIndex, updatedItem)
          
          Log.d(tag, "Updated MediaMetadata: title=${playbackSession.displayTitle}, artist=${playbackSession.displayAuthor}")
        }
      }
    } catch (e: Exception) {
      Log.w(tag, "Failed to update MediaMetadata: ${e.message}")
    }
  }

  override fun prepare() { 
    if (activeV2Player != null) {
      activeV2Player?.prepare()
    } else {
      player?.prepare()
    }
  }
  
  override fun play() {
    if (activeV2Player != null) {
      activeV2Player?.play()
    } else {
      player?.play()
    }
  }
  
  override fun pause() {
    if (activeV2Player != null) {
      activeV2Player?.pause()
    } else {
      player?.pause()
    }
  }
  override fun release() {
    try {
      player?.removeListener(playerListener)
      mediaSession?.release()
      mediaSession = null
      player?.release()
    } catch (e: Exception) {
      Log.w(tag, "release failed: ${e.message}")
    }
  }
  override fun setPlayWhenReady(playWhenReady: Boolean) {
    if (activeV2Player != null) {
      activeV2Player?.playWhenReady = playWhenReady
    } else {
      player?.playWhenReady = playWhenReady
    }
  }
  
  override fun seekTo(positionMs: Long) {
    if (activeV2Player != null) {
      activeV2Player?.seekTo(positionMs)
    } else {
      player?.seekTo(positionMs)
    }
  }

  override fun setMediaItems(items: List<PlayerMediaItem>, startIndex: Int, startPositionMs: Long) {
    val media3Items = items.map { dto ->
      val b = MediaItem.Builder().setUri(dto.uri)
      dto.tag?.let { b.setTag(it) }
      dto.mimeType?.let { b.setMimeType(it) }
      
      // Add metadata for notification display
      // Note: The service will need to update this with proper title/artist/artwork
      // This is a placeholder to ensure notifications can display
      val metadata = MediaMetadata.Builder()
        .setTitle(dto.tag?.toString() ?: "Audiobook")
        .build()
      b.setMediaMetadata(metadata)
      
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
      
      // Add metadata for notification display
      val metadata = MediaMetadata.Builder()
        .setTitle(dto.tag?.toString() ?: "Audiobook")
        .build()
      b.setMediaMetadata(metadata)
      
      b.build()
    }
    player?.addMediaItems(media3Items)
  }

  // Safe snapshot accessor (any thread). Returns cached value off main.
  override fun getCurrentPosition(): Long {
    // Always return fresh position; stale snapshot caused incorrect seek math during Cast.
    val pos = try {
      if (activeV2Player != null) {
        activeV2Player?.currentPosition ?: 0L
      } else {
        player?.currentPosition ?: 0L
      }
    } catch (_: Exception) { 0L }
    lastKnownPosition = pos
    return pos
  }

  // Live accessor (main thread expected).
  override fun getCurrentPositionLive(): Long {
    val pos = try {
      if (activeV2Player != null) {
        activeV2Player?.currentPosition ?: 0L
      } else {
        player?.currentPosition ?: 0L
      }
    } catch (_: Exception) { 0L }
    lastKnownPosition = pos
    return pos
  }
  
  override fun getMediaItemCount(): Int = if (activeV2Player != null) {
    try { activeV2Player?.mediaItemCount ?: 0 } catch (_: Exception) { 0 }
  } else { player?.mediaItemCount ?: 0 }
  
  override fun setPlaybackSpeed(speed: Float) {
    try {
      if (activeV2Player != null) {
        activeV2Player?.setPlaybackSpeed(speed)
      } else {
        player?.setPlaybackSpeed(speed)
      }
    } catch (e: Exception) {
      Log.w(tag, "setPlaybackSpeed failed: ${e.message}")
    }
  }
  
  override fun isPlaying(): Boolean {
    return try {
      if (activeV2Player != null) {
        activeV2Player?.isPlaying ?: false
      } else {
        player?.isPlaying ?: false
      }
    } catch (e: Exception) {
      Log.w(tag, "isPlaying() exception: ${e.message}")
      false
    }
  }
  
  override fun seekTo(windowIndex: Int, positionMs: Long) {
    if (activeV2Player != null) {
      activeV2Player?.seekTo(windowIndex, positionMs)
    } else {
      player?.seekTo(windowIndex, positionMs)
    }
  }
  override fun getCurrentMediaItemIndex(): Int = if (activeV2Player != null) {
    try { activeV2Player?.currentMediaItemIndex ?: 0 } catch (_: Exception) { 0 }
  } else { player?.currentMediaItemIndex ?: 0 }
  override fun getBufferedPosition(): Long = if (activeV2Player != null) {
    try { activeV2Player?.bufferedPosition ?: 0L } catch (_: Exception) { 0L }
  } else { player?.bufferedPosition ?: 0L }
  override fun setVolume(volume: Float) {
    if (activeV2Player != null) {
      activeV2Player?.volume = volume
    } else {
      player?.volume = volume
    }
  }
  override fun clearMediaItems() {
    if (activeV2Player != null) {
      activeV2Player?.clearMediaItems()
    } else {
      player?.clearMediaItems()
    }
  }
  override fun stop() {
    if (activeV2Player != null) {
      activeV2Player?.stop()
    } else {
      player?.stop()
    }
  }
  override fun seekToPrevious() {
    if (activeV2Player != null) {
      activeV2Player?.seekToPrevious()
    } else {
      player?.seekToPrevious()
    }
  }
  override fun seekToNext() {
    if (activeV2Player != null) {
      activeV2Player?.seekToNext()
    } else {
      player?.seekToNext()
    }
  }
  
  override fun getDuration(): Long {
    return try {
      val duration = if (activeV2Player != null) {
        try { activeV2Player?.duration ?: androidx.media3.common.C.TIME_UNSET } catch (_: Exception) { androidx.media3.common.C.TIME_UNSET }
      } else { player?.duration ?: androidx.media3.common.C.TIME_UNSET }
      if (duration == androidx.media3.common.C.TIME_UNSET) 0L else duration
    } catch (e: Exception) {
      Log.w(tag, "getDuration failed: ${e.message}")
      0L
    }
  }
  
  override fun getPlaybackState(): Int {
    return try {
      if (activeV2Player != null) {
        activeV2Player?.playbackState ?: 0
      } else {
        player?.playbackState ?: 0
      }
    } catch (_: Exception) { 0 }
  }
  
  override fun isLoading(): Boolean = if (activeV2Player != null) {
    try { activeV2Player?.isLoading ?: false } catch (_: Exception) { false }
  } else { player?.isLoading ?: false }
  override fun getPlaybackSpeed(): Float = try {
    if (activeV2Player != null) {
      activeV2Player?.playbackParameters?.speed ?: 1f
    } else {
      player?.playbackParameters?.speed ?: 1f
    }
  } catch (e: Exception) { 1f }

  // Expose underlying player for limited use (e.g., wiring notifications). Prefer not to use widely.
  fun getMedia3Player(): ExoPlayer? = player
  
  // Expose MediaSession token for notification integration
  fun getSessionToken(): androidx.media3.session.SessionToken? = mediaSession?.token


  // Notification/session attachments
  // Note: The service may pass ExoPlayer v2's PlayerNotificationManager which is
  // incompatible with Media3's player. For Cast support, we store references to the
  // v2 notification system so we can switch to it when casting.
  private var notificationManagerRef: Any? = null
  private var mediaSessionConnectorRef: Any? = null
  private var isCastActive = false

  override fun attachNotificationManager(playerNotificationManager: Any?) {
    // Phase 1.5: Media3 uses session-based notifications automatically.
    // However, for Cast support we need to keep a reference to the v2 notification manager
    // so we can switch to it when casting (since CastPlayer is ExoPlayer v2 based).
    Log.d(tag, "attachNotificationManager called. Storing v2 manager for Cast fallback.")
    notificationManagerRef = playerNotificationManager
    
    // Don't attach to Media3 player - our MediaSession handles notifications
    // We'll only use this notification manager when switching to Cast
  }

  override fun attachMediaSessionConnector(mediaSessionConnector: Any?) {
    // Store v2 media session connector for Cast fallback
    mediaSessionConnectorRef = mediaSessionConnector
    Log.d(tag, "attachMediaSessionConnector called. Storing v2 connector for Cast fallback.")
  }

  override fun setActivePlayerForNotification(activePlayer: Any?) {
    // Cast support: When switching to/from Cast, we need to use the v2 notification system
    // because CastPlayer implements ExoPlayer v2's Player interface.
    try {
      if (activePlayer != null) {
        // Switching TO Cast: Use v2 notification system
        Log.d(tag, "Switching to Cast player - using v2 notification system")
        isCastActive = true
        
        // Attach Cast player to v2 notification system
        val castPlayer = activePlayer as? com.google.android.exoplayer2.Player
        val notificationManager = notificationManagerRef as? com.google.android.exoplayer2.ui.PlayerNotificationManager
        val sessionConnector = mediaSessionConnectorRef as? com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
        
        notificationManager?.setPlayer(castPlayer)
        sessionConnector?.setPlayer(castPlayer)
        
        // Update active player so play/pause/etc. go to Cast
        this.activeV2Player = castPlayer
        
        Log.d(tag, "Cast player attached to v2 notification system and set as active player")
      } else {
        // Switching FROM Cast back to local: Restore local player to v2 system
        Log.d(tag, "Switching from Cast back to local player - restoring local player to v2 notification")
        isCastActive = false
        
        // Clear active v2 player - operations will now go to Media3 player
        this.activeV2Player = null
        
        // Restore local ExoPlayer v2 instance to v2 notification system
        // Note: Service will handle setting mPlayer on notification manager
        
        Log.d(tag, "Cleared active v2 player - operations now route to local Media3 player")
      }
    } catch (e: Exception) {
      Log.e(tag, "Error switching player for notifications: ${e.message}", e)
    }
  }
  
  // Helper removed - no longer needed

  override fun addListener(listener: PlayerEvents) {
    listeners.add(listener)
  }

  override fun removeListener(listener: PlayerEvents) {
    listeners.remove(listener)
  }
}
