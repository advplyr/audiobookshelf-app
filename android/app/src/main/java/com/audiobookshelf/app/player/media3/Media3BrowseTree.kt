package com.audiobookshelf.app.player.media3

import android.content.Context
import android.os.Build
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
import com.audiobookshelf.app.player.toPlayerMediaItems
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Manages the browsable media tree for Android Auto and other Media3 clients.
 * Builds hierarchical browse structure from root -> libraries -> categories -> items.
 * Uses pull-based architecture: queries MediaManager on demand rather than pre-loading.
 */
@OptIn(UnstableApi::class)
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
      Log.d(TAG, "Attempting to resolve playable item for mediaId: $mediaId")

    val mediaTarget = findMediaTarget(mediaId)
    if (mediaTarget == null) {
        Log.e(TAG, "Failed to find a media target for mediaId: $mediaId")
      return@withContext null
    }

    val playbackSession = requestPlaybackSession(mediaTarget, playRequestPayload)
    if (playbackSession == null) {
        Log.e(TAG, "Failed to create a playback session for mediaId: $mediaId")
      return@withContext null
    }

      val mediaItems = playbackSession.toPlayerMediaItems(
      context,
      preferServerUrisForCast = preferServerUrisForCast
      ).map { playerMediaItem ->
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
      Log.d(TAG, "Successfully resolved mediaId: $mediaId into ${mediaItems.size} item(s).")
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

  private data class MediaTarget(
    val libraryItem: LibraryItemWrapper,
    val episode: PodcastEpisode? = null
  )

  private fun findMediaTarget(mediaId: String): MediaTarget? {
    return mediaManager.getById(mediaId)?.let { MediaTarget(it) }
      ?: mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)?.let {
        MediaTarget(it.libraryItemWrapper, it.episode)
      }
  }

  /**
   * Requests a playback session using the callback-based API wrapped inside a suspend helper.
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

    sessionContinuation.invokeOnCancellation {
        Log.w(TAG, "Playback request was cancelled for library item: ${mediaTargetPair.libraryItem.id}")
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
   */
  suspend fun getItem(mediaId: String): MediaItem? {
      Log.d(TAG, "getItem: Resolving mediaId='$mediaId'")

    when {
        mediaId==ROOT_ID -> return getRootItem()
        mediaId==DOWNLOADS_ID -> return itemBuilder.createBrowsableCategory(DOWNLOADS_ID, "Downloads", "downloads")
        mediaId==CONTINUE_LISTENING_ID -> return itemBuilder.createBrowsableCategory(CONTINUE_LISTENING_ID, "Continue Listening", "music")
        mediaId==LIBRARIES_ROOT -> return itemBuilder.createBrowsableCategory(LIBRARIES_ROOT, "Libraries", "library-folder")
        mediaId==RECENTLY_ROOT -> return itemBuilder.createBrowsableCategory(RECENTLY_ROOT, "Recent", "clock")

        mediaId.startsWith(LIBRARIES_ROOT) && mediaId!=LIBRARIES_ROOT -> {
            val libraryId = mediaId.removePrefix(LIBRARIES_ROOT).trimStart('_')
            val library = mediaManager.getLibrary(libraryId)
            return library?.let { itemBuilder.libraryToMediaItem(it, LIBRARIES_ROOT) }
        }

        mediaId.startsWith(RECENTLY_ROOT) && mediaId!=RECENTLY_ROOT -> {
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
                    "AUTHORS" -> return itemBuilder.createBrowsableCategory(mediaId, "Authors", "authors")
                    "SERIES_LIST" -> return itemBuilder.createBrowsableCategory(mediaId, "Series", "books-2")
                    "COLLECTIONS" -> return itemBuilder.createBrowsableCategory(mediaId, "Collections", "books-1")
                    "DISCOVERY" -> return itemBuilder.createBrowsableCategory(mediaId, "Discovery", "rocket")
                    "AUTHOR" -> return mediaIdSegments.getOrNull(4)?.let { authorId ->
                        dataLoader.loadAuthorsWithBooks(libraryId).find { it.id==authorId }
                            ?.let { author ->
                                itemBuilder.buildMediaItem(
                                    mediaId, author.name, "${author.bookCount} books",
                                    getUriToAbsIconDrawable(context, "person"), true, null
                                )
                            }
                    }

                    "SERIES" -> return mediaIdSegments.getOrNull(4)?.let { seriesId ->
                        dataLoader.loadLibrarySeriesWithAudio(libraryId).find { it.id==seriesId }
                            ?.let { seriesItem ->
                                itemBuilder.buildMediaItem(
                                    mediaId, seriesItem.title, "${seriesItem.audiobookCount} books",
                                    getUriToAbsIconDrawable(context, "bookshelf"), true, null
                                )
                            }
                    }

                    "COLLECTION" -> return mediaIdSegments.getOrNull(4)?.let { collectionId ->
                        dataLoader.loadLibraryCollectionsWithAudio(libraryId)
                            .find { it.id==collectionId }?.let { collection ->
                                itemBuilder.buildMediaItem(
                                    mediaId, collection.name, "${collection.audiobookCount} books",
                                    getUriToAbsIconDrawable(context, "list-box"), true, null
                                )
                            }
                    }
                }
            }
        }
    }

    val mediaTargetPair = mediaManager.getById(mediaId)?.let { it to null }
        ?: mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)?.let {
            it.libraryItemWrapper to it.episode
        }

    if (mediaTargetPair == null) {
        Log.w(TAG, "getItem: Unable to resolve playable mediaId='$mediaId'")
        return null
    }

    val (libraryItem, episode) = mediaTargetPair
    val userMediaProgress = mediaManager.serverUserMediaProgress.find {
        it.libraryItemId==libraryItem.id && it.episodeId==episode?.id
    }

    return episode?.getMediaItem(libraryItem, userMediaProgress, context)
      ?: libraryItem.getMediaItem(userMediaProgress, context)
  }

  /**
   * Builds the children for a given parent ID in the browse tree hierarchy.
   */
  suspend fun getChildren(parentId: String): ImmutableList<MediaItem> {
      Log.d(TAG, "getChildren: parentId=$parentId")

    val mediaItems = when {
      parentId == ROOT_ID -> itemBuilder.getRootChildren()
      parentId == DOWNLOADS_ID -> itemBuilder.buildDownloadsItems().also {
          Log.d(TAG, "downloads items=${it.size}")
      }
      parentId == CONTINUE_LISTENING_ID -> itemBuilder.buildContinueListeningItems().also {
          Log.d(TAG, "continueListening items=${it.size}")
      }
      parentId == LIBRARIES_ROOT -> itemBuilder.buildLibraryList(LIBRARIES_ROOT).also {
          Log.d(TAG, "libraries items=${it.size}")
      }
      parentId == RECENTLY_ROOT -> itemBuilder.buildLibraryList(RECENTLY_ROOT).also {
          Log.d(TAG, "recently libraries items=${it.size}")
      }
      parentId.startsWith("__PODCAST__") -> {
        val podcastId = parentId.substringAfter("__PODCAST__")
        itemBuilder.buildPodcastEpisodes(podcastId).also {
            Log.d(TAG, "podcastEpisodes id=$podcastId items=${it.size}")
        }
      }
      parentId.startsWith(LIBRARIES_ROOT) -> {
        val libraryId = parentId.removePrefix(LIBRARIES_ROOT).trimStart('_')
        if (libraryId.isBlank()) return ImmutableList.of()
        itemBuilder.buildLibraryChildren(libraryId).also {
            Log.d(TAG, "libraryChildren library=$libraryId items=${it.size}")
        }
      }
      parentId.startsWith("__LIBRARY__") -> itemBuilder.buildLibrarySubChildren(parentId).also {
          Log.d(TAG, "librarySubChildren parent=$parentId items=${it.size}")
      }
      parentId.startsWith(RECENTLY_ROOT) -> {
        return itemBuilder.handleRecentChildren(parentId)
      }
      else -> {
          Log.w(TAG, "getChildren: Unhandled parentId: $parentId")
        emptyList()
      }
    }
    return ImmutableList.copyOf(mediaItems)
  }

  fun getRootItem(): MediaItem {
      Log.d(TAG, "getRootItem: creating browsable root.")
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

    fun invalidateSeriesCache() {
        itemBuilder.clearSeriesViewCache()
  }

  companion object {
      private const val TAG = "M3BrowseTree"
    const val ROOT_ID = "__ROOT__"
    const val DOWNLOADS_ID = "__DOWNLOADS__"
    const val CONTINUE_LISTENING_ID = "__CONTINUE_LISTENING__"
    const val LIBRARIES_ROOT = "__LIBRARIES__"
    const val RECENTLY_ROOT = "__RECENTLY__"
  }
}
