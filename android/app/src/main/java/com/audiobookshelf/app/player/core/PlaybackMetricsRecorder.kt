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
  private var playbackStartTimeMs: Long = 0L
  private var timeToFirstReadyMs: Long = -1L
  private var bufferCount: Int = 0
  private var playbackErrorCount: Int = 0
  private var playerId: String? = null
  private var mediaItemId: String? = null
  private var serviceStartMonotonicMs: Long = 0L
  private var serviceReadyLatencyMs: Long = -1L
  private var castHandoffStartMs: Long = -1L
  private var castHandoffCount: Int = 0
  private var recoverableRetryCount: Int = 0

  fun begin(playerId: String?, mediaItemId: String?) {
    playbackStartTimeMs = SystemClock.elapsedRealtime()
    timeToFirstReadyMs = -1L
    bufferCount = 0
    playbackErrorCount = 0
    this@PlaybackMetricsRecorder.playerId = playerId
    this@PlaybackMetricsRecorder.mediaItemId = mediaItemId
  }

  fun updatePlayerId(playerId: String?) {
    this@PlaybackMetricsRecorder.playerId = playerId
  }

  fun noteServiceStart() {
    serviceStartMonotonicMs = SystemClock.elapsedRealtime()
    serviceReadyLatencyMs = -1L
  }

  fun recordServiceReady() {
    if (serviceReadyLatencyMs >= 0 || serviceStartMonotonicMs <= 0L) return
    serviceReadyLatencyMs = SystemClock.elapsedRealtime() - serviceStartMonotonicMs
    AbsLogger.info(
      logTag,
      "serviceReadyLatencyMs=$serviceReadyLatencyMs"
    )
  }

  fun markCastHandoffStart() {
    castHandoffStartMs = SystemClock.elapsedRealtime()
  }

  fun recordCastHandoffComplete(castTargetPlayerId: String) {
    if (castHandoffStartMs <= 0L) return
    val duration = SystemClock.elapsedRealtime() - castHandoffStartMs
    castHandoffStartMs = -1L
    castHandoffCount += 1
    AbsLogger.info(
      logTag,
      "castHandoff castTargetPlayerId=$castTargetPlayerId durationMs=$duration"
    )
  }

  fun recordRecoverableRetry() {
    recoverableRetryCount += 1
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
      if (timeToFirstReadyMs >= 0 || playbackStartTimeMs <= 0L) return
      timeToFirstReadyMs = SystemClock.elapsedRealtime() - playbackStartTimeMs
      AbsLogger.info(
        logTag,
        "startupReadyLatencyMs=$timeToFirstReadyMs player=$playerId item=$mediaItemId"
      )
    } catch (_: Exception) {
    }
  }

  fun logSummary() {
    try {
      if (playbackStartTimeMs <= 0L) return
      AbsLogger.info(
        logTag,
        "summary player=$playerId item=$mediaItemId buffers=$bufferCount errors=$playbackErrorCount startupReadyLatencyMs=$timeToFirstReadyMs serviceReadyLatencyMs=$serviceReadyLatencyMs castHandoffs=$castHandoffCount recoverableRetries=$recoverableRetryCount"
      )
    } catch (_: Exception) {
    }
  }
}
