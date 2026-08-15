package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounds and volume behaviour for `Podcast` and `PlaybackSession`, from
 * the large/invalid-media issue cluster (see `TESTING.md` §10) -
 * [#1684](https://github.com/advplyr/audiobookshelf-app/issues/1684),
 * [#1650](https://github.com/advplyr/audiobookshelf-app/issues/1650),
 * [#1731](https://github.com/advplyr/audiobookshelf-app/issues/1731). Reported contract:
 *
 * > *A very large episode list or chapter list must not crash, loop, or choose invalid queue/index
 * > values.*
 *
 * No harness: these are pure model calls, so the "10,000 episodes" and ">1,100 chapters" inputs the
 * reports describe cost nothing to run for real rather than in miniature.
 *
 * The Cast queue/timeline half of that row stays out of scope - `CastTimeline` and
 * `CastTimelineTracker` need `android.util.SparseArray`, whose `get`/`put` are no-ops under the
 * mockable `android.jar` (recorded in `TESTING.md` §6).
 */
class LargeMediaBoundsTest {

  private fun podcast(episodes: MutableList<PodcastEpisode>?, numEpisodes: Int? = episodes?.size) =
          Podcast(
                  metadata = PodcastMetadata("Podcast", "Author", null, mutableListOf(), false),
                  coverPath = null,
                  tags = mutableListOf(),
                  episodes = episodes,
                  autoDownloadEpisodes = false,
                  numEpisodes = numEpisodes
          )

  private fun episode(index: Int, track: AudioTrack? = audioTrack(index = index)) =
          PodcastEpisode(
                  "ep-$index", index, null, null, "Episode $index", null, null, null, null, null,
                  track, null, track?.duration, null, null, null
          )

  private fun chapters(count: Int, lengthSeconds: Double = 60.0) =
          (0 until count).map {
            BookChapter(it, it * lengthSeconds, (it + 1) * lengthSeconds, "Chapter $it")
          }

  // --- Episode lists --------------------------------------------------------------------------

  @Test
  fun `a podcast with no episode list reports no tracks instead of throwing`() {
    val nullEpisodes = podcast(episodes = null, numEpisodes = 0)

    assertTrue(nullEpisodes.getAudioTracks().isEmpty())
    assertFalse(nullEpisodes.checkHasTracks())
  }

  @Test
  fun `a podcast with an empty episode list reports no tracks`() {
    val empty = podcast(mutableListOf())

    assertTrue(empty.getAudioTracks().isEmpty())
    assertFalse(empty.checkHasTracks())
  }

  /**
   * A server-side podcast the device has not downloaded has no local `episodes` but a non-zero
   * `numEpisodes`; it must still report as having tracks, or Android Auto hides it entirely.
   */
  @Test
  fun `a podcast with no local episodes still reports tracks from the server count`() {
    assertTrue(podcast(episodes = null, numEpisodes = 42).checkHasTracks())
  }

  @Test
  fun `episodes with no audio track are dropped from the track list rather than yielding nulls`() {
    val mixed = podcast(mutableListOf(episode(1), episode(2, track = null), episode(3)))

    assertEquals(2, mixed.getAudioTracks().size)
  }

  @Test
  fun `a ten thousand episode podcast resolves its track list without looping or overflowing`() {
    val large = podcast((1..10_000).map { episode(it) }.toMutableList())

    assertEquals(10_000, large.getAudioTracks().size)
    assertTrue(large.checkHasTracks())
  }

  @Test
  fun `setAudioTracks renumbers a duplicate and unsorted episode list into a dense sequence`() {
    // Indices arrive duplicated and out of order - the state behind "chooses invalid queue values".
    val scrambled =
            podcast(
                    mutableListOf(
                            episode(5, audioTrack(index = 0)),
                            episode(5, audioTrack(index = 1)),
                            episode(1, audioTrack(index = 2))
                    )
            )

    scrambled.setAudioTracks(scrambled.getAudioTracks().toMutableList())

    assertEquals(listOf(1, 2, 3), scrambled.episodes!!.map { it.index })
  }

  @Test
  fun `removing an episode's track renumbers the remainder without leaving a gap`() {
    val threeEpisodes =
            podcast(
                    mutableListOf(
                            episode(1, audioTrack(index = 0)),
                            episode(2, audioTrack(index = 1)),
                            episode(3, audioTrack(index = 2))
                    )
            )

    threeEpisodes.removeAudioTrack("track-1")

    assertEquals(listOf(1, 2), threeEpisodes.episodes!!.map { it.index })
    assertEquals(2, threeEpisodes.getAudioTracks().size)
  }

  // --- Chapter lists --------------------------------------------------------------------------

  @Test
  fun `a session with no chapters returns null rather than an out-of-range chapter`() {
    val session = playbackSession(mutableListOf(audioTrack(duration = 100.0)))

    assertNull(session.getChapterForTime(0L))
    assertNull(session.getNextChapterForTime(0L))
  }

  @Test
  fun `a session with more than eleven hundred chapters resolves positions in both directions`() {
    val session =
            playbackSession(
                    mutableListOf(audioTrack(duration = 1_200 * 60.0)),
                    chapters = chapters(1_200)
            )

    assertEquals("Chapter 0", session.getChapterForTime(0L)?.title)
    assertEquals("Chapter 1199", session.getChapterForTime(1_199 * 60_000L)?.title)
    assertEquals("Chapter 600", session.getNextChapterForTime(599 * 60_000L + 1)?.title)
    // Past the final chapter there is no next chapter, and asking must not throw.
    assertNull(session.getNextChapterForTime(1_200 * 60_000L))
  }

  @Test
  fun `a time past the end of every chapter resolves to no chapter rather than the last one`() {
    val session =
            playbackSession(mutableListOf(audioTrack(duration = 180.0)), chapters = chapters(3))

    assertNull(session.getChapterForTime(180_000L))
    assertNotNull(session.getChapterForTime(179_999L))
  }

  // --- Track indices --------------------------------------------------------------------------

  @Test
  fun `the current track index stays inside the track list for times beyond the duration`() {
    val session =
            playbackSession(
                    mutableListOf(audioTrack(index = 0, duration = 10.0), audioTrack(index = 1, startOffset = 10.0, duration = 10.0)),
                    currentTime = 5_000.0
            )

    assertEquals(1, session.getCurrentTrackIndex())
    assertEquals(1, session.getNextTrackIndex())
  }

  /**
   * Inputs:   a two-track session with `currentTime = -5.0` - the state left by a bad seek, or by a
   *           session restored against a re-scanned item.
   *
   * Expected: the first track. A position before the book starts is at its beginning.
   *
   * Observed: the **last** track (index 1). `getCurrentTrackIndex`'s loop matches nothing, and its
   *           fallthrough returns `audioTracks.size - 1` unconditionally - correct for a position
   *           past the end, wrong for one before the start (`PlaybackSession.kt:110`).
   *
   * Path:     `getCurrentTrackIndex` -> `getCurrentTrackEndTime`/`getCurrentTrackTimeMs` and
   *           `PlayerNotificationService`'s window index. Note `getNextTrackIndex` answers `0` for
   *           the same input, so the two disagree about where the listener is.
   *
   * Previously asserted as `getCurrentTrackIndex() in 0..1`, which admitted both answers and hid
   * this; the name always claimed the first track.
   */
  @Test
  fun `a negative current time still selects the first track`() {
    val session =
            playbackSession(
                    mutableListOf(audioTrack(index = 0, duration = 10.0), audioTrack(index = 1, startOffset = 10.0, duration = 10.0)),
                    currentTime = -5.0
            )

    assertEquals(0, session.getNextTrackIndex())
    // `in 0..1` here would admit either track and leave the name ("selects the first track")
    // overclaiming what the assertion checks.
    assertEquals(0, session.getCurrentTrackIndex())
  }

  /**
   * **Newly found this pass.** Directly the "current/next index always in bounds" case
   * ([#1684](https://github.com/advplyr/audiobookshelf-app/issues/1684),
   * [#1650](https://github.com/advplyr/audiobookshelf-app/issues/1650)).
   *
   * Inputs: a `PlaybackSession` whose `audioTracks` is empty - the state left by an item whose
   * tracks failed to resolve, a podcast episode with no audio file, or an invalid/zero-duration
   * media record.
   *
   * Expected: an index that can be used to address the track list, or an explicit "no track"
   * answer.
   *
   * Observed: `getCurrentTrackIndex()` and `getNextTrackIndex()` both fall through their loops and
   * `return audioTracks.size - 1`, which is **-1** for an empty list (`PlaybackSession.kt:97` and
   * `:108`). Every consumer then indexes with it: `getCurrentTrackEndTime()` (`:119`),
   * `getCurrentTrackTimeMs()` (`:137`) and `getNextTrackEndTime()` (`:129`) do
   * `audioTracks[-1]` -> `IndexOutOfBoundsException`, and
   * `PlayerNotificationService:547/567/986` pass it to the player as a window index.
   *
   * Sibling `getTrackStartOffsetMs(index)` already guards exactly this (`if (index < 0 || index >=
   * audioTracks.size) return 0L`, `:143`), so the missing guard is an omission rather than a
   * deliberate contract.
   */
  @Test
  fun `track indices for a session with no tracks must not be negative`() {
    val session = playbackSession(mutableListOf())

    assertTrue(
            "getCurrentTrackIndex returned ${session.getCurrentTrackIndex()}, which every caller " +
                    "uses to index audioTracks",
            session.getCurrentTrackIndex() >= 0
    )
    assertTrue(session.getNextTrackIndex() >= 0)
  }

  /** The consequence of the above, at the call site that reaches the player's queue navigator. */
  @Test
  fun `asking a session with no tracks for its current track end time must not throw`() {
    val session = playbackSession(mutableListOf())

    var thrown: Throwable? = null
    var endTime: Long? = null
    try {
      endTime = session.getCurrentTrackEndTime()
    } catch (e: Throwable) {
      thrown = e
    }

    assertNull("an empty track list must not throw out of a queue-navigation helper", thrown)
    // "did not throw" and "returned something a caller can use" are different contracts, and only
    // the second one keeps the queue navigator correct - a helper that returned a negative end
    // time without throwing would still be wrong.
    assertEquals("a session with no tracks has no playable time", 0L, endTime)
  }

  @Test
  fun `total duration of a session with no tracks is zero rather than an error`() {
    assertEquals(0.0, playbackSession(mutableListOf()).getTotalDuration(), 0.0)
  }
}
