package com.audiobookshelf.wear.data

import com.audiobookshelf.wear.ApiHarness
import com.audiobookshelf.wear.fixture
import com.audiobookshelf.wear.jsonResponse
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AbsApiTest {
  private val server = MockWebServer()
  private lateinit var harness: ApiHarness
  private val api get() = harness.api

  @Before
  fun setUp() {
    server.start()
    harness = ApiHarness(server)
    harness.signIn()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `lists audiobook and podcast libraries but not ebook ones`() = runBlocking {
    server.enqueue(jsonResponse(fixture("libraries.json")))

    val libraries = api.audioLibraries()

    assertEquals(listOf("lib_books", "lib_pods"), libraries.map { it.id })
    assertFalse(libraries[0].isPodcast)
    assertTrue(libraries[1].isPodcast)
  }

  @Test
  fun `reads each library's icon, empty when the server sends none`() = runBlocking {
    server.enqueue(jsonResponse(fixture("libraries.json")))

    val libraries = api.audioLibraries()

    assertEquals(listOf("books-2", ""), libraries.map { it.icon })
  }

  @Test
  fun `in progress includes podcast episodes`() = runBlocking {
    server.enqueue(jsonResponse(fixture("items_in_progress.json")))

    val items = api.itemsInProgress()

    assertEquals(listOf(MediaKey("li_book1"), MediaKey("li_pod1", "ep_new")), items.map { it.mediaKey })
    val episode = items[1]
    assertEquals("Newest episode", episode.title)
    assertEquals("The Daily Show", episode.author)
    assertEquals(2400.0, episode.duration, 0.0)
    assertEquals(MEDIA_PODCAST, episode.mediaType)
    assertFalse(episode.isPodcast)
  }

  @Test
  fun `all progress is keyed by book or episode`() = runBlocking {
    server.enqueue(jsonResponse(fixture("me.json")))

    val progress = api.allProgress()

    assertEquals(setOf("li_book1", "li_pod1/ep_new"), progress.keys)
    assertTrue(progress.getValue("li_pod1/ep_new").isFinished)
  }

  @Test
  fun `episode progress uses the episode path`() = runBlocking {
    server.enqueue(jsonResponse("""{"libraryItemId":"li_pod1","episodeId":"ep_new","currentTime":12,"progress":0.1,"isFinished":false,"lastUpdate":5}"""))

    val progress = api.progress(MediaKey("li_pod1", "ep_new"))

    assertEquals("/api/me/progress/li_pod1/ep_new", server.takeRequest().path)
    assertEquals(12.0, progress!!.currentTime, 0.0)
  }

  @Test
  fun `no progress yet is null`() = runBlocking {
    server.enqueue(jsonResponse("Not found", code = 404))
    assertNull(api.progress("li_book1"))
  }

  @Test
  fun `marks finished`() = runBlocking {
    server.enqueue(jsonResponse("{}"))

    api.setFinished(MediaKey("li_book1"), true)

    val request = server.takeRequest()
    assertEquals("PATCH", request.method)
    assertEquals("/api/me/progress/li_book1", request.path)
    assertEquals("Bearer token1", request.getHeader("Authorization"))
    val body = JSONObject(request.body.readUtf8())
    assertTrue(body.getBoolean("isFinished"))
    assertFalse(body.has("currentTime"))
  }

  @Test
  fun `marking not finished starts the episode over`() = runBlocking {
    server.enqueue(jsonResponse("{}"))

    api.setFinished(MediaKey("li_pod1", "ep_new"), false)

    val request = server.takeRequest()
    assertEquals("/api/me/progress/li_pod1/ep_new", request.path)
    val body = JSONObject(request.body.readUtf8())
    assertFalse(body.getBoolean("isFinished"))
    assertEquals(0, body.getInt("currentTime"))
  }

  @Test
  fun `loads a single episode`() = runBlocking {
    server.enqueue(jsonResponse(fixture("podcast_item.json")))

    val episode = api.playable(MediaKey("li_pod1", "ep_old"))

    assertEquals("/api/items/li_pod1?expanded=1", server.takeRequest().path)
    assertEquals("Older episode", episode.title)
    assertEquals("ep_old", episode.episodeId)
  }

  @Test
  fun `a missing episode is an error`() = runBlocking {
    server.enqueue(jsonResponse(fixture("podcast_item.json")))
    try {
      api.episode("li_pod1", "ep_gone")
      fail("Expected an error")
    } catch (e: ApiException) {
      assertEquals(404, e.code)
    }
  }

  @Test
  fun `an expired token is refreshed and the request retried`() = runBlocking {
    server.enqueue(jsonResponse("Unauthorized", code = 401))
    server.enqueue(jsonResponse("""{"user":{"accessToken":"token2","refreshToken":"refresh2"}}"""))
    server.enqueue(jsonResponse(fixture("libraries.json")))

    val libraries = api.audioLibraries()

    assertEquals(2, libraries.size)
    assertEquals("Bearer token1", server.takeRequest().getHeader("Authorization"))
    val refresh = server.takeRequest()
    assertEquals("/auth/refresh", refresh.path)
    assertEquals("refresh1", refresh.getHeader("x-refresh-token"))
    assertEquals("Bearer token2", server.takeRequest().getHeader("Authorization"))
    assertEquals("token2", harness.auth.current?.accessToken)
    assertEquals("refresh2", harness.auth.current?.refreshToken)
  }

  @Test
  fun `a rejected refresh token signs out`() = runBlocking {
    server.enqueue(jsonResponse("Unauthorized", code = 401))
    server.enqueue(jsonResponse("Unauthorized", code = 401))

    try {
      api.audioLibraries()
      fail("Expected an error")
    } catch (e: ApiException) {
      assertEquals(401, e.code)
    }
    assertNull(harness.auth.current)
  }

  @Test
  fun `loads listening stats`() = runBlocking {
    server.enqueue(jsonResponse(fixture("listening_stats.json")))

    val json = api.listeningStats()

    assertEquals("/api/me/listening-stats", server.takeRequest().path)
    assertEquals(3600, json.getInt("today"))
  }

  @Test
  fun `search returns books and podcasts`() = runBlocking {
    server.enqueue(
      jsonResponse(
        """{"book":[{"libraryItem":{"id":"li_b","mediaType":"book","media":{"metadata":{"title":"B"}}}}],
           "podcast":[{"libraryItem":{"id":"li_p","mediaType":"podcast","media":{"metadata":{"title":"P"}}}}]}"""
      )
    )

    val results = api.search("lib", "x y")

    assertEquals("/api/libraries/lib/search?q=x+y&limit=20", server.takeRequest().path)
    assertEquals(listOf("li_b", "li_p"), results.map { it.id })
    assertTrue(results[1].isPodcast)
  }

  @Test
  fun `credentials only go to the signed in server`() {
    fun url(s: String) = okhttp3.HttpUrl.Companion.run { s.toHttpUrl() }
    assertTrue(AbsApi.isForServer(url("https://abs.example.com/api/me"), "https://abs.example.com"))
    assertFalse(AbsApi.isForServer(url("https://abs.example.com.evil.net/x"), "https://abs.example.com"))
    assertFalse(AbsApi.isForServer(url("http://host:13378/api"), "http://host:1337"))
    assertFalse(AbsApi.isForServer(url("http://abs.example.com/api"), "https://abs.example.com"))
    assertTrue(AbsApi.isForServer(url("https://host/abs/api/me"), "https://host/abs"))
    assertFalse(AbsApi.isForServer(url("https://host/absolute/x"), "https://host/abs"))
  }
}
