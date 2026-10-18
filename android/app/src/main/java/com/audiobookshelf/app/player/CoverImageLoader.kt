package com.audiobookshelf.app.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CoverImageLoader"

// 320 px keeps Binder transactions well below the limit (512x512 ARGB is already ~1 MB).
private const val COVER_MAX_DIMENSION_PX = 320

/**
 * In-memory cache for downscaled cover bitmaps, keyed by URI. Kept outside PlaybackSession so
 * the cache is not part of the session that PaperDB/Kryo serializes.
 */
object CoverBitmapCache {
  private val cache = LruCache<String, Bitmap>(2)

  fun get(uri: Uri): Bitmap? = cache.get(uri.toString())

  fun put(uri: Uri, bitmap: Bitmap) {
    cache.put(uri.toString(), bitmap)
  }
}

/**
 * Loads [uri] as a downscaled software bitmap via Glide, falling back to the app icon if the
 * load fails. Results are cached in [CoverBitmapCache] so the cover is only decoded once.
 */
suspend fun resolveUriAsBitmap(context: Context, uri: Uri): Bitmap? {
  CoverBitmapCache.get(uri)?.let { return it }

  return withContext(Dispatchers.IO) {
    val loaded = try {
      Glide.with(context)
        .asBitmap()
        .load(uri)
        .downsample(DownsampleStrategy.AT_MOST)
        .override(COVER_MAX_DIMENSION_PX, COVER_MAX_DIMENSION_PX)
        .disallowHardwareConfig()
        .submit()
        .get()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load cover bitmap for uri: $uri", e)
      null
    }

    // Only cache real cover images, not the fallback icon, so a transient failure can be retried.
    loaded?.let { CoverBitmapCache.put(uri, it) }

    loaded ?: try {
      Glide.with(context)
        .asBitmap()
        .load(Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon))
        .submit()
        .get()
    } catch (fallbackError: Exception) {
      Log.e(TAG, "Failed to load fallback cover bitmap", fallbackError)
      null
    }
  }
}
