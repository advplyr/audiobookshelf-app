package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `AbsDatabase.syncServerMediaProgressWithLocalMediaProgress` - the websocket path by which the
 * server pushes progress updates into local storage - viewed as part of the progress-conflict
 * cluster. See `ProgressConflictTest`'s class KDoc for the cluster, its eight open issues, and the
 * shared contract.
 *
 * These live in their own class rather than in `AbsDatabaseTest` for a concrete reason worth
 * recording: adding them to that class deterministically breaks its two
 * `setCurrentServerConnectionConfig` "new config" tests, which are the only tests there that reach
 * `DeviceManager.getBase64Id` and so the only ones that depend on
 * `AbsTestEnvironment.mockLocalFileStatics()`'s `mockkStatic(Base64::class)` being live. Probing
 * `Base64.encodeToString` from that class's `@Before` shows the stub returning the mocked value in
 * all 40 of its tests as it stands, and `null` in all 43 once these three are added - the static
 * mock goes inert for the *whole class*, before any test body runs. It is not an ordering effect
 * (pinning `@FixMethodOrder(NAME_ASCENDING)` does not help) and not a test-count effect (adding a
 * trivial extra `@Test`, or these same three cases under different method names, keeps it working).
 *
 * The practical consequence is a latent trap in `AbsDatabaseTest`, not in these tests: any future
 * addition there can silently make those two credential tests fail for a reason unrelated to the
 * change. Keeping this cluster separate avoids paying that cost now; the trap itself is reported in
 * `TESTING.md` §6.1 as its own finding, with the full bisection.
 */
class AbsDatabaseProgressConflictTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager
  private lateinit var plugin: AbsDatabase

  @Before
  fun setUp() {
    db = DbManager()
    plugin = AbsDatabase()
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  /** A relaxed `PluginCall` whose two-arg `getString` returns the given params, else its default. */
  private fun pluginCall(vararg params: Pair<String, String>): PluginCall {
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString(any(), any()) } answers { secondArg() }
    params.forEach { (key, value) -> every { call.getString(key, "") } returns value }
    return call
  }

  /** Stubs `resolve`/`resolve(JSObject)` to release a latch, runs [action], and awaits it. */
  private fun awaitResolve(call: PluginCall, action: () -> Unit): JSObject? {
    val latch = CountDownLatch(1)
    val captured = slot<JSObject>()
    every { call.resolve(capture(captured)) } answers { latch.countDown() }
    every { call.resolve() } answers { latch.countDown() }
    action()
    assertTrue("plugin call never resolved", latch.await(5, TimeUnit.SECONDS))
    return if (captured.isCaptured) captured.captured else null
  }

  /** The pushed server record: `currentTime=80.0`, `lastUpdate=5000`. */
  private fun mediaProgressCall(
          localLibraryItemId: String,
          localMediaProgressId: String? = null,
          localEpisodeId: String = ""
  ): PluginCall {
    val call =
            pluginCall(
                    "localLibraryItemId" to localLibraryItemId,
                    "localEpisodeId" to localEpisodeId
            )
    every { call.getObject("mediaProgress") } returns
            JSObject(
                    """{"id":"sp1","libraryItemId":"item-1","episodeId":null,"duration":100.0,
                       "progress":0.8,"currentTime":80.0,"isFinished":false,"ebookLocation":null,
                       "ebookProgress":null,"lastUpdate":5000,"startedAt":0,"finishedAt":null}"""
            )
    every { call.getString("localMediaProgressId") } returns localMediaProgressId
    return call
  }

  private fun storedProgress(id: String, currentTime: Double, lastUpdate: Long, episodeId: String? = null) =
          LocalMediaProgress(
                  id, "local-1", episodeId, 100.0, currentTime / 100.0, currentTime, false, null,
                  null, lastUpdate, 0, null, null, null, null, "item-1", episodeId
          )

  /**
   * The reachable half of the cluster's server direction
   * ([#1945](https://github.com/advplyr/audiobookshelf-app/issues/1945) - an old browser session
   * clobbering the phone).
   *
   * `LocalMediaProgress.updateFromServerMediaProgress` performs no age check. Its two production
   * callers disagree about whether that matters: `ApiHandler.syncLocalMediaProgressForUser`
   * compares `mediaProgress.lastUpdate > localMediaProgress.lastUpdate` before calling it
   * (`ApiHandler.kt:825`); this websocket path calls it unguarded (`AbsDatabase.kt:368`). So this
   * is the caller that actually exposes the missing guard.
   *
   * Inputs: local record `currentTime=90.0, lastUpdate=9_000_000`; pushed server record
   * `currentTime=80.0, lastUpdate=5_000`.
   *
   * Expected: the newer local position survives; a stale push is ignored.
   *
   * Observed: the record is overwritten to `80.0`, and its `lastUpdate` dragged back to `5_000`.
   */
  @Test
  fun `a websocket progress push older than the local record must not overwrite it`() {
    db.saveLocalMediaProgress(storedProgress("p1", currentTime = 90.0, lastUpdate = 9_000_000L))
    val call = mediaProgressCall(localLibraryItemId = "local-1", localMediaProgressId = "p1")

    awaitResolve(call) { plugin.syncServerMediaProgressWithLocalMediaProgress(call) }

    assertEquals(
            "a server push from t=5000 must not replace a local position recorded at t=9000000",
            90.0,
            db.getLocalMediaProgress("p1")!!.currentTime,
            0.0
    )
  }

  /**
   * The other side of the same guard: a genuinely newer server push must still be applied, so a fix
   * for the spec above cannot simply refuse every websocket update.
   */
  @Test
  fun `a websocket progress push newer than the local record is applied`() {
    db.saveLocalMediaProgress(storedProgress("p1", currentTime = 10.0, lastUpdate = 1_000L))
    val call = mediaProgressCall(localLibraryItemId = "local-1", localMediaProgressId = "p1")

    awaitResolve(call) { plugin.syncServerMediaProgressWithLocalMediaProgress(call) }

    assertEquals(80.0, db.getLocalMediaProgress("p1")!!.currentTime, 0.0)
  }

  /**
   * The cluster's "different episode IDs must remain independent" case. Two episodes of one podcast
   * share a `localLibraryItemId`, so a push addressed to episode 2 must create/update only episode
   * 2's record and leave episode 1's position alone.
   */
  @Test
  fun `a websocket push for one podcast episode leaves the other episode untouched`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))
    db.saveLocalMediaProgress(
            storedProgress("local-1-ep-1", currentTime = 30.0, lastUpdate = 1_000L, episodeId = "ep-1")
    )
    // No localMediaProgressId -> the create-new branch, which derives the id from the episode.
    val call = mediaProgressCall(localLibraryItemId = "local-1", localEpisodeId = "ep-2")

    awaitResolve(call) { plugin.syncServerMediaProgressWithLocalMediaProgress(call) }

    assertEquals(80.0, db.getLocalMediaProgress("local-1-ep-2")!!.currentTime, 0.0)
    assertEquals(30.0, db.getLocalMediaProgress("local-1-ep-1")!!.currentTime, 0.0)
  }
}
