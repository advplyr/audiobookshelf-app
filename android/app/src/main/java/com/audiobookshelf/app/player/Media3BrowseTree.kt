package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.AndroidAutoBrowseSeriesSequenceOrderSetting
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibraryShelfBookEntity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Handles the creation of a browsable media tree for Media3 clients like Android Auto.
 */
@OptIn(UnstableApi::class)
class Media3BrowseTree(private val context: Context, private var mediaManager: MediaManager) {

  suspend fun getChildren(parentId: String): ImmutableList<MediaItem> {
    Log.d("M3BrowseTree", "getChildren: parentId=$parentId")

    val mediaItems = when {
      parentId == ROOT_ID -> getRootChildren()
      parentId == DOWNLOADS_ID -> buildDownloadsItems()
      parentId == CONTINUE_LISTENING_ID -> buildContinueListeningItems()
      parentId == LIBRARIES_ROOT -> buildLibraryList(LIBRARIES_ROOT)
      parentId == RECENTLY_ROOT -> buildLibraryList(RECENTLY_ROOT)

      parentId.startsWith("__PODCAST__") -> {
        val podcastId = parentId.substringAfter("__PODCAST__")
        buildPodcastEpisodes(podcastId)
      }

      parentId.startsWith(LIBRARIES_ROOT) -> {
        val parts = parentId.split("__")
        val libraryId = parts.getOrNull(2) ?: return ImmutableList.of()
        if (parts.size > 3) {
          buildLibrarySubChildren(parentId)
        } else {
          buildLibraryChildren(libraryId)
        }
      }

      parentId.startsWith(RECENTLY_ROOT) -> {
        val libraryId = parentId.substringAfter("${RECENTLY_ROOT}__")
        buildRecentlyShelfItems(libraryId)
      }

      else -> {
        Log.w("M3BrowseTree", "getChildren: Unhandled parentId: $parentId")
        emptyList()
      }
    }
    return ImmutableList.copyOf(mediaItems)
  }

  fun getRootItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle(context.getString(R.string.app_name))
      .setIsBrowsable(true)
      .setIsPlayable(false)
      .build()
    return MediaItem.Builder()
      .setMediaId(ROOT_ID)
      .setMediaMetadata(metadata)
      .build()
  }

  fun getRootChildren(): List<MediaItem> {
    return buildList {
      if (mediaManager.serverItemsInProgress.isNotEmpty()) {
        add(createBrowsableCategory(CONTINUE_LISTENING_ID, "Continue Listening"))
      }
      if (DeviceManager.hasLocalMedia()) {
        add(createBrowsableCategory(DOWNLOADS_ID, "Downloads"))
      }
      if (mediaManager.serverLibraries.isNotEmpty()) {
        add(createBrowsableCategory(LIBRARIES_ROOT, "Libraries"))
        add(createBrowsableCategory(RECENTLY_ROOT, "Recent"))
      }
    }
  }

  fun buildDownloadsItems(): List<MediaItem> {
    val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
    val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")

    val bookItems = localBooks.mapNotNull { item ->
      if (!item.hasTracks(null)) return@mapNotNull null
      val progress = DeviceManager.dbManager.getLocalMediaProgress(item.id)
      item.getMediaItem(progress, context)
    }

    val podcastItems = localPodcasts.map { item ->
      val progress = DeviceManager.dbManager.getLocalMediaProgress(item.id)
      item.getMediaItem(progress, context)
    }
    return bookItems + podcastItems
  }

  fun buildContinueListeningItems(): List<MediaItem> {
    return mediaManager.serverItemsInProgress.mapNotNull { itemInProgress ->

      val libraryItem = itemInProgress.libraryItemWrapper as? LibraryItem ?: return@mapNotNull null
      val progress =
        mediaManager.serverUserMediaProgress.find { it.libraryItemId == libraryItem.id && it.episodeId == itemInProgress.episode?.id }
      itemInProgress.episode?.getMediaItem(libraryItem, progress, context)
        ?: libraryItem.getMediaItem(progress, context)
    }
  }

  private suspend fun buildLibraryChildren(libraryId: String): List<MediaItem> =
    suspendCancellableCoroutine { continuation ->
      val selectedLibrary = mediaManager.getLibrary(libraryId)
      if (selectedLibrary?.mediaType == "podcast") {
        mediaManager.loadLibraryPodcasts(libraryId) { podcasts ->
          val mediaItems = podcasts?.map { it.getMediaItem(null, context) } ?: emptyList()
          if (continuation.isActive) continuation.resume(mediaItems)
        }
      } else {
        val subFolders = buildList {
          add(createBrowsableCategory("__LIBRARY__${libraryId}__AUTHORS", "Authors"))
          add(createBrowsableCategory("__LIBRARY__${libraryId}__SERIES_LIST", "Series"))
          add(createBrowsableCategory("__LIBRARY__${libraryId}__COLLECTIONS", "Collections"))
          if (mediaManager.getHasDiscovery(libraryId)) {
            add(createBrowsableCategory("__LIBRARY__${libraryId}__DISCOVERY", "Discovery"))
          }
        }
        if (continuation.isActive) continuation.resume(subFolders)
      }
    }

  private suspend fun buildLibrarySubChildren(parentId: String): List<MediaItem> =
    suspendCancellableCoroutine { continuation ->
      val parts = parentId.split("__")
      if (parts.size < 4) {
        continuation.resume(emptyList())
        return@suspendCancellableCoroutine
      }

      val libraryId = parts[2]
      val browseType = parts[3]

      when (browseType) {
        "AUTHORS" -> {
          mediaManager.loadAuthorsWithBooks(libraryId) { authors ->
            val mediaItems = authors.map { author ->
              buildMediaItem(
                mediaId = "__LIBRARY__${libraryId}__AUTHOR__${author.id}",
                title = author.name,
                subtitle = "${author.bookCount} books",
                artworkUri = getUriToAbsIconDrawable(context, "person"),
                isBrowsable = true, mimeType = null
              )
            }
            continuation.resume(mediaItems)
          }
        }

        "SERIES_LIST" -> {
          mediaManager.loadLibrarySeriesWithAudio(libraryId) { series ->
            val mediaItems = series.map { seriesItem ->
              buildMediaItem(
                mediaId = "__LIBRARY__${libraryId}__SERIES__${seriesItem.id}",
                title = seriesItem.title,
                subtitle = "${seriesItem.audiobookCount} books",
                artworkUri = getUriToAbsIconDrawable(context, "bookshelf"),
                isBrowsable = true, mimeType = null
              )
            }
            continuation.resume(mediaItems)
          }
        }

        "COLLECTIONS" -> {
          mediaManager.loadLibraryCollectionsWithAudio(libraryId) { collections ->
            val mediaItems = collections.map { collection ->
              buildMediaItem(
                mediaId = "__LIBRARY__${libraryId}__COLLECTION__${collection.id}",
                title = collection.name,
                subtitle = "${collection.audiobookCount} books",
                artworkUri = getUriToAbsIconDrawable(context, "list-box"),
                isBrowsable = true, mimeType = null
              )
            }
            continuation.resume(mediaItems)
          }
        }

        "DISCOVERY" -> {
          mediaManager.loadLibraryDiscoveryBooksWithAudio(libraryId) { books ->
            continuation.resume(books.map { book -> libraryItemToMediaItem(book, parentId) })
          }
        }

        "AUTHOR" -> {
          val authorId = parts.getOrNull(4)
            ?: return@suspendCancellableCoroutine continuation.resume(emptyList())
          mediaManager.loadAuthorBooksWithAudio(libraryId, authorId) { books ->
            continuation.resume(books.map { book -> libraryItemToMediaItem(book, parentId) })
          }
        }

        "SERIES" -> {
          val seriesId = parts.getOrNull(4)
            ?: return@suspendCancellableCoroutine continuation.resume(emptyList())
          mediaManager.loadLibrarySeriesItemsWithAudio(libraryId, seriesId) { books ->
            continuation.resume(books.map { book -> libraryItemToMediaItem(book, parentId) })
          }
        }

        "COLLECTION" -> {
          val collectionId = parts.getOrNull(4)
            ?: return@suspendCancellableCoroutine continuation.resume(emptyList())
          mediaManager.loadLibraryCollectionBooksWithAudio(libraryId, collectionId) { books ->
            continuation.resume(books.map { book -> libraryItemToMediaItem(book, parentId) })
          }
        }

        else -> continuation.resume(emptyList())
      }
    }

  suspend fun buildPodcastEpisodes(podcastId: String): List<MediaItem> {
    return mediaManager.loadPodcastEpisodes(podcastId, context) ?: emptyList()
  }

  private suspend fun buildRecentlyShelfItems(libraryId: String): List<MediaItem> =
    suspendCancellableCoroutine { continuation ->
      mediaManager.getLibraryRecentShelfByType(libraryId, "book") { shelf ->
        val bookShelf = shelf as? LibraryShelfBookEntity
        val mediaItems = bookShelf?.entities?.map { book ->
          libraryItemToMediaItem(book, "__RECENTLY__${libraryId}")
        } ?: emptyList()
        continuation.resume(mediaItems)
      }
    }

  private fun buildMediaItem(
    mediaId: String, title: String, subtitle: String?, artworkUri: Uri?,
    isBrowsable: Boolean, mimeType: String?, extras: Bundle? = null
  ): MediaItem {
    val metadataBuilder = MediaMetadata.Builder()
      .setTitle(title).setArtist(subtitle).setArtworkUri(artworkUri)
      .setIsBrowsable(isBrowsable).setIsPlayable(!isBrowsable)
    extras?.let { metadataBuilder.setExtras(it) }

    val mediaItemBuilder =
      MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadataBuilder.build())
    if (!mimeType.isNullOrBlank()) {
      mediaItemBuilder.setMimeType(mimeType)
    }
    return mediaItemBuilder.build()
  }

  private fun buildLibraryList(prefix: String): List<MediaItem> {
    val libraries = mediaManager.serverLibraries
      .filter { (it.stats?.numAudioFiles ?: 0) > 0 }
      .sortedBy { it.name }
    return if (shouldGroupLetters(libraries)) {
      groupByLetter(libraries, prefix)
    } else {
      libraries.map { library -> libraryToMediaItem(library, prefix) }
    }
  }

  private fun libraryItemToMediaItem(item: LibraryItem, parentId: String): MediaItem {
    val isBrowsable = item.mediaType == "podcast" || item.collapsedSeries != null
    val mediaId = if (isBrowsable) "${parentId}_${item.id}" else item.id

    return buildMediaItem(
      mediaId = mediaId,
      title = item.media.metadata.title,
      subtitle = item.media.metadata.getAuthorDisplayName(),
      artworkUri = item.getCoverUri(),
      isBrowsable = isBrowsable,
      mimeType = if (isBrowsable) null else item.media.getAudioTracks().firstOrNull()?.mimeType
    )
  }

  private fun libraryToMediaItem(library: Library, prefix: String): MediaItem {
    val mediaId = "${prefix}_${library.id}"
    val metadata = MediaMetadata.Builder()
      .setTitle(library.name)
      .setArtist("${library.stats?.numAudioFiles ?: 0} files")
      .setArtworkUri(getUriToAbsIconDrawable(context, library.icon))
      .setIsBrowsable(true)
      .setIsPlayable(false)
      .build()
    return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
  }

  private fun createBrowsableCategory(id: String, title: String): MediaItem {
    return buildMediaItem(id, title, null, null, true, null)
  }

  private fun shouldGroupLetters(libraries: List<Library>): Boolean {
    val limit = DeviceManager.deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping ?: 100
    return libraries.size > limit && libraries.groupBy {
      it.name.firstOrNull()?.uppercaseChar() ?: '#'
    }.size > 1
  }

  private fun groupByLetter(libraries: List<Library>, prefix: String): List<MediaItem> {
    val limit = DeviceManager.deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder
      ?: AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
    val grouped = libraries.groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
    val sortedLetters = grouped.keys.sorted()
    val finalLetters =
      if (limit == AndroidAutoBrowseSeriesSequenceOrderSetting.DESC) sortedLetters.reversed() else sortedLetters
    return finalLetters.map { letter ->
      buildMediaItem(
        mediaId = "${prefix}__${letter}",
        title = letter.toString(),
        subtitle = "${grouped[letter]?.size ?: 0} libraries",
        artworkUri = getUriToAbsIconDrawable(context, "library"),
        isBrowsable = true,
        mimeType = null
      )
    }
  }

  companion object {
    const val ROOT_ID = "__ROOT__"
    const val DOWNLOADS_ID = "__DOWNLOADS__"
    const val CONTINUE_LISTENING_ID = "__CONTINUE_LISTENING__"
    const val LIBRARIES_ROOT = "__LIBRARIES__"
    const val RECENTLY_ROOT = "__RECENTLY__"
  }
}
