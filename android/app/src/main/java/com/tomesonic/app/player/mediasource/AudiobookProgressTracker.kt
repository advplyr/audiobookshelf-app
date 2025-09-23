package com.tomesonic.app.player.mediasource

import android.util.Log
import androidx.media3.common.Player
import com.tomesonic.app.data.BookChapter
import com.tomesonic.app.data.PlaybackSession

/**
 * Tracks progress in an audiobook using the new Media3 architecture where each
 * chapter is a separate MediaItem in a ConcatenatingMediaSource.
 *
 * This simplifies progress tracking since ExoPlayer naturally handles the timeline
 * and we can use getCurrentMediaItemIndex() for the current chapter.
 */
class AudiobookProgressTracker(
    private val player: Player,
    private var playbackSession: PlaybackSession?
) {

    companion object {
        private const val TAG = "AudiobookProgressTracker"
    }

    private val chapterSegments = mutableListOf<ChapterSegment>()
    private var progressUpdateListener: ((absolutePositionMs: Long, chapterIndex: Int, chapterProgress: Float) -> Unit)? = null
    private var chapterChangeListener: ((newChapter: BookChapter?, chapterIndex: Int) -> Unit)? = null
    private var lastReportedChapterIndex = -1

    /**
     * Initialize the tracker with chapter segments
     */
    fun setChapterSegments(segments: List<ChapterSegment>) {
        chapterSegments.clear()
        chapterSegments.addAll(segments)
        Log.d(TAG, "Initialized with ${segments.size} chapter segments")
    }

    /**
     * Update the playback session when it changes
     */
    fun updatePlaybackSession(session: PlaybackSession?) {
        playbackSession = session
        lastReportedChapterIndex = -1
        Log.d(TAG, "Updated playback session: ${session?.displayTitle}")
    }

    /**
     * Set listener for progress updates
     */
    fun setProgressUpdateListener(listener: (absolutePositionMs: Long, chapterIndex: Int, chapterProgress: Float) -> Unit) {
        progressUpdateListener = listener
    }

    /**
     * Set listener for chapter changes
     */
    fun setChapterChangeListener(listener: (newChapter: BookChapter?, chapterIndex: Int) -> Unit) {
        chapterChangeListener = listener
    }

    /**
     * Get the current absolute position in the audiobook
     */
    fun getCurrentAbsolutePosition(): Long {
        val currentChapterIndex = player.currentMediaItemIndex
        val currentPositionInChapter = player.currentPosition

        if (currentChapterIndex < 0 || currentChapterIndex >= chapterSegments.size) {
            Log.w(TAG, "Invalid chapter index: $currentChapterIndex")
            return 0L
        }

        val segment = chapterSegments[currentChapterIndex]
        val absolutePosition = segment.chapterStartMs + currentPositionInChapter

        Log.v(TAG, "Current position: chapter $currentChapterIndex, position ${currentPositionInChapter}ms, absolute ${absolutePosition}ms")
        return absolutePosition
    }

    /**
     * Get the current chapter index (0-based)
     */
    fun getCurrentChapterIndex(): Int {
        return player.currentMediaItemIndex
    }

    /**
     * Get the current chapter
     */
    fun getCurrentChapter(): BookChapter? {
        val chapterIndex = getCurrentChapterIndex()
        if (chapterIndex < 0) return null

        return playbackSession?.chapters?.getOrNull(chapterIndex)
    }

    /**
     * Get current chapter progress (0.0 to 1.0)
     */
    fun getCurrentChapterProgress(): Float {
        val currentChapterIndex = player.currentMediaItemIndex
        val currentPositionInChapter = player.currentPosition

        if (currentChapterIndex < 0 || currentChapterIndex >= chapterSegments.size) {
            return 0f
        }

        val segment = chapterSegments[currentChapterIndex]
        if (segment.durationMs <= 0) return 0f

        return (currentPositionInChapter.toFloat() / segment.durationMs).coerceIn(0f, 1f)
    }

    /**
     * Get total audiobook progress (0.0 to 1.0)
     */
    fun getTotalProgress(): Float {
        val absolutePosition = getCurrentAbsolutePosition()
        val totalDuration = getTotalDuration()

        if (totalDuration <= 0) return 0f
        return (absolutePosition.toFloat() / totalDuration).coerceIn(0f, 1f)
    }

    /**
     * Get total duration of the audiobook
     */
    fun getTotalDuration(): Long {
        if (chapterSegments.isEmpty()) return 0L
        return chapterSegments.last().chapterEndMs
    }

    /**
     * Seek to an absolute position in the audiobook
     */
    fun seekToAbsolutePosition(absolutePositionMs: Long) {
        Log.d(TAG, "Seeking to absolute position: ${absolutePositionMs}ms")

        // Find which chapter contains this absolute position
        val targetSegment = chapterSegments.find { segment ->
            absolutePositionMs >= segment.chapterStartMs && absolutePositionMs < segment.chapterEndMs
        } ?: chapterSegments.lastOrNull()

        if (targetSegment == null) {
            Log.e(TAG, "No chapter found for position ${absolutePositionMs}ms")
            return
        }

        val chapterIndex = targetSegment.chapterIndex
        val positionInChapter = absolutePositionMs - targetSegment.chapterStartMs

        Log.d(TAG, "Seeking to chapter $chapterIndex, position ${positionInChapter}ms")
        player.seekTo(chapterIndex, positionInChapter)
    }

    /**
     * Seek to a specific chapter
     */
    fun seekToChapter(chapterIndex: Int, positionInChapterMs: Long = 0L) {
        if (chapterIndex < 0 || chapterIndex >= chapterSegments.size) {
            Log.w(TAG, "Invalid chapter index: $chapterIndex")
            return
        }

        Log.d(TAG, "Seeking to chapter $chapterIndex, position ${positionInChapterMs}ms")
        player.seekTo(chapterIndex, positionInChapterMs)
    }

    /**
     * Navigate to the next chapter
     */
    fun seekToNextChapter(): Boolean {
        val currentIndex = getCurrentChapterIndex()
        val nextIndex = currentIndex + 1

        return if (nextIndex < chapterSegments.size) {
            seekToChapter(nextIndex)
            true
        } else {
            Log.d(TAG, "Already at last chapter")
            false
        }
    }

    /**
     * Navigate to the previous chapter
     */
    fun seekToPreviousChapter(): Boolean {
        val currentIndex = getCurrentChapterIndex()
        val currentPositionInChapter = player.currentPosition

        // If we're more than 3 seconds into the current chapter, go to start of current chapter
        if (currentPositionInChapter > 3000L) {
            seekToChapter(currentIndex, 0L)
            return true
        }

        // Otherwise go to previous chapter
        val previousIndex = currentIndex - 1
        return if (previousIndex >= 0) {
            seekToChapter(previousIndex)
            true
        } else {
            Log.d(TAG, "Already at first chapter")
            false
        }
    }

    /**
     * Update progress and check for chapter changes (call this periodically)
     */
    fun updateProgress() {
        val currentChapterIndex = getCurrentChapterIndex()
        val absolutePosition = getCurrentAbsolutePosition()
        val chapterProgress = getCurrentChapterProgress()

        // Report progress update
        progressUpdateListener?.invoke(absolutePosition, currentChapterIndex, chapterProgress)

        // Check for chapter change
        if (currentChapterIndex != lastReportedChapterIndex && currentChapterIndex >= 0) {
            val currentChapter = getCurrentChapter()
            Log.d(TAG, "Chapter changed from $lastReportedChapterIndex to $currentChapterIndex: ${currentChapter?.title}")

            chapterChangeListener?.invoke(currentChapter, currentChapterIndex)
            lastReportedChapterIndex = currentChapterIndex
        }
    }

    /**
     * Get comprehensive progress information
     */
    data class ProgressInfo(
        val absolutePositionMs: Long,
        val chapterIndex: Int,
        val chapterTitle: String?,
        val chapterPositionMs: Long,
        val chapterDurationMs: Long,
        val chapterProgress: Float,
        val totalProgress: Float,
        val totalDurationMs: Long
    )

    /**
     * Get detailed progress information
     */
    fun getProgressInfo(): ProgressInfo? {
        val chapterIndex = getCurrentChapterIndex()
        if (chapterIndex < 0 || chapterIndex >= chapterSegments.size) {
            return null
        }

        val segment = chapterSegments[chapterIndex]
        val currentChapter = getCurrentChapter()

        return ProgressInfo(
            absolutePositionMs = getCurrentAbsolutePosition(),
            chapterIndex = chapterIndex,
            chapterTitle = currentChapter?.title ?: segment.displayTitle,
            chapterPositionMs = player.currentPosition,
            chapterDurationMs = segment.durationMs,
            chapterProgress = getCurrentChapterProgress(),
            totalProgress = getTotalProgress(),
            totalDurationMs = getTotalDuration()
        )
    }
}
