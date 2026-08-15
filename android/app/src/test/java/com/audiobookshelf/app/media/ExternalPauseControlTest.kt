package com.audiobookshelf.app.media

import android.content.Context
import android.net.ConnectivityManager
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.data.playbackSession
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pause from an external control - the pause/external-control issue cluster (see `TESTING.md` §10): [#1847](https://github.com/advplyr/audiobookshelf-app/issues/1847),
 * [#1828](https://github.com/advplyr/audiobookshelf-app/issues/1828),
 * [#1491](https://github.com/advplyr/audiobookshelf-app/issues/1491). Reported contract:
 *
 * > *Pause from notification, lock screen, Bluetooth, or Android Auto persists the exact position
 * > once and syncs the correct item.*
 *
 * The four named cases are "pause action calls save exactly once", "no active session is a no-op",
 * "a delayed duplicate callback cannot overwrite a later position", and "podcast episode identity
 * is retained".
 *
 * All four are decided in `MediaProgressSyncer`, which is where they are tested. The *delivery* of
 * the pause - `MediaSessionCallback.onPause`, the notification action, the Bluetooth media button -
 * is already covered by `MediaSessionCallbackTest` for everything reachable, and the rest is
 * `Timer`/`Handler`-bound and stays blocked. What was untested is what happens *after* the pause
 * reaches the syncer, which is where the reported data loss occurs.
 *
 * `start()` is deliberately never called here (its non-daemon 15-second `Timer` leaks and its body
 * cannot run on the host JVM); `listeningTimerRunning` is a public `var` and is set directly.
 */
class ExternalPauseControlTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var pns: PlayerNotificationService
  private lateinit var syncer: MediaProgressSyncer
  private lateinit var db: DbManager

  /**
   * Held explicitly rather than reached through `pns.clientEventEmitter` at each verify site. A
   * `verify(exactly = 0) { pns.clientEventEmitter?.onX() }` records nothing at all when the
   * property is null, so it passes for the wrong reason - the null-accepting shape TESTING.md
   * warns about, in a form the written rule does not name. Asserting through a non-null local
   * removes the possibility.
   */
  private lateinit var emitter: PlayerNotificationService.ClientEventEmitter

  @Before
  fun setUp() {
    db = DbManager()
    pns = mockk(relaxed = true)
    // A relaxed Context returns a generic proxy for CONNECTIVITY_SERVICE, which
    // DeviceManager.checkConnectivity's unchecked cast turns into a ClassCastException rather
    // than a plain "not connected".
    every { pns.getSystemService(Context.CONNECTIVITY_SERVICE) } returns
            mockk<ConnectivityManager>(relaxed = true)
    emitter = mockk(relaxed = true)
    every { pns.clientEventEmitter } returns emitter
    syncer = MediaProgressSyncer(pns, AbsTestEnvironment.apiHandler())
  }

  private fun setLastSyncTime(millisAgo: Long) {
    val field = MediaProgressSyncer::class.java.getDeclaredField("lastSyncTime")
    field.isAccessible = true
    field.set(syncer, System.currentTimeMillis() - millisAgo)
  }

  /** Puts the syncer in the state `start()` would leave it in, without scheduling the Timer. */
  private fun listening(session: PlaybackSession, playerPosition: Double) {
    syncer.currentPlaybackSession = session
    syncer.listeningTimerRunning = true
    setLastSyncTime(5_000L)
    every { pns.getCurrentTimeSeconds() } returns playerPosition
  }

  private fun bookSession(currentTime: Double = 10.0) =
          playbackSession(mutableListOf(audioTrack(duration = 600.0)), currentTime = currentTime)
                  .apply {
                    playMethod = 3 // PLAYMETHOD_LOCAL
                    localLibraryItem = localLibraryItem(id = "local-book")
                  }

  private fun episodeSession(episodeId: String, currentTime: Double = 10.0) =
          playbackSession(
                          mutableListOf(audioTrack(duration = 600.0)),
                          currentTime = currentTime,
                          episodeId = episodeId,
                          localEpisodeId = episodeId
                  )
                  .apply {
                    playMethod = 3
                    localLibraryItem = localLibraryItem(id = "local-pod")
                  }

  private fun awaitPause(): Boolean {
    val latch = CountDownLatch(1)
    syncer.pause { latch.countDown() }
    return latch.await(5, TimeUnit.SECONDS)
  }

  // --- "persists the exact position, once" ----------------------------------------------------

  @Test
  fun `an external pause persists exactly the position the player reports`() {
    listening(bookSession(currentTime = 10.0), playerPosition = 347.5)

    assertTrue("pause's callback was never invoked", awaitPause())

    assertEquals(347.5, db.getAllLocalMediaProgress().single().currentTime, 0.0)
  }

  @Test
  fun `an external pause saves progress exactly once`() {
    listening(bookSession(), playerPosition = 100.0)

    awaitPause()

    // Every save goes out through this emitter (MediaProgressSyncer.kt:344), so counting its
    // invocations counts saves - a double save is what turns a duplicate callback into data loss.
    verify(exactly = 1) { emitter.onLocalMediaProgressUpdate(any()) }
  }

  @Test
  fun `a second pause delivered while already paused writes nothing further`() {
    // The duplicate-delivery shape: the notification and the Bluetooth headset both send pause.
    listening(bookSession(), playerPosition = 100.0)
    awaitPause()

    syncer.pause { } // no session is being listened to any more - must be a no-op

    verify(exactly = 1) { emitter.onLocalMediaProgressUpdate(any()) }
    assertEquals(100.0, db.getAllLocalMediaProgress().single().currentTime, 0.0)
  }

  // --- "syncs the correct item" ---------------------------------------------------------------

  @Test
  fun `pausing a podcast episode writes to that episode's progress record`() {
    listening(episodeSession("ep-2"), playerPosition = 250.0)

    awaitPause()

    val saved = db.getAllLocalMediaProgress().single()
    assertEquals("local-pod-ep-2", saved.id)
    assertEquals("ep-2", saved.episodeId)
    assertEquals(250.0, saved.currentTime, 0.0)
  }

  @Test
  fun `pausing one podcast episode leaves a sibling episode's position alone`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "local-pod-ep-1", "local-pod", "ep-1", 600.0, 0.05, 30.0, false, null, null,
                    1_000L, 0, null, null, null, null, "library-item", "ep-1"
            )
    )
    listening(episodeSession("ep-2"), playerPosition = 250.0)

    awaitPause()

    assertEquals(30.0, db.getLocalMediaProgress("local-pod-ep-1")!!.currentTime, 0.0)
    assertEquals(250.0, db.getLocalMediaProgress("local-pod-ep-2")!!.currentTime, 0.0)
  }

  // --- "a delayed duplicate callback cannot overwrite a later position" -----------------------

  /**
   * #1847/#1828's reported shape: pausing from the notification or a Bluetooth headset occasionally
   * loses the position, rewinding the book to somewhere earlier.
   *
   * Inputs: a save already recorded position `400.0`; a **delayed** sync callback then arrives
   * carrying the older position `20.0` - the state produced when a queued external pause is
   * dispatched after a newer position has already been written (the media session, the player
   * listener and the 15-second timer can all drive a save, and none of them is serialised against
   * the others).
   *
   * Expected: the later position stands. A save that describes an earlier moment cannot be the
   * truth about where the listener is.
   *
   * Observed: `20.0` is written. `MediaProgressSyncer.sync` assigns the incoming time
   * unconditionally through `PlaybackSession.syncData` (`PlaybackSession.kt:420-424`), which sets
   * `currentTime = syncData.currentTime` with no comparison against what is already stored, and
   * `saveLocalProgress` then persists it. This is the same missing comparison as the
   * progress-conflict cluster (`ProgressConflictTest`), reached through the external-control path
   * rather than through a stale session object.
   */
  @Test
  fun `a delayed pause callback must not rewind an already-saved later position`() {
    val session = bookSession()
    listening(session, playerPosition = 400.0)
    awaitPause()
    assertEquals("guard: the later position must be saved first", 400.0, db.getAllLocalMediaProgress().single().currentTime, 0.0)

    // The delayed duplicate arrives, still carrying the position it captured before the save above.
    syncer.listeningTimerRunning = true
    setLastSyncTime(5_000L)
    every { pns.getCurrentTimeSeconds() } returns 20.0
    awaitPause()

    assertEquals(
            "a pause callback delivered late must not move the saved position backwards",
            400.0,
            db.getAllLocalMediaProgress().single().currentTime,
            0.0
    )
  }

  // --- "no active session is a no-op" ---------------------------------------------------------

  @Test
  fun `a pause with no session being listened to writes nothing`() {
    syncer.pause { }

    assertTrue(db.getAllLocalMediaProgress().isEmpty())
    verify(exactly = 0) { emitter.onLocalMediaProgressUpdate(any()) }
  }

  @Test
  fun `a pause reported at position zero still clears the listening state without writing`() {
    // getCurrentTimeSeconds() == 0.0 fails pause's `if (currentTime > 0)` guard
    // (MediaProgressSyncer.kt:156), so the sync is skipped entirely while the listening state is
    // still cleared. Pinned because that branch is the one that runs when a pause arrives before
    // the player has actually started rendering.
    listening(bookSession(), playerPosition = 0.0)

    assertTrue(awaitPause())

    assertTrue(db.getAllLocalMediaProgress().isEmpty())
    assertEquals(false, syncer.listeningTimerRunning)
  }
}
