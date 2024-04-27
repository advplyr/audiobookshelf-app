package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.*

class AbMediaDescriptionAdapter constructor(private val controller: MediaControllerCompat, private val playerNotificationService: PlayerNotificationService) : PlayerNotificationManager.MediaDescriptionAdapter {
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

    return if (currentIconUri != albumArtUri || currentBitmap == null) {
      // Cache the bitmap for the current audiobook so that successive calls to
      // `getCurrentLargeIcon` don't cause the bitmap to be recreated.
      currentIconUri = albumArtUri
      Log.d(tag, "ART $currentIconUri")

      if (currentIconUri.toString().startsWith("content://")) {
        currentBitmap = if (Build.VERSION.SDK_INT < 28) {
          @Suppress("DEPRECATION")
          MediaStore.Images.Media.getBitmap(playerNotificationService.contentResolver, currentIconUri)
        } else {
          val source: ImageDecoder.Source = ImageDecoder.createSource(playerNotificationService.contentResolver, currentIconUri!!)
          ImageDecoder.decodeBitmap(source)
        }
        currentBitmap
      } else {
        serviceScope.launch {
          currentBitmap = albumArtUri?.let {
            resolveUriAsBitmap(it)
          }
          currentBitmap?.let { callback.onBitmap(it) }
        }
        null
      }

    } else {
      currentBitmap
    }
  }

  private suspend fun resolveUriAsBitmap(uri: Uri): Bitmap? {
    return withContext(Dispatchers.IO) {
      try {
        Glide.with(playerNotificationService)
          .asBitmap()
          .load(uri)
          .placeholder(R.drawable.icon)
          .error(R.drawable.icon)
          .submit()
          .get()
      } catch (e: Exception) {
        e.printStackTrace()

        Glide.with(playerNotificationService)
          .asBitmap()
          .load(Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon))
          .submit()
          .get()
      }
    }
  }
}
