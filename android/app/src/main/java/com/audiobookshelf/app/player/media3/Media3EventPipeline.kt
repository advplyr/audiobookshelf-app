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
 * Media3EventPipeline: Single source of truth for all Media3 playback event emission.
 *
 * This pipeline ensures:
 * - All events (Play/Pause/Stop/Seek/Finished) are emitted consistently
 * - Events are delivered on the main thread for UI safety
 * - SyncResult is properly attached to Pause/Stop/Seek events
 * - No duplicate events during transitions
 * - Complete feature parity with ExoPlayer event patterns
 *
 * The pipeline does NOT handle persistence (that's the syncer's job).
 * It ONLY handles: event emission to history + UI delivery.
 */
class Media3EventPipeline {
  private val loggingTag = "Media3EventPipeline"
  private val mainHandler = Handler(Looper.getMainLooper())

  fun emitPlayEvent(
    playbackSession: PlaybackSession,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) Log.d(loggingTag, "Emit Play: ${playbackSession.displayTitle}")
    mainHandler.post {
      MediaEventManager.playEvent(playbackSession, source)
    }
  }

  fun emitPauseEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        loggingTag,
        "Emit Pause: ${playbackSession.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.pauseEvent(playbackSession, syncResult, source)
    }
  }

  fun emitStopEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        loggingTag,
        "Emit Stop: ${playbackSession.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.stopEvent(playbackSession, syncResult, source)
    }
  }

  fun emitSaveEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        loggingTag,
        "Emit Save: ${playbackSession.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.saveEvent(playbackSession, syncResult, source)
    }
  }

  fun emitFinishedEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        loggingTag,
        "Emit Finished: ${playbackSession.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.finishedEvent(playbackSession, syncResult, source)
    }
  }

  fun emitSeekEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        loggingTag,
        "Emit Seek: ${playbackSession.displayTitle} to ${playbackSession.currentTime}s (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.seekEvent(playbackSession, syncResult, source)
    }
  }
}
