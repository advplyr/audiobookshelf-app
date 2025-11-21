package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.audiobookshelf.app.data.PlaybackSession

/**
 * Media3-backed PlayerWrapper using the androidx.media3 ExoPlayer implementation.
 * This class manages both a local Media3 ExoPlayer and a fallback v2 ExoPlayer for casting,
 * routing commands to the currently active player.
 */
class Media3Wrapper(private val ctx: Context) : PlayerWrapper {

  companion object {
    private const val TAG = "Media3Wrapper"
  }

  private var player: ExoPlayer? = null
  private val listeners = mutableSetOf<PlayerEvents>()
  private var mediaSession: MediaSession? = null

  // The v2 player used for casting. When this is not null, it's the active player.
  private var activeV2Player: com.google.android.exoplayer2.Player? = null

  /**
   * Smart property to get the currently active player instance (either local Media3 or Cast v2).
   * This avoids repetitive if/else checks throughout the class.
   */
  private val activePlayer: Any?
    get() = activeV2Player ?: player

  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      listeners.forEach { it.onPlaybackStateChanged(playbackState) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.e(TAG, "onPlayerError: ${error.message}", error)
      listeners.forEach { it.onPlayerError(error.message ?: "Unknown error", error.errorCode) }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
      val isSeek = reason == Player.DISCONTINUITY_REASON_SEEK
      listeners.forEach { it.onPositionDiscontinuity(isSeek) }
    }
  }

  init {
    try {
      player = ExoPlayer.Builder(ctx).build().also { newPlayer ->
        val audioAttributes = AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
          .build()
        newPlayer.setAudioAttributes(audioAttributes, true)
        newPlayer.setHandleAudioBecomingNoisy(true)
        newPlayer.addListener(playerListener)
      }
      createMediaSession()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to construct Media3 ExoPlayer", e)
      player = null
    }
  }

  private fun createMediaSession() {
    try {
      val launchIntent = ctx.packageManager?.getLaunchIntentForPackage(ctx.packageName)
      val sessionActivityPendingIntent = launchIntent?.let { intent ->
        PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      }

      mediaSession = player?.let { p ->
        val sessionId = "audiobookshelf.media3.session.${System.currentTimeMillis()}"
        MediaSession.Builder(ctx, p)
          .setId(sessionId)
          .apply { sessionActivityPendingIntent?.let(::setSessionActivity) }
          .build()
      }
      Log.d(TAG, "Media3 MediaSession created successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create MediaSession", e)
      mediaSession = null
    }
  }

  /**
   * Configure seek forward/backward increments for notification buttons.
   * These will appear as standard seek forward/back actions in notifications.
   */
  fun setSeekIncrements(backwardMs: Long, forwardMs: Long) {
    try {
      // ExoPlayer seek increment setters not available in current Media3 version.
      // Skipping configuration to maintain compatibility.
      Log.d(TAG, "Seek increments skipped for current Media3 version")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to set seek increments: ${e.message}")
    }
  }

  override fun release() {
    try {
      player?.removeListener(playerListener)
      mediaSession?.release()
      player?.release()
    } catch (e: Exception) {
      Log.w(TAG, "Exception during release: ${e.message}")
    } finally {
      mediaSession = null
      player = null
    }
  }

  fun getMediaSession(): MediaSession? = mediaSession
  fun getSessionToken(): androidx.media3.session.SessionToken? = mediaSession?.token

  fun updateMediaMetadata(playbackSession: PlaybackSession) {
    player?.run {
      if (mediaItemCount == 0) return@run // Nothing to update
      try {
        val metadata = MediaMetadata.Builder()
          .setTitle(playbackSession.displayTitle ?: "Unknown Title")
          .setArtist(playbackSession.displayAuthor ?: "Unknown Author")
          .setArtworkUri(playbackSession.getCoverUri(ctx))
          .build()

        val currentItem = getMediaItemAt(currentMediaItemIndex)
        val updatedItem = currentItem.buildUpon().setMediaMetadata(metadata).build()
        replaceMediaItem(currentMediaItemIndex, updatedItem)

        Log.d(TAG, "Updated MediaMetadata for: ${playbackSession.displayTitle}")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to update MediaMetadata", e)
      }
    }
  }

  // region ===== Player Command Implementation ==============================================
  // The following methods delegate actions to the `activePlayer`.

  override fun prepare() {
    when (val p = activePlayer) {
      is ExoPlayer -> p.prepare()
      is com.google.android.exoplayer2.Player -> p.prepare()
    }
  }

  override fun play() {
    when (val p = activePlayer) {
      is ExoPlayer -> p.play()
      is com.google.android.exoplayer2.Player -> p.play()
    }
  }

  override fun pause() {
    when (val p = activePlayer) {
      is ExoPlayer -> p.pause()
      is com.google.android.exoplayer2.Player -> p.pause()
    }
  }

  override fun stop() {
    when (val p = activePlayer) {
      is ExoPlayer -> p.stop()
      is com.google.android.exoplayer2.Player -> p.stop()
    }
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    when (val p = activePlayer) {
      is ExoPlayer -> p.playWhenReady = playWhenReady
      is com.google.android.exoplayer2.Player -> p.playWhenReady = playWhenReady
    }
  }

  override fun seekTo(positionMs: Long) {
    when (val p = activePlayer) {
      is ExoPlayer -> p.seekTo(positionMs)
      is com.google.android.exoplayer2.Player -> p.seekTo(positionMs)
    }
  }

  override fun seekTo(windowIndex: Int, positionMs: Long) {
    when (val p = activePlayer) {
      is ExoPlayer -> p.seekTo(windowIndex, positionMs)
      is com.google.android.exoplayer2.Player -> p.seekTo(windowIndex, positionMs)
    }
  }

  override fun seekToPrevious() {
    when (val p = activePlayer) {
      is ExoPlayer -> p.seekToPrevious()
      is com.google.android.exoplayer2.Player -> p.seekToPrevious()
    }
  }

  override fun seekToNext() {
    when (val p = activePlayer) {
      is ExoPlayer -> p.seekToNext()
      is com.google.android.exoplayer2.Player -> p.seekToNext()
    }
  }

  override fun setPlaybackSpeed(speed: Float) {
    try {
      when (val p = activePlayer) {
        is ExoPlayer -> p.setPlaybackSpeed(speed)
        is com.google.android.exoplayer2.Player -> p.setPlaybackSpeed(speed)
      }
    } catch (e: Exception) {
      Log.w(TAG, "setPlaybackSpeed failed: ${e.message}")
    }
  }

  override fun setVolume(volume: Float) {
    when (val p = activePlayer) {
      is ExoPlayer -> p.volume = volume
      is com.google.android.exoplayer2.Player -> p.volume = volume
    }
  }

  override fun setMediaItems(items: List<PlayerMediaItem>, startIndex: Int, startPositionMs: Long) {
    player?.setMediaItems(items.map { it.toMedia3Item() }, startIndex, startPositionMs)
  }

  override fun addMediaItems(items: List<PlayerMediaItem>) {
    player?.addMediaItems(items.map { it.toMedia3Item() })
  }

  override fun clearMediaItems() {
    when (val p = activePlayer) {
      is ExoPlayer -> p.clearMediaItems()
      is com.google.android.exoplayer2.Player -> p.clearMediaItems()
    }
  }
  // endregion

  // region ===== Player State Getters =======================================================

  override fun isPlaying(): Boolean = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.isPlaying
      is com.google.android.exoplayer2.Player -> p.isPlaying
      else -> false
    }
  } catch (e: Exception) {
    Log.w(TAG, "isPlaying() check failed", e)
    false
  }

  override fun isLoading(): Boolean = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.isLoading
      is com.google.android.exoplayer2.Player -> p.isLoading
      else -> false
    }
  } catch (_: Exception) { false }

  override fun getPlaybackState(): Int = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.playbackState
      is com.google.android.exoplayer2.Player -> p.playbackState
      else -> Player.STATE_IDLE
    }
  } catch (_: Exception) { Player.STATE_IDLE }

  override fun getCurrentPosition(): Long = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.currentPosition
      is com.google.android.exoplayer2.Player -> p.currentPosition
      else -> 0L
    }
  } catch (_: Exception) { 0L }

  override fun getCurrentPositionLive(): Long = getCurrentPosition() // Live accessor is the same now

  override fun getBufferedPosition(): Long = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.bufferedPosition
      is com.google.android.exoplayer2.Player -> p.bufferedPosition
      else -> 0L
    }
  } catch (_: Exception) { 0L }

  override fun getDuration(): Long {
    val duration = try {
      when (val p = activePlayer) {
        is ExoPlayer -> p.duration
        is com.google.android.exoplayer2.Player -> p.duration
        else -> C.TIME_UNSET
      }
    } catch (_: Exception) { C.TIME_UNSET }
    return if (duration == C.TIME_UNSET) 0L else duration
  }

  override fun getMediaItemCount(): Int = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.mediaItemCount
      is com.google.android.exoplayer2.Player -> p.mediaItemCount
      else -> 0
    }
  } catch (_: Exception) { 0 }

  override fun getCurrentMediaItemIndex(): Int = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.currentMediaItemIndex
      is com.google.android.exoplayer2.Player -> p.currentMediaItemIndex
      else -> 0
    }
  } catch (_: Exception) { 0 }

  override fun getPlaybackSpeed(): Float = try {
    when (val p = activePlayer) {
      is ExoPlayer -> p.playbackParameters.speed
      is com.google.android.exoplayer2.Player -> p.playbackParameters.speed
      else -> 1f
    }
  } catch (_: Exception) { 1f }
  // endregion

  // region ===== Listener Management ========================================================
  override fun addListener(listener: PlayerEvents) {
    listeners.add(listener)
  }

  override fun removeListener(listener: PlayerEvents) {
    listeners.remove(listener)
  }
  // endregion

  // region ===== Cast Support (v2 Player Interaction) =======================================
  private var notificationManagerRef: Any? = null
  private var mediaSessionConnectorRef: Any? = null

  override fun attachNotificationManager(playerNotificationManager: Any?) {
    Log.d(TAG, "Storing v2 PlayerNotificationManager for Cast fallback.")
    notificationManagerRef = playerNotificationManager
  }

  override fun attachMediaSessionConnector(mediaSessionConnector: Any?) {
    Log.d(TAG, "Storing v2 MediaSessionConnector for Cast fallback.")
    mediaSessionConnectorRef = mediaSessionConnector
  }

  override fun setActivePlayerForNotification(activePlayer: Any?) {
    try {
      if (activePlayer is com.google.android.exoplayer2.Player) {
        // Switching TO Cast player
        Log.d(TAG, "Activating Cast player; switching to v2 notification system.")
        this.activeV2Player = activePlayer

        val notificationManager = notificationManagerRef as? com.google.android.exoplayer2.ui.PlayerNotificationManager
        val sessionConnector = mediaSessionConnectorRef as? com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector

        notificationManager?.setPlayer(activePlayer)
        sessionConnector?.setPlayer(activePlayer)
      } else {
        // Switching FROM Cast back to local player
        Log.d(TAG, "Deactivating Cast player; local Media3 player is now active.")
        this.activeV2Player = null

        // The local Media3 player automatically uses its own MediaSession for notifications,
        // so we only need to clear the v2 player reference.
        // We can optionally clear the player from the v2 notification manager to be safe.
        (notificationManagerRef as? com.google.android.exoplayer2.ui.PlayerNotificationManager)?.setPlayer(null)
        (mediaSessionConnectorRef as? com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector)?.setPlayer(null)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error switching active player for notifications", e)
    }
  }
  // endregion

  // region ===== Helpers ====================================================================
  /**
   * Extension function to convert our internal PlayerMediaItem DTO to a Media3 MediaItem.
   */
  private fun PlayerMediaItem.toMedia3Item(): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle(this.tag?.toString() ?: "Audiobook") // Placeholder title
      .build()

    return MediaItem.Builder()
      .setUri(this.uri)
      .setTag(this.tag)
      .setMimeType(this.mimeType)
      .setMediaMetadata(metadata)
      .build()
  }
  // endregion
}
