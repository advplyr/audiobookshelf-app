package com.audiobookshelf.app.dlna

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Timeline

class DlnaTimeline(
    private val mediaItems: List<MediaItem>,
    private val durationsMs: List<Long>
) : Timeline() {

    companion object {
        val EMPTY = DlnaTimeline(emptyList(), emptyList())
    }

    override fun getWindowCount(): Int = mediaItems.size

    override fun getPeriodCount(): Int = mediaItems.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val mediaItem = mediaItems.getOrNull(windowIndex) ?: MediaItem.EMPTY
        val durationUs = durationsMs.getOrNull(windowIndex)?.let { it * 1000 } ?: C.TIME_UNSET
        val isDynamic = durationUs == C.TIME_UNSET

        return window.set(
            windowIndex,
            mediaItem,
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            !isDynamic,
            isDynamic,
            null,
            0,
            durationUs,
            windowIndex,
            windowIndex,
            0
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val durationUs = durationsMs.getOrNull(periodIndex)?.let { it * 1000 } ?: C.TIME_UNSET
        return period.set(periodIndex, periodIndex, periodIndex, durationUs, 0)
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        return if (uid is Int && uid >= 0 && uid < mediaItems.size) uid else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any {
        return periodIndex
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DlnaTimeline) return false
        return mediaItems == other.mediaItems && durationsMs == other.durationsMs
    }

    override fun hashCode(): Int {
        var result = mediaItems.hashCode()
        result = 31 * result + durationsMs.hashCode()
        return result
    }
}
