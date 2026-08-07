package com.audiobookshelf.app.player.core

import android.content.Context
import com.audiobookshelf.app.data.LocalMediaProgress

/**
 * Abstraction for components that expose playback telemetry required for cross-player sync.
 * Implementations bridge either the ExoPlayer notification service or the Media3 stack.
 */
interface PlaybackTelemetryHost {
  val appContext: Context
  val isUnmeteredNetwork: Boolean
  fun isPlayerActive(): Boolean
  fun getCurrentTimeSeconds(): Double
  fun alertSyncSuccess()
  fun alertSyncFailing()
  fun notifyLocalProgressUpdate(localMediaProgress: LocalMediaProgress)
  fun isSleepTimerActive(): Boolean = false
  fun checkAutoSleepTimer() {}
}
