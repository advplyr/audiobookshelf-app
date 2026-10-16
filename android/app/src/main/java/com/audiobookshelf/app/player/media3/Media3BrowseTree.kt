package com.audiobookshelf.app.player.media3

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.isNewerThanLocalProgress
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.audiobookshelf.app.player.PLAYER_MEDIA3
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.toMedia3MediaItems
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

  suspend fun resolvePlayableItem(
    mediaId: String,
    playRequestPayload: PlayItemRequestPayload? = null,
    preferServerUrisForCast: Boolean = false
  ): ResolvedPlayable? = withContext(Dispatchers.IO) {
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

    val mediaItems = playbackSession.toMedia3MediaItems(
      context,
      preferServerUrisForCast = preferServerUrisForCast
    )
    val resumePositionMs = resolveResumePositionMs(mediaTarget, playbackSession)
    val seekTarget = PlaybackPositionModel.seekTargetForPosition(
      playbackSession,
      resumePositionMs,
      mediaItems.lastIndex
    )
    val startIndex = seekTarget.trackIndex
    val startPositionMs = seekTarget.positionInTrackMs
    ResolvedPlayable(
      session = playbackSession,
      mediaItems = mediaItems,
      startIndex = startIndex,
      startPositionMs = startPositionMs
    )
  }

  /** Chooses between cached server progress and possibly newer offline progress. */
  private fun resolveResumePositionMs(
    mediaTargetPair: MediaTarget,
    session: PlaybackSession
  ): Long {
    val serverProgress = mediaManager.serverUserMediaProgress.find {
      it.libraryItemId == mediaTargetPair.libraryItem.id && it.episodeId == mediaTargetPair.episode?.id
    }
    val serverProgressMs = serverProgress?.currentTime?.times(1000)?.toLong() ?: 0L
    val sessionCurrentTimeMs = session.currentTimeMs

    // Fresh local sessions have no useful timestamp; the persisted progress does.
    val localLastUpdate = localProgressLastUpdate(mediaTargetPair, session)
    val serverIsNewer = serverProgress?.isNewerThanLocalProgress(localLastUpdate) == true

    // A newer local zero may represent a completed or reset item.
    val resumeCandidateMs = when {
      serverProgress == null -> sessionCurrentTimeMs
      localLastUpdate == 0L -> serverProgressMs
      serverIsNewer -> serverProgressMs
      else -> sessionCurrentTimeMs
    }
    return resumeCandidateMs.coerceIn(0L, session.totalDurationMs)
  }

  private fun localProgressLastUpdate(
    mediaTargetPair: MediaTarget,
    session: PlaybackSession
  ): Long {
    val localItemId = (mediaTargetPair.libraryItem as? LocalLibraryItem)?.id
      ?: session.localLibraryItem?.id
      ?: return 0L
    val localEpisodeId = session.localEpisodeId ?: mediaTargetPair.episode?.id
    val progressId =
      if (localEpisodeId.isNullOrEmpty()) localItemId else "$localItemId-$localEpisodeId"
    return DeviceManager.dbManager.getLocalMediaProgress(progressId)?.lastUpdate ?: 0L
  }

  private data class MediaTarget(
    val libraryItem: LibraryItemWrapper,
    val episode: PodcastEpisode? = null
  )

  private suspend fun findMediaTarget(mediaId: String): MediaTarget? {
    mediaManager.getById(mediaId)?.let { return MediaTarget(it) }
    mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)?.let {
      return MediaTarget(it.libraryItemWrapper, it.episode)
    }

    // Browse surfaces like the Auto recent shelves display items that are never registered
    // in the in-memory cache, so resolve them with a server fetch instead of failing the tap.
    debugLog(TAG) { "findMediaTarget: '$mediaId' not in memory cache, fetching from server" }
    val fetchedItem = suspendCancellableCoroutine<LibraryItemWrapper?> { itemContinuation ->
      mediaManager.getByIdOrFetch(mediaId) { result ->
        if (itemContinuation.isActive) itemContinuation.resume(result)
      }
    }
    return fetchedItem?.let { MediaTarget(it) }
  }

  private suspend fun requestPlaybackSession(
    mediaTargetPair: MediaTarget,
    playRequestPayload: PlayItemRequestPayload?
  ): PlaybackSession? = suspendCancellableCoroutine { sessionContinuation ->
    val finalRequestPayload = playRequestPayload ?: PlayItemRequestPayload(
      mediaPlayer = PLAYER_MEDIA3,
      forceDirectPlay = true,
      forceTranscode = false,
      deviceInfo = PlaybackConstants.buildDeviceInfo(context)
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

  // Media3 invokes browse callbacks on main; database and artwork work belongs on IO.
  suspend fun getItem(mediaId: String): MediaItem? = withContext(Dispatchers.IO) {
    when {
      mediaId == ROOT_ID -> return@withContext getRootItem()
      mediaId == DOWNLOADS_ID -> return@withContext itemBuilder.createBrowsableCategory(DOWNLOADS_ID, "Downloads", "downloads")
      mediaId == CONTINUE_LISTENING_ID -> return@withContext itemBuilder.createBrowsableCategory(CONTINUE_LISTENING_ID, "Continue Listening", "music")
      mediaId == LIBRARIES_ROOT -> return@withContext itemBuilder.createBrowsableCategory(LIBRARIES_ROOT, "Libraries", "library-folder")
      mediaId == RECENTLY_ROOT -> return@withContext itemBuilder.createBrowsableCategory(RECENTLY_ROOT, "Recent", "clock")

      mediaId.startsWith(LIBRARIES_ROOT) && mediaId != LIBRARIES_ROOT -> {
        val libraryId = mediaId.removePrefix(LIBRARIES_ROOT).trimStart('_')
        val library = mediaManager.getLibrary(libraryId)
        return@withContext library?.let { itemBuilder.libraryToMediaItem(it, LIBRARIES_ROOT) }
      }

      mediaId.startsWith(RECENTLY_ROOT) && mediaId != RECENTLY_ROOT -> {
        val libraryId = mediaId.removePrefix(RECENTLY_ROOT).trimStart('_')
        val library = mediaManager.getLibrary(libraryId)
        return@withContext library?.let { itemBuilder.libraryToMediaItem(it, RECENTLY_ROOT) }
      }

      mediaId.startsWith("__LIBRARY__") -> {
        val mediaIdSegments = mediaId.split("__")
        if (mediaIdSegments.size >= 4) {
          val libraryId = mediaIdSegments[2]
          val browseType = mediaIdSegments[3]
          when (browseType) {
            "AUTHORS" -> return@withContext itemBuilder.createBrowsableCategory(mediaId, "Authors", "authors")
            "SERIES_LIST" -> return@withContext itemBuilder.createBrowsableCategory(mediaId, "Series", "books-2")
            "COLLECTIONS" -> return@withContext itemBuilder.createBrowsableCategory(mediaId, "Collections", "books-1")
            "DISCOVERY" -> return@withContext itemBuilder.createBrowsableCategory(mediaId, "Discovery", "rocket")
            "AUTHOR" -> return@withContext mediaIdSegments.getOrNull(4)?.let { authorId ->
              dataLoader.loadAuthorsWithBooks(libraryId).find { it.id == authorId }
                ?.let { author ->
                  itemBuilder.buildMediaItem(
                    mediaId, author.name, "${author.bookCount} books",
                    getUriToAbsIconDrawable(context, "authors"), true, null
                  )
                }
            }

            "SERIES" -> return@withContext mediaIdSegments.getOrNull(4)?.let { seriesId ->
              dataLoader.loadLibrarySeriesWithAudio(libraryId).find { it.id == seriesId }
                ?.let { seriesItem ->
                  itemBuilder.buildMediaItem(
                    mediaId, seriesItem.title, "${seriesItem.audiobookCount} books",
                    getUriToAbsIconDrawable(context, "columns"), true, null
                  )
                }
            }

            "COLLECTION" -> return@withContext mediaIdSegments.getOrNull(4)?.let { collectionId ->
              dataLoader.loadLibraryCollectionsWithAudio(libraryId)
                .find { it.id == collectionId }?.let { collection ->
                  itemBuilder.buildMediaItem(
                    mediaId, collection.name, "${collection.audiobookCount} books",
                    getUriToAbsIconDrawable(context, "columns"), true, null
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
      return@withContext null
    }

    val (libraryItem, episode) = mediaTargetPair
    val userMediaProgress = mediaManager.serverUserMediaProgress.find {
      it.libraryItemId == libraryItem.id && it.episodeId == episode?.id
    }

    return@withContext episode?.getMediaItem(libraryItem, userMediaProgress, context)
      ?: libraryItem.getMediaItem(userMediaProgress, context)
  }

  suspend fun getChildren(parentId: String): ImmutableList<MediaItem> = withContext(Dispatchers.IO) {
    val mediaItems = when {
      parentId == ROOT_ID -> itemBuilder.getRootChildren()
      parentId == DOWNLOADS_ID -> itemBuilder.buildDownloadsItems()
      parentId == CONTINUE_LISTENING_ID -> itemBuilder.buildContinueListeningItems()
      parentId == LIBRARIES_ROOT -> itemBuilder.buildLibraryList(LIBRARIES_ROOT)
      parentId == RECENTLY_ROOT -> itemBuilder.buildLibraryList(RECENTLY_ROOT)
      parentId.startsWith("__PODCAST__") ->
        itemBuilder.buildPodcastEpisodes(parentId.substringAfter("__PODCAST__"))
      parentId.startsWith("local_") -> itemBuilder.buildPodcastEpisodes(parentId)
      parentId.startsWith(LIBRARIES_ROOT) -> {
        val libraryId = parentId.removePrefix(LIBRARIES_ROOT).trimStart('_')
        if (libraryId.isBlank()) return@withContext ImmutableList.of()
        itemBuilder.buildLibraryChildren(libraryId)
      }
      parentId.startsWith("__LIBRARY__") -> itemBuilder.buildLibrarySubChildren(parentId)
      parentId.startsWith(RECENTLY_ROOT) -> {
        return@withContext itemBuilder.handleRecentChildren(parentId)
      }
      else -> {
        Log.w(TAG, "getChildren: Unhandled parentId: $parentId")
        emptyList()
      }
    }
    return@withContext ImmutableList.copyOf(mediaItems)
  }

  /** Artwork decoding must stay off the main thread; Media3 invokes browse callbacks on main. */
  suspend fun withPagedArtwork(parentId: String, items: List<MediaItem>): List<MediaItem> =
    if (parentId == DOWNLOADS_ID) withContext(Dispatchers.IO) { applyDownloadArtwork(items, context) }
    else items

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

  fun invalidateSeriesCache() {
    dataLoader.clearCache()
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
