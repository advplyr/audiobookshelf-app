package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

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
  
  // Removed v2 conversion helpers; neutral wrapper should not expose v2 types

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
        val builder = MediaSession.Builder(ctx, p)
        if (sessionActivityPendingIntent != null) {
          builder.setSessionActivity(sessionActivityPendingIntent)
        }
        builder.build()
      }
      
      Log.d(tag, "Media3 MediaSession created successfully")
    } catch (e: Exception) {
      Log.w(tag, "Failed to create MediaSession: ${e.message}", e)
      mediaSession = null
    }
  }

  override fun prepare() { player?.prepare() }
  
  override fun play() { player?.play() }
  
  override fun pause() { player?.pause() }
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
  override fun setPlayWhenReady(playWhenReady: Boolean) { player?.playWhenReady = playWhenReady }
  
  override fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }

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
    return if (Looper.myLooper() == Looper.getMainLooper()) {
      lastKnownPosition = player?.currentPosition ?: 0L
      lastKnownPosition
    } else {
      lastKnownPosition
    }
  }

  // Live accessor (main thread expected).
  override fun getCurrentPositionLive(): Long {
    val pos = player?.currentPosition ?: 0L
    if (Looper.myLooper() == Looper.getMainLooper()) {
      lastKnownPosition = pos
    }
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
  
  // Expose MediaSession token for notification integration
  fun getSessionToken(): androidx.media3.session.SessionToken? = mediaSession?.token


  // Notification/session attachments
  // Note: The service may pass ExoPlayer v2's PlayerNotificationManager which is
  // incompatible with Media3's player. For now we attempt a best-effort attachment
  // using reflection. In a production implementation, the service should create a
  // Media3-compatible notification manager when USE_MEDIA3 is enabled.
  private var notificationManagerRef: Any? = null
  private var mediaSessionConnectorRef: Any? = null

  override fun attachNotificationManager(playerNotificationManager: Any?) {
    // Phase 1.5: Media3 uses session-based notifications automatically.
    // The service should create a Media3 NotificationManager using our MediaSession.
    // For now, we just log that the legacy notification manager was passed.
    // The service will need conditional logic to create the appropriate notification type.
    Log.d(tag, "attachNotificationManager called. Media3 notifications are session-driven.")
    Log.d(tag, "Service should use DefaultMediaNotificationProvider with our session token.")
    notificationManagerRef = playerNotificationManager
  }

  override fun attachMediaSessionConnector(mediaSessionConnector: Any?) {
    // Media3 uses a different media session API; store a reference for potential future use.
    mediaSessionConnectorRef = mediaSessionConnector
  }

  override fun setActivePlayerForNotification(activePlayer: Any?) {
    // The service uses ExoPlayer v2 notification APIs; Media3 player cannot be wired to them.
    // Cast player switching is ExoPlayer v2-based and won't work with Media3 wrapper until
    // Phase 2 implements Media3-native notification/session management.
    Log.w(tag, "setActivePlayerForNotification not supported in Media3Wrapper Phase 1. Cast notifications will not work.")
  }

  override fun addListener(listener: PlayerEvents) {
    listeners.add(listener)
  }

  override fun removeListener(listener: PlayerEvents) {
    listeners.remove(listener)
  }
}
