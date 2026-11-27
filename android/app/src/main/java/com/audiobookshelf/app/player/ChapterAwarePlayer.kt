package com.audiobookshelf.app.player

import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ForwardingPlayer

/**
 * Wraps the real player ONLY for the MediaSessionConnector (Android Auto, Bluetooth, lock screen).
 * When the current book has chapters, position/duration are reported relative to the current
 * chapter instead of the whole audio file. The app's own code keeps using the real player.
 * Books without chapters are passed straight through.
 */
class ChapterAwarePlayer(
  player: Player,
  private val service: PlayerNotificationService
) : ForwardingPlayer(player) {

  override fun getCurrentPosition(): Long {
    val ch = service.getCurrentBookChapter() ?: return super.getCurrentPosition()
    val len = ch.endMs - ch.startMs
    return (service.getCurrentTime() - ch.startMs).coerceIn(0L, len)
  }

  override fun getBufferedPosition(): Long {
    val ch = service.getCurrentBookChapter() ?: return super.getBufferedPosition()
    val len = ch.endMs - ch.startMs
    val buffered = (service.getBufferedTimeSeconds() * 1000).toLong()
    return (buffered - ch.startMs).coerceIn(0L, len)
  }

  override fun getDuration(): Long {
    val ch = service.getCurrentBookChapter() ?: return super.getDuration()
    return ch.endMs - ch.startMs
  }

  override fun seekTo(positionMs: Long) {
    val ch = service.getCurrentBookChapter()
    if (ch == null) super.seekTo(positionMs) else service.seekPlayer(ch.startMs + positionMs)
  }

  override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
    val ch = service.getCurrentBookChapter()
    if (ch == null) super.seekTo(mediaItemIndex, positionMs) else service.seekPlayer(ch.startMs + positionMs)
  }
}
