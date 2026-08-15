package com.audiobookshelf.app.media

import android.content.Context
import android.net.ConnectivityManager
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.localLibraryItem
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Offline and failed-sync recovery for a **server-hosted** item, as opposed to a downloaded one.
 *
 * The question these answer: when a progress sync cannot reach the server, does the listening
 * position survive, and in what form? The two media types take different branches, and the
 * difference is easy to misread as data loss, so it is pinned here rather than left to inference.
 *
 * **What the tests establish.** Recovery for a server-hosted item is *queue-based*, not
 * *progress-based*: the `PlaybackSession` is written to Paper on every sync attempt
 * (`MediaProgressSyncer.kt:253`) and flushed later by
 * `AbsDatabase.syncLocalSessionsWithServer`. The position is therefore not lost, which is the
 * important part - but it lives only in the session queue, so nothing reads it back as progress
 * until a successful reconnect. A downloaded (local) item takes a different branch and writes a
 * `LocalMediaProgress` immediately, so the two media types genuinely behave differently offline.
 *
 * That asymmetry is characterized here rather than asserted as a defect: whether an unsynced
 * server-item position should be visible in the UI is a product decision, not a correctness bug.
 * What *is* asserted as an invariant is that neither failure path may drop the queued session -
 * that would be real data loss, and it is what any change here must not break.
 */
class ServerProgressFallbackTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var pns: PlayerNotificationService
  private lateinit var syncer: MediaProgressSyncer
  private lateinit var db: DbManager

  @Before
  fun setUp() {
    db = DbManager()
    pns = mockk(relaxed = true)
    every { pns.getSystemService(Context.CONNECTIVITY_SERVICE) } returns
            mockk<ConnectivityManager>(relaxed = true)
    syncer = MediaProgressSyncer(pns, AbsTestEnvironment.apiHandler())
  }

  private fun setLastSyncTime(millisAgo: Long) {
    val field = MediaProgressSyncer::class.java.getDeclaredField("lastSyncTime")
    field.isAccessible = true
    field.set(syncer, System.currentTimeMillis() - millisAgo)
  }

  /** A session for an item hosted on the server - `playMethod` is not `PLAYMETHOD_LOCAL`. */
  private fun serverSession(currentTime: Double = 10.0) =
          playbackSession(mutableListOf(audioTrack(duration = 600.0)), currentTime = currentTime)
                  .apply { playMethod = 0 } // PLAYMETHOD_DIRECTPLAY

  /** A session for a downloaded item, for contrast. */
  private fun localSession(currentTime: Double = 10.0) =
          playbackSession(mutableListOf(audioTrack(duration = 600.0)), currentTime = currentTime)
                  .apply {
                    playMethod = 3 // PLAYMETHOD_LOCAL
                    localLibraryItem = localLibraryItem(id = "local-1")
                  }

  private fun goOnline() {
    every { pns.getSystemService(Context.CONNECTIVITY_SERVICE) } returns
            mockk<ConnectivityManager>(relaxed = true).also {
              every { it.getNetworkCapabilities(any()) } returns
                      mockk(relaxed = true) {
                        // A working network is a transport *plus* the capabilities that say it
                        // actually reaches the internet - stubbing the transport alone describes a
                        // captive portal, which checkConnectivity correctly reports as offline, so
                        // "online" here would silently have meant "offline".
                        every { hasTransport(any()) } returns true
                        every { hasCapability(any()) } returns true
                      }
            }
  }

  private fun syncOnce(session: PlaybackSession, currentTime: Double): SyncResult? {
    syncer.currentPlaybackSession = session
    setLastSyncTime(5_000L)
    val latch = CountDownLatch(1)
    var result: SyncResult? = null
    syncer.sync(shouldSyncServer = true, currentTime = currentTime) {
      result = it
      latch.countDown()
    }
    assertTrue("sync callback was never invoked", latch.await(5, TimeUnit.SECONDS))
    return result
  }

  // --- Offline ---------------------------------------------------------------------------------

  @Test
  fun `an offline server-item sync queues the playback session carrying the current position`() {
    val result = syncOnce(serverSession(), currentTime = 250.0)

    assertEquals("no server sync may be attempted while offline", false, result?.serverSyncAttempted)
    val queued = db.getPlaybackSessions()
    assertEquals(1, queued.size)
    assertEquals(250.0, queued.single().currentTime, 0.0)
  }

  @Test
  fun `an offline server-item sync writes no local media progress`() {
    // Characterization, not a defect spec: the position is recoverable from the queued session
    // above, but nothing surfaces it as progress until a reconnect.
    syncOnce(serverSession(), currentTime = 250.0)

    assertTrue(db.getAllLocalMediaProgress().isEmpty())
  }

  @Test
  fun `an offline downloaded-item sync does write local media progress`() {
    // The contrasting branch, which is what makes the behaviour above an asymmetry rather than a
    // uniform policy.
    syncOnce(localSession(), currentTime = 250.0)

    assertEquals(250.0, db.getAllLocalMediaProgress().single().currentTime, 0.0)
  }

  @Test
  fun `repeated offline syncs update the queued session in place rather than accumulating copies`() {
    val session = serverSession()
    syncOnce(session, currentTime = 100.0)
    syncOnce(session, currentTime = 200.0)
    syncOnce(session, currentTime = 300.0)

    val queued = db.getPlaybackSessions()
    assertEquals("the queue is keyed by session id, so it must not grow", 1, queued.size)
    assertEquals(300.0, queued.single().currentTime, 0.0)
  }

  // --- Server sync attempted and failed --------------------------------------------------------

  /**
   * The invariant any change here must preserve. With a network present but the server unreachable
   * (no listener on the configured address), `sendProgressSync` fails and
   * `MediaProgressSyncer.sync` leaves the session in the queue - it is only removed inside the
   * success branch (`MediaProgressSyncer.kt:308`).
   */
  @Test
  fun `a failed server sync keeps the queued session instead of discarding the position`() {
    goOnline()
    DeviceManager.serverConnectionConfig =
            ServerConnectionConfig("srv-1", 0, "n", "http://127.0.0.1:1", "2.17.0", "u", "un", "t", null)

    val result = syncOnce(serverSession(), currentTime = 420.0)

    assertEquals(true, result?.serverSyncAttempted)
    assertEquals(false, result?.serverSyncSuccess)
    assertFalse("a failed sync must not drop the queued position", db.getPlaybackSessions().isEmpty())
    assertEquals(420.0, db.getPlaybackSessions().single().currentTime, 0.0)
  }

  @Test
  fun `a failed server sync also writes no local media progress fallback`() {
    goOnline()
    DeviceManager.serverConnectionConfig =
            ServerConnectionConfig("srv-1", 0, "n", "http://127.0.0.1:1", "2.17.0", "u", "un", "t", null)

    syncOnce(serverSession(), currentTime = 420.0)

    // The second half of the asymmetry: no fallback record is written on this path either.
    assertTrue(db.getAllLocalMediaProgress().isEmpty())
  }
}
