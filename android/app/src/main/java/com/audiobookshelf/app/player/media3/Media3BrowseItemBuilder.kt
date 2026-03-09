package com.audiobookshelf.app.player.media3

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.AndroidAutoBrowseSeriesSequenceOrderSetting
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryAuthorItem
import com.audiobookshelf.app.data.LibraryCollection
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibrarySeriesItem
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.google.common.collect.ImmutableList
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "M3BrowseItemBuilder"
private const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
private const val URI_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

/**
 * Handles building MediaItems for the Media3 browse tree.
 */
@UnstableApi
class Media3BrowseItemBuilder(
  private val context: Context,
  private val mediaManager: MediaManager,
  private val browseDataLoader: Media3BrowseDataLoader
) {

  companion object {
      const val DOWNLOADS_ID = Media3BrowseTree.DOWNLOADS_ID
      const val CONTINUE_LISTENING_ID = Media3BrowseTree.CONTINUE_LISTENING_ID
      const val LIBRARIES_ROOT = Media3BrowseTree.LIBRARIES_ROOT
      const val RECENTLY_ROOT = Media3BrowseTree.RECENTLY_ROOT
  }

  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  fun createBrowsableCategory(mediaId: String, title: String, iconName: String): MediaItem {
    val mediaMetadata = MediaMetadata.Builder()
      .setTitle(title)
      .setArtworkUri(getUriToAbsIconDrawable(context, iconName))
      .setIsBrowsable(true)
      .setIsPlayable(false)
      .build()
    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(mediaMetadata)
      .build()
  }

  fun buildMediaItem(
    mediaId: String,
    title: String,
    subtitle: String,
    artworkUri: Uri,
    isBrowsable: Boolean,
    mimeType: String?
  ): MediaItem {
    val mediaMetadata = MediaMetadata.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setArtworkUri(artworkUri)
      .setIsBrowsable(isBrowsable)
      .setIsPlayable(!isBrowsable)
      .build()
    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(mediaMetadata)
      .setMimeType(mimeType)
      .build()
  }

  fun libraryToMediaItem(library: Library, parentId: String): MediaItem {
    val mediaId = "${parentId}_${library.id}"
    val iconName = library.icon.takeIf { it.isNotBlank() } ?: when (library.mediaType) {
      "book" -> "book-open-page-variant"
      "podcast" -> "podcast"
      else -> "library"
    }
    return buildMediaItem(
      mediaId,
      library.name,
      "${library.stats?.totalItems ?: 0} items",
      getUriToAbsIconDrawable(context, iconName),
      true,
      null
    )
  }

  fun getRootChildren(): List<MediaItem> {
    if (!mediaManager.isAutoDataLoaded) return emptyList()
    return buildList {
      add(createBrowsableCategory(CONTINUE_LISTENING_ID, "Continue Listening", "music"))
      if (mediaManager.serverLibraries.isNotEmpty()) {
        add(createBrowsableCategory(RECENTLY_ROOT, "Recent", "clock"))
        add(createBrowsableCategory(LIBRARIES_ROOT, "Libraries", "library-folder"))
      }
      add(createBrowsableCategory(DOWNLOADS_ID, "Downloads", "downloads"))
    }
  }

  fun buildDownloadsItems(): List<MediaItem> {
      Log.d(TAG, "buildDownloadsItems: start")
    val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
    val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")
      Log.d(TAG, "buildDownloadsItems: localBooks ${localBooks.size}, localPodcasts ${localPodcasts.size}")

    val bookItems = localBooks.mapNotNull { libraryItem ->
      if (!libraryItem.hasTracks(null)) return@mapNotNull null
      val progress = DeviceManager.dbManager.getLocalMediaProgress(libraryItem.id)
      libraryItem.getMediaItem(progress, context).withDownloadArtwork(libraryItem, context)
    }

    val podcastItems = localPodcasts.map { libraryItem ->
      val progress = DeviceManager.dbManager.getLocalMediaProgress(libraryItem.id)
      libraryItem.getMediaItem(progress, context).withDownloadArtwork(libraryItem, context)
    }
      Log.d(TAG, "buildDownloadsItems: bookItems ${bookItems.size}, podcastItems ${podcastItems.size}")
    return bookItems + podcastItems
  }

  fun buildContinueListeningItems(): List<MediaItem> {
    return mediaManager.serverItemsInProgress.mapNotNull { inProgressItem ->
      val libraryItem = inProgressItem.libraryItemWrapper as? LibraryItem ?: return@mapNotNull null
      val progress =
        mediaManager.serverUserMediaProgress.find { it.libraryItemId == libraryItem.id && it.episodeId == inProgressItem.episode?.id }
      inProgressItem.episode?.getMediaItem(libraryItem, progress, context)
        ?: libraryItem.getMediaItem(progress, context)
    }
  }

  fun buildLibraryList(parentId: String): List<MediaItem> {
    val libraries = mediaManager.serverLibraries
      .filter { (it.stats?.numAudioFiles ?: 0) > 0 }
      .sortedBy { it.name }
    return if (shouldGroupLetters(libraries)) {
      groupByLetter(libraries, parentId)
    } else {
        libraries.map { library -> libraryToMediaItem(library, parentId) }
    }
  }

  private fun shouldGroupLetters(libraries: List<Library>): Boolean {
    val groupingThreshold =
      DeviceManager.deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping ?: 100
    return libraries.size > groupingThreshold && libraries.groupBy {
      it.name.firstOrNull()?.uppercaseChar() ?: '#'
    }.size > 1
  }

  private fun groupByLetter(libraries: List<Library>, prefix: String): List<MediaItem> {
      val sortOrder =
      DeviceManager.deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder
        ?: AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
    val grouped = libraries.groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
    val sortedLetters = grouped.keys.sorted()
    val finalLetters =
        if (sortOrder==AndroidAutoBrowseSeriesSequenceOrderSetting.DESC) sortedLetters.reversed() else sortedLetters
    return finalLetters.map { letter ->
      buildMediaItem(
        mediaId = "${prefix}__${letter}",
        title = letter.toString(),
        subtitle = "${grouped[letter]?.size ?: 0} libraries",
        artworkUri = getUriToAbsIconDrawable(context, "library"),
        isBrowsable = true, mimeType = null
      )
    }
  }

  suspend fun buildLibraryChildren(libraryId: String): List<MediaItem> {
    val library = mediaManager.getLibrary(libraryId) ?: return emptyList()
    return when (library.mediaType) {
      "book" -> buildBookLibraryChildren(libraryId)
      "podcast" -> buildPodcastLibraryChildren(libraryId)
      else -> emptyList()
    }
  }

  private fun buildBookLibraryChildren(libraryId: String): List<MediaItem> {
    return listOf(
      createBrowsableCategory("__LIBRARY__${libraryId}__AUTHORS", "Authors", "authors"),
      createBrowsableCategory("__LIBRARY__${libraryId}__SERIES_LIST", "Series", "books-2"),
      createBrowsableCategory("__LIBRARY__${libraryId}__COLLECTIONS", "Collections", "books-1"),
      createBrowsableCategory("__LIBRARY__${libraryId}__DISCOVERY", "Discovery", "rocket")
    )
  }

  private suspend fun buildPodcastLibraryChildren(libraryId: String): List<MediaItem> {
    val recentPodcasts = browseDataLoader.loadLibraryPodcasts(libraryId)
    return recentPodcasts.map { podcast ->
      val artworkUri = resolveLocalCoverUri(podcast) ?: podcast.getCoverUri()
      buildMediaItem(
        mediaId = "__PODCAST__${podcast.id}",
        title = podcast.media.metadata.title,
        subtitle = podcast.media.metadata.getAuthorDisplayName(),
        artworkUri = artworkUri,
        isBrowsable = true,
        mimeType = null
      )
    }
  }

  suspend fun buildLibrarySubChildren(parentId: String): List<MediaItem> {
      Log.d(TAG, "buildLibrarySubChildren parent=$parentId")
    val mediaIdParts = parentId.split("__")
    if (mediaIdParts.size < 4) return emptyList()

    val libraryId = mediaIdParts[2]
    val librarySubBrowseType = mediaIdParts[3]

    return when (librarySubBrowseType) {
      "AUTHORS" -> buildAuthorsList(libraryId, mediaIdParts)
      "SERIES_LIST" -> buildSeriesList(libraryId)
      "COLLECTIONS" -> buildCollectionsList(libraryId)
      "DISCOVERY" -> browseDataLoader.loadLibraryDiscoveryBooksWithAudio(libraryId)
        .map { book -> libraryItemToMediaItem(book, parentId) }
      "AUTHOR" -> {
        val authorId = mediaIdParts.getOrNull(4) ?: return emptyList()
        browseDataLoader.loadAuthorBooksWithAudio(libraryId, authorId)
          .map { book -> libraryItemToMediaItem(book, parentId) }
      }
      "SERIES" -> {
        val seriesId = mediaIdParts.getOrNull(4) ?: return emptyList()
        browseDataLoader.loadLibrarySeriesItemsWithAudio(libraryId, seriesId)
          .map { book -> libraryItemToMediaItem(book, parentId) }
      }
      "COLLECTION" -> {
        val collectionId = mediaIdParts.getOrNull(4) ?: return emptyList()
        browseDataLoader.loadLibraryCollectionBooksWithAudio(libraryId, collectionId)
          .map { book -> libraryItemToMediaItem(book, parentId) }
      }
      else -> emptyList()
    }
  }

  private suspend fun buildAuthorsList(
    libraryId: String,
    mediaIdParts: List<String>
  ): List<MediaItem> {
    val libraryAuthors: List<LibraryAuthorItem> = browseDataLoader.loadAuthorsWithBooks(libraryId)
    val letter = mediaIdParts.getOrNull(4)?.firstOrNull()?.uppercaseChar()
    return if (letter == null) {
      buildAuthorIndex(libraryId, libraryAuthors)
    } else {
      buildAuthorLetterChildren(libraryAuthors, letter)
    }
  }

  private val seriesViewCache = mutableMapOf<String, List<MediaItem>>()

  fun clearSeriesViewCache() {
    seriesViewCache.clear()
  }

  private suspend fun buildSeriesList(libraryId: String): List<MediaItem> {
    seriesViewCache[libraryId]?.let { return it }
    val librarySeriesItems = orderSeries(browseDataLoader.loadLibrarySeriesWithAudio(libraryId))
    return librarySeriesItems.map { librarySeries ->
      buildMediaItem(
        "__LIBRARY__${libraryId}__SERIES__${librarySeries.id}",
        librarySeries.title,
        "${librarySeries.audiobookCount} books",
        getUriToAbsIconDrawable(context, "bookshelf"),
        true,
        null
      )
    }.also { seriesViewCache[libraryId] = it }
  }

  private suspend fun buildCollectionsList(libraryId: String): List<MediaItem> {
    val libraryCollections: List<LibraryCollection> =
      browseDataLoader.loadLibraryCollectionsWithAudio(libraryId)
    return libraryCollections.map { collection ->
      buildMediaItem(
        "__LIBRARY__${libraryId}__COLLECTION__${collection.id}",
        collection.name,
        "${collection.audiobookCount} books",
        getUriToAbsIconDrawable(context, "list-box"),
        true,
        null
      )
    }
  }

  fun libraryItemToMediaItem(libraryItem: LibraryItem, parentId: String): MediaItem {
    val parentIdSegments = parentId.split("__")
    val parentLibraryId = parentIdSegments.getOrNull(2)?.trimStart('_')
    val isSubFolderBrowseContext = parentId.contains("__AUTHOR__")
      || parentId.contains("__SERIES__")
      || parentId.contains("__COLLECTION__")
      || parentId.contains("__DISCOVERY__")

    if (libraryItem.mediaType == "podcast" && parentId.startsWith(RECENTLY_ROOT)) {
      val mediaMetadata = MediaMetadata.Builder()
        .setTitle(libraryItem.media.metadata.title)
        .setArtist(libraryItem.media.metadata.getAuthorDisplayName())
        .setArtworkUri(resolveLocalCoverUri(libraryItem) ?: libraryItem.getCoverUri())
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .build()
      return MediaItem.Builder()
        .setMediaId("__PODCAST__${libraryItem.id}")
        .setMediaMetadata(mediaMetadata)
        .build()
    }

    val collapsedSeries = libraryItem.collapsedSeries
    if (collapsedSeries != null) {
      val seriesMediaId = if (parentLibraryId != null) {
        "__LIBRARY__${parentLibraryId}__SERIES__${collapsedSeries.id}"
      } else {
        "${parentId}_${libraryItem.id}"
      }
      return buildMediaItem(
        mediaId = seriesMediaId,
        title = libraryItem.media.metadata.title,
        subtitle = libraryItem.media.metadata.getAuthorDisplayName(),
        artworkUri = libraryItem.getCoverUri(),
        isBrowsable = true,
        mimeType = null
      )
    }

    val isPodcast = libraryItem.mediaType == "podcast"
    val canBeBrowsed = if (isSubFolderBrowseContext) false else isPodcast
    val progress = mediaManager.serverUserMediaProgress.find {
      it.libraryItemId == libraryItem.id && it.episodeId.isNullOrBlank()
    }
    val authorId = if (parentId.contains("__AUTHOR__")) parentIdSegments.getOrNull(4) else null
    val showSeriesNumber =
      parentId.contains("__SERIES__") || parentId.contains("__AUTHOR_SERIES__")

    if (!canBeBrowsed) {
      val mediaItem =
        libraryItem.getMediaItem(progress, context, authorId, showSeriesNumber, null)
      val localUri = resolveLocalCoverUri(libraryItem)
      if (localUri != null && mediaItem.mediaMetadata.artworkUri != localUri) {
        val updatedMetadata = mediaItem.mediaMetadata.buildUpon().setArtworkUri(localUri).build()
        return mediaItem.buildUpon().setMediaMetadata(updatedMetadata).build()
      }
      return mediaItem
    }

    val mediaId = "${parentId}_${libraryItem.id}"
    val artworkUri = resolveLocalCoverUri(libraryItem) ?: libraryItem.getCoverUri()

    return buildMediaItem(
      mediaId = mediaId,
      title = libraryItem.media.metadata.title,
      subtitle = libraryItem.media.metadata.getAuthorDisplayName(),
      artworkUri = artworkUri,
      isBrowsable = true,
      mimeType = null
    )
  }

  private fun buildAuthorIndex(
    libraryId: String,
    libraryAuthors: List<LibraryAuthorItem>
  ): List<MediaItem> {
    if (!shouldGroupAuthors(libraryAuthors)) {
      return libraryAuthors.map { author -> author.getMediaItem(null, context) }
    }
    val authorsByLetter = libraryAuthors.groupBy { authorLetterKey(it.name) }
    if (authorsByLetter.size <= 1) {
      return libraryAuthors.map { author -> author.getMediaItem(null, context) }
    }
    return authorsByLetter.keys.sorted().map { letter ->
      val count = authorsByLetter[letter]?.size ?: 0
      buildMediaItem(
        mediaId = "__LIBRARY__${libraryId}__AUTHORS__${letter}",
        title = letter.toString(),
        subtitle = "$count authors",
        artworkUri = getUriToAbsIconDrawable(context, "person"),
        isBrowsable = true,
        mimeType = null
      )
    }
  }

  private fun buildAuthorLetterChildren(
    libraryAuthors: List<LibraryAuthorItem>,
    letter: Char
  ): List<MediaItem> {
    val normalized = letter.uppercaseChar()
    return libraryAuthors
      .filter { authorLetterKey(it.name) == normalized }
      .map { author -> author.getMediaItem(null, context, normalized.toString()) }
  }

  private fun shouldGroupAuthors(libraryAuthors: List<LibraryAuthorItem>): Boolean {
    val groupingThreshold = deviceSettings.androidAutoBrowseLimitForGrouping
    return libraryAuthors.size > groupingThreshold && libraryAuthors.size > 1
  }

  private fun authorLetterKey(name: String?): Char {
    val firstChar = name?.firstOrNull()?.uppercaseChar()
    return if (firstChar != null && firstChar.isLetter()) firstChar else '#'
  }

  private fun resolveLocalCoverUri(libraryItem: LibraryItem): Uri? {
    val localLibraryItemId = libraryItem.localLibraryItemId ?: return null
    val localLibraryItem =
      DeviceManager.dbManager.getLocalLibraryItemByLId(localLibraryItemId) ?: return null

    localLibraryItem.coverAbsolutePath?.let { coverPath ->
      val coverFile = File(coverPath)
      if (coverFile.exists()) {
          val uri = fileProviderUri(coverFile)
          grantReadPermission(uri)
        return uri
      }
    }

    localLibraryItem.coverContentUrl?.let { coverUrl ->
      val uri = if (coverUrl.startsWith("file:")) {
          val fileUri = fileProviderUri(coverUrl.toUri().toFile())
          grantReadPermission(fileUri)
        fileUri
      } else {
        coverUrl.toUri()
      }
      return uri
    }

    return null
  }

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)

    private fun grantReadPermission(uri: Uri) {
        try {
            context.grantUriPermission(null, uri, URI_GRANT_FLAGS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to grant URI permission for cover: ${e.message}")
        }
    }

  suspend fun buildPodcastEpisodes(podcastId: String): List<MediaItem> =
    browseDataLoader.loadPodcastEpisodes(podcastId, context)

  /**
   * Handles recent children with section support.
   */
  suspend fun handleRecentChildren(parentId: String): ImmutableList<MediaItem> {
    val trimmed = parentId.removePrefix(RECENTLY_ROOT).trimStart('_')
    val tokens = trimmed.split("__").filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return ImmutableList.of()

    val libraryId = tokens.first()
    val section = tokens.getOrNull(1)?.uppercase()
    val library = mediaManager.getLibrary(libraryId)

    val items: List<MediaItem> = when (section) {
      null -> {
        if (library?.mediaType == "podcast") {
          listOf(
              createBrowsableCategory("${RECENTLY_ROOT}${libraryId}__EPISODES", "Episodes", "microphone_2"),
            createBrowsableCategory("${RECENTLY_ROOT}${libraryId}__PODCASTS", "Podcasts", "podcast")
          )
        } else {
          listOf(
            createBrowsableCategory("${RECENTLY_ROOT}${libraryId}__BOOKS", "Books", "books-1"),
            createBrowsableCategory("${RECENTLY_ROOT}${libraryId}__AUTHORS", "Authors", "authors")
          )
        }
      }
      "BOOKS" -> browseDataLoader.loadRecentShelfBooks(libraryId)
        .map { item -> libraryItemToMediaItem(item, "${RECENTLY_ROOT}${libraryId}") }
      "AUTHORS" -> browseDataLoader.loadRecentShelfAuthors(libraryId)
        .map { author -> author.getMediaItem(null, context) }
      "PODCASTS" -> browseDataLoader.loadRecentShelfPodcasts(libraryId)
        .map { item -> libraryItemToMediaItem(item, "${RECENTLY_ROOT}${libraryId}") }
      "EPISODES" -> browseDataLoader.loadRecentShelfEpisodes(libraryId)
        .mapNotNull { podcastItem ->
          val recentEpisode = podcastItem.recentEpisode ?: return@mapNotNull null
          podcastItem.localLibraryItemId?.let { localId ->
            val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(localId)
            val localEpisode =
              (localLibraryItem?.media as? com.audiobookshelf.app.data.Podcast)?.episodes
                ?.find { it.serverEpisodeId == recentEpisode.id }
            recentEpisode.localEpisodeId = localEpisode?.id
          }
          val progress = mediaManager.serverUserMediaProgress.find {
            it.libraryItemId == podcastItem.id && it.episodeId == recentEpisode.id
          }
          recentEpisode.getMediaItem(podcastItem, progress, context)
        }
      else -> emptyList()
    }

    return ImmutableList.copyOf(items)
  }

  private fun orderSeries(librarySeries: List<LibrarySeriesItem>): List<LibrarySeriesItem> {
    return when (deviceSettings.androidAutoBrowseSeriesSequenceOrder) {
      AndroidAutoBrowseSeriesSequenceOrderSetting.ASC -> librarySeries
      AndroidAutoBrowseSeriesSequenceOrderSetting.DESC -> librarySeries.reversed()
    }
  }
}

internal fun MediaItem.withDownloadArtwork(item: LocalLibraryItem, context: Context): MediaItem {
  val coverUri = resolveLocalDownloadCover(item, context) ?: return this
    Log.d(TAG, "withDownloadArtwork: item ${item.id}, coverUri $coverUri")
  try {
      val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, coverUri)
    val resizedBitmap = bitmap.scale(256, 256)
      val outputStream = ByteArrayOutputStream()
      resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
    val artworkData = outputStream.toByteArray()
    val updatedMetadata = mediaMetadata.buildUpon()
      .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
      .build()
    return this.buildUpon()
      .setMediaMetadata(updatedMetadata)
      .build()
  } catch (e: Exception) {
      Log.w(TAG, "Failed to load bitmap for artwork: ${e.message}")
    return this
  }
}

private fun resolveLocalDownloadCover(item: LocalLibraryItem, context: Context): Uri? {
  val path = item.coverAbsolutePath ?: return null
  val file = File(path)
  if (!file.exists()) {
      Log.w(TAG, "resolveLocalDownloadCover: file does not exist $path for item ${item.id}")
    return null
  }
    val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
  try {
      context.grantUriPermission("com.google.android.projection.gearhead", uri, URI_GRANT_FLAGS)
  } catch (e: Exception) {
      Log.w(TAG, "Failed to grant URI permission for download cover: ${e.message}")
  }
    Log.d(TAG, "resolveLocalDownloadCover: item ${item.id}, path $path, uri $uri")
  return uri
}
