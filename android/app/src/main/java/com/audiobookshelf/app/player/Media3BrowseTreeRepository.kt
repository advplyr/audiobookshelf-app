package com.audiobookshelf.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import com.audiobookshelf.app.data.LibraryAuthorItem
import com.audiobookshelf.app.data.LibraryCollection
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibrarySeriesItem
import com.audiobookshelf.app.data.LibraryShelfBookEntity
import com.audiobookshelf.app.media.MediaManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Keeps Media3 browse-tree data loading isolated from tree construction so that
 * the tree can focus solely on building MediaItems for Android Auto's pull flow.
 */
class Media3BrowseTreeRepository(private val mediaManager: MediaManager) {

  suspend fun loadLibraryPodcasts(libraryId: String): List<LibraryItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadLibraryPodcasts(libraryId) { podcasts ->
        if (!continuation.isActive) return@loadLibraryPodcasts
        continuation.resume(podcasts ?: emptyList())
      }
    }

  suspend fun loadAuthorsWithBooks(libraryId: String): List<LibraryAuthorItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadAuthorsWithBooks(libraryId) { authors ->
        if (!continuation.isActive) return@loadAuthorsWithBooks
        continuation.resume(authors)
      }
    }

  suspend fun loadLibrarySeriesWithAudio(libraryId: String): List<LibrarySeriesItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadLibrarySeriesWithAudio(libraryId) { series ->
        if (!continuation.isActive) return@loadLibrarySeriesWithAudio
        continuation.resume(series)
      }
    }

  suspend fun loadLibraryCollectionsWithAudio(libraryId: String): List<LibraryCollection> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadLibraryCollectionsWithAudio(libraryId) { collections ->
        if (!continuation.isActive) return@loadLibraryCollectionsWithAudio
        continuation.resume(collections)
      }
    }

  suspend fun loadLibraryDiscoveryBooksWithAudio(libraryId: String): List<LibraryItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadLibraryDiscoveryBooksWithAudio(libraryId) { books ->
        if (!continuation.isActive) return@loadLibraryDiscoveryBooksWithAudio
        continuation.resume(books)
      }
    }

  suspend fun loadAuthorBooksWithAudio(libraryId: String, authorId: String): List<LibraryItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadAuthorBooksWithAudio(libraryId, authorId) { books ->
        if (!continuation.isActive) return@loadAuthorBooksWithAudio
        continuation.resume(books)
      }
    }

  suspend fun loadLibrarySeriesItemsWithAudio(
    libraryId: String,
    seriesId: String
  ): List<LibraryItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadLibrarySeriesItemsWithAudio(libraryId, seriesId) { books ->
        if (!continuation.isActive) return@loadLibrarySeriesItemsWithAudio
        continuation.resume(books)
      }
    }

  suspend fun loadLibraryCollectionBooksWithAudio(
    libraryId: String,
    collectionId: String
  ): List<LibraryItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.loadLibraryCollectionBooksWithAudio(libraryId, collectionId) { books ->
        if (!continuation.isActive) return@loadLibraryCollectionBooksWithAudio
        continuation.resume(books)
      }
    }

  suspend fun loadRecentShelfBooks(libraryId: String): List<LibraryItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.getLibraryRecentShelfByType(libraryId, "book") { shelf ->
        if (!continuation.isActive) return@getLibraryRecentShelfByType
        val bookShelf = shelf as? LibraryShelfBookEntity
        continuation.resume(bookShelf?.entities ?: emptyList())
      }
    }

  suspend fun loadPodcastEpisodes(podcastId: String, context: Context): List<MediaItem> =
    mediaManager.loadPodcastEpisodes(podcastId, context) ?: emptyList()
}
