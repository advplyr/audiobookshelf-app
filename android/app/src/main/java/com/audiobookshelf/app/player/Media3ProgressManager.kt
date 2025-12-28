package com.audiobookshelf.app.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.data.PlaybackSession
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class Media3ProgressManager
  (
  private val playbackServiceProvider: () -> Media3PlaybackService
) {
  // Cache for track ID -> index lookups to avoid O(n) search
  private var trackIdCache: Map<String, Int>? = null
  private var cachedSessionId: String? = null

  // Cache for cumulative track durations to optimize seek operations (binary search)
  private var cumulativeDurationsMs: LongArray? = null

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

  fun resolveTrackIndexForPlayer(session: PlaybackSession, player: Player): Int {
    if (session.audioTracks.isEmpty()) return 0

    // Build caches if session changed or not initialized
    if (trackIdCache == null || cachedSessionId != session.id) {
      trackIdCache = session.audioTracks.mapIndexed { index, track ->
        "${session.id}_${track.stableId}" to index
      }.toMap()

      // Pre-compute cumulative durations for binary search during seeks
      val cumulative = LongArray(session.audioTracks.size)
      var total = 0L
      session.audioTracks.forEachIndexed { index, track ->
        total += (track.duration * 1000).toLong()
        cumulative[index] = total
      }
      cumulativeDurationsMs = cumulative

      cachedSessionId = session.id
    }

    val mediaId = player.currentMediaItem?.mediaId
    if (!mediaId.isNullOrEmpty()) {
      // O(1) hash lookup instead of O(n) linear search
      trackIdCache?.get(mediaId)?.let { return it }
    }

    val playerIndex = player.currentMediaItemIndex
    if (playerIndex in session.audioTracks.indices) {
      return playerIndex
    }
    return session.getCurrentTrackIndex().coerceIn(0, session.audioTracks.lastIndex)
  }

  fun resolveTrackIndexForPosition(session: PlaybackSession, positionMs: Long): Int {
    if (session.audioTracks.isEmpty()) return 0

    // Ensure cache is built (shares cache with resolveTrackIndexForPlayer)
    if (cumulativeDurationsMs == null || cachedSessionId != session.id) {
      // Trigger cache build via resolveTrackIndexForPlayer which builds both caches
      val dummyPlayer = playbackServiceProvider().activePlayerPublic
      resolveTrackIndexForPlayer(session, dummyPlayer)
    }

    val cumulative = cumulativeDurationsMs ?: return 0

    // Binary search through pre-computed cumulative durations - O(log n) instead of O(n)
    val index = cumulative.binarySearch(positionMs)
    return when {
      index >= 0 -> index // Exact match
      else -> {
        val insertionPoint = -(index + 1)
        // Position falls before first track's end = track 0
        // Otherwise it's in the track at insertionPoint
        max(0, min(insertionPoint, session.audioTracks.size - 1))
      }
    }
  }

}
