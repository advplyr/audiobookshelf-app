package com.tomesonic.app.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
// MIGRATION: Remove MediaSessionCompat - now using Media3 MediaSession
// import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession.Callback
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerNotificationManager
import com.google.common.collect.ImmutableList
import com.tomesonic.app.R
import com.tomesonic.app.data.PlaybackSession

// MIGRATION-BACKUP: ExoPlayer2 Implementation (commented out for reference)
/*
// Original ExoPlayer2 MediaSessionManager implementation
// This used MediaSessionConnector, TimelineQueueNavigator, etc.
// Full backup saved in MediaSessionManager_ExoPlayer2_backup.kt
*/

/**
 * Manages MediaSession setup for Media3
 * Media3 handles notifications automatically through the MediaSession
 */
class MediaSessionManager(
    private val context: Context,
    private val service: PlayerNotificationService,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "MediaSessionManager"
    }

    // Media3 MediaSession components
    var mediaSession: MediaLibrarySession? = null
        private set

    private var playerNotificationManager: PlayerNotificationManager? = null

    fun initializeMediaSession(
        notificationId: Int,
        channelId: String,
        sessionActivityPendingIntent: PendingIntent?,
        player: Player
    ) {
        // Create Media3 MediaLibrarySession
        val sessionBuilder = MediaLibrarySession.Builder(context, player, callback)
        sessionActivityPendingIntent?.let { sessionBuilder.setSessionActivity(it) }

        // Enable custom commands and actions for Android Auto
        sessionBuilder.setCustomLayout(buildCustomMediaActions())

        mediaSession = sessionBuilder.build()

        // Set up PlayerNotificationManager for Media3
        setupPlayerNotificationManager(notificationId, channelId, player)

        Log.d(TAG, "Media3 MediaLibrarySession and PlayerNotificationManager initialized successfully")
    }

    private fun setupPlayerNotificationManager(notificationId: Int, channelId: String, player: Player) {
        val mediaDescriptionAdapter = AbMediaDescriptionAdapter(context)
        val notificationListener = PlayerNotificationListener(service)

        playerNotificationManager = PlayerNotificationManager.Builder(context, notificationId, channelId)
            .setMediaDescriptionAdapter(mediaDescriptionAdapter)
            .setNotificationListener(notificationListener)
            .build()

        playerNotificationManager?.setPlayer(player)
        playerNotificationManager?.setUseRewindAction(false)
        playerNotificationManager?.setUseFastForwardAction(false)
        playerNotificationManager?.setUseNextAction(false)
        playerNotificationManager?.setUsePreviousAction(false)

        // Enhanced logging for cast player debugging
        val playerType = when {
            player.javaClass.simpleName.contains("Cast") -> "CastPlayer"
            player.javaClass.simpleName.contains("ExoPlayer") -> "ExoPlayer"
            else -> player.javaClass.simpleName
        }

        Log.d(TAG, "PlayerNotificationManager set up for $playerType")
        Log.d(TAG, "Player state: playbackState=${player.playbackState}, isPlaying=${player.isPlaying}")
        Log.d(TAG, "Player mediaItemCount=${player.mediaItemCount}, currentIndex=${player.currentMediaItemIndex}")

        // Force notification update for cast players
        if (playerType == "CastPlayer" && player.currentMediaItem != null) {
            Log.d(TAG, "Forcing notification update for CastPlayer with mediaItem")
            // The PlayerNotificationManager should automatically create a notification
            // when a player has a current media item and is in a valid state
        }
    }

    private fun buildCustomMediaActions(): ImmutableList<androidx.media3.session.CommandButton> {
        return buildCustomMediaActionsWithSpeed(service.mediaManager.getSavedPlaybackRate())
    }

    private fun buildCustomMediaActionsWithSpeed(currentSpeed: Float): ImmutableList<androidx.media3.session.CommandButton> {
        val customActions = ImmutableList.builder<androidx.media3.session.CommandButton>()

        // Jump backward button (loop rewind icon)
        customActions.add(
            androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_SKIP_BACK)
                .setDisplayName("Jump Back")
                .setSessionCommand(SessionCommand(PlayerNotificationService.CUSTOM_ACTION_JUMP_BACKWARD, Bundle.EMPTY))
                .build()
        )

        // Jump forward button (loop forward icon)
        customActions.add(
            androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_SKIP_FORWARD)
                .setDisplayName("Jump Forward")
                .setSessionCommand(SessionCommand(PlayerNotificationService.CUSTOM_ACTION_JUMP_FORWARD, Bundle.EMPTY))
                .build()
        )

        // Speed control button with speed-specific icon
        customActions.add(
            androidx.media3.session.CommandButton.Builder()
                .setIconResId(getSpeedIcon(currentSpeed))
                .setDisplayName("Speed")
                .setSessionCommand(SessionCommand(PlayerNotificationService.CUSTOM_ACTION_CHANGE_PLAYBACK_SPEED, Bundle.EMPTY))
                .build()
        )

        return customActions.build()
    }

    private fun getSpeedIcon(playbackRate: Float): Int {
        return when (playbackRate) {
            in 0.5f..0.7f -> R.drawable.ic_play_speed_0_5x
            in 0.8f..1.0f -> R.drawable.ic_play_speed_1_0x
            in 1.1f..1.3f -> R.drawable.ic_play_speed_1_2x
            in 1.4f..1.6f -> R.drawable.ic_play_speed_1_5x
            in 1.7f..2.2f -> R.drawable.ic_play_speed_2_0x
            in 2.3f..3.0f -> R.drawable.ic_play_speed_3_0x
            // anything set above 3 will show the 3x icon
            else -> R.drawable.ic_play_speed_3_0x
        }
    }

    fun updateCustomLayout() {
        mediaSession?.let { session ->
            val newLayout = buildCustomMediaActionsWithSpeed(service.mediaManager.getSavedPlaybackRate())
            session.setCustomLayout(newLayout)
            Log.d(TAG, "Updated MediaSession custom layout with new speed icon")
        }
    }

    /**
     * Update MediaSession metadata with chapter-aware information
     * This makes Android Auto treat each chapter as a separate track with proper duration
     *
     * NOTE: With the new MediaSource architecture, MediaItems already have correct metadata
     * from creation, so this method primarily serves as a fallback or for notification updates
     */
    fun updateChapterMetadata(chapterTitle: String, chapterDuration: Long, bookTitle: String, author: String?, bitmap: Bitmap?) {
        Log.d(TAG, "Updating chapter metadata: chapter='$chapterTitle', duration=${chapterDuration}ms")

        // With the new MediaSource architecture, the MediaItems should already have the correct
        // metadata including chapter duration, so we don't need to replace the MediaItem.
        // Android Auto will use the MediaItem's original metadata.

        // However, we can still log this for debugging purposes
        mediaSession?.let { session ->
            val currentMediaItem = session.player.currentMediaItem
            if (currentMediaItem != null) {
                val currentMetadata = currentMediaItem.mediaMetadata
                Log.d(TAG, "Current MediaItem metadata: title='${currentMetadata.title}', duration=${currentMetadata.durationMs}ms")

                // Verify that the MediaItem already has the correct duration
                if (currentMetadata.durationMs != null && currentMetadata.durationMs!! > 0) {
                    Log.d(TAG, "MediaItem already has correct duration metadata, no update needed")
                } else {
                    Log.w(TAG, "MediaItem missing duration metadata, this may cause Android Auto timeline issues")
                }
            } else {
                Log.w(TAG, "No current MediaItem to check metadata")
            }
        }
    }

    fun getSessionToken(): androidx.media3.session.SessionToken? =
        SessionToken(context, ComponentName(context, service::class.java))

    fun getCompatSessionToken(): androidx.media3.session.SessionToken? =
        // Return Media3 SessionToken
        SessionToken(context, ComponentName(context, service::class.java))

    /**
     * Updates the MediaSession with a new player without recreating the session
     */
    fun updatePlayer(newPlayer: Player) {
        Log.d(TAG, "updatePlayer: Switching to new player type: ${newPlayer.javaClass.simpleName}")

        // Update notification manager with new player
        playerNotificationManager?.setPlayer(newPlayer)

        // The MediaSession itself doesn't need to be recreated in Media3
        // The playerNotificationManager handles the player switch seamlessly
        Log.d(TAG, "updatePlayer: Player updated successfully")
    }

    fun release() {
        playerNotificationManager?.setPlayer(null)
        playerNotificationManager = null

        mediaSession?.let { session: MediaLibrarySession ->
            session.release()
            Log.d(TAG, "Media3 MediaLibrarySession released")
        }

        mediaSession = null
    }
}
