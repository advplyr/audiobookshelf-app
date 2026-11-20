package com.audiobookshelf.app.player.media3

import androidx.media3.common.Player
import com.audiobookshelf.app.data.BookChapter
import com.audiobookshelf.app.data.PlaybackSession

data class SeekTarget(val trackIndex: Int, val positionInTrackMs: Long)

data class WriteBack(val positionMs: Long, val trackIndex: Int)

/**
 * `player.currentPosition` is relative to the current queue item, so it is only correct
 * for a single-track session. Anything user-facing or persisted — progress sync, sleep
 * timer, chapter lookup, widget, server state — needs the book-absolute position, and
 * anything handed back to `player.seekTo` needs the track-relative one. Every conversion
 * between the two goes through this type so the two units can't be mixed up at a call site.
 */
class PlaybackPositionModel(
  private val session: PlaybackSession,
  private val player: Player?
) {
  // Media id wins over the queue index: a cast reload can leave the two disagreeing.
  fun trackIndex(): Int {
    val tracks = session.audioTracks
    if (tracks.isEmpty()) return 0

    val mediaId = player?.currentMediaItem?.mediaId
    if (!mediaId.isNullOrEmpty()) {
      val prefix = "${session.id}_"
      if (mediaId.startsWith(prefix)) {
        val stableId = mediaId.substring(prefix.length)
        tracks.forEachIndexed { index, track ->
          if (stableId == track.stableId) {
            return index
          }
        }
      }
    }

    val playerIndex = player?.currentMediaItemIndex ?: -1
    if (playerIndex in tracks.indices) {
      return playerIndex
    }

    return session.getCurrentTrackIndex().coerceIn(0, tracks.lastIndex)
  }

  fun bookAbsoluteMsOrNull(): Long? {
    val player = player ?: return null
    val mediaItemCount = player.mediaItemCount
    if (mediaItemCount <= 0) return player.currentPosition.coerceAtLeast(0L)
    val index = trackIndex().coerceIn(0, mediaItemCount - 1)
    return (player.currentPosition + session.getTrackStartOffsetMs(index)).coerceAtLeast(0L)
  }

  fun bookAbsoluteMs(): Long = bookAbsoluteMsOrNull() ?: session.currentTimeMs

  fun seekTargetForSessionTime(maxIndex: Int = session.audioTracks.lastIndex): SeekTarget =
    seekTargetFor(session, session.currentTimeMs, session.getCurrentTrackIndex(), maxIndex)

  fun currentChapter(): BookChapter? = session.getChapterForTime(bookAbsoluteMs())

  fun nextChapter(): BookChapter? = session.getNextChapterForTime(bookAbsoluteMs())

  companion object {
    // Conversions that need only the session, for callers resolving a position before a player
    // exists (Android Auto resume, queue building).

    fun trackIndexForPosition(session: PlaybackSession, bookAbsoluteMs: Long): Int {
      val tracks = session.audioTracks
      if (tracks.isEmpty()) return 0
      val index = tracks.indexOfFirst {
        bookAbsoluteMs in it.startOffsetMs until it.endOffsetMs
      }
      return if (index >= 0) index else tracks.lastIndex
    }

    fun bookAbsoluteMsFor(session: PlaybackSession, trackIndex: Int, positionInTrackMs: Long): Long =
      (session.getTrackStartOffsetMs(trackIndex) + positionInTrackMs).coerceAtLeast(0L)

    fun seekTargetFor(
      session: PlaybackSession,
      bookAbsoluteMs: Long,
      rawIndex: Int,
      maxIndex: Int
    ): SeekTarget {
      if (maxIndex < 0) return SeekTarget(0, bookAbsoluteMs.coerceAtLeast(0L))
      val index = rawIndex.coerceIn(0, maxIndex)
      val positionInTrack =
        (bookAbsoluteMs - session.getTrackStartOffsetMs(index)).coerceAtLeast(0L)
      return SeekTarget(index, positionInTrack)
    }

    /** Resolves the track for [bookAbsoluteMs] and converts it in one step. */
    fun seekTargetForPosition(
      session: PlaybackSession,
      bookAbsoluteMs: Long,
      maxIndex: Int
    ): SeekTarget =
      seekTargetFor(session, bookAbsoluteMs, trackIndexForPosition(session, bookAbsoluteMs), maxIndex)
  }

  /** Returns null rather than overwriting progress when the player's queue belongs elsewhere. */
  fun writeBackToSession(): WriteBack? {
    val player = player ?: return null
    if (player.mediaItemCount == 0) return null
    val currentMediaId = player.currentMediaItem?.mediaId ?: return null
    if (!currentMediaId.startsWith("${session.id}_")) return null
    val index = trackIndex()
    val absolutePosMs = session.getTrackStartOffsetMs(index) + player.currentPosition
    session.currentTime = absolutePosMs / 1000.0
    return WriteBack(absolutePosMs, index)
  }
}
