package com.audiobookshelf.app.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.media.UnifiedMediaProgressSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(UnstableApi::class)
class Media3ProgressManager
  (
  private val unifiedProgressSyncer: UnifiedMediaProgressSyncer,
  private val serviceScope: CoroutineScope,
  private val currentSessionProvider: () -> PlaybackSession?,
  private val playbackServiceProvider: () -> Media3PlaybackService,
  private val debugLog: (String) -> Unit
) {
  companion object {
    private const val POSITION_UPDATE_INTERVAL_MS = 1_000L
  }

  private var positionUpdateJob: Job? = null

  fun startPositionUpdates() {
    stopPositionUpdates() // Cancel any existing job

    positionUpdateJob = serviceScope.launch {
      while (isActive) {
        val session = currentSessionProvider()
        if (session != null) {
          updateCurrentPosition(session)
        }
        delay(POSITION_UPDATE_INTERVAL_MS)
      }
    }
  }

  fun stopPositionUpdates() {
    positionUpdateJob?.cancel()
    positionUpdateJob = null
  }

  @OptIn(UnstableApi::class)
  fun updateCurrentPosition(session: PlaybackSession) {
    val playbackService = playbackServiceProvider()
    if (playbackService.hasActivePlayerPublic) {
      val player = playbackService.activePlayerPublic
      val trackIndex = resolveTrackIndexForPlayer(session, player)
      val positionInTrack = player.currentPosition
      val trackStartOffset = session.getTrackStartOffsetMs(trackIndex)
      val absolutePositionMs = trackStartOffset + positionInTrack

      session.currentTime = absolutePositionMs / 1000.0
    }
  }

  private fun resolveTrackIndexForPlayer(session: PlaybackSession, player: Player): Int {
    if (session.audioTracks.isEmpty()) return 0
    val mediaId = player.currentMediaItem?.mediaId
    if (!mediaId.isNullOrEmpty()) {
      val indexById = session.audioTracks.indexOfFirst { track ->
        val trackId = "${session.id}_${track.stableId}"
        trackId == mediaId
      }
      if (indexById >= 0) return indexById
    }
    val playerIndex = player.currentMediaItemIndex
    if (playerIndex in session.audioTracks.indices) {
      return playerIndex
    }
    return session.getCurrentTrackIndex().coerceIn(0, session.audioTracks.lastIndex)
  }

  fun maybeSyncProgress(
    reason: String,
    shouldSyncServer: Boolean,
    session: PlaybackSession? = null,
    onComplete: ((SyncResult?) -> Unit)? = null
  ) {
    val currentSession = session ?: currentSessionProvider() ?: return

    unifiedProgressSyncer.syncNow(reason, currentSession, shouldSyncServer, onComplete ?: {})
  }

  fun resolveTrackIndexForPosition(session: PlaybackSession, positionMs: Long): Int {
    if (session.audioTracks.isEmpty()) return 0

    var cumulativeDuration = 0L
    for ((index, track) in session.audioTracks.withIndex()) {
      cumulativeDuration += (track.duration * 1000).toLong()
      if (positionMs < cumulativeDuration) {
        return index
      }
    }

    // If position is beyond all tracks, return the last track
    return max(0, session.audioTracks.size - 1)
  }

}
