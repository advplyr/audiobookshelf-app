package com.audiobookshelf.app.player.media3

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
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import java.util.concurrent.ConcurrentHashMap

data class SeekConfig(
  val jumpBackwardMs: Long,
  val jumpForwardMs: Long,
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

/**
 * Media3 MediaSession.Callback implementation handling session interactions, browsing, and custom commands.
 * Manages Android Auto integration, custom playback controls, and session state.
 */
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
  private val markNextPlaybackEventSourceUi: (() -> Unit)? = null,
  private val debug: ((() -> String) -> Unit),
  private val sessionController: SessionController? = null
) : MediaLibraryService.MediaLibrarySession.Callback {

  companion object {
    private const val FINISHED_BOOK_THRESHOLD_MS = 5_000L
  }

  private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()
  private fun isWearController(controllerInfo: MediaSession.ControllerInfo): Boolean {
    val pkg = controllerInfo.packageName.lowercase()
    return pkg.contains("wear") || pkg.contains("com.google.android.apps.wear")
  }

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
      if (isWearController(controllerInfo)) {
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
    // Reject system UI to prevent MediaResumeListener connection delays
    if (controller.packageName == "com.android.systemui") {
      debug { "Rejecting MediaSession connection from system UI" }
      return MediaSession.ConnectionResult.reject()
    }

    val player = playerProvider()
    (player as? AbsPlayerWrapper)?.mapSkipToSeek = isWearController(controller)

    val isAppUiController = controller.connectionHints.getBoolean("isAppUiController", false)

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
        val controllerType = when {
            isAppUiController -> "APP_UI"
            isWearController(controller) -> "WEAR"
            controller.packageName.contains("gearhead", ignoreCase = true) -> "AUTO"
            else -> "OTHER"
        }
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
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_PREVIOUS_TRACK))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_NEXT_TRACK))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_PREVIOUS_CHAPTER))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_NEXT_CHAPTER))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_CHAPTER))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SYNC_PROGRESS_FORCE))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.MARK_UI_PLAYBACK_EVENT))
      builder.add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CLOSE_PLAYBACK))
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
        debug { "onAddMediaItems: empty request from ${controller.packageName}" }
        return@future mutableListOf()
      }

      val isPlayable =
        requestedMediaItem.localConfiguration != null || requestedMediaItem.requestMetadata.mediaUri != null
      if (isPlayable) {
          debug { "onAddMediaItems: passthrough playable request '${requestedMediaItem.mediaId}'" }
        if (!browseApi.passthroughAllowed(requestedMediaItem.mediaId, controller)) {
            debug { "onAddMediaItems: rejecting passthrough request for id=${requestedMediaItem.mediaId}" }
          return@future mutableListOf()
        }
        return@future mediaItems
      }

      val mediaId = requestedMediaItem.mediaId
      val preferCastStream = isCastActive()
      val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)

      if (resolvedPlayable == null) {
          debug { "onAddMediaItems: unable to resolve mediaId=$mediaId" }
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
        debug { "onSetMediaItems: empty request from ${controller.packageName}" }
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      val mediaId = requestedMediaItem.mediaId
      val preferCastStream = isCastActive()
      val resolvedPlayable = browseApi.resolve(mediaId, preferCastStream)

      if (resolvedPlayable == null) {
          debug { "onSetMediaItems: unable to resolve mediaId=$mediaId" }
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      browseApi.assignSession(resolvedPlayable.session)

      // Auto-restart logic: prevents awkward UX of resuming at the very end of a book
      // If within 5s of completion, restart from beginning instead
      var adjustedStartPositionMs = resolvedPlayable.startPositionMs
      val totalDurationMs = resolvedPlayable.session.totalDurationMs
      if (totalDurationMs > 0 && (totalDurationMs - adjustedStartPositionMs) < FINISHED_BOOK_THRESHOLD_MS) {
          debug { "onSetMediaItems: Book is finished (within ${FINISHED_BOOK_THRESHOLD_MS}ms of end), resetting to start" }
        adjustedStartPositionMs = 0L
      }

        debug {
            "onSetMediaItems: resolved ${resolvedPlayable.mediaItems.size} items for session=${resolvedPlayable.session.id} " +
                    "startIndex=${resolvedPlayable.startIndex} startPos=$adjustedStartPositionMs"
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
        val results = searchCache[query] ?: run {
        val computed = performSearch(query)
        searchCache[query] = computed
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
