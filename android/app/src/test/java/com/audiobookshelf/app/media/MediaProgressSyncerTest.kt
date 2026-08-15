package com.audiobookshelf.app.media

import android.content.Context
import android.net.ConnectivityManager
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.data.playbackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MediaProgressSyncerTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var pns: PlayerNotificationService
  private lateinit var syncer: MediaProgressSyncer
  private lateinit var db: DbManager

  @Before
  fun setUp() {
    db = DbManager()
    pns = mockk(relaxed = true)
    // Without this, Context.getSystemService(CONNECTIVITY_SERVICE) on a relaxed mock returns a
    // generic proxy that isn't a ConnectivityManager, and DeviceManager.checkConnectivity's
    // unchecked cast throws ClassCastException instead of just reporting "not connected".
    every { pns.getSystemService(Context.CONNECTIVITY_SERVICE) } returns
            mockk<ConnectivityManager>(relaxed = true)
    syncer = MediaProgressSyncer(pns, AbsTestEnvironment.apiHandler())
  }

  /** Sets the private `lastSyncTime` field without going through `start()`'s real Timer. */
  private fun setLastSyncTime(millisAgo: Long) {
    val field = MediaProgressSyncer::class.java.getDeclaredField("lastSyncTime")
    field.isAccessible = true
    field.set(syncer, System.currentTimeMillis() - millisAgo)
  }

  private fun syncOnce(shouldSyncServer: Boolean = false, currentTime: Double = 50.0): SyncResult? {
    val latch = CountDownLatch(1)
    var result: SyncResult? = null
    syncer.sync(shouldSyncServer, currentTime) {
      result = it
      latch.countDown()
    }
    assertTrue("sync callback was never invoked", latch.await(5, TimeUnit.SECONDS))
    return result
  }

  @Test
  fun `sync reports no result when it has never been started`() {
    assertNull(syncOnce())
  }

  @Test
  fun `sync reports no result within one second of the last sync`() {
    setLastSyncTime(500L)
    syncer.currentPlaybackSession = playbackSession(mutableListOf(audioTrack(duration = 100.0)))

    assertNull(syncOnce())
  }

  @Test
  fun `sync guards against a NaN progress session instead of persisting it`() {
    setLastSyncTime(5_000L)
    // Zero-duration session with a zero sync time -> progress = 0.0 / 0.0 = NaN. (A non-zero
    // currentTime against a zero duration is +Infinity, not NaN, and does not trip this guard.)
    syncer.currentPlaybackSession = playbackSession(mutableListOf())

    assertNull(syncOnce(currentTime = 0.0))
    assertTrue(db.getAllLocalMediaProgress().isEmpty())
  }

  @Test
  fun `offline local session sync saves progress locally without contacting the server`() {
    setLastSyncTime(5_000L)
    val session =
            playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = 10.0).apply {
              // isLocal is derived from playMethod; PLAYMETHOD_LOCAL = 3.
              playMethod = 3
            }
    syncer.currentPlaybackSession = session

    val result = syncOnce(shouldSyncServer = true, currentTime = 20.0)

    assertEquals(false, result?.serverSyncAttempted)
    val saved = db.getAllLocalMediaProgress()
    assertEquals(1, saved.size)
    assertEquals(20.0, saved.first().currentTime, 0.0)
  }

  @Test
  fun `local session syncs to the server when connected to the same server the item belongs to`() {
    setLastSyncTime(5_000L)
    DeviceManager.serverConnectionConfig =
            // 127.0.0.1:1 refuses immediately and deterministically. An earlier version used the
            // bare hostname "https://x" and depended on DNS *failing* to resolve it, which is not
            // true on a network with a wildcard resolver or a captive portal.
            ServerConnectionConfig(
                    "srv-1", 0, "n", "http://127.0.0.1:1", "2.17.0", "u", "un", "t", null)
    val session =
            playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = 10.0).apply {
              playMethod = 3
              serverConnectionConfigId = "srv-1"
            }
    syncer.currentPlaybackSession = session
    every { pns.getSystemService(Context.CONNECTIVITY_SERVICE) } returns
            mockk<ConnectivityManager>(relaxed = true).also {
              every { it.getNetworkCapabilities(any()) } returns
                      mockk(relaxed = true) {
                        // A working network is a transport *plus* the capabilities that say it
                        // actually reaches the internet - stubbing the transport alone describes a
                        // captive portal, which checkConnectivity correctly reports as offline and
                        // which would make this test assert against the wrong branch.
                        every { hasTransport(any()) } returns true
                        every { hasCapability(any()) } returns true
                      }
            }

    // No server is actually listening; sendLocalProgressSync will fail to connect, which is
    // still a valid, deterministic outcome to assert on (serverSyncAttempted = true, success = false).
    val result = syncOnce(shouldSyncServer = true, currentTime = 20.0)

    assertEquals(true, result?.serverSyncAttempted)
    assertEquals(false, result?.serverSyncSuccess)
  }

  @Test
  fun `reset clears the tracked playback session and local progress`() {
    syncer.currentPlaybackSession = playbackSession()
    syncer.currentLocalMediaProgress = null

    syncer.reset()

    assertNull(syncer.currentPlaybackSession)
    assertEquals("", syncer.currentSessionId)
  }

  // --- Lifecycle: stop / pause / finished / seek ----------------------------------------------
  //
  // None of these call `start()`. `start()` schedules on `Timer("ListeningTimer", false)` - a
  // *non-daemon* thread on a 15-second repeat that neither `reset()` nor `pause()` reaps (they
  // cancel the `TimerTask`, not the `Timer`), so every test that called it would leak a thread
  // that can keep the Gradle test JVM from exiting. It also buys no coverage: the timer body runs
  // inside `Handler(Looper.getMainLooper()).post {}`, and `Looper.getMainLooper()` is null on the
  // host JVM, so the body never executes. `listeningTimerRunning` is a public `var`, so the
  // "currently listening" precondition these methods branch on can be set directly instead.

  /** Puts the syncer in the state `start()` would leave it in, without scheduling the Timer. */
  private fun listening(session: com.audiobookshelf.app.data.PlaybackSession, currentTime: Double) {
    syncer.currentPlaybackSession = session
    syncer.listeningTimerRunning = true
    setLastSyncTime(5_000L)
    every { pns.getCurrentTimeSeconds() } returns currentTime
  }

  private fun localSession(currentTime: Double = 10.0) =
          playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = currentTime)
                  .apply { playMethod = 3 } // PLAYMETHOD_LOCAL

  private fun awaitCallback(action: (() -> Unit) -> Unit): Boolean {
    val latch = CountDownLatch(1)
    action { latch.countDown() }
    return latch.await(5, TimeUnit.SECONDS)
  }

  @Test
  fun `stop persists the final position, clears the session and fires its callback`() {
    listening(localSession(), currentTime = 42.0)

    assertTrue("stop's callback was never invoked", awaitCallback { cb -> syncer.stop(cb = cb) })

    assertEquals(42.0, db.getAllLocalMediaProgress().single().currentTime, 0.0)
    assertNull("stop must clear the tracked session", syncer.currentPlaybackSession)
    assertFalse(syncer.listeningTimerRunning)
  }

  @Test
  fun `stop when never started still fires its callback exactly once`() {
    // The no-active-session no-op from the issue doc's pause/external-control row: a stop
    // delivered after playback already ended must not hang the caller awaiting the callback.
    val calls = java.util.concurrent.atomic.AtomicInteger(0)

    assertTrue(awaitCallback { cb -> syncer.stop { calls.incrementAndGet(); cb() } })

    Thread.sleep(200) // give a second invocation a chance to arrive
    assertEquals(1, calls.get())
    assertTrue("nothing was playing, so nothing may be written", db.getAllLocalMediaProgress().isEmpty())
  }

  @Test
  fun `stop with shouldSync false does not write a position`() {
    listening(localSession(), currentTime = 42.0)

    assertTrue(awaitCallback { cb -> syncer.stop(shouldSync = false, cb = cb) })

    assertTrue(db.getAllLocalMediaProgress().isEmpty())
    assertNull(syncer.currentPlaybackSession)
  }

  @Test
  fun `pause persists the position and stops the listening timer but keeps the session`() {
    listening(localSession(), currentTime = 33.0)

    assertTrue("pause's callback was never invoked", awaitCallback { cb -> syncer.pause(cb) })

    assertEquals(33.0, db.getAllLocalMediaProgress().single().currentTime, 0.0)
    assertFalse(syncer.listeningTimerRunning)
    // Unlike stop, pause does not reset - the session stays open so playback can resume from it.
    assertNotNull(syncer.currentPlaybackSession)
  }

  /**
   * Characterization, not an invariant: `pause` and `finished` both `return` without invoking the
   * callback when the timer is not running (`MediaProgressSyncer.kt:147` and `:182`), unlike
   * `stop`, which calls it. Any caller that awaits these callbacks rather than treating them as
   * fire-and-forget would wait forever after a duplicate pause. Recorded so a change to either
   * branch is visible.
   */
  @Test
  fun `pause and finished are silent no-ops when nothing is being listened to`() {
    var pauseCalled = false
    var finishedCalled = false

    syncer.pause { pauseCalled = true }
    syncer.finished { finishedCalled = true }

    assertFalse(pauseCalled)
    assertFalse(finishedCalled)
    assertTrue(db.getAllLocalMediaProgress().isEmpty())
  }

  @Test
  fun `finished writes the full duration to local progress`() {
    listening(localSession(), currentTime = 90.0)

    assertTrue("finished's callback was never invoked", awaitCallback { cb -> syncer.finished(cb) })

    val saved = db.getAllLocalMediaProgress().single()
    assertEquals(100.0, saved.currentTime, 0.0)
    assertEquals(1.0, saved.progress, 0.0)
    assertNull("finished resets like stop does", syncer.currentPlaybackSession)
  }

  /**
   * **Newly found this pass**, while covering `finished()` - not previously reported, and the
   * "finished/not-finished conflict" case the progress-conflict cluster names.
   *
   * Inputs: a local session for a 100 s book with **no `LocalMediaProgress` record yet** (a
   * freshly downloaded item played through to the end for the first time), completed via
   * `finished()`, which syncs at the session's full duration.
   *
   * Expected: the persisted record is marked finished. `progress` is written as `1.0`, and every
   * other write path treats `>= 0.99` as finished (`LocalMediaProgress.updateFromPlaybackSession`,
   * `LocalMediaProgress.kt:63`).
   *
   * Observed: `progress = 1.0` but `isFinished = false`, so the book is never marked read.
   *
   * Path: `MediaProgressSyncer.saveLocalProgress` (`MediaProgressSyncer.kt:326-334`) branches on
   * whether a record already exists. The *existing-record* branch calls
   * `updateFromPlaybackSession`, which derives `isFinished` from progress. The *new-record* branch
   * calls `PlaybackSession.getNewLocalMediaProgress()`, which passes the `isFinished` constructor
   * argument as a hard-coded `false` (`PlaybackSession.kt:433`) no matter what `progress` is. The
   * two branches disagree, and only the second one is wrong.
   *
   * This is a first-listen-only bug, which is consistent with it going unreported: replaying the
   * same book takes the existing-record branch and marks it finished correctly.
   */
  @Test
  fun `finishing a book with no prior local record still marks it finished`() {
    listening(localSession(), currentTime = 90.0)

    awaitCallback { cb -> syncer.finished(cb) }

    val saved = db.getAllLocalMediaProgress().single()
    assertEquals("guard: progress did reach 1.0", 1.0, saved.progress, 0.0)
    assertTrue(
            "a first-listen record completed at 100% persisted with isFinished=false",
            saved.isFinished
    )
  }

  /** The contrasting branch: with a record already on disk, the finished flag is set correctly. */
  @Test
  fun `finishing a book that already has a local record marks it finished`() {
    val session = localSession()
    db.saveLocalMediaProgress(session.getNewLocalMediaProgress())
    listening(session, currentTime = 90.0)

    awaitCallback { cb -> syncer.finished(cb) }

    assertTrue(db.getAllLocalMediaProgress().single().isFinished)
  }

  @Test
  fun `seek records the player's current position onto the tracked session`() {
    syncer.currentPlaybackSession = localSession(currentTime = 10.0)
    every { pns.getCurrentTimeSeconds() } returns 61.5

    syncer.seek()

    assertEquals(61.5, syncer.currentPlaybackSession!!.currentTime, 0.0)
  }

  @Test
  fun `seek with no session set is guarded instead of throwing`() {
    every { pns.getCurrentTimeSeconds() } returns 61.5

    syncer.seek() // must not NPE on the `currentPlaybackSession!!` that follows the null check

    assertNull(syncer.currentPlaybackSession)
  }

  // --- syncFromServerProgress: the remote-overwrite path -------------------------------------

  /**
   * Part of the progress-conflict cluster (see `ProgressConflictTest` and `TESTING.md` §7.2), landed as a
   * **characterization** test rather than an enabled failure after checking the caller.
   *
   * `syncFromServerProgress` (`MediaProgressSyncer.kt:213`) assigns `currentTime` and `updatedAt`
   * from the server record with no comparison against the open session, then persists - taken
   * alone that is #1945's exact shape (an old browser session rewinding the phone). But it has
   * exactly one caller, `PlayerNotificationService.checkCurrentSessionProgress`
   * (`PlayerNotificationService.kt:882`), and that caller *does* guard, on
   * `mediaProgress.lastUpdate > playbackSession.updatedAt` where `playbackSession` is this same
   * `mediaProgressSyncer.currentPlaybackSession` object (`:845`, `:874`). An older server record
   * therefore cannot reach this method in production today, so asserting the invariant here would
   * be a failing spec against an unreachable path - the thing
   * `TESTING.md` §1 rule 7 warns against.
   *
   * Two facts worth recording while here: the `// Currently unused` comment above the method
   * (`:212`) is **stale** - it is called - and the guard lives in the caller rather than the
   * method, so any future second caller inherits no protection at all.
   */
  @Test
  fun `syncFromServerProgress applies the server position to the open session unconditionally`() {
    syncer.currentPlaybackSession = localSession(currentTime = 80.0)
    val olderServerRecord =
            MediaProgress(
                    id = "sp1", libraryItemId = "library-item", episodeId = null, duration = 100.0,
                    progress = 0.02, currentTime = 2.0, isFinished = false, ebookLocation = null,
                    ebookProgress = null, lastUpdate = 1L, startedAt = 0L, finishedAt = null
            )

    syncer.syncFromServerProgress(olderServerRecord)

    assertEquals(2.0, syncer.currentPlaybackSession!!.currentTime, 0.0)
    assertEquals(1L, syncer.currentPlaybackSession!!.updatedAt)
    assertEquals(2.0, db.getAllLocalMediaProgress().single().currentTime, 0.0)
  }

  @Test
  fun `syncFromServerProgress with no open session writes nothing`() {
    syncer.syncFromServerProgress(
            MediaProgress(
                    id = "sp1", libraryItemId = "library-item", episodeId = null, duration = 100.0,
                    progress = 0.02, currentTime = 2.0, isFinished = false, ebookLocation = null,
                    ebookProgress = null, lastUpdate = 1L, startedAt = 0L, finishedAt = null
            )
    )

    assertTrue(db.getAllLocalMediaProgress().isEmpty())
  }
}
