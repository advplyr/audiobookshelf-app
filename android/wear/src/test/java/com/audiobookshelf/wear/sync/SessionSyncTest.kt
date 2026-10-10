package com.audiobookshelf.wear.sync

import com.audiobookshelf.wear.ApiHarness
import com.audiobookshelf.wear.data.ProgressStore
import com.audiobookshelf.wear.jsonResponse
import com.audiobookshelf.wear.testContext
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SessionSyncTest {
  private val server = MockWebServer()
  private lateinit var progress: ProgressStore
  private lateinit var sync: SessionSync

  @Before
  fun setUp() {
    server.start()
    val harness = ApiHarness(server)
    harness.signIn()
    progress = ProgressStore(testContext)
    sync = SessionSync(testContext, harness.api, harness.auth, progress)
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun session(updatedAt: Long, episodeId: String? = null) = JSONObject()
    .put("id", "session1")
    .put("libraryItemId", "li_1")
    .put("episodeId", episodeId ?: JSONObject.NULL)
    .put("mediaType", if (episodeId == null) "book" else "podcast")
    .put("updatedAt", updatedAt)

  @Test
  fun `sends a session`() = runBlocking {
    server.enqueue(jsonResponse("{}"))

    sync.sync(session(100, episodeId = "ep_1"))

    val request = server.takeRequest()
    assertEquals("/api/session/local", request.path)
    val body = JSONObject(request.body.readUtf8())
    assertEquals("ep_1", body.getString("episodeId"))
    assertEquals("podcast", body.getString("mediaType"))
    assertTrue(progress.pendingSessions().isEmpty())
  }

  @Test
  fun `an older report after a newer one is not sent`() = runBlocking {
    server.enqueue(jsonResponse("{}"))
    sync.sync(session(200))
    server.takeRequest()

    sync.sync(session(100))

    assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
  }

  @Test
  fun `failed sends are queued and sent later`() = runBlocking {
    server.enqueue(jsonResponse("Server error", code = 500))
    sync.sync(session(100))
    server.takeRequest()
    assertEquals(1, progress.pendingSessions().size)

    server.enqueue(jsonResponse("{}"))
    sync.flushPending()

    val request = server.takeRequest()
    assertEquals("/api/session/local-all", request.path)
    val sessions = JSONObject(request.body.readUtf8()).getJSONArray("sessions")
    assertEquals("session1", sessions.getJSONObject(0).getString("id"))
    assertTrue(progress.pendingSessions().isEmpty())
  }
}
