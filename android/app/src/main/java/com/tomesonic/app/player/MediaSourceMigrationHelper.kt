package com.tomesonic.app.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import com.tomesonic.app.data.PlaybackSession
import com.tomesonic.app.player.mediasource.AudiobookMediaSourceBuilder
import com.tomesonic.app.player.mediasource.ChapterSegment

/**
 * Helper class to ease migration from MediaSource to MediaItem-based playback
 * Provides convenience methods for common playback scenarios
 */
object MediaSourceMigrationHelper {
    private const val TAG = "MediaSourceMigration"
    
    /**
     * Create MediaItems for a playback session with all necessary configuration
     * @param context Application context
     * @param session PlaybackSession to create items from
     * @param forCast Whether to create items for cast playback
     * @return PlaybackConfig with MediaItems and start position
     */
    fun createPlaybackConfig(
        context: Context,
        session: PlaybackSession,
        forCast: Boolean = false
    ): PlaybackConfig? {
        val builder = AudiobookMediaSourceBuilder(context)
        val mediaItems = builder.buildMediaItems(session, forCast)
        
        if (mediaItems.isEmpty()) {
            Log.e(TAG, "Failed to create media items for session")
            return null
        }
        
        val startIndex = builder.calculateStartMediaItemIndex(session)
        val startPosition = builder.calculateStartPositionInMediaItem(session)
        
        return PlaybackConfig(
            mediaItems = mediaItems,
            startMediaItemIndex = startIndex,
            startPositionMs = startPosition,
            chapterSegments = builder.getLastChapterSegments()
        )
    }
    
    /**
     * Configuration for playbook with all necessary parameters
     */
    data class PlaybackConfig(
        val mediaItems: List<MediaItem>,
        val startMediaItemIndex: Int,
        val startPositionMs: Long,
        val chapterSegments: List<ChapterSegment>
    )
    
    /**
     * Convert absolute position to MediaItem index and position
     */
    fun convertAbsolutePosition(
        session: PlaybackSession,
        absolutePositionMs: Long
    ): Pair<Int, Long> {
        session.chapters.forEachIndexed { index, chapter ->
            if (absolutePositionMs >= chapter.startMs && absolutePositionMs < chapter.endMs) {
                val positionInChapter = absolutePositionMs - chapter.startMs
                return Pair(index, positionInChapter)
            }
        }
        return Pair(0, 0L)
    }
    
    /**
     * Calculate absolute position from MediaItem index and position
     */
    fun calculateAbsolutePosition(
        session: PlaybackSession,
        mediaItemIndex: Int,
        positionInItemMs: Long
    ): Long {
        if (mediaItemIndex < session.chapters.size) {
            val chapter = session.chapters[mediaItemIndex]
            return chapter.startMs + positionInItemMs
        }
        return positionInItemMs
    }
    
    /**
     * Create a MediaSource for legacy code (backward compatibility)
     * @deprecated Use createPlaybackConfig for MediaItem-based approach
     */
    @Deprecated("Use createPlaybackConfig for MediaItem-based playback")
    fun createLegacyMediaSource(
        context: Context,
        session: PlaybackSession,
        forCast: Boolean = false
    ): MediaSource? {
        val builder = AudiobookMediaSourceBuilder(context)
        return builder.buildMediaSource(session, forCast)
    }
    
    /**
     * Convenience method to prepare a player with MediaItems
     * @param context Application context
     * @param session PlaybackSession to play
     * @param forCast Whether to prepare for cast
     * @param playerPreparation Callback to prepare the actual player with MediaItems
     */
    fun preparePlayerWithMediaItems(
        context: Context,
        session: PlaybackSession,
        forCast: Boolean = false,
        playerPreparation: (mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) -> Unit
    ): Boolean {
        val config = createPlaybackConfig(context, session, forCast)
        
        return if (config != null) {
            Log.d(TAG, "Preparing player with ${config.mediaItems.size} MediaItems, starting at item ${config.startMediaItemIndex}")
            playerPreparation(config.mediaItems, config.startMediaItemIndex, config.startPositionMs)
            true
        } else {
            Log.e(TAG, "Failed to create playback config for session")
            false
        }
    }
    
    /**
     * Calculate the total duration of an audiobook from chapters
     */
    fun calculateTotalDuration(session: PlaybackSession): Long {
        return if (session.chapters.isNotEmpty()) {
            session.chapters.sumOf { it.endMs - it.startMs }
        } else {
            session.audioTracks.sumOf { (it.duration * 1000).toLong() }
        }
    }
    
    /**
     * Find the chapter that contains the given absolute position
     */
    fun findChapterAtPosition(session: PlaybackSession, positionMs: Long): Pair<Int, com.tomesonic.app.data.BookChapter?> {
        session.chapters.forEachIndexed { index, chapter ->
            if (positionMs >= chapter.startMs && positionMs < chapter.endMs) {
                return Pair(index, chapter)
            }
        }
        return Pair(-1, null)
    }
    
    /**
     * Validate that a PlaybackSession is ready for MediaItem-based playback
     */
    fun validatePlaybackSession(session: PlaybackSession): ValidationResult {
        val issues = mutableListOf<String>()
        
        if (session.audioTracks.isEmpty()) {
            issues.add("No audio tracks found")
        }
        
        if (session.chapters.isEmpty()) {
            issues.add("No chapters found - will use track-based segments")
        }
        
        // Validate chapters are properly ordered and non-overlapping
        if (session.chapters.isNotEmpty()) {
            session.chapters.forEachIndexed { index, chapter ->
                if (index > 0) {
                    val prevChapter = session.chapters[index - 1]
                    if (chapter.startMs < prevChapter.endMs) {
                        issues.add("Chapter $index overlaps with previous chapter")
                    }
                }
                
                if (chapter.startMs >= chapter.endMs) {
                    issues.add("Chapter $index has invalid duration (${chapter.startMs} >= ${chapter.endMs})")
                }
            }
        }
        
        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues
        )
    }
    
    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )
}