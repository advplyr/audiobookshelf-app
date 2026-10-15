package com.audiobookshelf.app.media

import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService

object MediaEventManager {
  const val tag = "MediaEventManager"

  var clientEventEmitter: PlayerNotificationService.ClientEventEmitter? = null

  fun playEvent(playbackSession: PlaybackSession) {
    Log.i(tag, "Play Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Play", playbackSession, null)
  }

  fun pauseEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    Log.i(tag, "Pause Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Pause", playbackSession, syncResult)
  }

  fun stopEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    Log.i(tag, "Stop Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Stop", playbackSession, syncResult)
  }

  fun saveEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    Log.i(tag, "Save Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Save", playbackSession, syncResult)
  }

  fun finishedEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    Log.i(tag, "Finished Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Finished", playbackSession, syncResult)
  }

  fun seekEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    Log.i(
            tag,
            "Seek Event for media \"${playbackSession.displayTitle}\", currentTime=${playbackSession.currentTime}"
    )
    addPlaybackEvent("Seek", playbackSession, syncResult)
  }

  fun syncEvent(mediaProgress: MediaProgressWrapper, description: String) {
    Log.i(
            tag,
            "Sync Event for media item id \"${mediaProgress.mediaItemId}\", currentTime=${mediaProgress.currentTime}"
    )
    addSyncEvent("Sync", mediaProgress, description)
  }

  private fun addSyncEvent(
          eventName: String,
          mediaProgress: MediaProgressWrapper,
          description: String
  ) {
    // Sync events carry no identity of their own; the metadata is created by the playback events that
    // precede them, so an item that has never been played has nothing to attach to.
    val mediaItemHistory = DeviceManager.dbManager.getMediaItemHistoryMetadata(mediaProgress.mediaItemId)
    if (mediaItemHistory == null) {
      Log.w(
              tag,
              "addSyncEvent: Media Item History not created yet for media item id ${mediaProgress.mediaItemId}"
      )
      return
    }

    val mediaItemEvent =
            MediaItemEvent(
                    name = eventName,
                    type = "Sync",
                    description = description,
                    currentTime = mediaProgress.currentTime,
                    serverSyncAttempted = false,
                    serverSyncSuccess = null,
                    serverSyncMessage = null,
                    timestamp = System.currentTimeMillis()
            )
    DeviceManager.dbManager.appendMediaItemEvent(mediaItemHistory, mediaItemEvent)
    clientEventEmitter?.onMediaItemHistoryEventAdded(mediaItemHistory.id, mediaItemHistory.copyWithoutEvents(), mediaItemEvent)
  }

  private fun addPlaybackEvent(
          eventName: String,
          playbackSession: PlaybackSession,
          syncResult: SyncResult?
  ) {
    val mediaItemHistory = buildHistoryMetadataForSession(playbackSession)

    val mediaItemEvent =
            MediaItemEvent(
                    name = eventName,
                    type = "Playback",
                    description = "",
                    currentTime = playbackSession.currentTime,
                    serverSyncAttempted = syncResult?.serverSyncAttempted ?: false,
                    serverSyncSuccess = syncResult?.serverSyncSuccess,
                    serverSyncMessage = syncResult?.serverSyncMessage,
                    timestamp = System.currentTimeMillis()
            )
    DeviceManager.dbManager.appendMediaItemEvent(mediaItemHistory, mediaItemEvent)
    clientEventEmitter?.onMediaItemHistoryEventAdded(mediaItemHistory.id, mediaItemHistory.copyWithoutEvents(), mediaItemEvent)
  }

  private fun buildHistoryMetadataForSession(playbackSession: PlaybackSession): MediaItemHistory {
    val libraryItemId = playbackSession.libraryItemId ?: ""
    val episodeId: String? = playbackSession.episodeId
    return MediaItemHistory(
            id = playbackSession.mediaItemId,
            mediaDisplayTitle = playbackSession.displayTitle ?: "Unset",
            libraryItemId,
            episodeId,
            false, // local-only items are not supported
            playbackSession.serverConnectionConfigId,
            playbackSession.serverAddress,
            playbackSession.userId,
            createdAt = System.currentTimeMillis(),
            events = mutableListOf()
    )
  }
}
