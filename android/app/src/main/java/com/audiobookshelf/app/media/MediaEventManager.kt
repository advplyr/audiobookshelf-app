package com.audiobookshelf.app.media

import android.util.Log
import com.audiobookshelf.app.data.MediaItemEvent
import com.audiobookshelf.app.data.MediaItemHistory
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.SyncResult

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
    Log.i(tag, "Seek Event for media \"${playbackSession.displayTitle}\", currentTime=${playbackSession.currentTime}")
    addPlaybackEvent("Seek", playbackSession, syncResult)
  }

  private fun addPlaybackEvent(eventName:String, playbackSession:PlaybackSession, syncResult:SyncResult?) {
    val mediaItemHistory = getMediaItemHistoryForSession(playbackSession) ?: createMediaItemHistoryForSession(playbackSession)

    val mediaItemEvent = MediaItemEvent(
      name = eventName,
      type = "Playback",
      description = "",
      currentTime = playbackSession.currentTime,
      serverSyncAttempted = syncResult?.serverSyncAttempted ?: false,
      serverSyncSuccess = syncResult?.serverSyncSuccess,
      serverSyncMessage = syncResult?.serverSyncMessage,
      timestamp = System.currentTimeMillis()
    )
    mediaItemHistory.events.add(mediaItemEvent)
    DeviceManager.dbManager.saveMediaItemHistory(mediaItemHistory)

    clientEventEmitter?.onMediaItemHistoryUpdated(mediaItemHistory)
  }

  private fun getMediaItemHistoryForSession(playbackSession: PlaybackSession) : MediaItemHistory? {
    return DeviceManager.dbManager.getMediaItemHistory(playbackSession.mediaItemId)
  }

  private fun createMediaItemHistoryForSession(playbackSession: PlaybackSession):MediaItemHistory {
    Log.i(tag, "Creating new media item history for media \"${playbackSession.displayTitle}\"")
    val isLocalOnly = playbackSession.isLocalLibraryItemOnly
    val libraryItemId = if (isLocalOnly) playbackSession.localLibraryItemId else playbackSession.libraryItemId ?: ""
    val episodeId:String? = if (isLocalOnly && playbackSession.localEpisodeId != null) playbackSession.localEpisodeId else playbackSession.episodeId
    return MediaItemHistory(
      id = playbackSession.mediaItemId,
      mediaDisplayTitle = playbackSession.displayTitle ?: "Unset",
      libraryItemId,
      episodeId,
      isLocalOnly,
      playbackSession.
      serverConnectionConfigId,
      playbackSession.serverAddress,
      playbackSession.userId,
      createdAt = System.currentTimeMillis(),
      events = mutableListOf())
  }
}
