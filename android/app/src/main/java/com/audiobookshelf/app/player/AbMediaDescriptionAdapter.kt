package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.*

class AbMediaDescriptionAdapter (private val controller: MediaControllerCompat, private val playerNotificationService: PlayerNotificationService) : PlayerNotificationManager.MediaDescriptionAdapter {
  private val tag = "MediaDescriptionAdapter"

  private var currentIconUri: Uri? = null
  private var currentBitmap: Bitmap? = null

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

  override fun createCurrentContentIntent(player: Player): PendingIntent? =
    controller.sessionActivity

  override fun getCurrentContentText(player: Player) = controller.metadata.description.subtitle.toString()

  override fun getCurrentContentTitle(player: Player) = controller.metadata.description.title.toString()

  override fun getCurrentLargeIcon(
    player: Player,
    callback: PlayerNotificationManager.BitmapCallback
  ): Bitmap? {
    val albumArtUri = controller.metadata.description.iconUri
    val albumBitmap = controller.metadata.description.iconBitmap
      ?: controller.metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

    // Reuse bitmap from queue navigator (local) or PlaybackSession.resolveCoverBitmapAsync (streaming)
    // For local cover images, bitmap is set in PlayerNotificationService TimelineQueueNavigator.getMediaDescription
    if (albumBitmap != null) {
      return albumBitmap
    }

    return if (currentIconUri != albumArtUri || currentBitmap == null) {
      // Cache the bitmap for the current audiobook so that successive calls to
      // `getCurrentLargeIcon` don't cause the bitmap to be recreated.
      currentIconUri = albumArtUri

      if (currentIconUri.toString().startsWith("content://")) {
        currentBitmap = try {
          if (Build.VERSION.SDK_INT < 28) {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(playerNotificationService.contentResolver, currentIconUri)
          } else {
            val source: ImageDecoder.Source = ImageDecoder.createSource(playerNotificationService.contentResolver, currentIconUri!!)
            ImageDecoder.decodeBitmap(source)
          }
        } catch (e: Exception) {
          Log.e("AbMediaDescriptionAdapter", "Failed to decode bitmap: ${e.message}")
          null
        }
        currentBitmap
      } else {
        serviceScope.launch {
          currentBitmap = albumArtUri?.let {
            resolveUriAsBitmap(playerNotificationService, it)
          }
          currentBitmap?.let { callback.onBitmap(it) }
        }
        null
      }
    } else {
      currentBitmap
    }
  }
}
