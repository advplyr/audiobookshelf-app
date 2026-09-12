package com.audiobookshelf.app.player.media3

import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.player.PlaybackConstants
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import java.util.concurrent.ConcurrentHashMap

data class SeekConfig(
  val allowSeekingOnMediaControls: Boolean
)

interface BrowseApi {
  suspend fun resolve(mediaId: String, preferCast: Boolean): Media3BrowseTree.ResolvedPlayable?
  fun assignSession(session: PlaybackSession)
  fun passthroughAllowed(
    mediaId: String?,
    controller: MediaSession.ControllerInfo?
  ): Boolean
}

@UnstableApi
class Media3SessionCallback(
  private val logTag: String,
  private val scope: CoroutineScope,
  private val browseTree: Media3BrowseTree,
  private val autoLibraryCoordinator: Media3AutoLibraryCoordinator,
  private val mediaManager: MediaManager,
  private val playerProvider: () -> Player,
  private val isCastActive: () -> Boolean,
  private val seekConfig: SeekConfig,
  private val browseApi: BrowseApi,
  private val awaitFinalSync: suspend () -> Unit,
  private val sessionController: SessionController? = null
) : MediaLibraryService.MediaLibrarySession.Callback {

  companion object {
    private const val FINISHED_BOOK_THRESHOLD_MS = 5_000L
    private const val SEARCH_CACHE_MAX_QUERIES = 20
  }

  private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()

  private fun cacheSearchResults(query: String, results: List<MediaItem>) {
    // Crude bound: search queries are session-scoped, so dropping the cache is cheap
    if (searchCache.size >= SEARCH_CACHE_MAX_QUERIES) searchCache.clear()
    searchCache[query] = results
  }

  override fun onConnectAsync(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
  ): ListenableFuture<MediaSession.ConnectionResult> =
    Futures.immediateFuture(buildConnectionResult(session, controller))

  private fun buildConnectionResult(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
  ): MediaSession.ConnectionResult {
    // Deliberate tradeoff: rejecting SystemUI's MediaResumeListener avoids its slow connection
    // handshake delaying playback start, at the cost of post-reboot/dead-app media resumption
    // from quick settings. Media notification controls are unaffected (they use the session token).
    if (controller.packageName == "com.android.systemui") {
      debugLog(logTag) { "Rejecting MediaSession connection from system UI" }
      return MediaSession.ConnectionResult.reject()
    }

    val player = playerProvider()

    val isAppUiController =
      controller.connectionHints.getBoolean(PlaybackConstants.KEY_IS_APP_UI_CONTROLLER, false)

    val playerCommands = sessionController?.buildPlayerCommands(
      controllerInfo = controller,
      allowSeekingOnMediaControls = seekConfig.allowSeekingOnMediaControls
    ) ?: run {
      debugLog(logTag) { "onConnect: sessionController is null, using fallback commands for pkg=${controller.packageName}" }
      val availablePlayerCommands = player.availableCommands
      Player.Commands.Builder().addAll(availablePlayerCommands)
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_GET_DEVICE_VOLUME)
        .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
        .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        .build()
    }

    val sessionCommands = SessionController.buildSessionCommands(
      isAppUiController,
      sessionController?.availableSessionCommands
        ?: MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
    )

    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
      .setAvailableSessionCommands(sessionCommands)
      .setAvailablePlayerCommands(playerCommands)
      .build()
  }


  override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle
  ): ListenableFuture<SessionResult> {
    when (customCommand.customAction) {
      PlaybackConstants.Commands.CLOSE_PLAYBACK -> {
        val future = SettableFuture.create<SessionResult>()
        val afterStop: () -> Unit = { future.set(SessionResult(SessionResult.RESULT_SUCCESS)) }
        sessionController?.closePlayback(afterStop)
          ?: future.set(SessionResult(SessionError.ERROR_UNKNOWN))
        return future
      }

      PlaybackConstants.Commands.SYNC_PROGRESS_FORCE -> {
        // Async so the session callback thread (main) is never blocked; the future completes
        // once the sync finishes, letting callers sequence a new session behind the old
        // session's final server sync.
        val future = SettableFuture.create<SessionResult>()
        sessionController?.forceSyncProgress {
          future.set(SessionResult(SessionResult.RESULT_SUCCESS))
        } ?: future.set(SessionResult(SessionError.ERROR_UNKNOWN))
        return future
      }

      else -> {
        val result = sessionController?.onCustomCommand(customCommand, args)
          ?: SessionResult(SessionResult.RESULT_SUCCESS)
        return Futures.immediateFuture(result)
      }
    }
  }


  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>
  ): ListenableFuture<MutableList<MediaItem>> {
    return scope.future {
      awaitFinalSync()
      val requestedMediaItem = mediaItems.firstOrNull()
      if (requestedMediaItem == null) {
        return@future mutableListOf()
      }

      val isPlayable =
        requestedMediaItem.localConfiguration != null || requestedMediaItem.requestMetadata.mediaUri != null
      if (isPlayable) {
        if (!browseApi.passthroughAllowed(requestedMediaItem.mediaId, controller)) {
          debugLog(logTag) { "onAddMediaItems: rejecting passthrough request for id=${requestedMediaItem.mediaId}" }
          return@future mutableListOf()
        }
        return@future mediaItems
      }

      val mediaId = requestedMediaItem.mediaId
      val preferCastStream = isCastActive()
      val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)

      if (resolvedPlayable == null) {
        debugLog(logTag) { "onAddMediaItems: unable to resolve mediaId=$mediaId" }
        return@future mutableListOf()
      }

      browseApi.assignSession(resolvedPlayable.session)
      // Only return the resolved items: the session applies them to the player itself.
      // Mutating the player here would double-add the queue for addMediaItems flows.
      return@future resolvedPlayable.mediaItems.toMutableList()
    }
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    return scope.future {
      awaitFinalSync()
      val playablePassthrough =
        mediaItems.filter { it.localConfiguration != null || it.requestMetadata.mediaUri != null }
      if (playablePassthrough.isNotEmpty()) {
        if (playablePassthrough.any { !browseApi.passthroughAllowed(it.mediaId, controller) }) {
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }
        return@future MediaSession.MediaItemsWithStartPosition(
          playablePassthrough,
          startIndex,
          startPositionMs
        )
      }

      val requestedMediaItem = mediaItems.firstOrNull()
      if (requestedMediaItem == null) {
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      val mediaId = requestedMediaItem.mediaId
      val preferCastStream = isCastActive()
      val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)

      if (resolvedPlayable == null || resolvedPlayable.mediaItems.isEmpty()) {
        debugLog(logTag) { "onSetMediaItems: unable to resolve mediaId=$mediaId" }
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      browseApi.assignSession(resolvedPlayable.session)

      // Avoid resuming in the final five seconds. startPositionMs is relative to its track, so
      // the completion check must use the absolute position.
      var adjustedStartIndex =
        resolvedPlayable.startIndex.coerceIn(0, resolvedPlayable.mediaItems.lastIndex)
      var adjustedStartPositionMs = resolvedPlayable.startPositionMs
      val resolvedSession = resolvedPlayable.session
      val totalDurationMs = resolvedSession.totalDurationMs
      val absoluteStartMs = PlaybackPositionModel.bookAbsoluteMsFor(
        resolvedSession,
        adjustedStartIndex,
        adjustedStartPositionMs
      )
      if (totalDurationMs > 0 && (totalDurationMs - absoluteStartMs) < FINISHED_BOOK_THRESHOLD_MS) {
        adjustedStartIndex = 0
        adjustedStartPositionMs = 0L
      }

      MediaSession.MediaItemsWithStartPosition(
        resolvedPlayable.mediaItems,
        adjustedStartIndex,
        adjustedStartPositionMs
      )
    }
  }


  override fun onGetLibraryRoot(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<MediaItem>> {
    return Futures.immediateFuture(LibraryResult.ofItem(browseTree.getRootItem(), params))
  }

  override fun onGetChildren(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return autoLibraryCoordinator.requestChildren(parentId, page, pageSize, params)
  }

  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String
  ): ListenableFuture<LibraryResult<MediaItem>> {
    return scope.future {
      val mediaItem = browseTree.getItem(mediaId)
      if (mediaItem == null) {
        debugLog(logTag) { "onGetItem: browseTree.getItem failed to resolve '$mediaId'" }
        return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      }
      LibraryResult.ofItem(mediaItem, null)
    }
  }


  override fun onSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<Void>> {
    return scope.future {
      if (query.isBlank()) {
        searchCache.remove(query)
        session.notifySearchResultChanged(browser, query, 0, params)
        return@future LibraryResult.ofVoid()
      }
      val results = performSearch(query)
      cacheSearchResults(query, results)
      session.notifySearchResultChanged(browser, query, results.size, params)
      LibraryResult.ofVoid()
    }
  }

  override fun onGetSearchResult(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return scope.future {
      val results = searchCache[query] ?: run {
        val computed = performSearch(query)
        cacheSearchResults(query, computed)
        computed
      }
      session.notifySearchResultChanged(browser, query, results.size, params)
      val start = page * pageSize
      val end = (start + pageSize).coerceAtMost(results.size)
      val pageItems = if (start >= results.size) emptyList() else results.subList(start, end)
      LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params)
    }
  }

  private suspend fun performSearch(query: String): List<MediaItem> {
    if (query.isBlank()) return emptyList()
    val aggregatedResults = mutableListOf<MediaItem>()
    mediaManager.serverLibraries.forEach { library ->
      if ((library.stats?.numAudioFiles ?: 0) == 0) return@forEach
      val searchResult = runCatching { mediaManager.doSearchMedia3(library.id, query) }
        .onFailure { throwable ->
          Log.w(logTag, "onSearch: Failed to search ${library.id}", throwable)
        }
        .getOrNull()
        ?: return@forEach
      aggregatedResults.addAll(searchResult)
    }
    return aggregatedResults
  }



}
