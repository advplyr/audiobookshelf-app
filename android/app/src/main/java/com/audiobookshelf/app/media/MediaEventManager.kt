package com.audiobookshelf.app.media

import android.util.Log
import com.audiobookshelf.app.data.MediaItemEvent
import com.audiobookshelf.app.data.MediaItemHistory
import com.audiobookshelf.app.data.MediaProgressWrapper
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService

enum class PlaybackEventSource {
  UI,
  SYSTEM
}

object MediaEventManager {
  const val tag = "MediaEventManager"
  private const val DUPLICATE_PLAYBACK_EVENT_WINDOW_MS = 1000L
  private const val SEEK_PLAYBACK_SUPPRESSION_MS = 2000L
  private var lastSeekTimestampMs: Long = 0L
  private var skipNextPostSeekPlaybackEvent = false
  private var hasRecordedUiPlaybackEvent = false

  var clientEventEmitter: PlayerNotificationService.ClientEventEmitter? = null

  fun playEvent(
          playbackSession: PlaybackSession,
          source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    Log.i(tag, "Play Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Play", playbackSession, null, source)
  }

  fun pauseEvent(
          playbackSession: PlaybackSession,
          syncResult: SyncResult?,
          source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    Log.i(tag, "Pause Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Pause", playbackSession, syncResult, source)
  }

  fun stopEvent(
          playbackSession: PlaybackSession,
          syncResult: SyncResult?,
          source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    Log.i(tag, "Stop Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Stop", playbackSession, syncResult, source)
  }

  fun saveEvent(
          playbackSession: PlaybackSession,
          syncResult: SyncResult?,
          source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    Log.i(tag, "Save Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Save", playbackSession, syncResult, source)
  }

  fun finishedEvent(
          playbackSession: PlaybackSession,
          syncResult: SyncResult?,
          source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    Log.i(tag, "Finished Event for media \"${playbackSession.displayTitle}\"")
    addPlaybackEvent("Finished", playbackSession, syncResult, source)
  }

  fun seekEvent(
          playbackSession: PlaybackSession,
          syncResult: SyncResult?,
          source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    Log.i(
            tag,
            "Seek Event for media \"${playbackSession.displayTitle}\", currentTime=${playbackSession.currentTime}"
    )
    addPlaybackEvent("Seek", playbackSession, syncResult, source)
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
    val mediaItemHistory = getMediaItemHistoryMediaItem(mediaProgress.mediaItemId)
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
    mediaItemHistory.events.add(mediaItemEvent)
    DeviceManager.dbManager.saveMediaItemHistory(mediaItemHistory)

    clientEventEmitter?.onMediaItemHistoryUpdated(mediaItemHistory)
  }

  private fun addPlaybackEvent(
          eventName: String,
          playbackSession: PlaybackSession,
          syncResult: SyncResult?,
          source: PlaybackEventSource
  ) {
    val mediaItemHistory =
            getMediaItemHistoryMediaItem(playbackSession.mediaItemId)
                    ?: createMediaItemHistoryForSession(playbackSession)

    if (source == PlaybackEventSource.UI) {
      skipNextPostSeekPlaybackEvent = false
      lastSeekTimestampMs = 0L
      hasRecordedUiPlaybackEvent = true
    }
    if (shouldSkipPlaybackAfterRecentSeek(eventName)) {
      return
    }
    if (eventName in listOf("Play", "Pause") && source == PlaybackEventSource.SYSTEM && hasRecordedUiPlaybackEvent) {
      hasRecordedUiPlaybackEvent = false
      return
    }

    val now = System.currentTimeMillis()
    if (shouldSkipDuplicatePlaybackEvent(mediaItemHistory.events.lastOrNull(), eventName, playbackSession.currentTime, now)) {
      return
    }

    val mediaItemEvent =
            MediaItemEvent(
                    name = eventName,
                    type = "Playback",
                    description = "",
                    currentTime = playbackSession.currentTime,
                    serverSyncAttempted = syncResult?.serverSyncAttempted ?: false,
                    serverSyncSuccess = syncResult?.serverSyncSuccess,
                    serverSyncMessage = syncResult?.serverSyncMessage,
                    timestamp = now
            )
    mediaItemHistory.events.add(mediaItemEvent)
    if (eventName == "Seek") {
      lastSeekTimestampMs = now
      skipNextPostSeekPlaybackEvent = true
    }
    DeviceManager.dbManager.saveMediaItemHistory(mediaItemHistory)

    clientEventEmitter?.onMediaItemHistoryUpdated(mediaItemHistory)
  }

  private fun getMediaItemHistoryMediaItem(mediaItemId: String): MediaItemHistory? {
    return DeviceManager.dbManager.getMediaItemHistory(mediaItemId)
  }

  private fun createMediaItemHistoryForSession(playbackSession: PlaybackSession): MediaItemHistory {
    Log.i(tag, "Creating new media item history for media \"${playbackSession.displayTitle}\"")
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

  private fun shouldSkipDuplicatePlaybackEvent(
          lastEvent: MediaItemEvent?,
          eventName: String,
          currentTime: Double,
          nowMs: Long
  ): Boolean {
    if (lastEvent == null) return false
    if (lastEvent.type != "Playback") return false
    if (lastEvent.name != eventName) return false
    val lastCurrent = lastEvent.currentTime?.toDouble() ?: return false
    if (lastCurrent != currentTime) return false
    if (nowMs - lastEvent.timestamp > DUPLICATE_PLAYBACK_EVENT_WINDOW_MS) return false
    return true
  }

  private fun shouldSkipPlaybackAfterRecentSeek(eventName: String): Boolean {
    if (!skipNextPostSeekPlaybackEvent) return false
    if (eventName != "Play" && eventName != "Pause") return false
    val nowMs = System.currentTimeMillis()
    if (nowMs - lastSeekTimestampMs > SEEK_PLAYBACK_SUPPRESSION_MS) {
      skipNextPostSeekPlaybackEvent = false
      lastSeekTimestampMs = 0L
      return false
    }
    return true
  }
}
