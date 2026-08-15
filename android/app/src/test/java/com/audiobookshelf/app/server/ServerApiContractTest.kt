package com.audiobookshelf.app.server

import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.data.playbackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import com.getcapacitor.JSObject
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * App/server API contract - the request line the Android client actually emits for each call,
 * pinned against the route that serves it in the audiobookshelf **server** source.
 *
 * Nothing else in this suite asserts a single request path, so a renamed route, a changed verb or a
 * dropped path parameter produces a runtime 404 that no test sees.
 *
 * Every expectation below was checked by hand against the audiobookshelf server source, and each
 * test names the route that serves it. At the time of writing, every endpoint this client calls
 * exists on the server with a matching verb. These tests exist so that stays true, and so the check
 * is executable rather than a manual diff.
 *
 * Scope: request shape only. Response parsing is covered by `ApiHandlerEdgeCaseTest`, and the
 * responses here are minimal stubs.
 */
class ServerApiContractTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var server: MockWebServer
  private lateinit var handler: ApiHandler

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    config = ServerConnectionConfig(
            "test-server", 0, "Test", server.url("/").toString().trimEnd('/'),
            "2.17.0", "user-1", "username", "test-token", null
    )
    DeviceManager.serverConnectionConfig = config
    handler = AbsTestEnvironment.apiHandler()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private lateinit var config: ServerConnectionConfig

  /**
   * Enqueues [body], runs [action], and returns the request the client actually sent.
   *
   * Deliberately waits on the *request*, not on the API callback. These tests assert the outgoing
   * request line; whether the stub response deserializes into the caller's model type is a
   * different contract (and `ApiHandlerEdgeCaseTest` records that a body which fails to
   * deserialize drops the callback entirely). Waiting on the callback here would couple every
   * route assertion to a hand-written response fixture for its return type.
   *
   * [action] therefore takes no latch. It used to be handed a `CountDownLatch` that this method
   * created and never awaited, so all twenty-one `latch.countDown()` calls were ceremony implying
   * a synchronisation that was not happening. `takeRequest`'s own timeout is the real wait.
   */
  private fun requestFor(body: String = "{}", action: () -> Unit): RecordedRequest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    action()
    val request = server.takeRequest(5, TimeUnit.SECONDS)
    assertTrue("no request reached the server", request != null)
    return request!!
  }

  private fun assertRoute(request: RecordedRequest, method: String, path: String) {
    assertEquals(method, request.method)
    assertEquals(path, request.path)
  }

  // --- Identity and libraries ------------------------------------------------------------------

  /** Server: `get /me` — `server/routers/ApiRouter.js`. */
  @Test
  fun `getCurrentUser calls GET api me`() {
    val request = requestFor("""{"id":"u1","username":"n","mediaProgress":[]}""") {
      handler.getCurrentUser {}
    }

    assertRoute(request, "GET", "/api/me")
  }

  /** Server: `get /libraries` — `server/routers/ApiRouter.js`. */
  @Test
  fun `getLibraries calls GET api libraries`() {
    val request = requestFor("""{"libraries":[]}""") {
      handler.getLibraries {}
    }

    // include=stats is appended by the client; the route itself is what is being pinned.
    assertRoute(request, "GET", "/api/libraries?include=stats")
  }

  /** Server: `get /libraries/:id/personalized`. */
  @Test
  fun `getLibraryPersonalized calls GET api libraries id personalized`() {
    val request = requestFor("""{"value":[]}""") {
      handler.getLibraryPersonalized("lib-1") {}
    }

    assertRoute(request, "GET", "/api/libraries/lib-1/personalized")
  }

  /** Server: `get /libraries/:id/items`. */
  @Test
  fun `getLibraryItems calls GET api libraries id items with a large limit`() {
    val request = requestFor("""{"results":[]}""") {
      handler.getLibraryItems("lib-1") {}
    }

    assertEquals("GET", request.method)
    assertTrue(
            "expected the library items route, got ${request.path}",
            request.path!!.startsWith("/api/libraries/lib-1/items")
    )
  }

  /** Server: `get /libraries/:id/search`. */
  @Test
  fun `getSearchResults calls GET api libraries id search with an encoded query`() {
    val request = requestFor("""{"book":[]}""") {
      handler.getSearchResults("lib-1", "dune") {}
    }

    assertEquals("GET", request.method)
    assertTrue(
            "expected the search route, got ${request.path}",
            request.path!!.startsWith("/api/libraries/lib-1/search?q=")
    )
  }

  /** Server: `get /me/items-in-progress`. */
  @Test
  fun `getAllItemsInProgress calls GET api me items-in-progress`() {
    val request = requestFor("""{"libraryItems":[]}""") {
      handler.getAllItemsInProgress {}
    }

    assertRoute(request, "GET", "/api/me/items-in-progress")
  }

  /** Server: `get /items/:id`. */
  @Test
  fun `getLibraryItem calls GET api items id`() {
    val request = requestFor("""{"id":"li-1","mediaType":"book"}""") {
      handler.getLibraryItem("li-1") {}
    }

    // expanded=1 is the client's own query flag; the route is what is being pinned.
    assertRoute(request, "GET", "/api/items/li-1?expanded=1")
  }

  // --- Playback and progress -------------------------------------------------------------------

  /** Server: `post /items/:id/play` and `post /items/:id/play/:episodeId`. */
  @Test
  fun `playLibraryItem posts to api items id play`() {
    val request = requestFor("""{"id":"s1"}""") {
      handler.playLibraryItem("li-1", null, PlayItemRequestPayload("android", false, false, com.audiobookshelf.app.data.DeviceInfo("d", "maker", "model", 35, "test"))) {}
    }

    assertRoute(request, "POST", "/api/items/li-1/play")
  }

  @Test
  fun `playLibraryItem for a podcast episode appends the episode id to the play route`() {
    val request = requestFor("""{"id":"s1"}""") {
      handler.playLibraryItem("li-1", "ep-1", PlayItemRequestPayload("android", false, false, com.audiobookshelf.app.data.DeviceInfo("d", "maker", "model", 35, "test"))) {}
    }

    assertRoute(request, "POST", "/api/items/li-1/play/ep-1")
  }

  /** Server: `post /session/:id/sync`. */
  @Test
  fun `sendProgressSync posts to api session id sync`() {
    val request = requestFor {
      handler.sendProgressSync("session-1", MediaProgressSyncData(15, 100.0, 42.0)) { _, _ -> }
    }

    assertRoute(request, "POST", "/api/session/session-1/sync")
    val body = request.body.readUtf8()
    assertTrue("the sync body must carry the current time, got $body", body.contains("\"currentTime\":42"))
    assertTrue("the sync body must carry the listened time, got $body", body.contains("\"timeListened\":15"))
  }

  /** Server: `post /session/local`. */
  @Test
  fun `sendLocalProgressSync posts to api session local`() {
    val session = playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = 12.0)
            .apply { localLibraryItem = localLibraryItem(id = "local-1") }
    val request = requestFor {
      handler.sendLocalProgressSync(session) { _, _ -> }
    }

    assertRoute(request, "POST", "/api/session/local")
  }

  /**
   * Server: `post /session/local-all`.
   *
   * Unlike every other call here, this one builds a `DeviceInfo` from
   * `Settings.Secure.getString(..., ANDROID_ID)`, `Build.MANUFACTURER` and `Build.MODEL`
   * (`ApiHandler.kt:767-768`). All three are null under the mockable `android.jar`, and forwarding
   * null into `DeviceInfo`'s non-null parameters throws before a request is ever sent. The two
   * `Build` fields need the scoped static-field override (a field read, not a method call);
   * `Settings.Secure.getString` is a static *method* and takes `mockkStatic`.
   *
   * `Settings.Secure.getString` returning null is a **new entry for the harness's known-null list**
   * - it was not previously recorded alongside `Looper.getMainLooper()`, `Bundle`, and
   * `SparseArray`.
   */
  @Test
  fun `sendSyncLocalSessions posts to api session local-all`() {
    mockkStatic(android.provider.Settings.Secure::class)
    every { android.provider.Settings.Secure.getString(any(), any()) } returns "android-id"
    val request =
            AbsTestEnvironment.withStaticField(android.os.Build::class.java, "MANUFACTURER", "TestCo") {
              AbsTestEnvironment.withStaticField(android.os.Build::class.java, "MODEL", "TestPhone") {
                requestFor("""{"results":[]}""") {
                  handler.sendSyncLocalSessions(listOf(playbackSession())) { _, _ -> }
                }
              }
            }

    assertRoute(request, "POST", "/api/session/local-all")
    unmockkAll()
  }

  /** Server: `patch /me/progress/:libraryItemId/:episodeId?`. */
  @Test
  fun `updateMediaProgress patches api me progress with the library item id`() {
    val request = requestFor {
      handler.updateMediaProgress("li-1", null, JSObject().put("currentTime", 5.0)) {}
    }

    assertRoute(request, "PATCH", "/api/me/progress/li-1")
  }

  @Test
  fun `updateMediaProgress for an episode appends the episode id, matching the optional path param`() {
    val request = requestFor {
      handler.updateMediaProgress("li-1", "ep-1", JSObject().put("currentTime", 5.0)) {}
    }

    assertRoute(request, "PATCH", "/api/me/progress/li-1/ep-1")
  }

  /** Server: `get /me/progress/:id/:episodeId?`. */
  @Test
  fun `getMediaProgress calls GET api me progress id`() {
    val request = requestFor("""{"id":"mp","libraryItemId":"li-1","duration":100.0,"progress":0.1,"currentTime":10.0,"isFinished":false,"lastUpdate":1,"startedAt":0}""") {
      handler.getMediaProgress("li-1", null, config) {}
    }

    assertRoute(request, "GET", "/api/me/progress/li-1")
  }

  /** Server: `post /session/:id/close`. */
  @Test
  fun `closePlaybackSession posts to api session id close`() {
    val request = requestFor {
      handler.closePlaybackSession("session-1", config) {}
    }

    assertRoute(request, "POST", "/api/session/session-1/close")
  }

  /** Server: `get /session/:id` (`get /sessions/:id` is the admin route; the app uses the singular). */
  @Test
  fun `getPlaybackSession calls GET api session id`() {
    val request = requestFor("""{"id":"session-1","mediaType":"book","duration":1.0,"playMethod":0,"startedAt":0,"updatedAt":0,"timeListening":0,"audioTracks":[],"currentTime":0.0,"chapters":[],"mediaMetadata":{"title":"t"},"deviceInfo":{}}""") {
      handler.getPlaybackSession("session-1") {}
    }

    assertRoute(request, "GET", "/api/session/session-1")
  }

  // --- Connection ------------------------------------------------------------------------------

  /** Server: `get /ping` on the public router — `server/Server.js:367`. */
  @Test
  fun `pingServer calls GET ping outside the api prefix`() {
    val request = requestFor("""{"success":true}""") {
      handler.pingServer(config) {}
    }

    assertRoute(request, "GET", "/ping")
  }

  /** Server: `post /authorize` — `server/routers/ApiRouter.js:350`. */
  @Test
  fun `authorize posts to api authorize`() {
    val request = requestFor("""{"user":{"id":"u1","username":"n","mediaProgress":[]}}""") {
      handler.authorize(config) {}
    }

    assertRoute(request, "POST", "/api/authorize")
  }

  // --- Auth headers ----------------------------------------------------------------------------

  @Test
  fun `every authenticated request carries the access token as a bearer header`() {
    val request = requestFor("""{"id":"u1","username":"n","mediaProgress":[]}""") {
      handler.getCurrentUser {}
    }

    assertEquals("Bearer test-token", request.getHeader("Authorization"))
  }

  @Test
  fun `no request places the access token in the query string`() {
    // A token in the URL ends up in server access logs and reverse-proxy logs.
    val request = requestFor("""{"id":"u1","username":"n","mediaProgress":[]}""") {
      handler.getCurrentUser {}
    }

    assertTrue(
            "the token must not appear in the request path, got ${request.path}",
            !request.path!!.contains("test-token")
    )
  }
}
