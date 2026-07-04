package com.audiobookshelf.app.player.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.player.core.PlaybackMetricsRecorder

/**
 * The capabilities Media3PlaybackService exposes to its collaborators
 * (Media3PlayerEventListener, Media3SessionManager, SessionController).
 * The service implements this directly so collaborators share one surface
 * instead of per-class bridge objects.
 */
interface Media3ServiceHost {
  val playbackMetrics: PlaybackMetricsRecorder
  var isPlayerInitialized: Boolean

  /* State queries */
  fun currentSession(): PlaybackSession?
  fun playerOrNull(): Player?
  fun isEffectivelyPlaying(): Boolean
  fun currentMediaPlayerId(): String
  fun currentAbsolutePositionMs(): Long?

  /* Progress sync */
  fun updateCurrentPosition(session: PlaybackSession)
  fun maybeSyncProgress(
    reason: String,
    force: Boolean = false,
    targetSession: PlaybackSession? = null,
    onSyncComplete: ((SyncResult?) -> Unit)? = null
  )
  fun progressSyncPlay(session: PlaybackSession)
  fun progressSyncPause()
  fun resetProgressSyncState()
  fun closeSessionOnServer(sessionId: String)

  /* Playback events */
  fun onPlayStarted(sessionId: String)
  fun handlePlaybackError(playbackError: PlaybackException)
  fun handleFatalPlaybackError(message: String)
  fun handlePlaybackEnded(session: PlaybackSession)
  fun handlePlaybackResumed(pauseDurationMs: Long)
  fun handleCastDeviceChanged(isCast: Boolean)
  fun closePlayback(calledOnError: Boolean = false, onPlaybackStopped: (() -> Unit)? = null)

  /* Sleep timer */
  fun setSleepTimer(sessionId: String, timeMs: Long, isChapter: Boolean)
  fun cancelSleepTimer()
  fun adjustSleepTimer(deltaMs: Long, increase: Boolean)
  fun getSleepTimerTimeMs(): Long
  fun resyncSleepTimerState()

  /* UI surfaces */
  fun cyclePlaybackSpeed(): Float
  fun notifyWidgetState(isPlaybackClosed: Boolean = false, isPlayingOverride: Boolean? = null)
  fun updatePlaybackSpeedButton(speed: Float)

  fun debug(message: () -> String)
}
