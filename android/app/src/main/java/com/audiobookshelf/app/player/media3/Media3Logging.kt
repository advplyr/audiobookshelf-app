package com.audiobookshelf.app.player.media3

import android.util.Log
import com.audiobookshelf.app.BuildConfig

/** Avoids building debug messages in release builds. */
internal inline fun debugLog(tag: String, crossinline lazyMessage: () -> String) {
  if (BuildConfig.DEBUG) Log.d(tag, lazyMessage())
}
