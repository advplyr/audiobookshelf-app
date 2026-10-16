package com.audiobookshelf.app.player.core

import android.content.Context
import com.audiobookshelf.app.data.LocalMediaProgress

/**
 * The player state a progress syncer needs, and the callbacks it reports back through.
 * Implemented by both playback services so [com.audiobookshelf.app.media.MediaProgressSyncer]
 * and [com.audiobookshelf.app.player.media3.Media3ProgressSyncer] can share one contract.
 */
interface PlaybackStateHost {
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
