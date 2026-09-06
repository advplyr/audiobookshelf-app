package com.audiobookshelf.app.player.media3

import android.content.Context
import androidx.media3.common.MediaItem
import com.audiobookshelf.app.data.LibraryAuthorItem
import com.audiobookshelf.app.data.LibraryCollection
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibrarySeriesItem
import com.audiobookshelf.app.data.LibraryShelfAuthorEntity
import com.audiobookshelf.app.data.LibraryShelfBookEntity
import com.audiobookshelf.app.data.LibraryShelfEpisodeEntity
import com.audiobookshelf.app.data.LibraryShelfPodcastEntity
import com.audiobookshelf.app.data.LibraryShelfType
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class Media3BrowseDataLoader(private val mediaManager: MediaManager) {

  private val authorBooksRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
  private val seriesItemsRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
  private val discoveryBooksRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
  private val collectionBooksRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
  private val authorsListRequests: MutableMap<String, Deferred<List<LibraryAuthorItem>>> = mutableMapOf()
  private val seriesListRequests: MutableMap<String, Deferred<List<LibrarySeriesItem>>> = mutableMapOf()
  private val collectionsListRequests: MutableMap<String, Deferred<List<LibraryCollection>>> = mutableMapOf()

  // Persistent caches to avoid repeated server hits during navigation
  private val authorsCache = mutableMapOf<String, List<LibraryAuthorItem>>()
  private val seriesCache = mutableMapOf<String, List<LibrarySeriesItem>>()
  private val collectionsCache = mutableMapOf<String, List<LibraryCollection>>()

  fun clearCache() {
    authorsCache.clear()
    seriesCache.clear()
    collectionsCache.clear()
  }

  private suspend fun <T> withMediaManagerCallback(operation: (callback: (T?) -> Unit) -> Unit): T =
    withTimeout(CALLBACK_TIMEOUT_MS.milliseconds) {
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
    withTimeout(CALLBACK_TIMEOUT_MS.milliseconds) {
      suspendCancellableCoroutine { continuation ->
        operation { result ->
          if (continuation.isActive) {
            continuation.resume(result)
          }
        }
      }
    }

  /** Concurrent browse callbacks for the same key share one network request. */
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

  suspend fun loadLibraryPodcasts(libraryId: String): List<LibraryItem> =
    withMediaManagerCallback {
      mediaManager.loadLibraryPodcasts(libraryId) { result ->
        it(result)
      }
    }

  suspend fun loadAuthorsWithBooks(libraryId: String): List<LibraryAuthorItem> {
    authorsCache[libraryId]?.let { return it }
    return coalescedLoad(authorsListRequests, libraryId) {
      withMediaManagerCallback {
        mediaManager.loadAuthorsWithBooks(libraryId) { result ->
          it(result)
        }
      }
    }.also { authorsCache[libraryId] = it }
  }

  suspend fun loadLibrarySeriesWithAudio(libraryId: String): List<LibrarySeriesItem> {
    seriesCache[libraryId]?.let { return it }
    return coalescedLoad(seriesListRequests, libraryId) {
      withMediaManagerCallback {
        mediaManager.loadLibrarySeriesWithAudio(libraryId) { result ->
          it(result)
        }
      }
    }.also { seriesCache[libraryId] = it }
  }

  suspend fun loadLibraryCollectionsWithAudio(libraryId: String): List<LibraryCollection> {
    collectionsCache[libraryId]?.let { return it }
    return coalescedLoad(collectionsListRequests, libraryId) {
      withMediaManagerCallback {
        mediaManager.loadLibraryCollectionsWithAudio(libraryId) { result ->
          it(result)
        }
      }
    }.also { collectionsCache[libraryId] = it }
  }

  suspend fun loadLibraryDiscoveryBooksWithAudio(libraryId: String): List<LibraryItem> =
    coalescedLoad(discoveryBooksRequests, libraryId) {
      withMediaManagerCallback {
        mediaManager.loadLibraryDiscoveryBooksWithAudio(libraryId) { result ->
          it(result)
        }
      }
    }

  suspend fun loadAuthorBooksWithAudio(libraryId: String, authorId: String): List<LibraryItem> =
    coalescedLoad(authorBooksRequests, "$libraryId:$authorId") {
      withMediaManagerCallback {
        mediaManager.loadAuthorBooksWithAudio(libraryId, authorId) { result ->
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
          it(result)
        }
      }
    }

  suspend fun loadPodcastEpisodes(podcastId: String, context: Context): List<MediaItem> {
    val episodes = mediaManager.loadPodcastEpisodes(podcastId, context) ?: emptyList()
    return episodes.toList()
  }

  suspend fun loadRecentShelfBooks(libraryId: String): List<LibraryItem> =
    loadRecentShelfLibraryItems(libraryId, "book") { (it as? LibraryShelfBookEntity)?.entities }

  suspend fun loadRecentShelfAuthors(libraryId: String): List<LibraryAuthorItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, "authors", it)
    } as? LibraryShelfAuthorEntity
    return recentShelf?.entities?.toList() ?: emptyList()
  }

  suspend fun loadRecentShelfPodcasts(libraryId: String): List<LibraryItem> =
    loadRecentShelfLibraryItems(libraryId, "podcast") { (it as? LibraryShelfPodcastEntity)?.entities }

  suspend fun loadRecentShelfEpisodes(libraryId: String): List<LibraryItem> =
    loadRecentShelfLibraryItems(libraryId, "episode") { (it as? LibraryShelfEpisodeEntity)?.entities }

  /** Adds local IDs so downloaded copies play locally. */
  private suspend fun loadRecentShelfLibraryItems(
    libraryId: String,
    shelfType: String,
    entitiesOf: (LibraryShelfType?) -> List<LibraryItem>?
  ): List<LibraryItem> {
    val recentShelf = withSingleItemCallback {
      mediaManager.getLibraryRecentShelfByType(libraryId, shelfType, it)
    }
    val entities = entitiesOf(recentShelf) ?: return emptyList()
    val localItemsByLId = DeviceManager.dbManager.getLocalLibraryItemsByLId()
    return entities.map { item ->
      item.localLibraryItemId = localItemsByLId[item.id]?.id
      item
    }
  }

  companion object {
    private const val TAG = "M3BrowseDataLoader"
    private const val CALLBACK_TIMEOUT_MS = 15_000L
  }
}
