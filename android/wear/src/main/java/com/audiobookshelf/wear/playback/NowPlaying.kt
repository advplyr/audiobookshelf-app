package com.audiobookshelf.wear.playback

import androidx.media3.common.Player
import com.audiobookshelf.wear.data.BookDetail
import kotlinx.coroutines.flow.MutableStateFlow

/** The book loaded into the player. Shared in-process between the UI and [PlaybackService]. */
data class PlayingBook(
  val book: BookDetail,
  val isLocal: Boolean,
  val sessionId: String
) {
  /** Position in the whole book, from the player's position within the current track. */
  fun bookTime(player: Player): Double {
    val track = book.tracks.getOrNull(player.currentMediaItemIndex) ?: return 0.0
    return track.startOffset + player.currentPosition / 1000.0
  }

  /** The track index and offset in milliseconds for a position in the whole book. */
  fun trackPosition(time: Double): Pair<Int, Long> {
    val clamped = time.coerceIn(0.0, book.duration)
    val index = book.tracks.indexOfLast { it.startOffset <= clamped }.coerceAtLeast(0)
    return index to ((clamped - book.tracks[index].startOffset) * 1000).toLong()
  }
}

object NowPlaying {
  val book = MutableStateFlow<PlayingBook?>(null)

  /** True while a play request waits for headphones (see [HeadphoneGuard]). */
  val waitingForHeadphones = MutableStateFlow(false)
}
