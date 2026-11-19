package com.audiobookshelf.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.audiobookshelf.app.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Makes the playing cover available to Android Auto through a local, read-only content URI. */
internal suspend fun cacheMediaSessionCover(context: Context, bitmap: Bitmap): Uri? =
  withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "mediaSessionArtwork")
    var file: File? = null
    try {
      if (!directory.exists() && !directory.mkdirs()) error("Could not create artwork cache")
      val artworkFile = File.createTempFile("cover-", ".png", directory)
      file = artworkFile
      artworkFile.outputStream().use { stream ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
          error("Could not encode cover artwork")
        }
      }
      FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        artworkFile
      )
    } catch (error: Exception) {
      file?.delete()
      Log.e("MediaSessionArtworkCache", "Could not cache cover artwork", error)
      null
    }
  }
