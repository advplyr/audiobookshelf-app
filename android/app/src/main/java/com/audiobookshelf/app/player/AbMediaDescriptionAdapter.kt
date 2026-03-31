package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.bumptech.glide.Glide
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerNotificationManager
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class AbMediaDescriptionAdapter (private val playerNotificationService: PlayerNotificationService) : PlayerNotificationManager.MediaDescriptionAdapter {
  private val tag = "MediaDescriptionAdapter"

  private var currentIconUri: Uri? = null
  private var currentBitmap: Bitmap? = null

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

  override fun createCurrentContentIntent(player: Player): PendingIntent? =
    playerNotificationService.mediaSession.sessionActivity

  override fun getCurrentContentText(player: Player): CharSequence =
    playerNotificationService.currentPlaybackSession?.displayAuthor ?: ""

  override fun getCurrentContentTitle(player: Player): CharSequence =
    playerNotificationService.currentPlaybackSession?.displayTitle ?: ""

  override fun getCurrentLargeIcon(
    player: Player,
    callback: PlayerNotificationManager.BitmapCallback
  ): Bitmap? {
    val session = playerNotificationService.currentPlaybackSession ?: return null
    val albumArtUri = session.getCoverUri(playerNotificationService.getContext())

    // For local cover images, load bitmap directly
    if (session.localLibraryItem?.coverContentUrl != null) {
      return try {
        if (Build.VERSION.SDK_INT < 28) {
          @Suppress("DEPRECATION")
          MediaStore.Images.Media.getBitmap(playerNotificationService.contentResolver, albumArtUri)
        } else {
          val source: ImageDecoder.Source = ImageDecoder.createSource(playerNotificationService.contentResolver, albumArtUri)
          ImageDecoder.decodeBitmap(source)
        }
      } catch (e: Exception) {
        null
      }
    }

    return if (currentIconUri != albumArtUri || currentBitmap == null) {
      currentIconUri = albumArtUri

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
