package com.audiobookshelf.app.player.media3

import android.content.Context
import android.os.Bundle
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
import com.audiobookshelf.app.player.toPlayerMediaItems
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

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
class Media3SessionCallback(
  private val logTag: String,
  private val appPackageName: String,
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
  // Extras keys
  private val debug: ((() -> String) -> Unit),
  private val sessionController: SessionController? = null
) : MediaLibraryService.MediaLibrarySession.Callback {

  override fun onPlayerInteractionFinished(
    session: MediaSession,
    controllerInfo: MediaSession.ControllerInfo,
    playerCommands: Player.Commands
  ) {
    try {
      val pkg = controllerInfo.packageName
      val player = playerProvider()
      val jumpBack = seekConfig.jumpBackwardMs
      val jumpForward = seekConfig.jumpForwardMs
      if (pkg.contains("wear", ignoreCase = true) || pkg.contains(
          "com.google.android.apps.wear",
          ignoreCase = true
        )
      ) {
        if (playerCommands.contains(Player.COMMAND_SEEK_BACK) || playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) {
          val p = player.currentPosition
          val target = (p - jumpBack).coerceAtLeast(0L)
          debug { "Wear interaction SEEK_BACK -> seekTo=$target" }
          player.seekTo(target)
        }
        if (playerCommands.contains(Player.COMMAND_SEEK_FORWARD) || playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) {
          val p = player.currentPosition
          val dur = player.duration
          val target = (p + jumpForward).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
          debug { "Wear interaction SEEK_FORWARD -> seekTo=$target" }
          player.seekTo(target)
        }
      }
    } catch (t: Throwable) {
      Log.w(logTag, "onPlayerInteractionFinished handling error: ${t.message}")
    }
  }

  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
  ): MediaSession.ConnectionResult {
    val player = playerProvider()
    val isWear = controller.packageName.contains("wear", ignoreCase = true)
    (player as? AbsPlayerWrapper)?.mapSkipToSeek = isWear

    val playerCommands = sessionController?.buildPlayerCommands(
      controllerInfo = controller,
      allowSeekingOnMediaControls = seekConfig.allowSeekingOnMediaControls,
      appPackageName = appPackageName
    ) ?: run {
      val availablePlayerCommands = player.availableCommands
      val builder = Player.Commands.Builder().addAll(availablePlayerCommands)
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_DEVICE_VOLUME)
        .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
        .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
      builder.build()
    }

    fun cmd(c: Int) = if (playerCommands.contains(c)) "Y" else "N"
    debug {
      "Controller connected pkg=${controller.packageName}. cmd: BACK=${cmd(Player.COMMAND_SEEK_BACK)} " +
        "FWD=${cmd(Player.COMMAND_SEEK_FORWARD)} SEEK_IN_ITEM=${cmd(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)} " +
        "PREV=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS)} NEXT=${cmd(Player.COMMAND_SEEK_TO_NEXT)} " +
        "PREV_ITEM=${cmd(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)} NEXT_ITEM=${cmd(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)} " +
        "VOL_GET=${cmd(Player.COMMAND_GET_DEVICE_VOLUME)} VOL_SET=${cmd(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)} " +
        "VOL_ADJ=${cmd(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)}"
    }

    // Advertise custom session commands alongside defaults so controllers can render actions
    val sessionCommands = run {
      val base = sessionController?.availableSessionCommands
        ?: MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
      val builder = base.buildUpon()
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.CYCLE_PLAYBACK_SPEED,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.SEEK_BACK_INCREMENT,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.SEEK_FORWARD_INCREMENT,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.SEEK_TO_PREVIOUS_CHAPTER,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.SEEK_TO_NEXT_CHAPTER,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.SEEK_TO_CHAPTER,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.SYNC_PROGRESS_FORCE,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.MARK_UI_PLAYBACK_EVENT,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.SleepTimer.ACTION_SET,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.SleepTimer.ACTION_CANCEL,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.SleepTimer.ACTION_ADJUST,
          Bundle.EMPTY
        )
      )
      builder.add(
        SessionCommand(
          com.audiobookshelf.app.player.Media3PlaybackService.Companion.SleepTimer.ACTION_GET_TIME,
          Bundle.EMPTY
        )
      )
      builder.build()
    }

    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
      .setAvailableSessionCommands(sessionCommands)
      .setAvailablePlayerCommands(playerCommands)
      .build()
  }

  override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
    super.onPostConnect(session, controller)
    if (BuildConfig.DEBUG) {
      debug { "Post-connect: controller=${controller.packageName}" }
    }
  }

  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    if (BuildConfig.DEBUG) {
      debug { "onPlaybackResumption: Controller '${controller.packageName}' requested to resume playback." }
    }

    return scope.future {
      val preferServerUrisForCast = isCastActive()

      val latestServerItem = mediaManager.latestServerItemInProgress

      if (latestServerItem != null) {
        val mediaId = latestServerItem.episode?.id ?: latestServerItem.libraryItemWrapper.id
        val resolved = browseApi.resolve(mediaId, preferServerUrisForCast)

        if (resolved != null) {
          browseApi.assignSession(resolved.session)
          if (BuildConfig.DEBUG) {
          debug {
            "onPlaybackResumption: Resolved server in-progress item=${resolved.session.id} " +
              "startIndex=${resolved.startIndex} startPos=${resolved.startPositionMs}"
          }
          }
          return@future MediaSession.MediaItemsWithStartPosition(
            resolved.mediaItems,
            resolved.startIndex,
            resolved.startPositionMs
          )
        } else {
          Log.w(
            logTag,
            "onPlaybackResumption: Failed to resolve server in-progress item '$mediaId'. Falling back to last local session."
          )
        }
      }

      val lastSession = com.audiobookshelf.app.device.DeviceManager.getLastPlaybackSession()
      if (lastSession == null) {
        Log.w(logTag, "onPlaybackResumption: No last playback session found. Returning empty.")
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      if (BuildConfig.DEBUG) {
        debug { "onPlaybackResumption: Found last session for item '${lastSession.libraryItemId}' at ${lastSession.currentTimeMs}ms." }
      }

      val preferServerUris = preferServerUrisForCast && lastSession.isLocal
      val mediaItems = lastSession.toPlayerMediaItems(
        appContext,
        preferServerUrisForCast = preferServerUris
      ).mapIndexed { index, playerMediaItem ->
        val mediaId = "${lastSession.id}_${index}"
        MediaItem.Builder()
          .setUri(playerMediaItem.uri)
          .setMediaId(mediaId)
          .setMediaMetadata(
            MediaMetadata.Builder()
              .setTitle(lastSession.displayTitle)
              .setArtist(lastSession.displayAuthor)
              .setArtworkUri(playerMediaItem.artworkUri)
              .build()
          )
          .build()
      }

      if (mediaItems.isEmpty()) {
        Log.e(logTag, "onPlaybackResumption: Failed to create MediaItems from last session.")
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      } else {
        val serverProgressMs = mediaManager.serverUserMediaProgress.find {
          it.libraryItemId == lastSession.libraryItemId && it.episodeId == lastSession.episodeId
        }?.currentTime?.times(1000)?.toLong() ?: 0L

        val resumeMs = maxOf(serverProgressMs, lastSession.currentTimeMs)
        val startIndex = resolveTrackIndexForPosition(lastSession, resumeMs)
          .coerceIn(0, mediaItems.lastIndex)
        val trackStartOffsetMs = lastSession.getTrackStartOffsetMs(startIndex)
        val startPositionMs = (resumeMs - trackStartOffsetMs).coerceAtLeast(0L)

        debug { "onPlaybackResumption: Resuming at index $startIndex with position ${startPositionMs}ms." }

        browseApi.assignSession(lastSession)
        MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
      }
    }
  }

  override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle
  ): ListenableFuture<SessionResult> {
    if (customCommand.customAction ==
      com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.CLOSE_PLAYBACK
    ) {
      val future = SettableFuture.create<SessionResult>()
      sessionController?.closePlayback {
        future.set(SessionResult(SessionResult.RESULT_SUCCESS))
      } ?: future.set(SessionResult(SessionError.ERROR_UNKNOWN))
      return future
    }
    if (customCommand.customAction ==
      com.audiobookshelf.app.player.Media3PlaybackService.Companion.CustomCommands.MARK_UI_PLAYBACK_EVENT
    ) {
      markNextPlaybackEventSourceUi?.invoke()
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
    val result = sessionController?.onCustomCommand(customCommand, args)
      ?: SessionResult(SessionResult.RESULT_SUCCESS)
    return Futures.immediateFuture(result)
  }


  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: MutableList<MediaItem>
  ): ListenableFuture<MutableList<MediaItem>> {
    return scope.future {
      awaitFinalSync()
      // Signal service to avoid server sync on the final close of the current session (handoff).
      (appContext as? com.audiobookshelf.app.player.Media3PlaybackService)?.markSuppressFinalServerSync()
      val requested = mediaItems.firstOrNull()
      if (requested == null) {
        if (BuildConfig.DEBUG) {
        debug { "onAddMediaItems: empty request from ${controller.packageName}" }
        }
        return@future mutableListOf()
      }

      val isPlayableRequest =
        requested.localConfiguration != null || requested.requestMetadata.mediaUri != null
      if (isPlayableRequest) {
        if (BuildConfig.DEBUG) {
          debug { "onAddMediaItems: passthrough playable request '${requested.mediaId}'" }
        }
        if (!browseApi.passthroughAllowed(requested.mediaId, controller)) {
          if (BuildConfig.DEBUG) {
          debug { "onAddMediaItems: rejecting passthrough request for id=${requested.mediaId}" }
          }
          return@future mutableListOf()
        }
        return@future mediaItems
      }

      val mediaId = requested.mediaId
      val preferServerUrisForCast = isCastActive()
      val resolved = browseApi.resolve(mediaId, preferServerUrisForCast)

      if (resolved == null) {
        if (BuildConfig.DEBUG) {
          debug { "onAddMediaItems: unable to resolve mediaId=$mediaId" }
        }
        return@future mutableListOf()
      }

      browseApi.assignSession(resolved.session)
      if (BuildConfig.DEBUG) {
        debug {
          "onAddMediaItems: resolved ${resolved.mediaItems.size} items for session=${resolved.session.id} " +
            "startIndex=${resolved.startIndex} startPos=${resolved.startPositionMs}"
        }
      }
      val player = playerProvider()
      player.setMediaItems(
        resolved.mediaItems,
        resolved.startIndex.coerceIn(0, resolved.mediaItems.lastIndex),
        resolved.startPositionMs
      )
      player.prepare()
      return@future resolved.mediaItems.toMutableList()
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
      (appContext as? com.audiobookshelf.app.player.Media3PlaybackService)?.markSuppressFinalServerSync()
      val playablePassthrough =
        mediaItems.filter { it.localConfiguration != null || it.requestMetadata.mediaUri != null }
      if (playablePassthrough.isNotEmpty()) {
        debug { "onSetMediaItems: passthrough items=${playablePassthrough.size}" }
        if (playablePassthrough.any { !browseApi.passthroughAllowed(it.mediaId, controller) }) {
          debug { "onSetMediaItems: rejecting passthrough set; ids do not match current session" }
          return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }
        return@future MediaSession.MediaItemsWithStartPosition(
          playablePassthrough,
          startIndex,
          startPositionMs
        )
      }

      val requested = mediaItems.firstOrNull()
      if (requested == null) {
        debug { "onSetMediaItems: empty request from ${controller.packageName}" }
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      val mediaId = requested.mediaId
      val preferServerUrisForCast = isCastActive()
      val resolved = browseApi.resolve(mediaId, preferServerUrisForCast)

      if (resolved == null) {
        if (BuildConfig.DEBUG) {
          debug { "onSetMediaItems: unable to resolve mediaId=$mediaId" }
        }
        return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      browseApi.assignSession(resolved.session)
      if (BuildConfig.DEBUG) {
        debug {
          "onSetMediaItems: resolved ${resolved.mediaItems.size} items for session=${resolved.session.id} " +
            "startIndex=${resolved.startIndex} startPos=${resolved.startPositionMs}"
        }
      }
      MediaSession.MediaItemsWithStartPosition(
        resolved.mediaItems,
        resolved.startIndex,
        resolved.startPositionMs
      )
    }
  }

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
      Log.d(logTag, "onGetChildren requested for parentId: '$parentId'")
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

}
