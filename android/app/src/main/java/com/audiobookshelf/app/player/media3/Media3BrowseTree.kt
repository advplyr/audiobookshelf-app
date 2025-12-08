package com.audiobookshelf.app.player.media3

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.audiobookshelf.app.player.PLAYER_MEDIA3
import com.audiobookshelf.app.player.PlayerMediaItem
import com.audiobookshelf.app.player.toPlayerMediaItems
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Handles the creation of a browsable media tree for Media3 clients like Android Auto.
 */
@OptIn(UnstableApi::class) // Media3 MediaItem/Metadata helpers are marked unstable
class Media3BrowseTree(
  private val context: Context,
  private val mediaManager: MediaManager
) {

  private val dataLoader = Media3BrowseDataLoader(mediaManager)
  private val itemBuilder = Media3BrowseItemBuilder(context, mediaManager, dataLoader)

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

  private fun playerItemsToMediaItems(playerMediaItems: List<PlayerMediaItem>): List<MediaItem> {
    return playerMediaItems.map { playerMediaItem ->
      val metadata = MediaMetadata.Builder()
        .setTitle(playerMediaItem.title)
        .setArtworkUri(playerMediaItem.artworkUri)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
      MediaItem.Builder()
        .setMediaId(playerMediaItem.mediaId)
        .setUri(playerMediaItem.uri)
        .setMediaMetadata(metadata)
        .setMimeType(playerMediaItem.mimeType)
        .build()
    }
  }

  /**
   * Resolves a media ID to a playable item by finding the target, requesting a session, and converting to MediaItems.
   * @param mediaId The media identifier to resolve
   * @param playRequestPayload Optional play request playRequestPayload for custom parameters
   * @param preferServerUrisForCast Whether to prefer server URIs when casting
   * @return ResolvedPlayable containing session, media items, start position, or null if resolution fails
   */
  suspend fun resolvePlayableItem(
    mediaId: String,
    playRequestPayload: PlayItemRequestPayload? = null,
    preferServerUrisForCast: Boolean = false
  ): ResolvedPlayable? = withContext(Dispatchers.IO) {
    Log.d("M3BrowseTree", "Attempting to resolve playable item for mediaId: $mediaId")

    val mediaTarget = findMediaTarget(mediaId)
    if (mediaTarget == null) {
      Log.e("M3BrowseTree", "Failed to find a media target for mediaId: $mediaId")
      return@withContext null
    }

    val playbackSession = requestPlaybackSession(mediaTarget, playRequestPayload)
    if (playbackSession == null) {
      Log.e("M3BrowseTree", "Failed to create a playback session for mediaId: $mediaId")
      return@withContext null
    }

    val playerItems = playbackSession.toPlayerMediaItems(
      context,
      preferServerUrisForCast = preferServerUrisForCast
    )

    val mediaItems = playerItemsToMediaItems(playerItems)
    Log.d(
      "M3BrowseTree",
      "Successfully resolved mediaId: $mediaId into ${mediaItems.size} item(s)."
    )
    val resumePositionMs = resolveResumePositionMs(mediaTarget, playbackSession)
    val startIndex = resolveTrackIndexForPosition(playbackSession, resumePositionMs).coerceIn(
      0,
      mediaItems.lastIndex
    )
    val trackStartOffsetMs = playbackSession.getTrackStartOffsetMs(startIndex)
    val startPositionMs = (resumePositionMs - trackStartOffsetMs).coerceAtLeast(0L)
    ResolvedPlayable(
      session = playbackSession,
      mediaItems = mediaItems,
      startIndex = startIndex,
      startPositionMs = startPositionMs
    )
  }

  /**
   * Prefer server-side progress if available; otherwise fall back to the session's currentTime.
   */
  private fun resolveResumePositionMs(
    mediaTargetPair: MediaTarget,
    session: PlaybackSession
  ): Long {
    val userMediaProgress = mediaManager.serverUserMediaProgress.find {
      it.libraryItemId == mediaTargetPair.libraryItem.id && it.episodeId == mediaTargetPair.episode?.id
    }
    val userProgressMs = userMediaProgress?.currentTime?.times(1000)?.toLong() ?: 0L
    val sessionCurrentTimeMs = session.currentTimeMs
    // Use whichever is greater than zero; cap to duration.
    val resumeCandidateMs = if (userProgressMs > 0) userProgressMs else sessionCurrentTimeMs
    return resumeCandidateMs.coerceIn(0L, session.totalDurationMs)
  }

  private fun resolveTrackIndexForPosition(session: PlaybackSession, positionMs: Long): Int {
    val audioTracks = session.audioTracks
    if (audioTracks.isEmpty()) return 0
    val currentTrack =
      audioTracks.firstOrNull { positionMs in it.startOffsetMs until it.endOffsetMs }
        ?: audioTracks.last()
    return audioTracks.indexOf(currentTrack)
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
  private fun findMediaTarget(mediaId: String): MediaTarget? {
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
    mediaTargetPair: MediaTarget,
    playRequestPayload: PlayItemRequestPayload?
  ): PlaybackSession? = suspendCancellableCoroutine { sessionContinuation ->
    val finalRequestPayload = playRequestPayload ?: PlayItemRequestPayload(
      mediaPlayer = PLAYER_MEDIA3,
      forceDirectPlay = true,
      forceTranscode = false,
      deviceInfo = toDeviceInfo()
    )

    val onSessionResult = { session: PlaybackSession? ->
      if (sessionContinuation.isActive) {
        sessionContinuation.resume(session)
      }
    }

    // Automatically handles cancellation by invoking this block if the coroutine is cancelled.
    sessionContinuation.invokeOnCancellation {
      // If your mediaManager supports request cancellation, you would call it here.
      Log.w(
        "M3BrowseTree",
        "Playback request was cancelled for library item: ${mediaTargetPair.libraryItem.id}"
      )
    }

    mediaManager.play(
      mediaTargetPair.libraryItem,
      mediaTargetPair.episode,
      finalRequestPayload,
      onSessionResult
    )
  }


  /**
   * Retrieves a single MediaItem by media ID, handling browsable categories and library items.
   * @param mediaId The media identifier to look up
   * @return MediaItem if found, null otherwise
   */
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
          val libraryId = mediaId.removePrefix(LIBRARIES_ROOT).trimStart('_')
            val library = mediaManager.getLibrary(libraryId)
          return library?.let { itemBuilder.libraryToMediaItem(it, LIBRARIES_ROOT) }
        }

        mediaId.startsWith(RECENTLY_ROOT) && mediaId != RECENTLY_ROOT -> {
          val libraryId = mediaId.removePrefix(RECENTLY_ROOT).trimStart('_')
            val library = mediaManager.getLibrary(libraryId)
          return library?.let { itemBuilder.libraryToMediaItem(it, RECENTLY_ROOT) }
        }

        mediaId.startsWith("__LIBRARY__") -> {
          val mediaIdSegments = mediaId.split("__")
          if (mediaIdSegments.size >= 4) {
            val libraryId = mediaIdSegments[2]
            val browseType = mediaIdSegments[3]
            when (browseType) {
              "AUTHORS" -> return itemBuilder.createBrowsableCategory(
                mediaId,
                "Authors",
                "authors"
              )

              "SERIES_LIST" -> return itemBuilder.createBrowsableCategory(
                mediaId,
                "Series",
                "books-2"
              )

              "COLLECTIONS" -> return itemBuilder.createBrowsableCategory(
                mediaId,
                "Collections",
                "books-1"
              )

              "DISCOVERY" -> return itemBuilder.createBrowsableCategory(
                mediaId,
                "Discovery",
                "rocket"
              )

              "AUTHOR" -> return mediaIdSegments.getOrNull(4)?.let { authorId ->
                dataLoader.loadAuthorsWithBooks(libraryId).find { it.id == authorId }
                  ?.let { author ->
                    itemBuilder.buildMediaItem(
                      mediaId,
                      author.name,
                      "${author.bookCount} books",
                      getUriToAbsIconDrawable(context, "person"),
                      true,
                      null
                    )
                        }
                    }

              "SERIES" -> return mediaIdSegments.getOrNull(4)?.let { seriesId ->
                dataLoader.loadLibrarySeriesWithAudio(libraryId).find { it.id == seriesId }
                  ?.let { seriesItem ->
                    itemBuilder.buildMediaItem(
                      mediaId,
                      seriesItem.title,
                      "${seriesItem.audiobookCount} books",
                      getUriToAbsIconDrawable(context, "bookshelf"),
                      true,
                      null
                    )
                        }
                    }

              "COLLECTION" -> return mediaIdSegments.getOrNull(4)?.let { collectionId ->
                dataLoader.loadLibraryCollectionsWithAudio(libraryId)
                  .find { it.id == collectionId }?.let { collection ->
                    itemBuilder.buildMediaItem(
                      mediaId,
                      collection.name,
                      "${collection.audiobookCount} books",
                      getUriToAbsIconDrawable(context, "list-box"),
                      true,
                      null
                    )
                        }
                    }
                 }
            }
        }
    }

    // If it wasn't a browsable category, assume it's a playable item ID.
    val mediaTargetPair = mediaManager.getById(mediaId)?.let { it to null }
        ?: mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)?.let {
            it.libraryItemWrapper to it.episode
        }

    if (mediaTargetPair == null) {
        Log.w("M3BrowseTree", "getItem: Unable to resolve playable mediaId='$mediaId'")
        return null
    }

    val (libraryItem, episode) = mediaTargetPair
    val userMediaProgress = mediaManager.serverUserMediaProgress.find {
        it.libraryItemId == libraryItem.id && it.episodeId == episode?.id
    }

    // Use the existing getMediaItem() helpers on the data classes.
    return episode?.getMediaItem(libraryItem, userMediaProgress, context)
      ?: libraryItem.getMediaItem(userMediaProgress, context)
  }


  /**
   * Builds the children for a given parent ID in the browse tree hierarchy.
   * Handles different parent types like root, downloads, continue listening, libraries, etc.
   * @param parentId The parent node identifier
   * @return Immutable list of MediaItems representing the children
   */
suspend fun getChildren(parentId: String): ImmutableList<MediaItem> {
    Log.d("M3BrowseTree", "getChildren: parentId=$parentId")

    val mediaItems = when {
      parentId == ROOT_ID -> itemBuilder.getRootChildren()
      parentId == DOWNLOADS_ID -> itemBuilder.buildDownloadsItems().also {
        Log.d("M3BrowseTree", "downloads items=${it.size}")
      }

      parentId == CONTINUE_LISTENING_ID -> itemBuilder.buildContinueListeningItems().also {
        Log.d("M3BrowseTree", "continueListening items=${it.size}")
      }

      parentId == LIBRARIES_ROOT -> itemBuilder.buildLibraryList(LIBRARIES_ROOT).also {
        Log.d("M3BrowseTree", "libraries items=${it.size}")
      }

      parentId == RECENTLY_ROOT -> itemBuilder.buildLibraryList(RECENTLY_ROOT).also {
        Log.d("M3BrowseTree", "recently libraries items=${it.size}")
      }

      parentId.startsWith("__PODCAST__") -> {
        val podcastId = parentId.substringAfter("__PODCAST__")
        itemBuilder.buildPodcastEpisodes(podcastId).also {
          Log.d("M3BrowseTree", "podcastEpisodes id=$podcastId items=${it.size}")
        }
      }

      parentId.startsWith(LIBRARIES_ROOT) -> {
        val libraryId = parentId.removePrefix(LIBRARIES_ROOT).trimStart('_')
        if (libraryId.isBlank()) return ImmutableList.of()
        itemBuilder.buildLibraryChildren(libraryId).also {
          Log.d("M3BrowseTree", "libraryChildren library=$libraryId items=${it.size}")
        }
      }

      parentId.startsWith("__LIBRARY__") -> itemBuilder.buildLibrarySubChildren(parentId).also {
        Log.d("M3BrowseTree", "librarySubChildren parent=$parentId items=${it.size}")
      }

      parentId.startsWith(RECENTLY_ROOT) -> {
        return itemBuilder.handleRecentChildren(parentId)
      }

      else -> {
        Log.w("M3BrowseTree", "getChildren: Unhandled parentId: $parentId")
        emptyList()
      }
    }
    return ImmutableList.copyOf(mediaItems)
  }

  /**
   * Creates the root browsable MediaItem for the Android Auto browse tree.
   * @return MediaItem configured as browsable root with app title
   */
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

  // Deprecated duplicate browse-building helpers removed; functionality has been
  // consolidated into `Media3BrowseItemBuilder` and `Media3BrowseDataLoader`.

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

  private fun createBrowsableCategory(
    categoryId: String,
    title: String,
    iconName: String? = null
  ): MediaItem {
    val artworkUri = iconName?.let { getUriToAbsIconDrawable(context, it) }
    return buildMediaItem(categoryId, title, null, artworkUri, true, null)
  }

  // Helper logic for author/library grouping and cover resolution
  // has been moved to `Media3BrowseItemBuilder` and `Media3BrowseDataLoader`.

  companion object {
    const val ROOT_ID = "__ROOT__"
    const val DOWNLOADS_ID = "__DOWNLOADS__"
    const val CONTINUE_LISTENING_ID = "__CONTINUE_LISTENING__"
    const val LIBRARIES_ROOT = "__LIBRARIES__"
    const val RECENTLY_ROOT = "__RECENTLY__"
  }
}


// `resolveLocalDownloadCover` moved to `Media3BrowseItemBuilder.kt` to centralize
// download-cover resolution logic. The duplicate implementation was removed.
