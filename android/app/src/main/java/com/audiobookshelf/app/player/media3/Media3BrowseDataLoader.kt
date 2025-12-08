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
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Handles data loading operations for Media3 browse tree.
 * Provides suspend functions for fetching library content from MediaManager.
 */
class Media3BrowseDataLoader(private val mediaManager: MediaManager) {

  // In-flight request coalescing maps to avoid duplicate concurrent calls
  private val authorBooksRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
  private val seriesItemsRequests: MutableMap<String, Deferred<List<LibraryItem>>> = mutableMapOf()
  private val collectionBooksRequests: MutableMap<String, Deferred<List<LibraryItem>>> =
    mutableMapOf()
  private val authorsListRequests: MutableMap<String, Deferred<List<LibraryAuthorItem>>> =
    mutableMapOf()
  private val seriesListRequests: MutableMap<String, Deferred<List<LibrarySeriesItem>>> =
    mutableMapOf()
  private val collectionsListRequests: MutableMap<String, Deferred<List<LibraryCollection>>> =
    mutableMapOf()

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

  suspend fun loadAuthorsWithBooks(libraryId: String): List<LibraryAuthorItem> {
    val key = libraryId
    var waiter: Deferred<List<LibraryAuthorItem>>? = null
    val created = CompletableDeferred<List<LibraryAuthorItem>>()
    synchronized(authorsListRequests) {
      val existing = authorsListRequests[key]
      if (existing != null) {
        waiter = existing
      } else {
        authorsListRequests[key] = created
        waiter = created
      }
    }
    if (waiter !== created) {
      return waiter!!.await()
    }
    try {
      val result = withMediaManagerCallback {
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
      val copy = result.toList()
      created.complete(copy)
      return copy
    } catch (e: Exception) {
      created.completeExceptionally(e)
      throw e
    } finally {
      synchronized(authorsListRequests) { authorsListRequests.remove(key) }
    }
  }

  suspend fun loadLibrarySeriesWithAudio(libraryId: String): List<LibrarySeriesItem> {
    val key = libraryId
    var waiter: Deferred<List<LibrarySeriesItem>>? = null
    val created = CompletableDeferred<List<LibrarySeriesItem>>()
    synchronized(seriesListRequests) {
      val existing = seriesListRequests[key]
      if (existing != null) {
        waiter = existing
      } else {
        seriesListRequests[key] = created
        waiter = created
      }
    }
    if (waiter !== created) {
      return waiter!!.await()
    }
    try {
      val result = withMediaManagerCallback {
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
      val copy = result.toList()
      created.complete(copy)
      return copy
    } catch (e: Exception) {
      created.completeExceptionally(e)
      throw e
    } finally {
      synchronized(seriesListRequests) { seriesListRequests.remove(key) }
    }
  }

  suspend fun loadLibraryCollectionsWithAudio(libraryId: String): List<LibraryCollection> {
    val key = libraryId
    var waiter: Deferred<List<LibraryCollection>>? = null
    val created = CompletableDeferred<List<LibraryCollection>>()
    synchronized(collectionsListRequests) {
      val existing = collectionsListRequests[key]
      if (existing != null) {
        waiter = existing
      } else {
        collectionsListRequests[key] = created
        waiter = created
      }
    }
    if (waiter !== created) {
      return waiter!!.await()
    }
    try {
      val result = withMediaManagerCallback {
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
      val copy = result.toList()
      created.complete(copy)
      return copy
    } catch (e: Exception) {
      created.completeExceptionally(e)
      throw e
    } finally {
      synchronized(collectionsListRequests) { collectionsListRequests.remove(key) }
    }
  }

  suspend fun loadLibraryDiscoveryBooksWithAudio(libraryId: String): List<LibraryItem> {
    // Discovery loads can be heavy and duplicated; coalesce per library
    val key = libraryId
    var waiter: Deferred<List<LibraryItem>>? = null
    val created = CompletableDeferred<List<LibraryItem>>()
    synchronized(seriesItemsRequests) {
      val existing = seriesItemsRequests[key]
      if (existing != null) {
        waiter = existing
      } else {
        // reuse seriesItemsRequests map here because discovery and series use similar load patterns
        seriesItemsRequests[key] = created as Deferred<List<LibraryItem>>
        waiter = created
      }
    }
    if (waiter !== created) {
      return waiter!!.await()
    }
    try {
      val result = withMediaManagerCallback {
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
      val copy = result.toList()
      created.complete(copy)
      return copy
    } catch (e: Exception) {
      created.completeExceptionally(e)
      throw e
    } finally {
      synchronized(seriesItemsRequests) { seriesItemsRequests.remove(key) }
    }
  }

  suspend fun loadAuthorBooksWithAudio(libraryId: String, authorId: String): List<LibraryItem> =
    // Coalesce concurrent identical author requests to avoid duplicate network calls
    run {
      val key = "$libraryId:$authorId"
      var waiter: Deferred<List<LibraryItem>>? = null
      val created = CompletableDeferred<List<LibraryItem>>()
      synchronized(authorBooksRequests) {
        val existing = authorBooksRequests[key]
        if (existing != null) {
          waiter = existing
        } else {
          authorBooksRequests[key] = created
          waiter = created
        }
      }
      if (waiter !== created) {
        return waiter!!.await()
      }
      try {
        val result = withMediaManagerCallback {
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
        val copy = result.toList()
        created.complete(copy)
        return copy
      } catch (e: Exception) {
        created.completeExceptionally(e)
        throw e
      } finally {
        synchronized(authorBooksRequests) { authorBooksRequests.remove(key) }
      }
    }

  suspend fun loadLibrarySeriesItemsWithAudio(
    libraryId: String,
    seriesId: String
  ): List<LibraryItem> {
    // coalesce series item requests
    val key = "$libraryId:$seriesId"
    var waiter: Deferred<List<LibraryItem>>? = null
    val created = CompletableDeferred<List<LibraryItem>>()
    synchronized(seriesItemsRequests) {
      val existing = seriesItemsRequests[key]
      if (existing != null) {
        waiter = existing
      } else {
        seriesItemsRequests[key] = created
        waiter = created
      }
    }
    if (waiter !== created) {
      return waiter!!.await()
    }
    try {
      val result = withMediaManagerCallback {
        mediaManager.loadLibrarySeriesItemsWithAudio(libraryId, seriesId) { result ->
          android.util.Log.d(
            "M3BrowseDataLoader",
            "series items loaded library=$libraryId series=$seriesId count=${result.size}"
          )
          it(result)
        }
      }
      val copy = result.toList()
      created.complete(copy)
      return copy
    } catch (e: Exception) {
      created.completeExceptionally(e)
      throw e
    } finally {
      synchronized(seriesItemsRequests) { seriesItemsRequests.remove(key) }
    }
  }

  suspend fun loadLibraryCollectionBooksWithAudio(
    libraryId: String,
    collectionId: String
  ): List<LibraryItem> {
    val key = "$libraryId:$collectionId"
    var waiter: Deferred<List<LibraryItem>>? = null
    val created = CompletableDeferred<List<LibraryItem>>()
    synchronized(collectionBooksRequests) {
      val existing = collectionBooksRequests[key]
      if (existing != null) {
        waiter = existing
      } else {
        collectionBooksRequests[key] = created
        waiter = created
      }
    }
    if (waiter !== created) {
      return waiter!!.await()
    }
    try {
      val result = withMediaManagerCallback {
        mediaManager.loadLibraryCollectionBooksWithAudio(libraryId, collectionId) { result ->
          android.util.Log.d(
            "M3BrowseDataLoader",
            "collection books loaded library=$libraryId collection=$collectionId count=${result.size}"
          )
          it(result)
        }
      }
      val copy = result.toList()
      created.complete(copy)
      return copy
    } catch (e: Exception) {
      created.completeExceptionally(e)
      throw e
    } finally {
      synchronized(collectionBooksRequests) { collectionBooksRequests.remove(key) }
    }
  }

  suspend fun loadPodcastEpisodes(podcastId: String, context: Context): List<MediaItem> {
    val episodes = mediaManager.loadPodcastEpisodes(podcastId, context) ?: emptyList()
    android.util.Log.d(
      "M3BrowseDataLoader",
      "podcast episodes loaded podcast=$podcastId count=${episodes.size}"
    )
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
}
