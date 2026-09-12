package com.audiobookshelf.app.player.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder

interface PlaybackSessionHost {
  val playbackMetrics: PlaybackMetricsRecorder
  var isPlayerInitialized: Boolean

  fun currentSession(): PlaybackSession?
  fun playerOrNull(): Player?
  fun currentMediaPlayerId(): String

  fun updateCurrentPosition(session: PlaybackSession)
  fun maybeSyncProgress(
    reason: String,
    force: Boolean = false,
    targetSession: PlaybackSession? = null,
    onSyncComplete: ((SyncResult?) -> Unit)? = null
  )
  fun claimTerminalSync(): Boolean
  fun startProgressSyncIfPlaying(session: PlaybackSession)
  fun resetProgressSyncState()
  fun closeSessionOnServer(sessionId: String)

  fun notifyWidgetState(isPlaybackClosed: Boolean = false, isPlayingOverride: Boolean? = null)
}

interface PlaybackEventSink : PlaybackSessionHost {
  fun isEffectivelyPlaying(): Boolean

  fun progressSyncPlay(session: PlaybackSession)
  fun progressSyncPause()

  fun onPlayStarted(sessionId: String)
  fun handlePlaybackError(playbackError: PlaybackException)
  fun handleFatalPlaybackError(message: String)
  fun handlePlaybackEnded(session: PlaybackSession)
  fun handlePlaybackResumed(pauseDurationMs: Long)
  fun handleCastDeviceChanged(isCast: Boolean)

  fun updatePlaybackSpeedButton(speed: Float)

  fun debug(message: () -> String)
}

interface PlaybackCommandTarget : PlaybackSessionHost {
  fun currentAbsolutePositionMs(): Long?

  fun closePlayback(calledOnError: Boolean = false, onPlaybackStopped: (() -> Unit)? = null)
  fun jumpBackward()
  fun jumpForward()
  fun cyclePlaybackSpeed(): Float

  fun setSleepTimer(sessionId: String, timeMs: Long, isChapter: Boolean)
  fun cancelSleepTimer()
  fun adjustSleepTimer(deltaMs: Long, increase: Boolean)
  fun getSleepTimerTimeMs(): Long
  fun resyncSleepTimerState()
}
