package com.audiobookshelf.app.data

import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.player.PLAYMETHOD_DIRECTPLAY
import com.audiobookshelf.app.player.PLAYMETHOD_LOCAL
import com.audiobookshelf.app.player.PLAYMETHOD_TRANSCODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionExtraTest {
  @Test
  fun `play method flags are mutually exclusive`() {
    val session = playbackSession()

    session.playMethod = PLAYMETHOD_DIRECTPLAY
    assertTrue(session.isDirectPlay)
    assertFalse(session.isHLS)
    assertFalse(session.isLocal)

    session.playMethod = PLAYMETHOD_TRANSCODE
    assertTrue(session.isHLS)
    assertFalse(session.isDirectPlay)

    session.playMethod = PLAYMETHOD_LOCAL
    assertTrue(session.isLocal)
    assertFalse(session.isHLS)
  }

  @Test
  fun `isPodcastEpisode reflects media type only`() {
    assertTrue(playbackSession(episodeId = "episode").isPodcastEpisode)
    assertFalse(playbackSession(episodeId = null).isPodcastEpisode)
  }

  @Test
  fun `currentTimeMs and totalDurationMs convert seconds to milliseconds`() {
    val session = playbackSession(tracks = mutableListOf(audioTrack(duration = 12.5)), currentTime = 3.25)

    assertEquals(3_250L, session.currentTimeMs)
    assertEquals(12_500L, session.totalDurationMs)
  }

  @Test
  fun `localLibraryItemId is empty without a local library item`() {
    val session = playbackSession()

    assertEquals("", session.localLibraryItemId)
    assertEquals("", session.localMediaProgressId)
  }

  @Test
  fun `localMediaProgressId combines local item and episode ids`() {
    val session = playbackSession(localEpisodeId = "local-ep")
    session.localLibraryItem = localLibraryItem(id = "local-item")

    assertEquals("local-item", session.localLibraryItemId)
    assertEquals("local-item-local-ep", session.localMediaProgressId)
  }

  @Test
  fun `localMediaProgressId omits episode suffix when episode id is blank`() {
    val session = playbackSession(localEpisodeId = "")
    session.localLibraryItem = localLibraryItem(id = "local-item")

    assertEquals("local-item", session.localMediaProgressId)
  }

  @Test
  fun `mediaItemId distinguishes books and episodes and tolerates null library item`() {
    assertEquals("library-item", playbackSession(episodeId = null).mediaItemId)
    assertEquals("library-item-episode", playbackSession(episodeId = "episode").mediaItemId)

    val session = playbackSession(episodeId = null)
    session.libraryItemId = null
    assertEquals("", session.mediaItemId)
  }

  /**
   * Characterization of `clone()`'s depth, not just its identity. The `assertSame` below is the
   * load-bearing assertion: the copy is **shallow**, so both sessions share one `audioTracks`
   * instance and a mutation through either is visible through the other.
   *
   * That is safe as production uses it today - `clone()` is called to snapshot timing fields for a
   * sync payload, and the track list is not mutated afterwards - but it is a real aliasing edge,
   * so it is pinned rather than left to be discovered.
   */
  @Test
  fun `clone is a distinct instance but shares its track list with the original`() {
    val session = playbackSession(tracks = mutableListOf(audioTrack()), currentTime = 5.0)
    session.timeListening = 42L

    val clone = session.clone()

    assertFalse(clone === session)
    assertEquals(session.id, clone.id)
    assertEquals(session.timeListening, clone.timeListening)
    assertEquals(session.currentTime, clone.currentTime, 0.0)
    assertSame("clone() is shallow: both sessions share one track list", session.audioTracks, clone.audioTracks)

    // The consequence, made explicit rather than left implied by assertSame.
    session.audioTracks.add(audioTrack(index = 9))
    assertEquals(
            "a track added through the original is visible through the clone",
            session.audioTracks.size,
            clone.audioTracks.size
    )
  }

  @Test
  fun `syncData accumulates listening time and updates current time`() {
    val session = playbackSession(tracks = mutableListOf(audioTrack(duration = 100.0)))
    session.timeListening = 10L

    session.syncData(MediaProgressSyncData(timeListened = 5L, duration = 100.0, currentTime = 15.0))

    assertEquals(15L, session.timeListening)
    assertEquals(15.0, session.currentTime, 0.0)
  }

  @Test
  fun `getNewLocalMediaProgress maps session fields into local progress`() {
    val session =
            playbackSession(tracks = mutableListOf(audioTrack(duration = 100.0)), currentTime = 25.0)
    session.localLibraryItem = localLibraryItem(id = "local-item")
    session.localEpisodeId = "local-ep"

    val progress = session.getNewLocalMediaProgress()

    assertEquals("local-item-local-ep", progress.id)
    assertEquals("local-item", progress.localLibraryItemId)
    assertEquals("local-ep", progress.localEpisodeId)
    assertEquals(100.0, progress.duration, 0.0)
    assertEquals(0.25, progress.progress, 0.0)
    assertEquals(25.0, progress.currentTime, 0.0)
    assertFalse(progress.isFinished)
  }

  @Test
  fun `getCurrentTrackTimeMs is relative to the current track start offset`() {
    val tracks = mutableListOf(audioTrack(0, 0.0, 10.0), audioTrack(1, 10.0, 10.0))
    val session = playbackSession(tracks = tracks, currentTime = 13.0)

    assertEquals(3_000L, session.getCurrentTrackTimeMs())
  }

  @Test
  fun `getNextTrackEndTime reports the end of the upcoming track`() {
    val tracks = mutableListOf(audioTrack(0, 0.0, 10.0), audioTrack(1, 10.0, 5.0))
    val session = playbackSession(tracks = tracks, currentTime = 2.0)

    assertEquals(15_000L, session.getNextTrackEndTime())
  }
}
