package com.audiobookshelf.app.player

import com.audiobookshelf.app.data.BookChapter
import com.google.android.exoplayer2.ForwardingPlayer
import com.google.android.exoplayer2.Player

// PlayerNotificationManager and MediaSessionConnector both pull position/duration
// from the Player interface, so chapter-relative reporting has to live at this
// boundary. Mirrors the iOS NowPlayingInfo override in AudioPlayer.updateNowPlaying().
class ChapterAwareForwardingPlayer(
  delegate: Player,
  private val playerNotificationService: PlayerNotificationService
) : ForwardingPlayer(delegate) {

  private fun activeChapter(): BookChapter? {
    if (!playerNotificationService.isChapterTrackEnabled()) return null
    val absoluteMs: Long = playerNotificationService.getAbsoluteBookPositionFor(wrappedPlayer)
    return playerNotificationService.currentPlaybackSession?.getChapterForTime(absoluteMs)
  }

  override fun getCurrentPosition(): Long {
    val chapter: BookChapter = activeChapter() ?: return super.getCurrentPosition()
    val absoluteMs: Long = playerNotificationService.getAbsoluteBookPositionFor(wrappedPlayer)
    val chapterDur: Long = chapter.endMs - chapter.startMs
    return (absoluteMs - chapter.startMs).coerceIn(0L, chapterDur)
  }

  override fun getContentPosition(): Long = currentPosition

  override fun getDuration(): Long {
    val chapter: BookChapter = activeChapter() ?: return super.getDuration()
    return chapter.endMs - chapter.startMs
  }

  override fun getContentDuration(): Long = duration

  override fun getBufferedPosition(): Long {
    val chapter: BookChapter = activeChapter() ?: return super.getBufferedPosition()
    val absoluteBufferedMs: Long = playerNotificationService.getAbsoluteBookBufferedPositionFor(wrappedPlayer)
    val chapterDur: Long = chapter.endMs - chapter.startMs
    return (absoluteBufferedMs - chapter.startMs).coerceIn(0L, chapterDur)
  }

  override fun getContentBufferedPosition(): Long = bufferedPosition

  // Note: seekTo is intentionally not overridden. ACTION_SEEK_TO from system UI
  // is handled by MediaSessionCallback.onSeekTo (chapter-aware), and skip /
  // jump buttons go through PlayerNotificationService.seekPlayer on the
  // underlying player. Nothing in this codebase calls seekTo on the wrapper.
}
