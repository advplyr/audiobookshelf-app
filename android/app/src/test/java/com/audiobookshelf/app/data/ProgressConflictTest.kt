package com.audiobookshelf.app.data

import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The progress-conflict cluster: [#1945](https://github.com/advplyr/audiobookshelf-app/issues/1945),
 * [#1940](https://github.com/advplyr/audiobookshelf-app/issues/1940),
 * [#1852](https://github.com/advplyr/audiobookshelf-app/issues/1852),
 * [#1516](https://github.com/advplyr/audiobookshelf-app/issues/1516),
 * [#1510](https://github.com/advplyr/audiobookshelf-app/issues/1510),
 * [#1442](https://github.com/advplyr/audiobookshelf-app/issues/1442),
 * [#1416](https://github.com/advplyr/audiobookshelf-app/issues/1416),
 * [#1338](https://github.com/advplyr/audiobookshelf-app/issues/1338) - eight open reports of the
 * app losing a listener's place in a book. The shared reported contract is:
 *
 * > *A stale, duplicate, remote, or post-restart session must not replace newer local progress or
 * > mark media finished from a value beyond duration.*
 *
 * `LocalMediaProgress.updateFromPlaybackSession` is the single point where that contract is
 * decided for the local (on-device) direction, and it makes **no comparison at all** - it assigns
 * `currentTime`, `progress`, `lastUpdate` and `isFinished` unconditionally
 * (`LocalMediaProgress.kt:59-65`). The live path is
 * `MediaProgressSyncer.saveLocalProgress`, which loads the existing record from Paper and calls
 * exactly this method on it (`MediaProgressSyncer.kt:328-336`), so whichever session arrives last
 * wins regardless of age.
 *
 * The failing specs below are enabled by design (`TESTING.md` §1 rule 3). Each states its exact inputs, the expected
 * result, and the currently observed result. The production fix belongs on its own branch.
 *
 * The **server** direction (`updateFromServerMediaProgress`) is deliberately not specified here as
 * a failing invariant: it is also unguarded, but its two production callers differ -
 * `ApiHandler.syncLocalMediaProgressForUser` compares `mediaProgress.lastUpdate >
 * localMediaProgress.lastUpdate` before calling it (`ApiHandler.kt:825`), while
 * `AbsDatabase.syncServerMediaProgressWithLocalMediaProgress` (the websocket path, and #1945's
 * reported shape) does not (`AbsDatabase.kt:368`). The defect is therefore specified where it is
 * actually reachable, in `AbsDatabaseTest`; what this class pins is the unguarded behaviour of the
 * method itself, as a characterization test, so a future guard added at the method level is a
 * visible change rather than a silent one.
 */
class ProgressConflictTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager

  @Before
  fun setUp() {
    db = DbManager()
  }

  /** A stored record 90 s into a 100 s book, last written at t=9,000,000. */
  private fun newerStoredProgress(id: String = "local-item") =
          localProgress().apply {
            this.id = id
            currentTime = 90.0
            progress = 0.90
            lastUpdate = 9_000_000L
          }

  /**
   * A playback session with an explicit `updatedAt`. Defaults to one *older* than
   * [newerStoredProgress], which is the stale-delivery case most specs here need; the guard specs
   * pass a newer `updatedAt` to prove a legitimate update still wins.
   */
  private fun session(currentTime: Double = 5.0, updatedAt: Long = 1_000L) =
          playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = currentTime)
                  .apply { this.updatedAt = updatedAt }

  // --- Defect specs: enabled failures with an open GitHub report -----------------------------

  /**
   * Inputs: stored record `currentTime=90.0, lastUpdate=9_000_000`; session `currentTime=5.0,
   * updatedAt=1_000`.
   *
   * Expected: the newer stored position survives - a session that predates the record cannot
   * describe a later listening position.
   *
   * Observed: `currentTime` becomes `5.0`. The user is thrown 85 seconds back in the book.
   *
   * Path: `MediaProgressSyncer.saveLocalProgress` -> `LocalMediaProgress
   * .updateFromPlaybackSession` (`LocalMediaProgress.kt:60`).
   */
  @Test
  fun `a playback session older than the stored record must not overwrite its position`() {
    val stored = newerStoredProgress()

    stored.updateFromPlaybackSession(session())

    assertEquals(
            "a session from t=1000 must not replace a position recorded at t=9000000",
            90.0,
            stored.currentTime,
            0.0
    )
  }

  /**
   * Inputs: as above.
   *
   * Expected: `lastUpdate` never decreases. It is the sole tiebreaker every later reconciliation
   * uses - `ApiHandler.syncLocalMediaProgressForUser` compares `mediaProgress.lastUpdate >
   * localMediaProgress.lastUpdate` to decide whether the server or the device is authoritative.
   *
   * Observed: `lastUpdate` is dragged back to `1_000`, so the *next* server sync now believes the
   * device's data is ancient and overwrites it too. This is why a single stale session can cascade
   * into repeated position loss rather than one recoverable glitch.
   */
  @Test
  fun `a stale playback session must not move lastUpdate backwards`() {
    val stored = newerStoredProgress()

    stored.updateFromPlaybackSession(session())

    assertTrue(
            "lastUpdate went backwards to ${stored.lastUpdate}, poisoning every later " +
                    "server/device comparison that reads it",
            stored.lastUpdate >= 9_000_000L
    )
  }

  /**
   * #1940's shape: the same session delivered twice, the second copy arriving after a newer one
   * has already been applied (a duplicate sync callback, or a session restored after a restart).
   *
   * Inputs: a record at `currentTime=90.0/lastUpdate=9_000_000`, updated by a *newer* session
   * (`currentTime=95.0, updatedAt=9_500_000`), then by a redelivery of the older session
   * (`currentTime=5.0, updatedAt=1_000`).
   *
   * Expected: `95.0` - out-of-order delivery must not decide the outcome.
   *
   * Observed: `5.0`. Last writer wins regardless of ordering.
   */
  @Test
  fun `a duplicate session redelivered out of order must not undo a newer one`() {
    val stored = newerStoredProgress()

    stored.updateFromPlaybackSession(session(currentTime = 95.0, updatedAt = 9_500_000L))
    stored.updateFromPlaybackSession(session(currentTime = 5.0, updatedAt = 1_000L))

    assertEquals(
            "a redelivered older session must not undo the newer position already applied",
            95.0,
            stored.currentTime,
            0.0
    )
  }

  /**
   * #1852/#1516: books marked finished that the listener never reached the end of.
   *
   * Inputs: a 100 s session whose `currentTime` is `500.0` - the state reported after a track-list
   * change, a bad seek, or a session restored against a shorter re-scanned duration.
   * `PlaybackSession.progress` is `currentTime / getTotalDuration()` with no clamp, so it reads
   * `5.0`.
   *
   * Expected: a progress fraction outside `0.0..1.0` never reaches durable storage.
   *
   * Observed: `progress` is persisted as `5.0`, and because `isFinished` is derived as
   * `progress >= 0.99` the book is also flagged finished from that same out-of-range value.
   *
   * Note this specifies the **persistence** consequence only. That `PlaybackSession.progress`
   * itself is unclamped is a separate, already-enabled failing spec (`PlaybackSessionTest.time
   * beyond duration uses final track and clamps progress`) and is not re-counted here. Once the
   * clamp lands, `progress` becomes `1.0` and `isFinished` becomes legitimately `true`, which is
   * why the assertion below is on the range rather than on the flag.
   */
  @Test
  fun `a progress fraction beyond the media duration must not reach durable storage`() {
    val stored = newerStoredProgress()
    val beyondDuration = session(currentTime = 500.0, updatedAt = 10_000_000L)

    stored.updateFromPlaybackSession(beyondDuration)
    db.saveLocalMediaProgress(stored)

    val persisted = db.getLocalMediaProgress("local-item")!!
    assertTrue(
            "persisted progress ${persisted.progress} is outside 0.0..1.0, and isFinished " +
                    "(${persisted.isFinished}) was derived from it",
            persisted.progress in 0.0..1.0
    )
  }

  // --- Characterization: current behaviour, pinned so a change is visible ---------------------

  /**
   * The server direction of the same missing comparison. Not asserted as an invariant here - see
   * the class KDoc: one of its two callers guards it, the other does not, so the defect is
   * specified against the unguarded caller in `AbsDatabaseTest`. This records that the method
   * itself performs no age check, so adding one becomes a deliberate, visible change.
   */
  @Test
  fun `updateFromServerMediaProgress applies an older server record unconditionally today`() {
    val stored = newerStoredProgress()
    val olderServerRecord =
            MediaProgress(
                    id = "sp1",
                    libraryItemId = "library-item",
                    episodeId = null,
                    duration = 100.0,
                    progress = 0.01,
                    currentTime = 1.0,
                    isFinished = false,
                    ebookLocation = null,
                    ebookProgress = null,
                    lastUpdate = 1_000L,
                    startedAt = 0L,
                    finishedAt = null
            )

    stored.updateFromServerMediaProgress(olderServerRecord)

    assertEquals(1.0, stored.currentTime, 0.0)
    assertEquals(1_000L, stored.lastUpdate)
  }

  /**
   * A session that is genuinely newer than the stored record must still win. Without this, a fix
   * for the specs above could satisfy them by refusing every update, which would break normal
   * playback instead of a stale one.
   */
  @Test
  fun `a newer playback session does update the stored position`() {
    val stored = newerStoredProgress()

    stored.updateFromPlaybackSession(session(currentTime = 95.0, updatedAt = 9_500_000L))

    assertEquals(95.0, stored.currentTime, 0.0)
    assertEquals(9_500_000L, stored.lastUpdate)
  }

  /** Re-applying the identical session is a no-op; only out-of-order delivery is a problem. */
  @Test
  fun `applying the same session twice is idempotent`() {
    val stored = newerStoredProgress()
    val session = session(currentTime = 95.0, updatedAt = 9_500_000L)

    stored.updateFromPlaybackSession(session)
    val firstPass = Triple(stored.currentTime, stored.progress, stored.lastUpdate)
    stored.updateFromPlaybackSession(session)

    assertEquals(firstPass, Triple(stored.currentTime, stored.progress, stored.lastUpdate))
  }

  // --- Episode independence ------------------------------------------------------------------

  /**
   * The issue cluster's "different episode IDs must remain independent" case: two episodes of one
   * podcast share a `localLibraryItemId`, so a progress record keyed on the item alone would make
   * listening to episode 2 destroy episode 1's position.
   */
  @Test
  fun `two episodes of one podcast get distinct progress ids and never cross-write`() {
    val episodeOne = sessionForEpisode("ep-1", currentTime = 30.0)
    val episodeTwo = sessionForEpisode("ep-2", currentTime = 70.0)

    assertEquals("local-item-ep-1", episodeOne.localMediaProgressId)
    assertEquals("local-item-ep-2", episodeTwo.localMediaProgressId)

    db.saveLocalMediaProgress(episodeOne.getNewLocalMediaProgress())
    db.saveLocalMediaProgress(episodeTwo.getNewLocalMediaProgress())

    assertEquals(30.0, db.getLocalMediaProgress("local-item-ep-1")!!.currentTime, 0.0)
    assertEquals(70.0, db.getLocalMediaProgress("local-item-ep-2")!!.currentTime, 0.0)
    assertEquals(2, db.getAllLocalMediaProgress().size)
  }

  /**
   * The same independence at the point of *update*: finishing episode 2 must not mark episode 1
   * finished. `updateIsFinished` also zeroes `currentTime`, which would silently discard episode
   * 1's position if the two records were conflated.
   */
  @Test
  fun `marking one podcast episode finished leaves the other episode untouched`() {
    db.saveLocalMediaProgress(sessionForEpisode("ep-1", currentTime = 30.0).getNewLocalMediaProgress())
    db.saveLocalMediaProgress(sessionForEpisode("ep-2", currentTime = 70.0).getNewLocalMediaProgress())

    val episodeTwo = db.getLocalMediaProgress("local-item-ep-2")!!
    episodeTwo.updateIsFinished(true)
    db.saveLocalMediaProgress(episodeTwo)

    val episodeOne = db.getLocalMediaProgress("local-item-ep-1")!!
    assertFalse("episode 1 must not be finished by finishing episode 2", episodeOne.isFinished)
    assertEquals(30.0, episodeOne.currentTime, 0.0)
  }

  private fun sessionForEpisode(episodeId: String, currentTime: Double): PlaybackSession =
          playbackSession(
                          mutableListOf(audioTrack(duration = 100.0)),
                          currentTime = currentTime,
                          episodeId = episodeId,
                          localEpisodeId = episodeId
                  )
                  .apply { localLibraryItem = localLibraryItem(id = "local-item") }
}
