package com.audiobookshelf.app.player.media3

import android.content.Context
import android.util.Log
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
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Handles data loading operations for Media3 browse tree.
 * Provides suspend functions for fetching library content from MediaManager.
 */
class Media3BrowseDataLoader(private val mediaManager: MediaManager) {

  private val authorBooksRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
  private val seriesItemsRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
    private val collectionBooksRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
    private val authorsListRequests: MutableMap<String, Deferred<List<LibraryAuthorItem>>> = mutableMapOf()
    private val seriesListRequests: MutableMap<String, Deferred<List<LibrarySeriesItem>>> = mutableMapOf()
    private val collectionsListRequests: MutableMap<String, Deferred<List<LibraryCollection>>> = mutableMapOf()

  private suspend fun <T> withMediaManagerCallback(operation: (callback: (T?) -> Unit) -> Unit): T =
      withTimeout(CALLBACK_TIMEOUT_MS) {
          suspendCancellableCoroutine { continuation ->
              operation { result ->
                  if (continuation.isActive) {
                      @Suppress("UNCHECKED_CAST")
                      val nonNullResult = result ?: (emptyList<Any>() as T)
                      continuation.resume(nonNullResult)
                  }
        }
      }
    }

  private suspend fun <T> withSingleItemCallback(operation: (callback: (T?) -> Unit) -> Unit): T? =
      withTimeout(CALLBACK_TIMEOUT_MS) {
          suspendCancellableCoroutine { continuation ->
              operation { result ->
                  if (continuation.isActive) {
                      continuation.resume(result)
                  }
              }
          }
      }

    /**
     * Coalesces concurrent requests for the same key: the first caller performs the load,
     * subsequent callers for the same key await the same result.
     */
    private suspend fun <T> coalescedLoad(
        cache: MutableMap<String, Deferred<T>>,
        key: String,
        load: suspend () -> T
    ): T {
        val created = CompletableDeferred<T>()
        val waiter: Deferred<T>
        synchronized(cache) {
            val existing = cache[key]
      if (existing != null) {
        waiter = existing
      } else {
          cache[key] = created
        waiter = created
      }
    }
    if (waiter !== created) {
        return waiter.await()
    }
    try {
        val result = load()
        val copy = if (result is List<*>) {
            @Suppress("UNCHECKED_CAST")
            (result as List<*>).toList() as T
        } else {
            result
        }
      created.complete(copy)
      return copy
    } catch (e: Exception) {
      created.completeExceptionally(e)
      throw e
    } finally {
        synchronized(cache) { cache.remove(key) }
    }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    suspend fun loadLibraryPodcasts(libraryId: String): List<LibraryItem> =
        withMediaManagerCallback {
            mediaManager.loadLibraryPodcasts(libraryId) { result ->
                debugLog("podcasts loaded library=$libraryId count=${result?.size ?: 0}")
                it(result)
    }
  }

    suspend fun loadAuthorsWithBooks(libraryId: String): List<LibraryAuthorItem> =
        coalescedLoad(authorsListRequests, libraryId) {
            withMediaManagerCallback {
                mediaManager.loadAuthorsWithBooks(libraryId) { result ->
                    debugLog("authors loaded library=$libraryId count=${result.size}")
                    it(result)
                }
            }
    }

    suspend fun loadLibrarySeriesWithAudio(libraryId: String): List<LibrarySeriesItem> =
        coalescedLoad(seriesListRequests, libraryId) {
            withMediaManagerCallback {
        mediaManager.loadLibrarySeriesWithAudio(libraryId) { result ->
            debugLog("series loaded library=$libraryId count=${result.size}")
          it(result)
        }
      }
        }

    suspend fun loadLibraryCollectionsWithAudio(libraryId: String): List<LibraryCollection> =
        coalescedLoad(collectionsListRequests, libraryId) {
            withMediaManagerCallback {
        mediaManager.loadLibraryCollectionsWithAudio(libraryId) { result ->
            debugLog("collections loaded library=$libraryId count=${result.size}")
          it(result)
        }
      }
        }

    suspend fun loadLibraryDiscoveryBooksWithAudio(libraryId: String): List<LibraryItem> =
        coalescedLoad(seriesItemsRequests, libraryId) {
            withMediaManagerCallback {
        mediaManager.loadLibraryDiscoveryBooksWithAudio(libraryId) { result ->
            debugLog("discovery loaded library=$libraryId count=${result.size}")
          it(result)
        }
      }
        }

  suspend fun loadAuthorBooksWithAudio(libraryId: String, authorId: String): List<LibraryItem> =
      coalescedLoad(authorBooksRequests, "$libraryId:$authorId") {
          withMediaManagerCallback {
              mediaManager.loadAuthorBooksWithAudio(libraryId, authorId) { result ->
                  debugLog("author books loaded library=$libraryId author=$authorId count=${result.size}")
                  it(result)
              }
      }
    }

  suspend fun loadLibrarySeriesItemsWithAudio(
    libraryId: String,
    seriesId: String
  ): List<LibraryItem> =
      coalescedLoad(seriesItemsRequests, "$libraryId:$seriesId") {
          withMediaManagerCallback {
        mediaManager.loadLibrarySeriesItemsWithAudio(libraryId, seriesId) { result ->
            debugLog("series items loaded library=$libraryId series=$seriesId count=${result.size}")
          it(result)
        }
      }
      }

  suspend fun loadLibraryCollectionBooksWithAudio(
    libraryId: String,
    collectionId: String
  ): List<LibraryItem> =
      coalescedLoad(collectionBooksRequests, "$libraryId:$collectionId") {
          withMediaManagerCallback {
        mediaManager.loadLibraryCollectionBooksWithAudio(libraryId, collectionId) { result ->
            debugLog("collection books loaded library=$libraryId collection=$collectionId count=${result.size}")
          it(result)
        }
      }
      }

  suspend fun loadPodcastEpisodes(podcastId: String, context: Context): List<MediaItem> {
    val episodes = mediaManager.loadPodcastEpisodes(podcastId, context) ?: emptyList()
      debugLog("podcast episodes loaded podcast=$podcastId count=${episodes.size}")
    return episodes.toList()
  }

  suspend fun loadRecentShelfBooks(libraryId: String): List<LibraryItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "book", it)
    } as? LibraryShelfBookEntity
    return recentShelf?.entities?.map { item ->
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(item.id)
      item.localLibraryItemId = localLibraryItem?.id
      item
    } ?: emptyList()
  }

  suspend fun loadRecentShelfAuthors(libraryId: String): List<LibraryAuthorItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "authors", it)
    } as? LibraryShelfAuthorEntity
    return recentShelf?.entities?.toList() ?: emptyList()
  }

  suspend fun loadRecentShelfPodcasts(libraryId: String): List<LibraryItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "podcast", it)
    } as? LibraryShelfPodcastEntity
    return recentShelf?.entities?.map { item ->
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(item.id)
      item.localLibraryItemId = localLibraryItem?.id
      item
    } ?: emptyList()
  }

  suspend fun loadRecentShelfEpisodes(libraryId: String): List<LibraryItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "episode", it)
    } as? LibraryShelfEpisodeEntity
    return recentShelf?.entities?.map { item ->
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(item.id)
      item.localLibraryItemId = localLibraryItem?.id
      item
    } ?: emptyList()
  }

    companion object {
        private const val TAG = "M3BrowseDataLoader"
        private const val CALLBACK_TIMEOUT_MS = 15_000L
    }
}
