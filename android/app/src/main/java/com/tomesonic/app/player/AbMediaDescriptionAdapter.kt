package com.tomesonic.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.util.Log
import com.tomesonic.app.MainActivity

class AbMediaDescriptionAdapter(
    private val context: Context
) : PlayerNotificationManager.MediaDescriptionAdapter {

    companion object {
        private const val TAG = "AbMediaDescriptionAdapter"
    }

    override fun createCurrentContentIntent(player: Player): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun getCurrentContentText(player: Player): CharSequence {
        val currentMediaItem = player.currentMediaItem
        val metadata = currentMediaItem?.mediaMetadata

        return when {
            metadata?.artist != null -> metadata.artist.toString()
            metadata?.albumArtist != null -> metadata.albumArtist.toString()
            metadata?.subtitle != null -> metadata.subtitle.toString()
            else -> ""
        }.also {
            Log.d(TAG, "getCurrentContentText: '$it' (mediaItem=${currentMediaItem != null}, metadata=${metadata != null})")
        }
    }

    override fun getCurrentContentTitle(player: Player): CharSequence {
        val currentMediaItem = player.currentMediaItem
        val metadata = currentMediaItem?.mediaMetadata

        return when {
            metadata?.title != null -> metadata.title.toString()
            metadata?.displayTitle != null -> metadata.displayTitle.toString()
            currentMediaItem?.mediaId != null -> "Audiobook"
            else -> "Unknown"
        }.also {
            Log.d(TAG, "getCurrentContentTitle: '$it' (mediaItem=${currentMediaItem != null}, metadata=${metadata != null})")
        }
    }

    override fun getCurrentLargeIcon(
        player: Player,
        callback: PlayerNotificationManager.BitmapCallback
    ): Bitmap? {
        val currentMediaItem = player.currentMediaItem
        val artworkData = currentMediaItem?.mediaMetadata?.artworkData

        Log.d(TAG, "getCurrentLargeIcon: mediaItem=${currentMediaItem != null}, artworkData=${artworkData != null}")

        // If artwork is available in metadata, return it
        // Otherwise let the PlayerNotificationManager handle async loading
        return null
    }
}
