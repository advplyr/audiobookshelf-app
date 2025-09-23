package com.tomesonic.app.player.mediasource

import android.net.Uri

/**
 * Enhanced chapter model that contains all information needed to create a ClippingMediaSource
 * for a specific chapter, including the underlying audio file and timing information.
 */
data class ChapterSegment(
    val chapterIndex: Int,
    val title: String?,
    val audioFileUri: Uri,
    val chapterStartMs: Long,     // Start time of chapter within the entire book
    val chapterEndMs: Long,       // End time of chapter within the entire book
    val audioFileStartMs: Long,   // Start time of this chapter within its audio file
    val audioFileEndMs: Long,     // End time of this chapter within its audio file
    val audioFileDurationMs: Long // Total duration of the underlying audio file
) {
    /**
     * Duration of this chapter in milliseconds
     */
    val durationMs: Long
        get() = chapterEndMs - chapterStartMs

    /**
     * Whether this chapter spans the entire audio file
     */
    val spansEntireFile: Boolean
        get() = audioFileStartMs == 0L && audioFileEndMs == audioFileDurationMs

    /**
     * Chapter title with fallback to index-based name
     */
    val displayTitle: String
        get() = title ?: "Chapter ${chapterIndex + 1}"
}
