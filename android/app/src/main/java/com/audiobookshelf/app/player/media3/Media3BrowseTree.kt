package com.audiobookshelf.app.player.media3

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.AndroidAutoBrowseSeriesSequenceOrderSetting
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryAuthorItem
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.LibrarySeriesItem
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.audiobookshelf.app.player.PLAYER_MEDIA3
import com.audiobookshelf.app.player.PlayerMediaItem
import com.audiobookshelf.app.player.toPlayerMediaItems
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Handles the creation of a browsable media tree for Media3 clients like Android Auto.
 */
@OptIn(UnstableApi::class) // Media3 MediaItem/Metadata helpers are marked unstable
class Media3BrowseTree(
  private val context: Context,
  private val mediaManager: MediaManager,
  private val repository: Media3BrowseTreeRepository = Media3BrowseTreeRepository(mediaManager)
) {

  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  data class ResolvedPlayable(
    val session: PlaybackSession,
    val mediaItems: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long
  )

  private fun toDeviceInfo(): DeviceInfo {
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    return DeviceInfo(
      deviceId = deviceId,
      manufacturer = Build.MANUFACTURER,
      model = Build.MODEL,
      sdkVersion = Build.VERSION.SDK_INT,
      clientVersion = BuildConfig.VERSION_NAME
    )
  }

  private fun playerItemsToMediaItems(items: List<PlayerMediaItem>): List<MediaItem> {
    return items.map { pmi ->
      val metadata = MediaMetadata.Builder()
        .setTitle(pmi.title)
        .setArtworkUri(pmi.artworkUri)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
      MediaItem.Builder()
        .setMediaId(pmi.mediaId)
        .setUri(pmi.uri)
        .setMediaMetadata(metadata)
        .setMimeType(pmi.mimeType)
        .build()
    }
  }

  /**
   * Resolves a mediaId from any source into a fully playable MediaItem.
   * This is a critical path for starting playback.
   *
   * @param mediaId The ID of the item to resolve.
   * @param payload Optional request payload for playback.
   * @param preferServerUrisForCast If true, prefers server URLs for casting.
   * @return A ResolvedPlayable containing the session and media items, or null if resolution fails.
   */
  suspend fun resolvePlayableItem(
    mediaId: String,
    payload: PlayItemRequestPayload? = null,
    preferServerUrisForCast: Boolean = false
  ): ResolvedPlayable? = withContext(Dispatchers.IO) {
    Log.d("M3BrowseTree", "Attempting to resolve playable item for mediaId: $mediaId")

    val mediaTarget = findMediaTarget(mediaId)
    if (mediaTarget == null) {
      Log.e("M3BrowseTree", "Failed to find a media target for mediaId: $mediaId")
      return@withContext null
    }

    val playbackSession = requestPlaybackSession(mediaTarget, payload)
    if (playbackSession == null) {
      Log.e("M3BrowseTree", "Failed to create a playback session for mediaId: $mediaId")
      return@withContext null
    }

    val playerItems = playbackSession.toPlayerMediaItems(
      context,
      preferServerUrisForCast = preferServerUrisForCast
    )

    val resolvedMediaItems = playerItemsToMediaItems(playerItems)
    Log.d("M3BrowseTree", "Successfully resolved mediaId: $mediaId into ${resolvedMediaItems.size} item(s).")
    val resumeMs = resolveResumePositionMs(mediaTarget, playbackSession)
    val startIndex = resolveTrackIndexForPosition(playbackSession, resumeMs).coerceIn(0, resolvedMediaItems.lastIndex)
    val trackStartOffsetMs = playbackSession.getTrackStartOffsetMs(startIndex)
    val startPositionMs = (resumeMs - trackStartOffsetMs).coerceAtLeast(0L)
    ResolvedPlayable(
      session = playbackSession,
      mediaItems = resolvedMediaItems,
      startIndex = startIndex,
      startPositionMs = startPositionMs
    )
  }

  /**
   * Prefer server-side progress if available; otherwise fall back to the session's currentTime.
   */
  private fun resolveResumePositionMs(target: MediaTarget, session: PlaybackSession): Long {
    val progress = mediaManager.serverUserMediaProgress.find {
      it.libraryItemId == target.libraryItem.id && it.episodeId == target.episode?.id
    }
    val progressMs = progress?.currentTime?.times(1000)?.toLong() ?: 0L
    val sessionMs = session.currentTimeMs
    // Use whichever is greater than zero; cap to duration.
    val candidate = if (progressMs > 0) progressMs else sessionMs
    return candidate.coerceIn(0L, session.totalDurationMs)
  }

  private fun resolveTrackIndexForPosition(session: PlaybackSession, positionMs: Long): Int {
    val tracks = session.audioTracks
    if (tracks.isEmpty()) return 0
    val track = tracks.firstOrNull { positionMs in it.startOffsetMs until it.endOffsetMs }
      ?: tracks.last()
    return tracks.indexOf(track)
  }

  /**
   * Suggestion 4: A dedicated data class for clarity instead of a Pair.
   * Represents the media item to be played, which could be a standalone item or an episode within a podcast.
   */
  private data class MediaTarget(
    val libraryItem: LibraryItemWrapper,
    val episode: PodcastEpisode? = null // Nullable to represent items that aren't episodes
  )

  /**
   * Finds the media target from a given mediaId.
   */
  private suspend fun findMediaTarget(mediaId: String): MediaTarget? {
    // Suggestion 1: Simplified and more readable chaining
    return mediaManager.getById(mediaId)?.let { MediaTarget(it) }
      ?: mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)?.let {
        MediaTarget(it.libraryItemWrapper, it.episode)
      }
  }

  /**
   * Suggestion 2: Wraps the callback-based API into a modern suspend function.
   * This makes the calling code cleaner and improves error/cancellation handling.
   */
  private suspend fun requestPlaybackSession(
    target: MediaTarget,
    payload: PlayItemRequestPayload?
  ): PlaybackSession? = suspendCancellableCoroutine { continuation ->
    val requestPayload = payload ?: PlayItemRequestPayload(
      mediaPlayer = PLAYER_MEDIA3,
      forceDirectPlay = true,
      forceTranscode = false,
      deviceInfo = toDeviceInfo()
    )

    val callback = { session: PlaybackSession? ->
      if (continuation.isActive) {
        continuation.resume(session)
      }
    }

    // Automatically handles cancellation by invoking this block if the coroutine is cancelled.
    continuation.invokeOnCancellation {
      // If your mediaManager supports request cancellation, you would call it here.
      Log.w("M3BrowseTree", "Playback request was cancelled for library item: ${target.libraryItem.id}")
    }

    mediaManager.play(target.libraryItem, target.episode, requestPayload, callback)
  }



  suspend fun getItem(mediaId: String): MediaItem? {
    Log.d("M3BrowseTree", "getItem: Resolving mediaId='$mediaId'")

    // First, handle all known browsable category IDs
    when {
        mediaId == ROOT_ID -> return getRootItem()
        mediaId == DOWNLOADS_ID -> return createBrowsableCategory(DOWNLOADS_ID, "Downloads", "downloads")
        mediaId == CONTINUE_LISTENING_ID -> return createBrowsableCategory(CONTINUE_LISTENING_ID, "Continue Listening", "music")
        mediaId == LIBRARIES_ROOT -> return createBrowsableCategory(LIBRARIES_ROOT, "Libraries", "library-folder")
        mediaId == RECENTLY_ROOT -> return createBrowsableCategory(RECENTLY_ROOT, "Recent", "clock")

        mediaId.startsWith(LIBRARIES_ROOT) && mediaId != LIBRARIES_ROOT -> {
            val libraryId = mediaId.substringAfter(LIBRARIES_ROOT)
            val library = mediaManager.getLibrary(libraryId)
            return library?.let { libraryToMediaItem(it, LIBRARIES_ROOT) }
        }

        mediaId.startsWith(RECENTLY_ROOT) && mediaId != RECENTLY_ROOT -> {
            val libraryId = mediaId.substringAfter(RECENTLY_ROOT)
            val library = mediaManager.getLibrary(libraryId)
            return library?.let { libraryToMediaItem(it, RECENTLY_ROOT) }
        }

        mediaId.startsWith("__LIBRARY__") -> {
            val parts = mediaId.split("__")
            if (parts.size >= 4) {
                 val libraryId = parts[2]
                 val browseType = parts[3]
                 when (browseType) {
                    "AUTHORS" -> return createBrowsableCategory(mediaId, "Authors", "authors")
                    "SERIES_LIST" -> return createBrowsableCategory(mediaId, "Series", "books-2")
                    "COLLECTIONS" -> return createBrowsableCategory(mediaId, "Collections", "books-1")
                    "DISCOVERY" -> return createBrowsableCategory(mediaId, "Discovery", "rocket")

                    "AUTHOR" -> return parts.getOrNull(4)?.let { authorId ->
                        repository.loadAuthorsWithBooks(libraryId).find { it.id == authorId }?.let { author ->
                            buildMediaItem(mediaId, author.name, "${author.bookCount} books", getUriToAbsIconDrawable(context, "person"), true, null)
                        }
                    }
                    "SERIES" -> return parts.getOrNull(4)?.let { seriesId ->
                        repository.loadLibrarySeriesWithAudio(libraryId).find { it.id == seriesId }?.let { seriesItem ->
                            buildMediaItem(mediaId, seriesItem.title, "${seriesItem.audiobookCount} books", getUriToAbsIconDrawable(context, "bookshelf"), true, null)
                        }
                    }
                    "COLLECTION" -> return parts.getOrNull(4) ?.let { collectionId ->
                        repository.loadLibraryCollectionsWithAudio(libraryId).find { it.id == collectionId }?.let { collection ->
                           buildMediaItem(mediaId, collection.name, "${collection.audiobookCount} books", getUriToAbsIconDrawable(context, "list-box"), true, null)
                        }
                    }
                 }
            }
        }
    }

    // If it wasn't a browsable category, assume it's a playable item ID.
    val target = mediaManager.getById(mediaId)?.let { it to null }
        ?: mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)?.let {
            it.libraryItemWrapper to it.episode
        }

    if (target == null) {
        Log.w("M3BrowseTree", "getItem: Unable to resolve playable mediaId='$mediaId'")
        return null
    }

    val (libraryItem, episode) = target
    val progress = mediaManager.serverUserMediaProgress.find {
        it.libraryItemId == libraryItem.id && it.episodeId == episode?.id
    }

    // Use the existing getMediaItem() helpers on the data classes.
    return episode?.getMediaItem(libraryItem, progress, context)
        ?: libraryItem.getMediaItem(progress, context)
  }


suspend fun getChildren(parentId: String): ImmutableList<MediaItem> {
    Log.d("M3BrowseTree", "getChildren: parentId=$parentId")

    val mediaItems = when {
      parentId == ROOT_ID -> getRootChildren()
      parentId == DOWNLOADS_ID -> buildDownloadsItems().also {
        Log.d("M3BrowseTree", "downloads items=${it.size}")
      }
      parentId == CONTINUE_LISTENING_ID -> buildContinueListeningItems().also {
        Log.d("M3BrowseTree", "continueListening items=${it.size}")
      }
      parentId == LIBRARIES_ROOT -> buildLibraryList(LIBRARIES_ROOT).also {
        Log.d("M3BrowseTree", "libraries items=${it.size}")
      }
      parentId == RECENTLY_ROOT -> buildLibraryList(RECENTLY_ROOT).also {
        Log.d("M3BrowseTree", "recently libraries items=${it.size}")
      }

      parentId.startsWith("__PODCAST__") -> {
        val podcastId = parentId.substringAfter("__PODCAST__")
        buildPodcastEpisodes(podcastId).also {
          Log.d("M3BrowseTree", "podcastEpisodes id=$podcastId items=${it.size}")
        }
      }

      parentId.startsWith(LIBRARIES_ROOT) -> {
        val libraryId = parentId.removePrefix(LIBRARIES_ROOT).trimStart('_')
        if (libraryId.isBlank()) return ImmutableList.of()
        buildLibraryChildren(libraryId).also {
          Log.d("M3BrowseTree", "libraryChildren library=$libraryId items=${it.size}")
        }
      }

      // Handles sub-browse nodes that start with "__LIBRARY__<id>__..."
      parentId.startsWith("__LIBRARY__") -> buildLibrarySubChildren(parentId).also {
        Log.d("M3BrowseTree", "librarySubChildren parent=$parentId items=${it.size}")
      }

      parentId.startsWith(RECENTLY_ROOT) -> {
        return handleRecentChildren(parentId)
      }

      else -> {
        Log.w("M3BrowseTree", "getChildren: Unhandled parentId: $parentId")
        emptyList()
      }
    }
    return ImmutableList.copyOf(mediaItems)
  }

  fun getRootItem(): MediaItem {
    Log.d("M3BrowseTree", "getRootItem: creating browsable root.")
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
    // In the new, modularized architecture, the coordinator ensures that this method
    // is called only after the initial data load is complete. We can therefore
    // trust that the mediaManager's data is ready.
    if (!mediaManager.isAutoDataLoaded) return emptyList()
    return buildList {
      // Surface these top-level shelves even if the underlying lists are still
      // loading; the downstream calls will simply return empty lists.
      add(createBrowsableCategory(CONTINUE_LISTENING_ID, "Continue Listening", "music"))
      if (mediaManager.serverLibraries.isNotEmpty()) {
        add(createBrowsableCategory(RECENTLY_ROOT, "Recent", "clock"))
        add(createBrowsableCategory(LIBRARIES_ROOT, "Libraries", "library-folder"))
      }
      add(createBrowsableCategory(DOWNLOADS_ID, "Downloads", "downloads"))
    }
  }

  fun buildDownloadsItems(): List<MediaItem> {
    val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
    val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")

    val bookItems = localBooks.mapNotNull { item ->
      if (!item.hasTracks(null)) return@mapNotNull null
      val progress = DeviceManager.dbManager.getLocalMediaProgress(item.id)
      item.getMediaItem(progress, context).withDownloadArtwork(item, context)
    }

    val podcastItems = localPodcasts.map { item ->
      val progress = DeviceManager.dbManager.getLocalMediaProgress(item.id)
      item.getMediaItem(progress, context).withDownloadArtwork(item, context)
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

  private suspend fun buildLibraryChildren(libraryId: String): List<MediaItem> {
    val selectedLibrary = mediaManager.getLibrary(libraryId)
    if (selectedLibrary?.mediaType == "podcast") {
      val podcasts = repository.loadLibraryPodcasts(libraryId)
      // For podcasts, return browsable parents that resolve to episode lists.
      return podcasts.map { podcast ->
        val metadata = MediaMetadata.Builder()
          .setTitle(podcast.media.metadata.title)
          .setArtist(podcast.media.metadata.getAuthorDisplayName())
          .setArtworkUri(podcast.getCoverUri())
          .setIsBrowsable(true)
          .setIsPlayable(false)
          .build()
        MediaItem.Builder()
          .setMediaId("__PODCAST__${podcast.id}")
          .setMediaMetadata(metadata)
          .build()
      }
    }

    return buildList {
      add(createBrowsableCategory("__LIBRARY__${libraryId}__AUTHORS", "Authors", "authors"))
      add(createBrowsableCategory("__LIBRARY__${libraryId}__SERIES_LIST", "Series", "books-2"))
      add(createBrowsableCategory("__LIBRARY__${libraryId}__COLLECTIONS", "Collections", "books-1"))
      if (mediaManager.getHasDiscovery(libraryId)) {
        add(createBrowsableCategory("__LIBRARY__${libraryId}__DISCOVERY", "Discovery", "rocket"))
      }
    }
  }

  private suspend fun buildLibrarySubChildren(parentId: String): List<MediaItem> {
    val parts = parentId.split("__")
    if (parts.size < 4) return emptyList()

    val libraryId = parts[2]
    val browseType = parts[3]

    return when (browseType) {
      "AUTHORS" -> {
        val authors = repository.loadAuthorsWithBooks(libraryId)
        val letter = parts.getOrNull(4)?.firstOrNull()?.uppercaseChar()
        if (letter == null) buildAuthorIndex(libraryId, authors) else buildAuthorLetterChildren(
          authors,
          letter
        )
      }

      "SERIES_LIST" -> {
        val seriesItems = orderSeries(repository.loadLibrarySeriesWithAudio(libraryId))
        seriesItems.map { seriesItem ->
          buildMediaItem(
            mediaId = "__LIBRARY__${libraryId}__SERIES__${seriesItem.id}",
            title = seriesItem.title,
            subtitle = "${seriesItem.audiobookCount} books",
            artworkUri = getUriToAbsIconDrawable(context, "bookshelf"),
            isBrowsable = true, mimeType = null
          )
        }
      }

      "COLLECTIONS" -> repository.loadLibraryCollectionsWithAudio(libraryId).map { collection ->
        buildMediaItem(
          mediaId = "__LIBRARY__${libraryId}__COLLECTION__${collection.id}",
          title = collection.name,
          subtitle = "${collection.audiobookCount} books",
          artworkUri = getUriToAbsIconDrawable(context, "list-box"),
          isBrowsable = true, mimeType = null
        )
      }

      "DISCOVERY" -> repository.loadLibraryDiscoveryBooksWithAudio(libraryId)
        .map { book -> libraryItemToMediaItem(book, parentId) }

      "AUTHOR" -> parts.getOrNull(4)?.let { authorId ->
        repository.loadAuthorBooksWithAudio(libraryId, authorId)
          .map { book -> libraryItemToMediaItem(book, parentId) }
      } ?: emptyList()

      "SERIES" -> parts.getOrNull(4)?.let { seriesId ->
        repository.loadLibrarySeriesItemsWithAudio(libraryId, seriesId).map { book ->
          libraryItemToMediaItem(book, parentId)
        }
      } ?: emptyList()

      "COLLECTION" -> parts.getOrNull(4)?.let { collectionId ->
        repository.loadLibraryCollectionBooksWithAudio(libraryId, collectionId)
          .map { book -> libraryItemToMediaItem(book, parentId) }
      } ?: emptyList()

      else -> emptyList()
    }
  }

  suspend fun buildPodcastEpisodes(podcastId: String): List<MediaItem> =
    repository.loadPodcastEpisodes(podcastId, context)

  private suspend fun handleRecentChildren(parentId: String): ImmutableList<MediaItem> {
    val trimmed = parentId.removePrefix(RECENTLY_ROOT).trimStart('_')
    val tokens = trimmed.split("__").filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return ImmutableList.of()

    val libraryId = tokens.first()
    val section = tokens.getOrNull(1)?.uppercase()
    val library = mediaManager.getLibrary(libraryId)

    val items: List<MediaItem> = when (section) {
      null -> {
        // First level under Recent -> show section categories depending on library media type.
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

      "BOOKS" -> repository.loadRecentShelfBooks(libraryId)
        .map { item -> libraryItemToMediaItem(item, "${RECENTLY_ROOT}${libraryId}") }

      "AUTHORS" -> repository.loadRecentShelfAuthors(libraryId)
        .map { author -> author.getMediaItem(null, context) }

      "PODCASTS" -> repository.loadRecentShelfPodcasts(libraryId)
        .map { item -> libraryItemToMediaItem(item, "${RECENTLY_ROOT}${libraryId}") }

      "EPISODES" -> repository.loadRecentShelfEpisodes(libraryId)
        .mapNotNull { podcastItem ->
          val recentEpisode = podcastItem.recentEpisode ?: return@mapNotNull null

          // Align local mapping if available
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

    Log.d("M3BrowseTree", "recently children parent=$parentId items=${items.size}")
    return ImmutableList.copyOf(items)
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
    // In author/series/collection/discovery contexts we only want to make collapsed
    // series browsable; plain books should be playable leaf items so AA doesn't ask
    // for children of a book.
    val isSubBrowseContext = parentId.contains("__AUTHOR__")
      || parentId.contains("__SERIES__")
      || parentId.contains("__COLLECTION__")
      || parentId.contains("__DISCOVERY__")

    val libraryIdFromParent = parentId.split("__").getOrNull(2)?.trimStart('_')

    // In recent shelves (and any context outside the main library) make podcast items
    // browsable with the shared __PODCAST__ prefix so getChildren() routes to episodes.
    if (item.mediaType == "podcast" && parentId.startsWith(RECENTLY_ROOT)) {
      val metadata = MediaMetadata.Builder()
        .setTitle(item.media.metadata.title)
        .setArtist(item.media.metadata.getAuthorDisplayName())
        .setArtworkUri(resolveLocalCoverUri(item) ?: item.getCoverUri())
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .build()
      return MediaItem.Builder()
        .setMediaId("__PODCAST__${item.id}")
        .setMediaMetadata(metadata)
        .build()
    }

    // Collapsed series should navigate into the proper series node so AA can list episodes/books.
    val collapsedSeries = item.collapsedSeries
    if (collapsedSeries != null) {
      val seriesMediaId = if (libraryIdFromParent != null) {
        "__LIBRARY__${libraryIdFromParent}__SERIES__${collapsedSeries.id}"
      } else {
        "${parentId}_${item.id}"
      }
      return buildMediaItem(
        mediaId = seriesMediaId,
        title = item.media.metadata.title,
        subtitle = item.media.metadata.getAuthorDisplayName(),
        artworkUri = item.getCoverUri(),
        isBrowsable = true,
        mimeType = null
      )
    }

    val isBrowsable = item.mediaType == "podcast" || item.collapsedSeries != null
    val effectiveBrowsable = if (isSubBrowseContext && item.collapsedSeries == null) {
      false
    } else {
      isBrowsable
    }

    // If it's a leaf (playable book), reuse the data-class helper to preserve extras/completion state.
    if (!effectiveBrowsable) {
      return item.getMediaItem(null, context)
    }

    val mediaId = "${parentId}_${item.id}"
    val artworkUri = resolveLocalCoverUri(item) ?: item.getCoverUri()

    return buildMediaItem(
      mediaId = mediaId,
      title = item.media.metadata.title,
      subtitle = item.media.metadata.getAuthorDisplayName(),
      artworkUri = artworkUri,
      isBrowsable = true,
      mimeType = null
    )
  }

  private fun orderSeries(series: List<LibrarySeriesItem>): List<LibrarySeriesItem> {
    return if (deviceSettings.androidAutoBrowseSeriesSequenceOrder == AndroidAutoBrowseSeriesSequenceOrderSetting.DESC) {
      series.reversed()
    } else {
      series
    }
  }

  private fun buildAuthorIndex(
    libraryId: String,
    authors: List<LibraryAuthorItem>
  ): List<MediaItem> {
    if (!shouldGroupAuthors(authors)) {
      return authors.map { author -> author.getMediaItem(null, context) }
    }
    val groups = authors.groupBy { authorLetterKey(it.name) }
    if (groups.size <= 1) {
      return authors.map { author -> author.getMediaItem(null, context) }
    }
    return groups.keys.sorted().map { letter ->
      val count = groups[letter]?.size ?: 0
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
    authors: List<LibraryAuthorItem>,
    letter: Char
  ): List<MediaItem> {
    val normalized = letter.uppercaseChar()
    return authors
      .filter { authorLetterKey(it.name) == normalized }
      .map { author -> author.getMediaItem(null, context, normalized.toString()) }
  }

  private fun shouldGroupAuthors(authors: List<LibraryAuthorItem>): Boolean {
    val limit = deviceSettings.androidAutoBrowseLimitForGrouping
    return authors.size > limit && authors.size > 1
  }

  private fun authorLetterKey(name: String?): Char {
    val first = name?.firstOrNull()?.uppercaseChar()
    return if (first != null && first.isLetter()) first else '#'
  }

  private fun libraryToMediaItem(library: Library, prefix: String): MediaItem {
    val mediaId =
      if (prefix.endsWith("__")) "${prefix}${library.id}" else "${prefix}_${library.id}"
    val metadata = MediaMetadata.Builder()
      .setTitle(library.name)
      .setArtist("${library.stats?.numAudioFiles ?: 0} files")
      .setArtworkUri(getUriToAbsIconDrawable(context, library.icon))
      .setIsBrowsable(true)
      .setIsPlayable(false)
      .build()
    return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
  }

  private fun createBrowsableCategory(id: String, title: String, iconName: String? = null): MediaItem {
    val artwork = iconName?.let { getUriToAbsIconDrawable(context, it) }
    return buildMediaItem(id, title, null, artwork, true, null)
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

  private fun resolveLocalCoverUri(item: LibraryItem): Uri? {
    val localId = item.localLibraryItemId ?: return null
    val localItem = DeviceManager.dbManager.getLocalLibraryItemByLId(localId) ?: return null

    localItem.coverAbsolutePath?.let { path ->
      val file = File(path)
      if (file.exists()) {
        return FileProvider.getUriForFile(
          context,
          "${BuildConfig.APPLICATION_ID}.fileprovider",
          file
        )
      }
    }

    localItem.coverContentUrl?.let { url ->
      return if (url.startsWith("file:")) {
        FileProvider.getUriForFile(
          context,
          "${BuildConfig.APPLICATION_ID}.fileprovider",
          Uri.parse(url).toFile()
        )
      } else {
        Uri.parse(url)
      }
    }

    return null
  }

  companion object {
    const val ROOT_ID = "__ROOT__"
    const val DOWNLOADS_ID = "__DOWNLOADS__"
    const val CONTINUE_LISTENING_ID = "__CONTINUE_LISTENING__"
    const val LIBRARIES_ROOT = "__LIBRARIES__"
    const val RECENTLY_ROOT = "__RECENTLY__"
  }
}

private fun MediaItem.withDownloadArtwork(item: LocalLibraryItem, context: Context): MediaItem {
  val artwork = resolveLocalDownloadCover(item, context) ?: return this
  if (mediaMetadata.artworkUri == artwork) return this
  val updatedMetadata = mediaMetadata.buildUpon()
    .setArtworkUri(artwork)
    .build()
  return this.buildUpon()
    .setMediaMetadata(updatedMetadata)
    .build()
}

private fun resolveLocalDownloadCover(item: LocalLibraryItem, context: Context): Uri? {
  val path = item.coverAbsolutePath ?: return null
  val file = File(path)
  if (!file.exists()) return null
  return FileProvider.getUriForFile(
    context,
    "${BuildConfig.APPLICATION_ID}.fileprovider",
    file
  )
}
