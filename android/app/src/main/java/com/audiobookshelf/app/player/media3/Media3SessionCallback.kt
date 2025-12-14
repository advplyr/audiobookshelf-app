package com.audiobookshelf.app.player.media3

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.player.ANDROID_AUTOMOTIVE_PKG_NAME
import com.audiobookshelf.app.player.ANDROID_AUTO_PKG_NAME
import com.audiobookshelf.app.player.ANDROID_AUTO_SIMULATOR_PKG_NAME
import com.audiobookshelf.app.player.ANDROID_GSEARCH_PKG_NAME
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.core.NetworkMonitor
import com.audiobookshelf.app.player.toPlayerMediaItems
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

data class SeekConfig(
  val jumpBackwardMs: Long,
  val jumpForwardMs: Long,
  val allowSeekingOnMediaControls: Boolean
)

interface BrowseApi {
  fun getPayload(forceTranscode: Boolean): com.audiobookshelf.app.data.PlayItemRequestPayload
  suspend fun resolve(mediaId: String, preferCast: Boolean): Media3BrowseTree.ResolvedPlayable?
  fun assignSession(session: PlaybackSession)
  fun passthroughAllowed(
    mediaId: String?,
    controller: MediaSession.ControllerInfo?
  ): Boolean
}

@UnstableApi
/**
 * Media3 MediaSession.Callback implementation handling session interactions, browsing, and custom commands.
 * Manages Android Auto integration, custom playback controls, and session state.
 */
class Media3SessionCallback(
  private val logTag: String,
  private val scope: CoroutineScope,
  private val appContext: Context,
  private val browseTree: Media3BrowseTree,
  private val autoLibraryCoordinator: Media3AutoLibraryCoordinator,
  private val mediaManager: MediaManager,
  private val playerProvider: () -> Player,
  private val isCastActive: () -> Boolean,
  private val seekConfig: SeekConfig,
  private val browseApi: BrowseApi,
  private val resolveTrackIndexForPosition: (PlaybackSession, Long) -> Int,
  private val awaitFinalSync: suspend () -> Unit,
  private val markNextPlaybackEventSourceUi: (() -> Unit)? = null,
  private val debug: ((() -> String) -> Unit),
  private val sessionController: SessionController? = null
) : MediaLibraryService.MediaLibrarySession.Callback {

  companion object {
    private const val FINISHED_BOOK_THRESHOLD_MS = 5_000L
  }

  private val searchCache = Collections.synchronizedMap(mutableMapOf<String, List<MediaItem>>())
  private val androidAutoControllerPackages = setOf(
    ANDROID_AUTO_PKG_NAME,
    ANDROID_AUTO_SIMULATOR_PKG_NAME,
    ANDROID_GSEARCH_PKG_NAME,
    ANDROID_AUTOMOTIVE_PKG_NAME
  )

  /* ======== Session Management ======== */

  /**
   * Handles post-interaction adjustments for specific clients like Wear OS.
   * For Wear devices, converts skip commands to seek operations with custom jump distances.
   */
  override fun onPlayerInteractionFinished(
    session: MediaSession,
    controllerInfo: MediaSession.ControllerInfo,
    playerCommands: Player.Commands
  ) {
    try {
      val controllerPackageName = controllerInfo.packageName
      val isWearController = controllerPackageName.contains("wear", ignoreCase = true) ||
        controllerPackageName.contains("com.google.android.apps.wear", ignoreCase = true)

      if (isWearController) {
        val player = playerProvider()
        val jumpBackwardDurationMs = seekConfig.jumpBackwardMs
        val jumpForwardDurationMs = seekConfig.jumpForwardMs

        if (playerCommands.contains(Player.COMMAND_SEEK_BACK) || playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) {
          val currentPositionMs = player.currentPosition
          val target = (currentPositionMs - jumpBackwardDurationMs).coerceAtLeast(0L)
          debug { "Wear interaction SEEK_BACK -> seekTo=$target" }
          player.seekTo(target)
        }
        if (playerCommands.contains(Player.COMMAND_SEEK_FORWARD) || playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) {
          val currentPositionMs = player.currentPosition
          val durationMs = player.duration
          val target =
            (currentPositionMs + jumpForwardDurationMs).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
          debug { "Wear interaction SEEK_FORWARD -> seekTo=$target" }
          player.seekTo(target)
        }
      }
    } catch (throwable: Throwable) {
      Log.w(logTag, "onPlayerInteractionFinished handling error: ${throwable.message}")
    }
  }

  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
  ): MediaSession.ConnectionResult {
    val player = playerProvider()
    val isWearableDevice = controller.packageName.contains("wear", ignoreCase = true)
    (player as? AbsPlayerWrapper)?.mapSkipToSeek = isWearableDevice

    val isAppUiController = controller.connectionHints.getBoolean("isAppUiController", false)
    val controllerType = when {
      isAppUiController -> "APP_UI"
      controller.packageName.contains("wear", ignoreCase = true) -> "WEAR"
      controller.packageName.contains("auto", ignoreCase = true) -> "AUTO"
      else -> "OTHER"
    }

    val playerCommands = sessionController?.buildPlayerCommands(
      controllerInfo = controller,
      allowSeekingOnMediaControls = seekConfig.allowSeekingOnMediaControls
    ) ?: run {
      debug { "onConnect: sessionController is null, using fallback commands for pkg=${controller.packageName}" }
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


    if (BuildConfig.DEBUG) {
      fun cmd(commandCode: Int) = if (playerCommands.contains(commandCode)) "Y" else "N"
      Log.d(logTag, "onConnect: $controllerType controller (${controller.packageName})")
      Log.d(
        logTag,
        "  Commands: SEEK_IN_ITEM=${cmd(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)} BACK=${
          cmd(Player.COMMAND_SEEK_BACK)
        } FWD=${cmd(Player.COMMAND_SEEK_FORWARD)} " +
        "PREV=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS)} NEXT=${cmd(Player.COMMAND_SEEK_TO_NEXT)} " +
        "PREV_ITEM=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)} NEXT_ITEM=${cmd(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)} " +
          "VOL_GET=${cmd(Player.COMMAND_GET_DEVICE_VOLUME)} VOL_SET=${cmd(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)} VOL_ADJ=${
            cmd(
              Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
            )
          } " +
          "(allowSeekSetting=${seekConfig.allowSeekingOnMediaControls})"
      )
    }

    val sessionCommands = run {
      val baseSessionCommands = sessionController?.availableSessionCommands
        ?: MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
      val builder = baseSessionCommands.buildUpon()
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_BACK_INCREMENT))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_PREVIOUS_CHAPTER))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_NEXT_CHAPTER))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_CHAPTER))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SYNC_PROGRESS_FORCE))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.MARK_UI_PLAYBACK_EVENT))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_SET))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_CANCEL))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_ADJUST))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.SleepTimer.ACTION_GET_TIME))
      builder.build()
    }

    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
      .setAvailableSessionCommands(sessionCommands)
      .setAvailablePlayerCommands(playerCommands)
      .build()
  }

  /* ======== Playback Resumption ======== */

  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    if (BuildConfig.DEBUG) {
      debug { "onPlaybackResumption: Controller '${controller.packageName}' requested to resume playback." }
    }

    return scope.future {
      if (controller.packageName in androidAutoControllerPackages) {
        withTimeoutOrNull(3000) {
          autoLibraryCoordinator.awaitAutoDataLoaded()
        }
      }

      val preferCastStream = isCastActive()

      val latestUnfinishedItem = mediaManager.latestServerItemInProgress

      if (latestUnfinishedItem != null && NetworkMonitor.initialized && NetworkMonitor.hasConnectivity) {
        val mediaId = latestUnfinishedItem.episode?.id ?: latestUnfinishedItem.libraryItemWrapper.id
        val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)

        if (resolvedPlayable != null) {
          browseApi.assignSession(resolvedPlayable.session)
          if (BuildConfig.DEBUG) {
            debug {
              "onPlaybackResumption: Resolved server in-progress item=${resolvedPlayable.session.id} " +
                "startIndex=${resolvedPlayable.startIndex} startPos=${resolvedPlayable.startPositionMs}"
          }
          }
          return@future MediaSession.MediaItemsWithStartPosition(
            resolvedPlayable.mediaItems,
            resolvedPlayable.startIndex,
            resolvedPlayable.startPositionMs
          )
        } else {
          Log.w(
            logTag,
            "onPlaybackResumption: Failed to resolve server in-progress item '$mediaId'. Falling back to last local session."
          )
        }
      } else if (latestUnfinishedItem != null && (!NetworkMonitor.initialized || !NetworkMonitor.hasConnectivity)) {
        Log.w(
          logTag,
          "onPlaybackResumption: Network monitor not initialized or no network connectivity available. Skipping server in-progress item resolution and falling back to last local session."
        )
      }

      val lastLocalSession = com.audiobookshelf.app.device.DeviceManager.getLastPlaybackSession()
      if (lastLocalSession == null) {
        Log.w(logTag, "onPlaybackResumption: No last playback session found. Returning empty.")
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      if (BuildConfig.DEBUG) {
        debug { "onPlaybackResumption: Found last session for item '${lastLocalSession.libraryItemId}' at ${lastLocalSession.currentTimeMs}ms. isLocal=${lastLocalSession.isLocal}" }
      }

      if (!lastLocalSession.isLocal && NetworkMonitor.initialized && NetworkMonitor.hasConnectivity) {
        val mediaId = (lastLocalSession.episodeId ?: lastLocalSession.libraryItemId) ?: ""
        if (BuildConfig.DEBUG) {
          debug { "onPlaybackResumption: Attempting to re-resolve server item with mediaId='$mediaId'" }
        }
        if (mediaId.isNotEmpty()) {
          val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)
          if (resolvedPlayable != null) {
            browseApi.assignSession(resolvedPlayable.session)
            if (BuildConfig.DEBUG) {
              debug {
                "onPlaybackResumption: Re-resolved server session for last played item=${resolvedPlayable.session.id} " +
                  "startIndex=${resolvedPlayable.startIndex} startPos=${resolvedPlayable.startPositionMs}"
              }
            }
            return@future MediaSession.MediaItemsWithStartPosition(
              resolvedPlayable.mediaItems,
              resolvedPlayable.startIndex,
              resolvedPlayable.startPositionMs
            )
          } else {
            if (BuildConfig.DEBUG) {
              debug { "onPlaybackResumption: Failed to re-resolve server item '$mediaId', falling back to cached session" }
            }
          }
        }
      }

      val preferCastStreamForLocal = preferCastStream && lastLocalSession.isLocal
      val playerMediaItems = lastLocalSession.toPlayerMediaItems(
        appContext,
        preferServerUrisForCast = preferCastStreamForLocal
      )

      if (BuildConfig.DEBUG) {
        try {
          debug { "onPlaybackResumption: prepared URIs=${playerMediaItems.map { it.uri.toString() }}" }
        } catch (t: Throwable) {
          Log.w(logTag, "Failed to log playback URIs: ${t.message}")
        }
      }

      val mediaItems = playerMediaItems.mapIndexed { index, playerMediaItem ->
        val mediaId = "${lastLocalSession.id}_${index}"
        MediaItem.Builder()
          .setUri(playerMediaItem.uri)
          .setMediaId(mediaId)
          .setMediaMetadata(
            MediaMetadata.Builder()
              .setTitle(lastLocalSession.displayTitle)
              .setArtist(lastLocalSession.displayAuthor)
              .setArtworkUri(playerMediaItem.artworkUri)
              .build()
          )
          .build()
      }

      if (mediaItems.isEmpty()) {
        Log.e(logTag, "onPlaybackResumption: Failed to create MediaItems from last session.")
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      } else {
        val serverProgressDurationMs = mediaManager.serverUserMediaProgress.find {
          it.libraryItemId == lastLocalSession.libraryItemId && it.episodeId == lastLocalSession.episodeId
        }?.currentTime?.times(1000)?.toLong() ?: 0L

        val resumeAtMs = maxOf(serverProgressDurationMs, lastLocalSession.currentTimeMs)
        val startIndex = resolveTrackIndexForPosition(lastLocalSession, resumeAtMs)
          .coerceIn(0, mediaItems.lastIndex)
        val trackStartOffsetMs = lastLocalSession.getTrackStartOffsetMs(startIndex)
        val startPositionMs = (resumeAtMs - trackStartOffsetMs).coerceAtLeast(0L)

        debug { "onPlaybackResumption: Resuming at index $startIndex with position ${startPositionMs}ms." }

        browseApi.assignSession(lastLocalSession)
        MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
      }
    }
  }

  /* ======== Custom Commands ======== */

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

      PlaybackConstants.Commands.MARK_UI_PLAYBACK_EVENT -> {
        markNextPlaybackEventSourceUi?.invoke()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
      }

      else -> {
        val result = sessionController?.onCustomCommand(customCommand, args)
          ?: SessionResult(SessionResult.RESULT_SUCCESS)
        return Futures.immediateFuture(result)
      }
    }
  }

  /* ======== Media Item Management ======== */

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>
  ): ListenableFuture<MutableList<MediaItem>> {
    return scope.future {
      awaitFinalSync()
      val requestedMediaItem = mediaItems.firstOrNull()
      if (requestedMediaItem == null) {
        if (BuildConfig.DEBUG) {
        debug { "onAddMediaItems: empty request from ${controller.packageName}" }
        }
        return@future mutableListOf()
      }

      val isPlayable =
        requestedMediaItem.localConfiguration != null || requestedMediaItem.requestMetadata.mediaUri != null
      if (isPlayable) {
        if (BuildConfig.DEBUG) {
          debug { "onAddMediaItems: passthrough playable request '${requestedMediaItem.mediaId}'" }
        }
        if (!browseApi.passthroughAllowed(requestedMediaItem.mediaId, controller)) {
          if (BuildConfig.DEBUG) {
            debug { "onAddMediaItems: rejecting passthrough request for id=${requestedMediaItem.mediaId}" }
          }
          return@future mutableListOf()
        }
        return@future mediaItems
      }

      val mediaId = requestedMediaItem.mediaId
      val preferCastStream = isCastActive()
      val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)

      if (resolvedPlayable == null) {
        if (BuildConfig.DEBUG) {
          debug { "onAddMediaItems: unable to resolve mediaId=$mediaId" }
        }
        return@future mutableListOf()
      }

      browseApi.assignSession(resolvedPlayable.session)
      if (BuildConfig.DEBUG) {
        debug {
          "onAddMediaItems: resolved ${resolvedPlayable.mediaItems.size} items for session=${resolvedPlayable.session.id} " +
            "startIndex=${resolvedPlayable.startIndex} startPos=${resolvedPlayable.startPositionMs}"
        }
        try {
          debug { "onAddMediaItems: resolved URIs=${resolvedPlayable.mediaItems.map { item -> (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString() }}" }
        } catch (t: Throwable) {
          Log.w(logTag, "Failed to log resolvedPlayable URIs: ${t.message}")
        }
      }
      val player = playerProvider()
      player.setMediaItems(
        resolvedPlayable.mediaItems,
        resolvedPlayable.startIndex.coerceIn(0, resolvedPlayable.mediaItems.lastIndex),
        resolvedPlayable.startPositionMs
      )
      player.prepare()
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
        debug { "onSetMediaItems: passthrough items=${playablePassthrough.size}" }
        if (playablePassthrough.any { !browseApi.passthroughAllowed(it.mediaId, controller) }) {
          debug { "onSetMediaItems: rejecting passthrough set; ids do not match current session" }
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }
        if (BuildConfig.DEBUG) {
          debug { "onSetMediaItems: passthrough allowed, returning ${playablePassthrough.size} items with startIndex=$startIndex startPos=$startPositionMs from ${controller.packageName}" }
        }
        return@future MediaSession.MediaItemsWithStartPosition(
          playablePassthrough,
          startIndex,
          startPositionMs
        )
      }

      val requestedMediaItem = mediaItems.firstOrNull()
      if (requestedMediaItem == null) {
        debug { "onSetMediaItems: empty request from ${controller.packageName}" }
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      val mediaId = requestedMediaItem.mediaId
      val preferCastStream = isCastActive()
      val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)

      if (resolvedPlayable == null) {
        if (BuildConfig.DEBUG) {
          debug { "onSetMediaItems: unable to resolve mediaId=$mediaId" }
        }
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      browseApi.assignSession(resolvedPlayable.session)

      // Auto-restart logic: prevents awkward UX of resuming at the very end of a book
      // If within 5s of completion, restart from beginning instead
      var adjustedStartPositionMs = resolvedPlayable.startPositionMs
      val totalDurationMs = resolvedPlayable.session.totalDurationMs
      if (totalDurationMs > 0 && (totalDurationMs - adjustedStartPositionMs) < FINISHED_BOOK_THRESHOLD_MS) {
        if (BuildConfig.DEBUG) {
          debug { "onSetMediaItems: Book is finished (within ${FINISHED_BOOK_THRESHOLD_MS}ms of end), resetting to start" }
        }
        adjustedStartPositionMs = 0L
      }

      if (BuildConfig.DEBUG) {
        debug {
          "onSetMediaItems: resolved ${resolvedPlayable.mediaItems.size} items for session=${resolvedPlayable.session.id} " +
            "startIndex=${resolvedPlayable.startIndex} startPos=$adjustedStartPositionMs"
        }
      }
      MediaSession.MediaItemsWithStartPosition(
        resolvedPlayable.mediaItems,
        resolvedPlayable.startIndex,
        adjustedStartPositionMs
      )
    }
  }

  /* ======== Library Browsing ======== */

  override fun onGetLibraryRoot(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: LibraryParams?
  ): ListenableFuture<LibraryResult<MediaItem>> {
    debug { "onGetLibraryRoot requested by ${browser.packageName}" }
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
    if (BuildConfig.DEBUG) {
      Log.d(logTag, "onGetChildren requested for parentId: '$parentId' by ${browser.packageName}")
    }
    return autoLibraryCoordinator.requestChildren(parentId, params)
  }

  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String
  ): ListenableFuture<LibraryResult<MediaItem>> {
    debug { "onGetItem: Resolving '$mediaId' via browseTree for ${browser.packageName}" }
    return scope.future {
      val mediaItem = browseTree.getItem(mediaId)
      if (mediaItem == null) {
        debug { "onGetItem: browseTree.getItem failed to resolve '$mediaId'" }
        return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      }
      LibraryResult.ofItem(mediaItem, null)
    }
  }

  /* ======== Search ======== */

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
      searchCache[query] = results
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
      val (results, _) = searchCache[query]?.let { it to false } ?: run {
        val computed = performSearch(query)
        searchCache[query] = computed
        computed to true
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
      val searchResult = runCatching { mediaManager.doSearch(library.id, query) }
        .onFailure { throwable ->
          Log.w(logTag, "onSearch: Failed to search ${library.id}", throwable)
        }
        .getOrNull()
        ?: return@forEach
      searchResult.values.forEach { searchResultItems ->
        aggregatedResults.addAll(searchResultItems.mapNotNull { it.toMedia3Item() })
      }
    }
    return aggregatedResults
  }

  /* ======== Helper Functions ======== */

  private fun MediaBrowserCompat.MediaItem.toMedia3Item(): MediaItem? {
    val mediaDescription = description
    val isPlayable = flags and MediaBrowserCompat.MediaItem.FLAG_PLAYABLE != 0
    val isBrowsable = flags and MediaBrowserCompat.MediaItem.FLAG_BROWSABLE != 0
    val mediaExtras = mediaDescription.extras?.let { Bundle(it) }
    val metadata = MediaMetadata.Builder()
      .setTitle(mediaDescription.title?.toString())
      .setSubtitle(mediaDescription.subtitle?.toString())
      .setIsPlayable(isPlayable)
      .setIsBrowsable(isBrowsable)
      .apply {
        mediaDescription.iconUri?.let { setArtworkUri(it) }
        mediaExtras?.let { setExtras(it) }
      }
      .build()
    val mediaId = mediaDescription.mediaId ?: return null
    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(metadata)
      .also { mediaDescription.mediaUri?.let { artworkUri -> it.setUri(artworkUri) } }
      .build()
  }

}
