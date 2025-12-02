package com.audiobookshelf.app.player.core

import android.os.SystemClock
import com.audiobookshelf.app.plugins.AbsLogger

/**
 * Lightweight helper that mirrors the rollout metrics the ExoPlayer player recorded so
 * both the Media3 stack and the ExoPlayer-based PlayerNotificationService can share the same logic.
 */
class PlaybackMetricsRecorder(
  private val logTag: String = "PlaybackMetrics"
) {
  private var playbackStartMonotonicMs: Long = 0L
  private var firstReadyLatencyMs: Long = -1L
  private var bufferCount: Int = 0
  private var playbackErrorCount: Int = 0
  private var playerId: String? = null
  private var mediaItemId: String? = null

  fun begin(player: String?, mediaItem: String?) {
    playbackStartMonotonicMs = SystemClock.elapsedRealtime()
    firstReadyLatencyMs = -1L
    bufferCount = 0
    playbackErrorCount = 0
    playerId = player
    mediaItemId = mediaItem
  }

  fun recordError() {
    try {
      playbackErrorCount += 1
    } catch (_: Exception) {
    }
  }

  fun recordBuffer() {
    try {
      bufferCount += 1
    } catch (_: Exception) {
    }
  }

  fun recordFirstReadyIfUnset() {
    try {
      if (firstReadyLatencyMs >= 0 || playbackStartMonotonicMs <= 0L) return
      firstReadyLatencyMs = SystemClock.elapsedRealtime() - playbackStartMonotonicMs
      AbsLogger.info(
        logTag,
        "startupReadyLatencyMs=$firstReadyLatencyMs player=$playerId item=$mediaItemId"
      )
    } catch (_: Exception) {
    }
  }

  fun logSummary() {
    try {
      if (playbackStartMonotonicMs <= 0L) return
      AbsLogger.info(
        logTag,
        "summary player=$playerId item=$mediaItemId buffers=$bufferCount errors=$playbackErrorCount startupReadyLatencyMs=$firstReadyLatencyMs"
      )
    } catch (_: Exception) {
    }
  }
}
