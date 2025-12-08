package com.audiobookshelf.app.player.media3

import android.content.Context
import androidx.media3.common.MediaItem
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.LibraryAuthorItem
import com.audiobookshelf.app.data.LibraryCollection
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibrarySeriesItem
import com.audiobookshelf.app.data.LibraryShelfAuthorEntity
import com.audiobookshelf.app.data.LibraryShelfBookEntity
import com.audiobookshelf.app.data.LibraryShelfEpisodeEntity
import com.audiobookshelf.app.data.LibraryShelfPodcastEntity
import com.audiobookshelf.app.media.MediaManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Handles data loading operations for Media3 browse tree.
 * Provides suspend functions for fetching library content from MediaManager.
 */
class Media3BrowseDataLoader(private val mediaManager: MediaManager) {

  // Helper functions for MediaManager callback wrapping
  private suspend fun <T> withMediaManagerCallback(operation: (callback: (T?) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
      operation { result ->
        if (continuation.isActive) {
          // If the underlying API returns null, provide a default empty list.
          // This makes the result non-nullable, matching the function's return type.
          @Suppress("UNCHECKED_CAST")
          val nonNullResult = result ?: (emptyList<Any>() as T)
          continuation.resume(nonNullResult)
        }
      }
    }

  private suspend fun <T> withSingleItemCallback(operation: (callback: (T?) -> Unit) -> Unit): T? =
    suspendCancellableCoroutine { continuation ->
      operation { result ->
        if (continuation.isActive) {
          continuation.resume(result)
        }
      }
    }

  suspend fun loadLibraryPodcasts(libraryId: String): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadLibraryPodcasts(libraryId) { result ->
      if (BuildConfig.DEBUG) {
        android.util.Log.d(
          "M3BrowseDataLoader",
          "podcasts loaded library=$libraryId count=${result?.size ?: 0}"
        )
      }
      it(result)
    }
  }

  suspend fun loadAuthorsWithBooks(libraryId: String): List<LibraryAuthorItem> =
    withMediaManagerCallback {
      mediaManager.loadAuthorsWithBooks(libraryId) { result ->
        if (BuildConfig.DEBUG) {
          android.util.Log.d(
            "M3BrowseDataLoader",
            "authors loaded library=$libraryId count=${result.size}"
          )
        }
        it(result)
      }
    }

  suspend fun loadLibrarySeriesWithAudio(libraryId: String): List<LibrarySeriesItem> =
    withMediaManagerCallback {
      mediaManager.loadLibrarySeriesWithAudio(libraryId) { result ->
        if (BuildConfig.DEBUG) {
          android.util.Log.d(
            "M3BrowseDataLoader",
            "series loaded library=$libraryId count=${result.size}"
          )
        }
        it(result)
      }
    }

  suspend fun loadLibraryCollectionsWithAudio(libraryId: String): List<LibraryCollection> =
    withMediaManagerCallback {
      mediaManager.loadLibraryCollectionsWithAudio(libraryId) { result ->
        if (BuildConfig.DEBUG) {
          android.util.Log.d(
            "M3BrowseDataLoader",
            "collections loaded library=$libraryId count=${result.size}"
          )
        }
        it(result)
      }
    }

  suspend fun loadLibraryDiscoveryBooksWithAudio(libraryId: String): List<LibraryItem> =
    withMediaManagerCallback {
      mediaManager.loadLibraryDiscoveryBooksWithAudio(libraryId) { result ->
        if (BuildConfig.DEBUG) {
          android.util.Log.d(
            "M3BrowseDataLoader",
            "discovery loaded library=$libraryId count=${result.size}"
          )
        }
        it(result)
      }
    }

  suspend fun loadAuthorBooksWithAudio(libraryId: String, authorId: String): List<LibraryItem> =
    withMediaManagerCallback {
      mediaManager.loadAuthorBooksWithAudio(libraryId, authorId) { result ->
        if (BuildConfig.DEBUG) {
          android.util.Log.d(
            "M3BrowseDataLoader",
            "author books loaded library=$libraryId author=$authorId count=${result.size}"
          )
        }
        it(result)
      }
    }

  suspend fun loadLibrarySeriesItemsWithAudio(
    libraryId: String,
    seriesId: String
  ): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadLibrarySeriesItemsWithAudio(libraryId, seriesId) { result ->
      android.util.Log.d(
        "M3BrowseDataLoader",
        "series items loaded library=$libraryId series=$seriesId count=${result.size}"
      )
      it(result)
    }
  }

  suspend fun loadLibraryCollectionBooksWithAudio(
    libraryId: String,
    collectionId: String
  ): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadLibraryCollectionBooksWithAudio(libraryId, collectionId) { result ->
      android.util.Log.d(
        "M3BrowseDataLoader",
        "collection books loaded library=$libraryId collection=$collectionId count=${result.size}"
      )
      it(result)
    }
  }

  suspend fun loadPodcastEpisodes(podcastId: String, context: Context): List<MediaItem> {
    val episodes = mediaManager.loadPodcastEpisodes(podcastId, context) ?: emptyList()
    android.util.Log.d(
      "M3BrowseDataLoader",
      "podcast episodes loaded podcast=$podcastId count=${episodes.size}"
    )
    return episodes
  }

  suspend fun loadRecentShelfBooks(libraryId: String): List<LibraryItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "book", it)
    } as? LibraryShelfBookEntity
    return recentShelf?.entities ?: emptyList()
  }

  suspend fun loadRecentShelfAuthors(libraryId: String): List<LibraryAuthorItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "authors", it)
    } as? LibraryShelfAuthorEntity
    return recentShelf?.entities ?: emptyList()
  }

  suspend fun loadRecentShelfPodcasts(libraryId: String): List<LibraryItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "podcast", it)
    } as? LibraryShelfPodcastEntity
    return recentShelf?.entities ?: emptyList()
  }

  suspend fun loadRecentShelfEpisodes(libraryId: String): List<LibraryItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "episode", it)
    } as? LibraryShelfEpisodeEntity
    return recentShelf?.entities ?: emptyList()
  }
}
