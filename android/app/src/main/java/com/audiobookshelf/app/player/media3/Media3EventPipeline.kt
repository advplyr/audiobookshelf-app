package com.audiobookshelf.app.player.media3

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.PlaybackEventSource
import com.audiobookshelf.app.media.SyncResult

/**
 * Single source of truth for all Media3 playback event emission.
 *
 * Events are delivered on the main thread for UI safety. This pipeline does NOT
 * handle persistence (that is the syncer's job) -- it only handles event emission
 * to history and UI delivery.
 */
class Media3EventPipeline {
    companion object {
        private const val TAG = "Media3EventPipeline"
        private const val TERMINAL_EVENT_SUPPRESSION_WINDOW_MS = 2000L
    }

  private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTerminalStopMediaItemId: String? = null
    private var lastTerminalStopCurrentTime: Double? = null
    private var lastTerminalStopTimestampMs: Long = 0L

  fun emitPlayEvent(
    playbackSession: PlaybackSession,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
      debugLog { "Emit Play: ${playbackSession.displayTitle}" }
      mainHandler.post { MediaEventManager.playEvent(playbackSession, source) }
  }

  fun emitPauseEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
      if (shouldSuppressPauseAfterTerminalStop(playbackSession)) {
          debugLog { "Suppress Pause after recent Stop: ${playbackSession.displayTitle}" }
          return
      }
      debugLogWithSync("Pause", playbackSession, syncResult)
      mainHandler.post { MediaEventManager.pauseEvent(playbackSession, syncResult, source) }
  }

  fun emitStopEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
      recordTerminalStop(playbackSession)
      debugLogWithSync("Stop", playbackSession, syncResult)
      mainHandler.post { MediaEventManager.stopEvent(playbackSession, syncResult, source) }
  }

  fun emitSaveEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
      debugLogWithSync("Save", playbackSession, syncResult)
      mainHandler.post { MediaEventManager.saveEvent(playbackSession, syncResult, source) }
  }

  fun emitFinishedEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
      debugLogWithSync("Finished", playbackSession, syncResult)
      mainHandler.post { MediaEventManager.finishedEvent(playbackSession, syncResult, source) }
  }

  fun emitSeekEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
      debugLog {
          "Emit Seek: ${playbackSession.displayTitle} to ${playbackSession.currentTime}s (syncResult: ${syncResult?.serverSyncAttempted})"
    }
      mainHandler.post { MediaEventManager.seekEvent(playbackSession, syncResult, source) }
  }

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    private fun debugLogWithSync(eventName: String, session: PlaybackSession, syncResult: SyncResult?) {
        debugLog { "Emit $eventName: ${session.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})" }
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
        if (lastTerminalStopMediaItemId!=session.mediaItemId) {
            return false
        }
        return lastTerminalStopCurrentTime==session.currentTime
    }
}
