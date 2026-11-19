package com.audiobookshelf.wear.download

import android.util.Log
import com.audiobookshelf.wear.data.DownloadStore
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.data.ServerProgress

/**
 * Removes downloads of finished books and episodes when "Delete finished downloads" is on (it is
 * off by default). Never removes what is loaded in the player.
 */
class AutoDelete(
  private val downloads: DownloadStore,
  private val enabled: () -> Boolean,
  private val loadedInPlayer: () -> MediaKey?
) {
  /** Called when a book or episode was finished. Returns true if its download was removed. */
  fun onFinished(key: MediaKey): Boolean {
    if (!enabled() || !downloads.isDownloaded(key) || loadedInPlayer() == key) return false
    Log.i(TAG, "Removing finished download ${key.key}")
    downloads.delete(key)
    return true
  }

  /** Removes downloads that were finished elsewhere, e.g. on the phone. [progress] is keyed by [MediaKey.key]. */
  fun sweep(progress: Map<String, ServerProgress>): Int {
    if (!enabled()) return 0
    return downloads.books.value
      .map { it.book.mediaKey }
      .filter { progress[it.key]?.isFinished == true }
      .count(::onFinished)
  }

  companion object {
    private const val TAG = "AutoDelete"
  }
}
