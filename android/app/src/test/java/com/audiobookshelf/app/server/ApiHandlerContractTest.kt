package com.audiobookshelf.app.server

import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.LibraryItemSearchResultType
import com.audiobookshelf.app.data.LibraryShelfBookEntity
import com.audiobookshelf.app.data.LibraryShelfType
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.playbackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.Base64 as JdkBase64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Contract tests for every `ApiHandler` endpoint: request path, HTTP method, auth header, and
 * request/response body mapping. These exist to catch exactly the kind of change an unreviewed
 * edit can make invisibly - a renamed endpoint, a dropped header, a swapped verb - since nothing
 * else in the app exercises these paths against a real HTTP server.
 */
class ApiHandlerContractTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var server: MockWebServer
  private lateinit var handler: ApiHandler

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    DeviceManager.serverConnectionConfig =
            ServerConnectionConfig(
                    "test-server", 0, "Test", server.url("/").toString().trimEnd('/'),
                    "2.17.0", "user-1", "username", "test-token", null
            )
    handler = AbsTestEnvironment.apiHandler()

    // android.util.Base64 has no working implementation under the mockable android.jar; delegate
    // to the JDK's own encoder, which produces byte-identical output for Base64.DEFAULT on inputs
    // short enough to never hit Android's 76-column line wrapping (true for every id under test).
    mockkStatic(android.util.Base64::class)
    every { android.util.Base64.encodeToString(any(), any()) } answers {
      JdkBase64.getEncoder().encodeToString(firstArg())
    }
  }

  @After
  fun tearDown() {
    unmockkStatic(android.util.Base64::class)
    server.shutdown()
    // Otherwise DeviceManager.serverConnectionConfig keeps pointing at this now-shut-down
    // MockWebServer instance for whichever test class the shared JVM runs next.
  }

  private fun takeRequest(): RecordedRequest =
          server.takeRequest(5, TimeUnit.SECONDS) ?: error("ApiHandler never sent a request")

  private fun <T> awaitCallback(action: ((T) -> Unit) -> Unit): T? {
    val latch = CountDownLatch(1)
    var result: T? = null
    action {
      result = it
      latch.countDown()
    }
    assertTrue("callback was never invoked", latch.await(5, TimeUnit.SECONDS))
    return result
  }

  @Test
  fun `getCurrentUser hits GET api-me with bearer auth and maps the user`() {
    server.enqueue(MockResponse().setBody("""{"id":"u1","username":"jane","mediaProgress":[]}"""))

    val user = awaitCallback<com.audiobookshelf.app.models.User?> { handler.getCurrentUser(it) }
    val request = takeRequest()

    assertEquals("GET", request.method)
    assertEquals("/api/me", request.path)
    assertEquals("Bearer test-token", request.getHeader("Authorization"))
    assertEquals("jane", user?.username)
  }

  @Test
  fun `getLibraries requests stats and reads the libraries array`() {
    server.enqueue(MockResponse().setBody("""{"libraries":[{"id":"lib-1","name":"Main","folders":[],"icon":"database","mediaType":"book","stats":null}]}"""))

    val libraries = awaitCallback<List<com.audiobookshelf.app.data.Library>> { handler.getLibraries(it) }
    val request = takeRequest()

    assertEquals("/api/libraries?include=stats", request.path)
    assertEquals(1, libraries?.size)
    assertEquals("lib-1", libraries?.first()?.id)
  }

  @Test
  fun `getLibraries falls back to the legacy value key`() {
    server.enqueue(MockResponse().setBody("""{"value":[{"id":"lib-2","name":"Legacy","folders":[],"icon":"database","mediaType":"book","stats":null}]}"""))

    val libraries = awaitCallback<List<com.audiobookshelf.app.data.Library>> { handler.getLibraries(it) }

    assertEquals(listOf("lib-2"), libraries?.map { it.id })
  }

  @Test
  fun `getLibraries returns an empty list when neither key is present`() {
    server.enqueue(MockResponse().setBody("""{}"""))

    val libraries = awaitCallback<List<com.audiobookshelf.app.data.Library>> { handler.getLibraries(it) }

    assertNotNull(libraries)
    assertTrue(libraries!!.isEmpty())
  }

  @Test
  fun `getLibraryItem requests the expanded item`() {
    server.enqueue(MockResponse().setBody(minimalLibraryItemJson("item-1")))

    val item = awaitCallback<com.audiobookshelf.app.data.LibraryItem?> { handler.getLibraryItem("item-1", it) }
    val request = takeRequest()

    assertEquals("/api/items/item-1?expanded=1", request.path)
    assertEquals("item-1", item?.id)
  }

  @Test
  fun `getLibraryItemWithProgress omits the episode query param when episodeId is blank`() {
    server.enqueue(MockResponse().setBody(minimalLibraryItemJson("item-1")))

    awaitCallback<com.audiobookshelf.app.data.LibraryItem?> {
      handler.getLibraryItemWithProgress("item-1", null, it)
    }

    assertEquals("/api/items/item-1?expanded=1&include=progress", takeRequest().path)
  }

  @Test
  fun `getLibraryItemWithProgress appends the episode id when present`() {
    server.enqueue(MockResponse().setBody(minimalLibraryItemJson("item-1")))

    awaitCallback<com.audiobookshelf.app.data.LibraryItem?> {
      handler.getLibraryItemWithProgress("item-1", "ep-1", it)
    }

    assertEquals("/api/items/item-1?expanded=1&include=progress&episode=ep-1", takeRequest().path)
  }

  @Test
  fun `getLibraryItems reads the results array and defaults to empty when absent`() {
    server.enqueue(MockResponse().setBody("""{"results":[${minimalLibraryItemJson("item-1")}]}"""))

    val items = awaitCallback<List<com.audiobookshelf.app.data.LibraryItem>> { handler.getLibraryItems("lib-1", it) }
    val request = takeRequest()

    assertEquals("/api/libraries/lib-1/items?limit=100&minified=1", request.path)
    assertEquals(listOf("item-1"), items?.map { it.id })
  }

  @Test
  fun `getLibrarySeries requests the series listing`() {
    server.enqueue(MockResponse().setBody("""{"results":[]}"""))

    awaitCallback<List<com.audiobookshelf.app.data.LibrarySeriesItem>> { handler.getLibrarySeries("lib-1", it) }

    assertEquals("/api/libraries/lib-1/series?minified=1&sort=name&limit=10000", takeRequest().path)
  }

  @Test
  fun `getLibrarySeriesItems base64-encodes the series id into the filter`() {
    server.enqueue(MockResponse().setBody("""{"results":[]}"""))
    val expected = JdkBase64.getEncoder().encodeToString("series-1".toByteArray())

    awaitCallback<List<com.audiobookshelf.app.data.LibraryItem>> {
      handler.getLibrarySeriesItems("lib-1", "series-1", it)
    }

    val path = takeRequest().path
    assertEquals(
            "/api/libraries/lib-1/items?minified=1&sort=media.metadata.title&filter=series.$expected&limit=1000",
            path
    )
  }

  @Test
  fun `getLibraryAuthors reads the authors array and defaults to empty when absent`() {
    server.enqueue(MockResponse().setBody("""{"authors":[]}"""))

    val authors = awaitCallback<List<com.audiobookshelf.app.data.LibraryAuthorItem>> { handler.getLibraryAuthors("lib-1", it) }
    val request = takeRequest()

    assertEquals("/api/libraries/lib-1/authors", request.path)
    assertTrue(authors!!.isEmpty())
  }

  @Test
  fun `getLibraryItemsFromAuthor base64-encodes the author id and stamps collapsed series library id`() {
    val itemJson = minimalLibraryItemJson("item-1", collapsedSeries = true)
    server.enqueue(MockResponse().setBody("""{"results":[$itemJson]}"""))
    val expected = JdkBase64.getEncoder().encodeToString("author-1".toByteArray())

    val items = awaitCallback<List<com.audiobookshelf.app.data.LibraryItem>> {
      handler.getLibraryItemsFromAuthor("lib-1", "author-1", it)
    }

    val path = takeRequest().path
    assertTrue(path?.contains("filter=authors.$expected") == true)
    assertEquals("lib-1", items?.first()?.collapsedSeries?.libraryId)
  }

  @Test
  fun `getLibraryCollections requests the collections listing`() {
    server.enqueue(MockResponse().setBody("""{"results":[]}"""))

    awaitCallback<List<com.audiobookshelf.app.data.LibraryCollection>> { handler.getLibraryCollections("lib-1", it) }

    assertEquals("/api/libraries/lib-1/collections?minified=1&sort=name&limit=1000", takeRequest().path)
  }

  @Test
  fun `getAllItemsInProgress parses each raw library item via ItemInProgress`() {
    val itemWithProgress =
            org.json.JSONObject(minimalLibraryItemJson("item-1")).apply { put("progressLastUpdate", 123L) }
    val body = org.json.JSONObject().apply { put("libraryItems", org.json.JSONArray().put(itemWithProgress)) }
    server.enqueue(MockResponse().setBody(body.toString()))

    val items = awaitCallback<List<com.audiobookshelf.app.data.ItemInProgress>> {
      handler.getAllItemsInProgress(it)
    }

    assertEquals("/api/me/items-in-progress", takeRequest().path)
    assertEquals(1, items?.size)
    assertEquals(123L, items?.first()?.progressLastUpdate)
  }

  @Test
  fun `playLibraryItem posts the request payload and omits the episode segment when absent`() {
    server.enqueue(MockResponse().setBody(playbackSessionJson("session-1")))
    val payload = PlayItemRequestPayload("exo-player", false, false, DeviceInfo("d", "m", "mo", 35, "1"))

    val session = awaitCallback<com.audiobookshelf.app.data.PlaybackSession?> {
      handler.playLibraryItem("item-1", null, payload, it)
    }
    val request = takeRequest()

    assertEquals("POST", request.method)
    assertEquals("/api/items/item-1/play", request.path)
    assertTrue(request.body.readUtf8().contains("\"mediaPlayer\":\"exo-player\""))
    assertEquals("session-1", session?.id)
  }

  @Test
  fun `playLibraryItem appends the episode segment when present`() {
    server.enqueue(MockResponse().setBody(playbackSessionJson("session-1")))
    val payload = PlayItemRequestPayload("exo-player", false, false, DeviceInfo("d", "m", "mo", 35, "1"))

    awaitCallback<com.audiobookshelf.app.data.PlaybackSession?> {
      handler.playLibraryItem("item-1", "ep-1", payload, it)
    }

    assertEquals("/api/items/item-1/play/ep-1", takeRequest().path)
  }

  @Test
  fun `sendProgressSync posts sync data to the session endpoint`() {
    server.enqueue(MockResponse().setBody("""{}"""))

    val latch = CountDownLatch(1)
    var success: Boolean? = null
    handler.sendProgressSync("session-1", MediaProgressSyncData(5L, 100.0, 50.5)) { ok, _ ->
      success = ok
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    val request = takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/session/session-1/sync", request.path)
    // The payload round-trips through a Capacitor JSObject (org.json-backed), which collapses
    // whole-number doubles (e.g. 50.0 -> "50") - use a fractional value so the assertion isn't
    // sensitive to that quirk.
    assertTrue(request.body.readUtf8().contains("\"currentTime\":50.5"))
    assertEquals(true, success)
  }

  @Test
  fun `updateMediaProgress patches without an episode segment`() {
    server.enqueue(MockResponse().setBody("""{}"""))

    val latch = CountDownLatch(1)
    handler.updateMediaProgress("item-1", null, com.getcapacitor.JSObject().apply { put("isFinished", true) }) {
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    val request = takeRequest()
    assertEquals("PATCH", request.method)
    assertEquals("/api/me/progress/item-1", request.path)
  }

  @Test
  fun `updateMediaProgress patches with an episode segment`() {
    server.enqueue(MockResponse().setBody("""{}"""))

    val latch = CountDownLatch(1)
    handler.updateMediaProgress("item-1", "ep-1", com.getcapacitor.JSObject()) { latch.countDown() }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertEquals("/api/me/progress/item-1/ep-1", takeRequest().path)
  }

  @Test
  fun `getMediaProgress can target a server connection config other than the active one`() {
    val otherServer = MockWebServer()
    otherServer.start()
    try {
      otherServer.enqueue(
              MockResponse()
                      .setBody("""{"id":"p1","libraryItemId":"item-1","episodeId":null,"duration":10.0,"progress":0.5,"currentTime":5.0,"isFinished":false,"ebookLocation":null,"ebookProgress":null,"lastUpdate":0,"startedAt":0,"finishedAt":null}""")
      )
      val otherConfig =
              ServerConnectionConfig(
                      "other", 1, "Other", otherServer.url("/").toString().trimEnd('/'),
                      "2.17.0", "user-2", "other-user", "other-token", null
              )

      val progress = awaitCallback<com.audiobookshelf.app.data.MediaProgress?> {
        handler.getMediaProgress("item-1", null, otherConfig, it)
      }

      val request = otherServer.takeRequest(5, TimeUnit.SECONDS)
      assertEquals("/api/me/progress/item-1", request?.path)
      assertEquals("Bearer other-token", request?.getHeader("Authorization"))
      assertEquals(0, server.requestCount)
      assertEquals(0.5, progress?.progress ?: -1.0, 0.0)
    } finally {
      otherServer.shutdown()
    }
  }

  @Test
  fun `getPlaybackSession requests the session by id`() {
    server.enqueue(MockResponse().setBody(playbackSessionJson("session-1")))

    awaitCallback<com.audiobookshelf.app.data.PlaybackSession?> { handler.getPlaybackSession("session-1", it) }

    assertEquals("/api/session/session-1", takeRequest().path)
  }

  @Test
  fun `closePlaybackSession posts to the close endpoint and always reports success`() {
    server.enqueue(MockResponse().setBody("""{}"""))

    val result = awaitCallback<Boolean> { handler.closePlaybackSession("session-1", null, it) }
    val request = takeRequest()

    assertEquals("POST", request.method)
    assertEquals("/api/session/session-1/close", request.path)
    assertEquals(true, result)
  }

  @Test
  fun `authorize posts to the authorize endpoint and extracts media progress from the user`() {
    server.enqueue(
            MockResponse().setBody(
                    """{"user":{"mediaProgress":[{"id":"p1","libraryItemId":"item-1","episodeId":null,"duration":10.0,"progress":0.5,"currentTime":5.0,"isFinished":false,"ebookLocation":null,"ebookProgress":null,"lastUpdate":0,"startedAt":0,"finishedAt":null}]}}"""
            )
    )
    val config =
            DeviceManager.serverConnectionConfig
                    ?: error("expected a server connection config to be set")

    val progress = awaitCallback<MutableList<com.audiobookshelf.app.data.MediaProgress>?> {
      handler.authorize(config, it)
    }
    val request = takeRequest()

    assertEquals("POST", request.method)
    assertEquals("/api/authorize", request.path)
    assertEquals(1, progress?.size)
  }

  @Test
  fun `getLibraryPersonalized reads library shelf entities from the value array`() {
    server.enqueue(
            MockResponse().setBody(
                    """{"value":[{"type":"book","id":"shelf-1","label":"Continue","total":1,"entities":[${minimalLibraryItemJson("item-1")}]}]}"""
            )
    )

    val shelves = awaitCallback<List<LibraryShelfType>?> { handler.getLibraryPersonalized("lib-1", it) }
    val request = takeRequest()

    assertEquals("/api/libraries/lib-1/personalized", request.path)
    assertEquals(1, shelves?.size)
    assertTrue(shelves?.first() is LibraryShelfBookEntity)
  }

  @Test
  fun `getLibraryPersonalized reports null on an error response`() {
    server.enqueue(MockResponse().setBody("""{"error":"failed"}"""))

    val shelves = awaitCallback<List<LibraryShelfType>?> { handler.getLibraryPersonalized("lib-1", it) }

    assertNull(shelves)
  }

  @Test
  fun `getSearchResults requests the search endpoint and maps books`() {
    server.enqueue(
            MockResponse().setBody(
                    """{"book":[{"libraryItem": ${minimalLibraryItemJson("item-1")}}],"podcast":null,"series":null,"authors":null}"""
            )
    )

    val results = awaitCallback<LibraryItemSearchResultType?> { handler.getSearchResults("lib-1", "hobbit", it) }
    val request = takeRequest()

    assertEquals("/api/libraries/lib-1/search?q=hobbit", request.path)
    assertEquals("item-1", results?.book?.first()?.libraryItem?.id)
  }

  @Test
  fun `getSearchResults should percent-encode the query string instead of letting it inject extra query parameters`() {
    // queryString is interpolated directly into the URL with no encoding. A search term
    // containing '&' should still arrive as a single "q" value; instead OkHttp's URL parser
    // treats the raw '&' as a query-parameter delimiter, splitting the user's search text into a
    // second, attacker/user-controlled query parameter.
    server.enqueue(MockResponse().setBody("""{"book":null,"podcast":null,"series":null,"authors":null}"""))

    awaitCallback<LibraryItemSearchResultType?> { handler.getSearchResults("lib-1", "a&b=c", it) }

    val request = takeRequest()
    assertEquals(
            "the whole search text should arrive as the single \"q\" query parameter",
            "a&b=c",
            request.requestUrl?.queryParameter("q")
    )
  }

  @Test
  fun `sendLocalProgressSync posts a partial session with device info to the local session endpoint`() {
    server.enqueue(MockResponse().setBody("""{}"""))
    val session = playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = 10.0)

    val latch = CountDownLatch(1)
    var success: Boolean? = null
    handler.sendLocalProgressSync(session) { ok, _ ->
      success = ok
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    val request = takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/session/local", request.path)
    val body = request.body.readUtf8()
    assertTrue(body.contains("\"id\":\"${session.id}\""))
    assertTrue(body.contains("\"deviceInfo\""))
    assertEquals(true, success)
  }

  @Test
  fun `sendLocalProgressSync reports the server-provided error message on failure`() {
    server.enqueue(MockResponse().setBody("""{"error":"sync rejected"}"""))
    val session = playbackSession(mutableListOf(audioTrack(duration = 100.0)))

    val latch = CountDownLatch(1)
    var success: Boolean? = null
    var error: String? = null
    handler.sendLocalProgressSync(session) { ok, err ->
      success = ok
      error = err
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertEquals(false, success)
    assertEquals("sync rejected", error)
  }

  @Test
  fun `sendSyncLocalSessions posts partial sessions and device info to the local-all endpoint`() {
    mockkStatic(android.provider.Settings.Secure::class)
    every { android.provider.Settings.Secure.getString(any(), any()) } returns "device-123"
    try {
      // Build.MANUFACTURER/MODEL are non-null on a real device but null under the mockable
      // android.jar; DeviceInfo's constructor requires non-null Strings, so without this the call
      // throws NullPointerException before ever reaching the network. withStaticField restores
      // the original (null) value afterward instead of leaking it into later test classes.
      AbsTestEnvironment.withStaticField(android.os.Build::class.java, "MANUFACTURER", "TestManufacturer") {
        AbsTestEnvironment.withStaticField(android.os.Build::class.java, "MODEL", "TestModel") {
          val session = playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = 5.0)
          server.enqueue(
                  MockResponse().setBody(
                          """{"results":[{"id":"${session.id}","success":true,"progressSynced":true,"error":null}]}"""
                  )
          )

          val latch = CountDownLatch(1)
          var success: Boolean? = null
          handler.sendSyncLocalSessions(listOf(session)) { ok, _ ->
            success = ok
            latch.countDown()
          }
          assertTrue(latch.await(5, TimeUnit.SECONDS))

          val request = takeRequest()
          assertEquals("POST", request.method)
          assertEquals("/api/session/local-all", request.path)
          val body = request.body.readUtf8()
          assertTrue(body.contains("\"sessions\""))
          assertTrue(body.contains("\"deviceInfo\""))
          assertEquals(true, success)
        }
      }
    } finally {
      unmockkStatic(android.provider.Settings.Secure::class)
    }
  }

  @Test
  fun `syncLocalMediaProgressForUser calls back immediately when there is no local progress to sync`() {
    val latch = CountDownLatch(1)

    handler.syncLocalMediaProgressForUser { latch.countDown() }

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `syncLocalMediaProgressForUser updates local progress when the server copy is newer`() {
    val db = DbManager()
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "local-1", "local-item", null, 100.0, 0.1, 10.0, false, null, null,
                    1_000L, 0L, null, "test-server", server.url("/").toString().trimEnd('/'),
                    "test-user", "item-1", null
            )
    )
    server.enqueue(
            MockResponse().setBody(
                    """{"id":"u1","username":"jane","mediaProgress":[{"id":"mp1","libraryItemId":"item-1","episodeId":null,"duration":100.0,"progress":0.8,"currentTime":80.0,"isFinished":false,"ebookLocation":null,"ebookProgress":null,"lastUpdate":5000,"startedAt":0,"finishedAt":null}]}"""
            )
    )

    val latch = CountDownLatch(1)
    handler.syncLocalMediaProgressForUser { latch.countDown() }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    val updated = db.getLocalMediaProgress("local-1")
    assertEquals(80.0, updated?.currentTime ?: -1.0, 0.0)
    assertEquals(5_000L, updated?.lastUpdate)
  }

  @Test
  fun `syncLocalMediaProgressForUser patches the server when local ebook progress is newer`() {
    val db = DbManager()
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "local-1", "local-item", null, 100.0, 0.1, 10.0, false, "cfi-local", 0.3,
                    9_999L, 0L, null, "test-server", server.url("/").toString().trimEnd('/'),
                    "test-user", "item-1", null
            )
    )
    server.enqueue(
            MockResponse().setBody(
                    """{"id":"u1","username":"jane","mediaProgress":[{"id":"mp1","libraryItemId":"item-1","episodeId":null,"duration":100.0,"progress":0.1,"currentTime":10.0,"isFinished":false,"ebookLocation":"cfi-server","ebookProgress":0.2,"lastUpdate":1000,"startedAt":0,"finishedAt":null}]}"""
            )
    )
    server.enqueue(MockResponse().setBody("""{}""")) // response to the follow-up PATCH

    val latch = CountDownLatch(1)
    handler.syncLocalMediaProgressForUser { latch.countDown() }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    takeRequest() // GET /api/me
    val patchRequest = takeRequest()
    assertEquals("PATCH", patchRequest.method)
    assertEquals("/api/me/progress/item-1", patchRequest.path)
    assertTrue(patchRequest.body.readUtf8().contains("cfi-local"))
  }

  private fun minimalLibraryItemJson(id: String, collapsedSeries: Boolean = false): String {
    val collapsedSeriesField =
            if (collapsedSeries) {
              ""","collapsedSeries":{"id":"cs-1","libraryId":null,"name":"Series","sequence":null,"libraryItemIds":["$id"]}"""
            } else ""
    return """
      {
        "id": "$id", "ino": "ino", "libraryId": "lib-1", "folderId": "folder",
        "path": "/book", "relPath": "book", "mtimeMs": 0, "ctimeMs": 0, "birthtimeMs": 0,
        "addedAt": 0, "updatedAt": 0, "isMissing": false, "isInvalid": false, "mediaType": "book",
        "media": {
          "metadata": {"title": "Title", "genres": [], "explicit": false, "subtitle": null},
          "coverPath": null, "tags": [], "tracks": []
        }$collapsedSeriesField
      }
    """.trimIndent()
  }

  private fun playbackSessionJson(id: String): String {
    return """
      {
        "id": "$id", "userId": "user-1", "libraryItemId": "item-1", "episodeId": null,
        "mediaType": "book", "mediaMetadata": {"title": "Title", "explicit": false, "subtitle": null, "genres": []},
        "deviceInfo": {"deviceId": "d", "manufacturer": "m", "model": "mo", "sdkVersion": 35, "clientVersion": "1"},
        "chapters": [], "displayTitle": "Title", "displayAuthor": "Author", "coverPath": null,
        "duration": 100.0, "playMethod": 0, "startedAt": 0, "updatedAt": 0, "timeListening": 0,
        "audioTracks": [], "currentTime": 0.0, "libraryItem": null, "localLibraryItem": null,
        "localEpisodeId": null, "serverConnectionConfigId": null, "serverAddress": null,
        "mediaPlayer": "exo-player"
      }
    """.trimIndent()
  }
}
