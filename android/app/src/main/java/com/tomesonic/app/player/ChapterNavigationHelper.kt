package com.tomesonic.app.player

import android.util.Log
import androidx.media3.common.Player
import com.tomesonic.app.data.BookChapter
import com.tomesonic.app.player.mediasource.AudiobookProgressTracker

/**
 * Helper class for chapter navigation and utilities when using the new MediaSource architecture.
 * This replaces the need for a wrapper player since ExoPlayer now natively handles chapter-relative
 * positions through the ConcatenatingMediaSource + ClippingMediaSource architecture.
 */
class ChapterNavigationHelper(
    private val player: Player,
    private val progressTracker: AudiobookProgressTracker
) {
    companion object {
        private const val TAG = "ChapterNavigationHelper"
    }

    /**
     * Navigate to a specific chapter by index
     */
    fun seekToChapter(chapterIndex: Int) {
        progressTracker.seekToChapter(chapterIndex)
    }

    /**
     * Navigate to the next chapter
     */
    fun seekToNextChapter(): Boolean {
        return progressTracker.seekToNextChapter()
    }

    /**
     * Navigate to the previous chapter
     */
    fun seekToPreviousChapter(): Boolean {
        return progressTracker.seekToPreviousChapter()
    }

    /**
     * Get the current chapter
     */
    fun getCurrentChapter(): BookChapter? {
        return progressTracker.getCurrentChapter()
    }

    /**
     * Get the current chapter index (0-based)
     */
    fun getCurrentChapterIndex(): Int {
        return progressTracker.getCurrentChapterIndex()
    }

    /**
     * Check if we're currently in a chapter-based book
     */
    fun hasChapters(): Boolean {
        return progressTracker.getCurrentChapter() != null
    }

    /**
     * Get the absolute position in the entire audiobook
     */
    fun getAbsolutePosition(): Long {
        return progressTracker.getCurrentAbsolutePosition()
    }

    /**
     * Get the total duration of the entire audiobook
     */
    fun getTotalDuration(): Long {
        return progressTracker.getTotalDuration()
    }

    /**
     * Seek to an absolute position in the audiobook
     */
    fun seekToAbsolutePosition(absolutePositionMs: Long) {
        progressTracker.seekToAbsolutePosition(absolutePositionMs)
    }

    /**
     * Get the title to display in notifications and Android Auto
     * Returns current chapter title if available
     */
    fun getCurrentDisplayTitle(): String {
        val currentChapter = getCurrentChapter()
        return currentChapter?.title ?: "Chapter ${getCurrentChapterIndex() + 1}"
    }

    /**
     * Get detailed progress information
     */
    fun getProgressInfo(): AudiobookProgressTracker.ProgressInfo? {
        return progressTracker.getProgressInfo()
    }

    /**
     * Update progress and check for chapter changes (call this periodically)
     */
    fun updateProgress() {
        progressTracker.updateProgress()
    }

    /**
     * Set listener for chapter changes
     */
    fun setChapterChangeListener(listener: (BookChapter?, Int) -> Unit) {
        progressTracker.setChapterChangeListener(listener)
    }

    /**
     * Jump forward by the specified amount (within current chapter)
     */
    fun jumpForward(jumpTimeMs: Long) {
        val currentPosition = player.currentPosition
        val newPosition = currentPosition + jumpTimeMs
        player.seekTo(player.currentMediaItemIndex, newPosition)
    }

    /**
     * Jump backward by the specified amount (within current chapter)
     */
    fun jumpBackward(jumpTimeMs: Long) {
        val currentPosition = player.currentPosition
        val newPosition = (currentPosition - jumpTimeMs).coerceAtLeast(0L)
        player.seekTo(player.currentMediaItemIndex, newPosition)
    }
}
