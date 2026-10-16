package com.audiobookshelf.app.player.media3

import android.os.Handler
import android.os.Looper
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.SyncResult

/** UI event delivery must stay on the main thread; progress persistence remains in the syncer. */
class Media3EventPipeline {
  companion object {
    private const val TAG = "Media3EventPipeline"
    private const val TERMINAL_EVENT_SUPPRESSION_WINDOW_MS = 2000L
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private var lastTerminalStopMediaItemId: String? = null
  private var lastTerminalStopCurrentTime: Double? = null
  private var lastTerminalStopTimestampMs: Long = 0L

  fun emitPlayEvent(playbackSession: PlaybackSession) {
    debugLog(TAG) { "Emit Play: ${playbackSession.displayTitle}" }
    mainHandler.post { MediaEventManager.playEvent(playbackSession) }
  }

  fun emitPauseEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    if (shouldSuppressPauseAfterTerminalStop(playbackSession)) {
      debugLog(TAG) { "Suppress Pause after recent Stop: ${playbackSession.displayTitle}" }
      return
    }
    debugLogWithSync("Pause", playbackSession, syncResult)
    mainHandler.post { MediaEventManager.pauseEvent(playbackSession, syncResult) }
  }

  fun emitStopEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    recordTerminalStop(playbackSession)
    debugLogWithSync("Stop", playbackSession, syncResult)
    mainHandler.post { MediaEventManager.stopEvent(playbackSession, syncResult) }
  }

  fun emitSaveEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    debugLogWithSync("Save", playbackSession, syncResult)
    mainHandler.post { MediaEventManager.saveEvent(playbackSession, syncResult) }
  }

  fun emitFinishedEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    debugLogWithSync("Finished", playbackSession, syncResult)
    mainHandler.post { MediaEventManager.finishedEvent(playbackSession, syncResult) }
  }

  fun emitSeekEvent(playbackSession: PlaybackSession, syncResult: SyncResult?) {
    debugLog(TAG) {
      "Emit Seek: ${playbackSession.displayTitle} to ${playbackSession.currentTime}s (syncResult: ${syncResult?.serverSyncAttempted})"
    }
    mainHandler.post { MediaEventManager.seekEvent(playbackSession, syncResult) }
  }

  private fun debugLogWithSync(eventName: String, session: PlaybackSession, syncResult: SyncResult?) {
    debugLog(TAG) { "Emit $eventName: ${session.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})" }
  }

  @Synchronized
  private fun recordTerminalStop(session: PlaybackSession) {
    lastTerminalStopMediaItemId = session.mediaItemId
    lastTerminalStopCurrentTime = session.currentTime
    lastTerminalStopTimestampMs = System.currentTimeMillis()
  }

  @Synchronized
  private fun shouldSuppressPauseAfterTerminalStop(session: PlaybackSession): Boolean {
    if (System.currentTimeMillis() - lastTerminalStopTimestampMs > TERMINAL_EVENT_SUPPRESSION_WINDOW_MS) {
      return false
    }
    if (lastTerminalStopMediaItemId != session.mediaItemId) {
      return false
    }
    return lastTerminalStopCurrentTime == session.currentTime
  }
}
