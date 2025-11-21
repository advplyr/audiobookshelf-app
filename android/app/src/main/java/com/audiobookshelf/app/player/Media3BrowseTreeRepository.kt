package com.audiobookshelf.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import com.audiobookshelf.app.data.LibraryAuthorItem
import com.audiobookshelf.app.data.LibraryCollection
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibrarySeriesItem
import com.audiobookshelf.app.data.LibraryShelfBookEntity
import com.audiobookshelf.app.data.LibraryShelfAuthorEntity
import com.audiobookshelf.app.data.LibraryShelfEpisodeEntity
import com.audiobookshelf.app.data.LibraryShelfType
import com.audiobookshelf.app.data.LibraryShelfPodcastEntity
import com.audiobookshelf.app.media.MediaManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class Media3BrowseTreeRepository(private val mediaManager: MediaManager) {

  // FIX: Change (T) to (T?) in the callback definition
  private suspend fun <T> withMediaManagerCallback(block: (callback: (T?) -> Unit) -> Unit): T = suspendCancellableCoroutine {
      continuation ->
    block { result ->
      if (continuation.isActive) {
        // If the underlying API returns null, provide a default empty list.
        // This makes the result non-nullable, matching the function's return type.
        val nonNullResult = result ?: (emptyList<Any>() as T)
        continuation.resume(nonNullResult)
      }
    }
  }
  private suspend fun <T> withSingleItemCallback(block: (callback: (T?) -> Unit) -> Unit): T? = suspendCancellableCoroutine { continuation ->
    block { result ->
      if (continuation.isActive) {
        continuation.resume(result)
      }
    }
  }

  suspend fun loadLibraryPodcasts(libraryId: String): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadLibraryPodcasts(libraryId) { items ->
      android.util.Log.d("M3BrowseRepo", "podcasts loaded library=$libraryId count=${items?.size ?: 0}")
      it(items)
    }
  }

  suspend fun loadAuthorsWithBooks(libraryId: String): List<LibraryAuthorItem> = withMediaManagerCallback {
    mediaManager.loadAuthorsWithBooks(libraryId) { items ->
      android.util.Log.d("M3BrowseRepo", "authors loaded library=$libraryId count=${items.size}")
      it(items)
    }
  }

  suspend fun loadLibrarySeriesWithAudio(libraryId: String): List<LibrarySeriesItem> = withMediaManagerCallback {
    mediaManager.loadLibrarySeriesWithAudio(libraryId) { items ->
      android.util.Log.d("M3BrowseRepo", "series loaded library=$libraryId count=${items.size}")
      it(items)
    }
  }

  suspend fun loadLibraryCollectionsWithAudio(libraryId: String): List<LibraryCollection> = withMediaManagerCallback {
    mediaManager.loadLibraryCollectionsWithAudio(libraryId) { items ->
      android.util.Log.d("M3BrowseRepo", "collections loaded library=$libraryId count=${items.size}")
      it(items)
    }
  }

  suspend fun loadLibraryDiscoveryBooksWithAudio(libraryId: String): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadLibraryDiscoveryBooksWithAudio(libraryId) { items ->
      android.util.Log.d("M3BrowseRepo", "discovery loaded library=$libraryId count=${items.size}")
      it(items)
    }
  }

  suspend fun loadAuthorBooksWithAudio(libraryId: String, authorId: String): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadAuthorBooksWithAudio(libraryId, authorId) { items ->
      android.util.Log.d("M3BrowseRepo", "author books loaded library=$libraryId author=$authorId count=${items.size}")
      it(items)
    }
  }

  suspend fun loadLibrarySeriesItemsWithAudio(libraryId: String, seriesId: String): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadLibrarySeriesItemsWithAudio(libraryId, seriesId) { items ->
      android.util.Log.d("M3BrowseRepo", "series items loaded library=$libraryId series=$seriesId count=${items.size}")
      it(items)
    }
  }

  suspend fun loadLibraryCollectionBooksWithAudio(libraryId: String, collectionId: String): List<LibraryItem> = withMediaManagerCallback {
    mediaManager.loadLibraryCollectionBooksWithAudio(libraryId, collectionId) { items ->
      android.util.Log.d("M3BrowseRepo", "collection books loaded library=$libraryId collection=$collectionId count=${items.size}")
      it(items)
    }
  }

  suspend fun loadPodcastEpisodes(podcastId: String, context: Context): List<MediaItem> {
    val episodes = mediaManager.loadPodcastEpisodes(podcastId, context) ?: emptyList()
    android.util.Log.d("M3BrowseRepo", "podcast episodes loaded podcast=$podcastId count=${episodes.size}")
    return episodes
  }

  suspend fun loadRecentShelfBooks(libraryId: String): List<LibraryItem> {
    val shelf = withSingleItemCallback {
        mediaManager.getLibraryRecentShelfByType(libraryId, "book", it)
    } as? LibraryShelfBookEntity
    return shelf?.entities ?: emptyList()
  }

  suspend fun loadRecentShelfAuthors(libraryId: String): List<LibraryAuthorItem> {
    val shelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "authors", it)
    } as? LibraryShelfAuthorEntity
    return shelf?.entities ?: emptyList()
  }

  suspend fun loadRecentShelfPodcasts(libraryId: String): List<LibraryItem> {
    val shelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "podcast", it)
    } as? LibraryShelfPodcastEntity
    return shelf?.entities ?: emptyList()
  }

  suspend fun loadRecentShelfEpisodes(libraryId: String): List<LibraryItem> {
    val shelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "episode", it)
    } as? LibraryShelfEpisodeEntity
    return shelf?.entities ?: emptyList()
  }
}
