package com.tomesonic.app.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import com.tomesonic.app.data.PlaybackSession
import com.tomesonic.app.data.BookChapter

/**
 * Chapter-aware player wrapper that provides chapter-relative position and duration reporting.
 * This wrapper intercepts position and duration calls to provide chapter-aware information
 * for notifications, Android Auto, and UI components.
 *
 * Key features:
 * - Chapter-relative getCurrentPosition() and getDuration()
 * - Automatic chapter boundary detection and transitions
 * - Support for both single-file (clipped) and multi-file chapter scenarios
 * - Proper metadata updates when chapters change during playback
 * - Proper Player.Listener event forwarding to maintain ExoPlayer compatibility
 */
class ChapterAwarePlayer(
    private val wrappedPlayer: Player,
    private var playbackSession: PlaybackSession?
) : Player by wrappedPlayer {

    private val tag = "ChapterAwarePlayer"

    private var currentChapterIndex: Int = -1
    private var chapterChangeListener: ((BookChapter?) -> Unit)? = null
    private var lastKnownPosition: Long = 0L

    // Manage listeners registered with this wrapper
    private val wrapperListeners = mutableListOf<Player.Listener>()
    private var forwardingListener: Player.Listener? = null

    // Startup safety mechanism to avoid interfering with initial ExoPlayer preparation
    private var isStartupPhase = true
    private var startupPhaseEndTime = 0L

    companion object {
        // Threshold for detecting significant position changes (in milliseconds)
        private const val POSITION_CHANGE_THRESHOLD = 2000L
        // Minimum time to keep startup phase active (prevents premature chapter logic during preparation)
        private const val STARTUP_PHASE_DURATION = 3000L
    }

    init {
        // Set up listener forwarding from wrapped player to wrapper listeners
        setupListenerForwarding()
        // Initialize startup phase protection
        startupPhaseEndTime = System.currentTimeMillis() + STARTUP_PHASE_DURATION
    }

    /**
     * Check if we're still in the startup phase to avoid interfering with ExoPlayer preparation
     */
    private fun isInStartupPhase(): Boolean {
        if (!isStartupPhase) return false

        val now = System.currentTimeMillis()
        if (now > startupPhaseEndTime && wrappedPlayer.playbackState == Player.STATE_READY) {
            isStartupPhase = false
            Log.d(tag, "Startup phase ended - chapter logic now active")
        }
        return isStartupPhase
    }

    /**
     * Set up forwarding of Player.Listener events from wrapped player to this wrapper's listeners
     */
    private fun setupListenerForwarding() {
        forwardingListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(tag, "Forwarding onPlaybackStateChanged: $playbackState")
                wrapperListeners.forEach { it.onPlaybackStateChanged(playbackState) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(tag, "Forwarding onIsPlayingChanged: $isPlaying")
                wrapperListeners.forEach { it.onIsPlayingChanged(isPlaying) }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                Log.d(tag, "Forwarding onPositionDiscontinuity: reason=$reason")
                wrapperListeners.forEach { it.onPositionDiscontinuity(oldPosition, newPosition, reason) }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.d(tag, "Forwarding onPlayerError: ${error.message}")
                wrapperListeners.forEach { it.onPlayerError(error) }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                Log.d(tag, "Forwarding onMediaItemTransition: reason=$reason")
                wrapperListeners.forEach { it.onMediaItemTransition(mediaItem, reason) }
            }
        }

        forwardingListener?.let { wrappedPlayer.addListener(it) }
        Log.d(tag, "Listener forwarding setup complete")
    }

    /**
     * Override addListener to track listeners and enable forwarding
     * During startup phase, delegate directly to wrapped player to avoid interference
     */
    override fun addListener(listener: Player.Listener) {
        Log.d(tag, "Adding listener to ChapterAwarePlayer wrapper (startup phase: $isStartupPhase)")

        // During startup phase, add listeners directly to wrapped player to ensure proper startup sequence
        if (isInStartupPhase()) {
            Log.d(tag, "Startup phase active - adding listener directly to wrapped player")
            wrappedPlayer.addListener(listener)
        } else {
            Log.d(tag, "Adding listener to wrapper list for forwarding")
            wrapperListeners.add(listener)
        }
    }

    /**
     * Override removeListener to clean up tracked listeners
     * Handle both direct wrapped player listeners and wrapper listeners
     */
    override fun removeListener(listener: Player.Listener) {
        Log.d(tag, "Removing listener from ChapterAwarePlayer wrapper")

        // Try to remove from both wrapper listeners and wrapped player
        // (since we might have added it directly during startup phase)
        wrapperListeners.remove(listener)
        wrappedPlayer.removeListener(listener)
    }

    /**
     * Clean up resources when wrapper is no longer needed
     */
    override fun release() {
        forwardingListener?.let { wrappedPlayer.removeListener(it) }
        wrapperListeners.clear()
        Log.d(tag, "ChapterAwarePlayer wrapper released")

        // Call the wrapped player's release method
        wrappedPlayer.release()
    }

    /**
     * Update the playback session when it changes
     */
    fun updatePlaybackSession(newSession: PlaybackSession?) {
        playbackSession = newSession
        currentChapterIndex = -1 // Reset chapter tracking

        // Reset startup phase for new session
        isStartupPhase = true
        startupPhaseEndTime = System.currentTimeMillis() + STARTUP_PHASE_DURATION

        Log.d(tag, "Updated playback session: ${newSession?.displayTitle} (startup phase reset)")

        // Debug: Log all available chapters and their boundaries
        if (newSession != null && newSession.chapters.isNotEmpty()) {
            Log.d(tag, "Available chapters (${newSession.chapters.size} total):")
            newSession.chapters.forEachIndexed { index, chapter ->
                val startTime = chapter.startMs / 1000.0
                val endTime = chapter.endMs / 1000.0
                val duration = (chapter.endMs - chapter.startMs) / 1000.0
                Log.d(tag, "  Chapter $index: '${chapter.title}' - ${startTime}s to ${endTime}s (${duration}s duration)")
            }
        } else {
            Log.d(tag, "No chapters available in this session")
        }

        // Initialize chapter tracking based on current playback position
        if (newSession != null && newSession.hasChapters()) {
            val startingInfo = newSession.getStartingPlaybackInfo()
            currentChapterIndex = startingInfo.chapterIndex
            Log.d(tag, "Initialized chapter tracking - starting at chapter $currentChapterIndex: ${startingInfo.chapter?.title ?: "Unknown"}")
        }
    }

    /**
     * Set listener for chapter changes
     */
    fun setChapterChangeListener(listener: (BookChapter?) -> Unit) {
        chapterChangeListener = listener
    }

    /**
     * Set the current chapter index directly (used for initial positioning)
     */
    fun setCurrentChapterIndex(index: Int) {
        currentChapterIndex = index
        Log.d(tag, "Current chapter index set to: $index")
    }

    /**
     * Override seekTo to handle chapter-relative seeking from MediaSession
     * Since we report chapter duration, positions should be relative to current chapter
     */
    override fun seekTo(positionMs: Long) {
        // During startup phase, pass through to wrapped player
        if (isInStartupPhase()) {
            Log.d(tag, "Startup phase - seeking to absolute position: ${positionMs}ms")
            wrappedPlayer.seekTo(positionMs)
            return
        }

        val currentChapter = getCurrentChapter()
        val reportedDuration = getDuration()
        Log.d(tag, "seekTo called: position=${positionMs}ms (${positionMs/1000.0}s), reported duration=${reportedDuration}ms (${reportedDuration/1000.0}s)")

        if (currentChapter != null) {
            val chapterDuration = currentChapter.endMs - currentChapter.startMs
            Log.d(tag, "Current chapter: '${currentChapter.title}' (${currentChapter.startMs}ms-${currentChapter.endMs}ms, ${chapterDuration}ms duration)")

            // Check if position looks like it's based on total duration vs chapter duration
            if (positionMs > chapterDuration) {
                Log.w(tag, "WARNING: Seek position (${positionMs}ms) exceeds chapter duration (${chapterDuration}ms) - Android Auto may be using total duration!")
                Log.w(tag, "This suggests MediaSession is not reporting chapter duration correctly")
            }

            // Position is relative to chapter start since we report chapter duration to MediaSession
            val absolutePosition = currentChapter.startMs + positionMs
            // Ensure we don't seek beyond the chapter end
            val clampedAbsolutePosition = absolutePosition.coerceAtMost(currentChapter.endMs - 1)

            // Convert absolute position to MediaItem-relative position for ExoPlayer
            val (mediaItemIndex, localPosition) = convertAbsoluteToMediaItemPosition(clampedAbsolutePosition)
            Log.d(tag, "Chapter-relative seek: ${positionMs}ms in chapter '${currentChapter.title}' -> absolute ${clampedAbsolutePosition}ms -> MediaItem $mediaItemIndex at ${localPosition}ms")

            if (mediaItemIndex >= 0) {
                wrappedPlayer.seekTo(mediaItemIndex, localPosition)
            } else {
                Log.e(tag, "Failed to convert absolute position ${clampedAbsolutePosition}ms to MediaItem position")
                // Fallback to current MediaItem with original position
                wrappedPlayer.seekTo(positionMs)
            }
        } else {
            Log.d(tag, "No current chapter - seeking to absolute position: ${positionMs}ms")
            wrappedPlayer.seekTo(positionMs)
        }
    }

    /**
     * Override seekTo with mediaItemIndex to handle multi-track seeking
     */
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        Log.d(tag, "Seeking to mediaItem $mediaItemIndex at position ${positionMs}ms")
        wrappedPlayer.seekTo(mediaItemIndex, positionMs)
    }

    /**
     * Get current position relative to the current chapter
     * If no chapters available, returns absolute position
     * During startup phase, always returns absolute position to avoid interfering with ExoPlayer preparation
     */
    override fun getCurrentPosition(): Long {
        // During startup phase, always return absolute position
        if (isInStartupPhase()) {
            val absolutePosition = calculateAbsolutePosition()
            Log.v(tag, "Startup phase active - returning absolute position: ${absolutePosition}ms")
            lastKnownPosition = absolutePosition
            return absolutePosition
        }

        val absolutePosition = calculateAbsolutePosition()
        lastKnownPosition = absolutePosition

        val currentChapter = getCurrentChapter()
        return if (currentChapter != null) {
            // Return position relative to chapter start
            val chapterRelativePosition = absolutePosition - currentChapter.startMs
            chapterRelativePosition.coerceAtLeast(0L)
        } else {
            absolutePosition
        }
    }

    /**
     * Get duration of the current chapter
     * If no chapters available, returns total duration
     * During startup phase, always returns total duration to avoid interfering with ExoPlayer preparation
     */
    override fun getDuration(): Long {
        // During startup phase, always return total duration
        if (isInStartupPhase()) {
            val totalDuration = wrappedPlayer.duration
            Log.v(tag, "Startup phase active - returning total duration: ${totalDuration}ms")
            return totalDuration
        }

        val currentChapter = getCurrentChapter()
        return if (currentChapter != null) {
            // Calculate chapter duration
            val chapterDuration = currentChapter.endMs - currentChapter.startMs
            Log.d(tag, "getDuration: Chapter ${currentChapter.id} '${currentChapter.title}' - start=${currentChapter.startMs}ms, end=${currentChapter.endMs}ms, duration=${chapterDuration}ms")
            Log.d(tag, "getDuration: Reporting chapter duration to MediaSession: ${chapterDuration}ms (${chapterDuration/1000.0}s)")
            chapterDuration
        } else {
            val totalDuration = wrappedPlayer.duration
            Log.d(tag, "getDuration: No current chapter found - returning total duration: ${totalDuration}ms")
            totalDuration
        }
    }

    /**
     * Get the absolute position in the entire media
     */
    fun getAbsolutePosition(): Long {
        return calculateAbsolutePosition()
    }

    /**
     * Get the total duration of the entire media
     */
    fun getTotalDuration(): Long {
        return wrappedPlayer.duration
    }

    /**
     * Get the current chapter based on playback position
     */
    fun getCurrentChapter(): BookChapter? {
        val session = playbackSession ?: return null
        if (session.chapters.isEmpty()) return null

        // Calculate absolute position across all MediaItems for multi-file audiobooks
        val absolutePosition = calculateAbsolutePosition()
        val chapter = session.getChapterForTime(absolutePosition)

        if (chapter != null) {
            Log.d(tag, "getCurrentChapter: At absolute position ${absolutePosition}ms (mediaItem=${wrappedPlayer.currentMediaItemIndex}, localPos=${wrappedPlayer.currentPosition}ms), found chapter ${chapter.id} '${chapter.title}' (${chapter.startMs}ms - ${chapter.endMs}ms)")

            // Check if chapter has changed
            val previousChapterIndex = currentChapterIndex
            val newChapterIndex = session.chapters.indexOf(chapter)
            if (newChapterIndex != previousChapterIndex && previousChapterIndex != -1) {
                Log.i(tag, "CHAPTER TRANSITION: Changed from chapter $previousChapterIndex to chapter $newChapterIndex ('${chapter.title}')")
                currentChapterIndex = newChapterIndex
                chapterChangeListener?.invoke(chapter)
            } else if (currentChapterIndex == -1) {
                // First time setting current chapter
                currentChapterIndex = newChapterIndex
                Log.d(tag, "Initial chapter set to $newChapterIndex ('${chapter.title}')")
            }
        } else {
            Log.w(tag, "getCurrentChapter: At absolute position ${absolutePosition}ms (mediaItem=${wrappedPlayer.currentMediaItemIndex}, localPos=${wrappedPlayer.currentPosition}ms), no chapter found. Available chapters: ${session.chapters.size}")
        }

        return chapter
    }

    /**
     * Calculate absolute position across all MediaItems for multi-file audiobooks
     */
    private fun calculateAbsolutePosition(): Long {
        val currentMediaItemIndex = wrappedPlayer.currentMediaItemIndex
        val currentPosition = wrappedPlayer.currentPosition

        if (currentMediaItemIndex < 0) {
            return currentPosition
        }

        // Sum durations of all previous MediaItems
        var absolutePosition = 0L
        for (i in 0 until currentMediaItemIndex) {
            // Try to get duration of previous MediaItem
            val previousDuration = try {
                val timeline = wrappedPlayer.currentTimeline
                val window = androidx.media3.common.Timeline.Window()
                timeline.getWindow(i, window)
                val duration = window.durationMs
                
                // Check for invalid duration (C.TIME_UNSET = -9223372036854775808L)
                if (duration == androidx.media3.common.C.TIME_UNSET || duration < 0) {
                    Log.w(tag, "Invalid duration for MediaItem $i: ${duration}ms, falling back to chapter data")
                    // Fall back to using chapter data to calculate duration
                    getMediaItemDurationFromChapters(i)
                } else {
                    duration
                }
            } catch (e: Exception) {
                Log.w(tag, "Could not get duration for MediaItem $i: ${e.message}, falling back to chapter data")
                getMediaItemDurationFromChapters(i)
            }
            
            Log.v(tag, "calculateAbsolutePosition: MediaItem $i duration = ${previousDuration}ms")
            absolutePosition += previousDuration
        }

        // Add current position within current MediaItem
        absolutePosition += currentPosition

        Log.v(tag, "calculateAbsolutePosition: MediaItem $currentMediaItemIndex, localPos=${currentPosition}ms → absolutePos=${absolutePosition}ms")
        return absolutePosition
    }

    /**
     * Get MediaItem duration from chapter data as fallback
     */
    private fun getMediaItemDurationFromChapters(mediaItemIndex: Int): Long {
        val audioFiles = playbackSession?.audioTracks
        if (audioFiles != null && mediaItemIndex >= 0 && mediaItemIndex < audioFiles.size) {
            // Convert seconds to milliseconds
            val durationMs = (audioFiles[mediaItemIndex].duration * 1000).toLong()
            Log.v(tag, "getMediaItemDurationFromChapters: MediaItem $mediaItemIndex duration from chapter data = ${durationMs}ms")
            return durationMs
        }
        Log.w(tag, "getMediaItemDurationFromChapters: Invalid mediaItemIndex $mediaItemIndex or no audio files")
        return 0L
    }

    /**
     * Convert absolute position across all MediaItems to specific MediaItem index and local position
     * Returns Pair(mediaItemIndex, localPosition) or Pair(-1, 0) if conversion fails
     */
    fun convertAbsoluteToMediaItemPosition(absolutePosition: Long): Pair<Int, Long> {
        try {
            val timeline = wrappedPlayer.currentTimeline
            
            // First try using ExoPlayer timeline if available and valid
            if (!timeline.isEmpty) {
                var accumulatedDuration = 0L
                val windowCount = timeline.windowCount
                var hasValidDurations = true
                
                // Check if we have valid durations
                for (i in 0 until windowCount) {
                    val window = androidx.media3.common.Timeline.Window()
                    timeline.getWindow(i, window)
                    if (window.durationMs <= 0) {
                        hasValidDurations = false
                        break
                    }
                }
                
                if (hasValidDurations) {
                    Log.d(tag, "convertAbsoluteToMediaItemPosition: Using ExoPlayer timeline - Converting position ${absolutePosition}ms with ${windowCount} MediaItems")
                    
                    for (i in 0 until windowCount) {
                        val window = androidx.media3.common.Timeline.Window()
                        timeline.getWindow(i, window)
                        val mediaItemDuration = window.durationMs
                        val nextAccumulatedDuration = accumulatedDuration + mediaItemDuration

                        if (absolutePosition <= nextAccumulatedDuration) {
                            val localPosition = absolutePosition - accumulatedDuration
                            Log.d(tag, "convertAbsoluteToMediaItemPosition: ExoPlayer timeline - ${absolutePosition}ms -> MediaItem $i at ${localPosition}ms")
                            return Pair(i, localPosition)
                        }
                        accumulatedDuration = nextAccumulatedDuration
                    }
                }
            }
            
            // Fallback: Use chapter data from playback session
            val session = playbackSession
            if (session != null && session.chapters.isNotEmpty()) {
                Log.d(tag, "convertAbsoluteToMediaItemPosition: Using chapter data fallback - Converting position ${absolutePosition}ms with ${session.chapters.size} chapters")
                
                // Find which chapter the absolute position falls into
                for (i in session.chapters.indices) {
                    val chapter = session.chapters[i]
                    if (absolutePosition >= chapter.startMs && absolutePosition < chapter.endMs) {
                        // Position is within this chapter
                        val localPosition = absolutePosition - chapter.startMs
                        Log.d(tag, "convertAbsoluteToMediaItemPosition: Chapter data - ${absolutePosition}ms -> Chapter $i (MediaItem $i) at ${localPosition}ms")
                        return Pair(i, localPosition)
                    }
                }
                
                // If position is beyond all chapters, use the last chapter
                if (absolutePosition >= session.chapters.last().endMs) {
                    val lastChapterIndex = session.chapters.size - 1
                    val lastChapter = session.chapters[lastChapterIndex]
                    val localPosition = lastChapter.endMs - lastChapter.startMs - 1 // Near end of last chapter
                    Log.d(tag, "convertAbsoluteToMediaItemPosition: Position beyond chapters - using last chapter $lastChapterIndex at ${localPosition}ms")
                    return Pair(lastChapterIndex, localPosition)
                }
            }
            
            Log.w(tag, "convertAbsoluteToMediaItemPosition: No valid timeline or chapter data available, using fallback")

        } catch (e: Exception) {
            Log.e(tag, "convertAbsoluteToMediaItemPosition: Error converting position ${absolutePosition}ms: ${e.message}")
        }

        return Pair(-1, 0L)
    }

    /**
     * Get the current chapter index (0-based)
     */
    fun getCurrentChapterIndex(): Int {
        val currentChapter = getCurrentChapter() ?: return -1
        val session = playbackSession ?: return -1

        return session.chapters.indexOf(currentChapter)
    }

    /**
     * Check if we're currently in a chapter-based book
     */
    fun hasChapters(): Boolean {
        val session = playbackSession ?: return false
        return session.chapters.isNotEmpty()
    }

    /**
     * Navigate to a specific chapter by index
     */
    fun seekToChapter(chapterIndex: Int) {
        val session = playbackSession ?: return

        if (chapterIndex < 0 || chapterIndex >= session.chapters.size) {
            Log.w(tag, "Invalid chapter index: $chapterIndex")
            return
        }

        val chapter = session.chapters[chapterIndex]
        Log.d(tag, "Seeking to chapter $chapterIndex: ${chapter.title} at ${chapter.startMs}ms")

        wrappedPlayer.seekTo(chapter.startMs)
        currentChapterIndex = chapterIndex
    }

    /**
     * Navigate to the next chapter
     */
    fun seekToNextChapter(): Boolean {
        val session = playbackSession ?: return false
        if (session.chapters.isEmpty()) return false

        val currentIndex = getCurrentChapterIndex()
        val nextIndex = currentIndex + 1

        return if (nextIndex < session.chapters.size) {
            seekToChapter(nextIndex)
            true
        } else {
            Log.d(tag, "Already at last chapter")
            false
        }
    }

    /**
     * Navigate to the previous chapter
     */
    fun seekToPreviousChapter(): Boolean {
        val session = playbackSession ?: return false
        if (session.chapters.isEmpty()) return false

        val currentIndex = getCurrentChapterIndex()

        // If we're more than 3 seconds into the current chapter, go to start of current chapter
        val chapterRelativePosition = getCurrentPosition()
        if (chapterRelativePosition > 3000L) {
            val currentChapter = getCurrentChapter()
            if (currentChapter != null) {
                Log.d(tag, "Going to start of current chapter")
                wrappedPlayer.seekTo(currentChapter.startMs)
                return true
            }
        }

        // Otherwise go to previous chapter
        val previousIndex = currentIndex - 1
        return if (previousIndex >= 0) {
            seekToChapter(previousIndex)
            true
        } else {
            Log.d(tag, "Already at first chapter")
            false
        }
    }

    /**
     * Get the title to display in notifications and Android Auto
     * Returns current chapter title if available, otherwise book title
     */
    fun getCurrentDisplayTitle(): String {
        val currentChapter = getCurrentChapter()
        return if (currentChapter != null && !currentChapter.title.isNullOrBlank()) {
            currentChapter.title!!
        } else {
            playbackSession?.displayTitle ?: "Unknown"
        }
    }

    /**
     * Get the subtitle to display (book title and author)
     */
    fun getCurrentDisplaySubtitle(): String {
        val session = playbackSession ?: return "Unknown"
        val title = session.displayTitle ?: "Unknown"
        val author = session.displayAuthor

        return if (!author.isNullOrBlank()) {
            "$title • $author"
        } else {
            title
        }
    }

    /**
     * Check for chapter changes during playback
     * This should be called periodically during playback
     */
    fun checkForChapterChange() {
        val session = playbackSession ?: return
        if (session.chapters.isEmpty()) return

        val currentPos = wrappedPlayer.currentPosition
        val newChapterIndex = getCurrentChapterIndex()

        // Check if we've moved to a different chapter
        if (newChapterIndex != currentChapterIndex) {
            val positionDiff = Math.abs(currentPos - lastKnownPosition)

            // Only trigger chapter change if it's not a seek operation
            // (seeks will be handled by the seek methods)
            if (positionDiff < POSITION_CHANGE_THRESHOLD) {
                Log.d(tag, "Chapter changed from $currentChapterIndex to $newChapterIndex during playback")
                currentChapterIndex = newChapterIndex

                val currentChapter = getCurrentChapter()
                chapterChangeListener?.invoke(currentChapter)
            }
        }

        lastKnownPosition = currentPos
    }

    /**
     * Get chapter progress information for the current playback position
     */
    data class ChapterProgress(
        val chapterIndex: Int,
        val chapterTitle: String,
        val chapterPosition: Long,
        val chapterDuration: Long,
        val chapterProgress: Float,
        val totalProgress: Float
    )

    /**
     * Get detailed chapter progress information
     */
    fun getChapterProgress(): ChapterProgress? {
        val currentChapter = getCurrentChapter() ?: return null
        val session = playbackSession ?: return null

        val chapterIndex = getCurrentChapterIndex()
        val chapterPosition = getCurrentPosition()
        val chapterDuration = getDuration()
        val absolutePosition = getAbsolutePosition()
        val totalDuration = getTotalDuration()

        return ChapterProgress(
            chapterIndex = chapterIndex,
            chapterTitle = getCurrentDisplayTitle(),
            chapterPosition = chapterPosition,
            chapterDuration = chapterDuration,
            chapterProgress = if (chapterDuration > 0) chapterPosition.toFloat() / chapterDuration else 0f,
            totalProgress = if (totalDuration > 0) absolutePosition.toFloat() / totalDuration else 0f
        )
    }

    /**
     * Seek to a specific position within the current chapter
     * @param positionMs Position relative to the current chapter start
     */
    fun seekInCurrentChapter(positionMs: Long) {
        val currentChapter = getCurrentChapter() ?: return
        val absolutePosition = currentChapter.startMs + positionMs

        // Ensure we don't seek beyond the chapter end
        val clampedPosition = absolutePosition.coerceAtMost(currentChapter.endMs - 1)

        Log.d(tag, "Seeking within chapter to ${positionMs}ms (absolute: ${clampedPosition}ms)")
        wrappedPlayer.seekTo(clampedPosition)
    }

    /**
     * Get all available chapters for the current session
     */
    fun getChapters(): List<BookChapter> {
        return playbackSession?.chapters ?: emptyList()
    }

    /**
     * Seek to an absolute position in the audiobook by finding the correct chapter
     * and converting to chapter-relative position
     */
    fun seekToAbsolutePosition(absolutePositionMs: Long) {
        Log.d(tag, "seekToAbsolutePosition: Seeking to absolute position ${absolutePositionMs}ms")

        val session = playbackSession
        if (session == null || session.chapters.isEmpty()) {
            Log.d(tag, "seekToAbsolutePosition: No chapters available, using raw seek")
            wrappedPlayer.seekTo(absolutePositionMs)
            return
        }

        // Find which chapter contains this absolute position
        val targetChapter = session.chapters.find { chapter ->
            absolutePositionMs >= chapter.startMs && absolutePositionMs < chapter.endMs
        }

        if (targetChapter != null) {
            val chapterRelativePosition = absolutePositionMs - targetChapter.startMs
            Log.d(tag, "seekToAbsolutePosition: Found target chapter '${targetChapter.title}' (${targetChapter.startMs}ms-${targetChapter.endMs}ms)")
            Log.d(tag, "seekToAbsolutePosition: Converting absolute ${absolutePositionMs}ms to chapter-relative ${chapterRelativePosition}ms")

            // CRITICAL FIX: Use direct MediaItem seeking to bypass startup phase protection
            // Convert absolute position to MediaItem-relative position for ExoPlayer
            val (mediaItemIndex, localPosition) = convertAbsoluteToMediaItemPosition(absolutePositionMs)
            Log.d(tag, "seekToAbsolutePosition: Absolute ${absolutePositionMs}ms -> MediaItem $mediaItemIndex at ${localPosition}ms")

            if (mediaItemIndex >= 0) {
                Log.d(tag, "seekToAbsolutePosition: Executing direct MediaItem seek to item $mediaItemIndex, position ${localPosition}ms")
                wrappedPlayer.seekTo(mediaItemIndex, localPosition)

                // Update current chapter index to match the target
                val targetChapterIndex = session.chapters.indexOf(targetChapter)
                if (targetChapterIndex >= 0) {
                    currentChapterIndex = targetChapterIndex
                    Log.d(tag, "seekToAbsolutePosition: Updated current chapter index to $currentChapterIndex")
                }
            } else {
                Log.e(tag, "seekToAbsolutePosition: Failed to convert absolute position ${absolutePositionMs}ms to MediaItem position")
                wrappedPlayer.seekTo(absolutePositionMs)
            }
        } else {
            Log.w(tag, "seekToAbsolutePosition: No chapter found for position ${absolutePositionMs}ms, using raw seek")
            wrappedPlayer.seekTo(absolutePositionMs)
        }
    }

    /**
     * Jump forward within the current chapter by the specified amount
     * Uses chapter-aware seeking to ensure proper position handling
     */
    fun jumpForward(jumpTimeMs: Long) {
        Log.d(tag, "jumpForward: ${jumpTimeMs}ms within current chapter")

        val currentChapter = getCurrentChapter()
        if (currentChapter == null) {
            Log.d(tag, "jumpForward: No current chapter, using absolute seek")
            val newPosition = wrappedPlayer.currentPosition + jumpTimeMs
            wrappedPlayer.seekTo(newPosition)
            return
        }

        // Calculate current position within the chapter
        val currentAbsolutePosition = calculateAbsolutePosition()
        val currentChapterPosition = currentAbsolutePosition - currentChapter.startMs
        val newChapterPosition = currentChapterPosition + jumpTimeMs

        val chapterDuration = currentChapter.endMs - currentChapter.startMs

        Log.d(tag, "jumpForward: Current chapter position: ${currentChapterPosition}ms, jumping to: ${newChapterPosition}ms (chapter duration: ${chapterDuration}ms)")

        if (newChapterPosition >= chapterDuration) {
            // Jump would exceed chapter end - seek to end of chapter
            Log.d(tag, "jumpForward: Jump exceeds chapter boundary, seeking to chapter end")
            val (mediaItemIndex, localPosition) = convertAbsoluteToMediaItemPosition(currentChapter.endMs - 1)
            if (mediaItemIndex >= 0) {
                wrappedPlayer.seekTo(mediaItemIndex, localPosition)
            }
        } else {
            // Stay within chapter - use chapter-relative seeking
            seekTo(newChapterPosition)
        }
    }

    /**
     * Jump backward within the current chapter by the specified amount
     * Uses chapter-aware seeking to ensure proper position handling
     */
    fun jumpBackward(jumpTimeMs: Long) {
        Log.d(tag, "jumpBackward: ${jumpTimeMs}ms within current chapter")

        val currentChapter = getCurrentChapter()
        if (currentChapter == null) {
            Log.d(tag, "jumpBackward: No current chapter, using absolute seek")
            val newPosition = (wrappedPlayer.currentPosition - jumpTimeMs).coerceAtLeast(0L)
            wrappedPlayer.seekTo(newPosition)
            return
        }

        // Calculate current position within the chapter
        val currentAbsolutePosition = calculateAbsolutePosition()
        val currentChapterPosition = currentAbsolutePosition - currentChapter.startMs
        val newChapterPosition = currentChapterPosition - jumpTimeMs

        Log.d(tag, "jumpBackward: Current chapter position: ${currentChapterPosition}ms, jumping to: ${newChapterPosition}ms")

        if (newChapterPosition < 0) {
            // Jump would go before chapter start - seek to beginning of chapter
            Log.d(tag, "jumpBackward: Jump exceeds chapter beginning, seeking to chapter start")
            val (mediaItemIndex, localPosition) = convertAbsoluteToMediaItemPosition(currentChapter.startMs)
            if (mediaItemIndex >= 0) {
                wrappedPlayer.seekTo(mediaItemIndex, localPosition)
            }
        } else {
            // Stay within chapter - use chapter-relative seeking
            seekTo(newChapterPosition)
        }
    }
}
