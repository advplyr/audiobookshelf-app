package com.bookshelf.app.player

import android.app.PendingIntent
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log
import com.bookshelf.app.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.*

const val NOTIFICATION_LARGE_ICON_SIZE = 144 // px

class AbMediaDescriptionAdapter constructor(private val controller: MediaControllerCompat, val playerNotificationService: PlayerNotificationService) : PlayerNotificationManager.MediaDescriptionAdapter {
  private val tag = "MediaDescriptionAdapter"

  var currentIconUri: Uri? = null
  var currentBitmap: Bitmap? = null

  private val glideOptions = RequestOptions()
    .fallback(R.drawable.icon)
    .diskCacheStrategy(DiskCacheStrategy.DATA)
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

    return if (currentIconUri != albumArtUri || currentBitmap == null) {
      // Cache the bitmap for the current audiobook so that successive calls to
      // `getCurrentLargeIcon` don't cause the bitmap to be recreated.
      currentIconUri = albumArtUri
      Log.d(tag, "ART $currentIconUri")
      serviceScope.launch {
        currentBitmap = albumArtUri?.let {
          resolveUriAsBitmap(it)
        }
        currentBitmap?.let { callback.onBitmap(it) }
      }
      null
    } else {
      currentBitmap
    }
  }

  private suspend fun resolveUriAsBitmap(uri: Uri): Bitmap? {
    return withContext(Dispatchers.IO) {
      // Block on downloading artwork.
      try {
        Glide.with(playerNotificationService).applyDefaultRequestOptions(glideOptions)
          .asBitmap()
          .load(uri)
          .placeholder(R.drawable.icon)
          .error(R.drawable.icon)
          .submit(NOTIFICATION_LARGE_ICON_SIZE, NOTIFICATION_LARGE_ICON_SIZE)
          .get()
      } catch (e: Exception) {
        e.printStackTrace()

        Glide.with(playerNotificationService).applyDefaultRequestOptions(glideOptions)
          .asBitmap()
          .load(Uri.parse("android.resource://com.bookshelf.app/" + R.drawable.icon))
          .submit(NOTIFICATION_LARGE_ICON_SIZE, NOTIFICATION_LARGE_ICON_SIZE)
          .get()
      }
    }
  }
}
