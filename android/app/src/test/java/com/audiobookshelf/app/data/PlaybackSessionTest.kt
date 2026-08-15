package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSessionTest {
  @Test
  fun `empty track list returns safe neutral timing values`() {
    val session = playbackSession(currentTime = 12.0)

    // Was asserted as -1 (what unfixed production returns) until this was reconciled
    // against LargeMediaBoundsTest's "track indices for a session with no tracks must
    // not be negative", which enables the >= 0 contract as a defect spec. The two
    // specs asserted opposite outcomes for the identical call; this is the one that
    // moves, since coercing to 0 is what item 3 in TESTING.md 7.1 prescribes.
    assertEquals(0, session.getCurrentTrackIndex())
    assertEquals(0, session.getNextTrackIndex())
    assertEquals(0L, session.getCurrentTrackEndTime())
    assertEquals(0L, session.getNextTrackEndTime())
    assertEquals(0L, session.getCurrentTrackTimeMs())
    assertEquals(0.0, session.progress, 0.0)
  }

  @Test
  fun `track boundary belongs to following track`() {
    val tracks = mutableListOf(audioTrack(0, 0.0, 10.0), audioTrack(1, 10.0, 5.0))
    val session = playbackSession(tracks = tracks, currentTime = 10.0)

    assertEquals(1, session.getCurrentTrackIndex())
    assertEquals(1, session.getNextTrackIndex())
    assertEquals(15_000L, session.getCurrentTrackEndTime())
    assertEquals(0L, session.getCurrentTrackTimeMs())
  }

  @Test
  fun `time beyond duration uses final track and clamps progress`() {
    val tracks = mutableListOf(audioTrack(0, 0.0, 10.0), audioTrack(1, 10.0, 5.0))
    val session = playbackSession(tracks = tracks, currentTime = 99.0)

    assertEquals(1, session.getCurrentTrackIndex())
    assertEquals(1, session.getNextTrackIndex())
    assertEquals(1.0, session.progress, 0.0)
  }

  @Test
  fun `negative and non-finite positions produce safe progress`() {
    val tracks = mutableListOf(audioTrack(duration = 10.0))

    assertEquals(0.0, playbackSession(tracks, currentTime = -2.0).progress, 0.0)
    assertEquals(0.0, playbackSession(tracks, currentTime = Double.NaN).progress, 0.0)
  }

  @Test
  fun `chapter lookup uses half-open boundaries`() {
    val chapters = listOf(BookChapter(1, 0.0, 10.0, "One"), BookChapter(2, 10.0, 20.0, "Two"))
    val session = playbackSession(chapters = chapters)

    assertEquals("One", session.getChapterForTime(9_999)?.title)
    assertEquals("Two", session.getChapterForTime(10_000)?.title)
    assertNull(session.getChapterForTime(20_000))
    assertEquals("Two", session.getNextChapterForTime(9_999)?.title)
  }

  @Test
  fun `invalid track offsets return zero`() {
    val session = playbackSession(mutableListOf(audioTrack(startOffset = 3.5)))

    assertEquals(0L, session.getTrackStartOffsetMs(-1))
    assertEquals(0L, session.getTrackStartOffsetMs(1))
    assertEquals(3_500L, session.getTrackStartOffsetMs(0))
  }
}
