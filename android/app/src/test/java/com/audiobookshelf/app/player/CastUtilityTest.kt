package com.audiobookshelf.app.player

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.source.TrackGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CastUtilityTest {
  @Test
  fun `millisecond conversion preserves sentinel values`() {
    val tracker = CastTimelineTracker()

    assertEquals(C.TIME_UNSET, tracker.msToUs(C.TIME_UNSET))
    assertEquals(C.TIME_END_OF_SOURCE, tracker.msToUs(C.TIME_END_OF_SOURCE))
    assertEquals(0L, tracker.msToUs(0))
    assertEquals(1_234_000L, tracker.msToUs(1_234))
    assertEquals(-2_000L, tracker.msToUs(-2))
  }

  @Test
  fun `cast track selection exposes only first format`() {
    val first = Format.Builder().setId("first").build()
    val second = Format.Builder().setId("second").build()
    val group = TrackGroup(first, second)
    val selection = CastTrackSelection(group)

    assertEquals(1, selection.length())
    assertSame(first, selection.getFormat(0))
    assertEquals(0, selection.getIndexInTrackGroup(0))
    assertEquals(C.INDEX_UNSET, selection.getIndexInTrackGroup(1))
    assertEquals(0, selection.indexOf(first))
    assertEquals(C.INDEX_UNSET, selection.indexOf(second))
  }

  @Test
  fun `cast track selections compare groups by identity`() {
    val format = Format.Builder().setId("same").build()
    val group = TrackGroup(format)

    assertTrue(CastTrackSelection(group) == CastTrackSelection(group))
    assertFalse(CastTrackSelection(group) == CastTrackSelection(TrackGroup(format)))
  }
}
