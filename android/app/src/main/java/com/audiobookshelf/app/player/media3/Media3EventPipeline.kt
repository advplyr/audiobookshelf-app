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
  private val tag = "Media3EventPipeline"
  private val mainHandler = Handler(Looper.getMainLooper())

  fun emitPlayEvent(
    session: PlaybackSession,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) Log.d(tag, "Emit Play: ${session.displayTitle}")
    mainHandler.post {
      MediaEventManager.playEvent(session, source)
    }
  }

  fun emitPauseEvent(
    session: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        tag,
        "Emit Pause: ${session.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.pauseEvent(session, syncResult, source)
    }
  }

  fun emitStopEvent(
    session: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        tag,
        "Emit Stop: ${session.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.stopEvent(session, syncResult, source)
    }
  }

  fun emitSaveEvent(
    session: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        tag,
        "Emit Save: ${session.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.saveEvent(session, syncResult, source)
    }
  }

  fun emitFinishedEvent(
    session: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        tag,
        "Emit Finished: ${session.displayTitle} (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.finishedEvent(session, syncResult, source)
    }
  }

  fun emitSeekEvent(
    session: PlaybackSession,
    syncResult: SyncResult?,
    source: PlaybackEventSource = PlaybackEventSource.SYSTEM
  ) {
    if (BuildConfig.DEBUG) {
      Log.d(
        tag,
        "Emit Seek: ${session.displayTitle} to ${session.currentTime}s (syncResult: ${syncResult?.serverSyncAttempted})"
      )
    }
    mainHandler.post {
      MediaEventManager.seekEvent(session, syncResult, source)
    }
  }
}
