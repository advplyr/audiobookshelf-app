package com.audiobookshelf.app.player.media3

import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession

/**
 * Media3 exposes no metadata-invalidate call, so the only way to refresh what the
 * notification and Android Auto read is to replace the current queue item. That is
 * expensive and visibly flickers, hence the (track, chapter) cache and the string compare.
 */
class NotificationMetadataUpdater {
  private var lastTrackIndex = -1
  private var lastChapterTitle: String? = null

  fun reset() {
    lastTrackIndex = -1
    lastChapterTitle = null
  }

  fun syncIfNeeded(
    player: Player,
    session: PlaybackSession,
    currentPosMs: Long,
    trackIndex: Int,
    isCastActive: Boolean
  ) {
    // CastPlayer implements replaceMediaItem by reloading the receiver's current item, which
    // restarts playback from the item start. Skipped before the cache update so the title
    // refreshes on the first tick after cast ends.
    if (isCastActive) return

    val chapterTitle = session.getChapterForTime(currentPosMs)?.title
    if (trackIndex == lastTrackIndex && chapterTitle == lastChapterTitle) return

    val currentItem = player.currentMediaItem ?: return
    val artistLine = chapterTitle ?: (session.displayAuthor ?: "")

    lastTrackIndex = trackIndex
    lastChapterTitle = chapterTitle

    if (currentItem.mediaMetadata.artist == artistLine) return

    val newMetadata = currentItem.mediaMetadata.buildUpon()
      .setArtist(artistLine)
      .build()
    // Replace at the player's own index: currentItem came from the player, and the
    // session-resolved trackIndex could disagree with the queue position (e.g. cast reload)
    player.replaceMediaItem(
      player.currentMediaItemIndex,
      currentItem.buildUpon().setMediaMetadata(newMetadata).build()
    )
  }
}
