package com.audiobookshelf.app.media

import android.support.v4.media.MediaBrowserCompat
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibraryStats
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.MockServerRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MediaManagerTest {
  private val mockServer = MockServerRule()

  /**
   * Chained, not two `@get:Rule` fields: JUnit gives no ordering guarantee between rule fields on
   * one class, and the singleton reset has to wrap the server rule or it would null the config the
   * server rule just installed.
   */
  @get:Rule val rules = MockServerRule.chainedWith(mockServer)

  private val server: MockWebServer
    get() = mockServer.server

  private lateinit var mediaManager: MediaManager

  @Before
  fun setUp() {
    // loadAuthorBooksWithAudio/loadAuthorSeriesBooksWithAudio/loadPodcastEpisodeMediaBrowserItems
    // (added below) transitively need Base64.encodeToString (ApiHandler's author/series filter
    // query params) and Uri.parse (cover URI resolution), both null-stubbed by the mockable
    // android.jar - see TESTING.md §6 (the mockable-jar known-null list).
    AbsTestEnvironment.mockLocalFileStatics()
    mediaManager = MediaManager(AbsTestEnvironment.apiHandler(), AbsTestEnvironment.mockContext())
  }

  @After
  fun tearDown() {
    io.mockk.unmockkAll()
  }

  @Test
  fun `getFirstItem returns null when there are no server or local items`() {
    assertNull(mediaManager.getFirstItem())
  }

  @Test
  fun `getFirstItem falls back to the first local book when no server items are loaded`() {
    val db = DbManager()
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))

    assertEquals("local-1", mediaManager.getFirstItem()?.id)
  }

  @Test
  fun `getById resolves local-prefixed ids from the local database`() {
    val db = DbManager()
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))

    assertEquals("local-1", mediaManager.getById("local-1")?.id)
    assertNull(mediaManager.getById("local-missing"))
  }

  @Test
  fun `getPodcastWithEpisodeByEpisodeId resolves a local podcast episode from the local database`() {
    val episode =
            PodcastEpisode(
                    "local-ep-1", 1, null, null, "Episode 1", null, null, null, null, null,
                    audioTrack(localFileId = "lf-1"), null, 10.0, 100L, null, null
            )
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", null, null, mutableListOf(), false), null,
                    mutableListOf(), mutableListOf(episode), false, 1
            )
    val db = DbManager()
    db.saveLocalLibraryItem(
            localLibraryItem(id = "local-podcast-1", media = podcast, mediaType = "podcast")
    )

    val result = mediaManager.getPodcastWithEpisodeByEpisodeId("local-ep-1")

    assertEquals("local-podcast-1", (result?.libraryItemWrapper as? LocalLibraryItem)?.id)
    assertEquals("Episode 1", result?.episode?.title)
  }

  @Test
  fun `getPodcastWithEpisodeByEpisodeId misses cleanly for an unknown local episode id`() {
    assertNull(mediaManager.getPodcastWithEpisodeByEpisodeId("local-does-not-exist"))
  }

  @Test
  fun `getFromSearch with a blank query falls back to getFirstItem`() {
    val db = DbManager()
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))

    assertEquals("local-1", mediaManager.getFromSearch(null)?.id)
    assertEquals("local-1", mediaManager.getFromSearch("")?.id)
  }

  @Test
  fun `checkResetServerItems reports true when not connected to a server`() {
    DeviceManager.serverConnectionConfig = null

    assertTrue(mediaManager.checkResetServerItems())
  }

  @Test
  fun `checkResetServerItems actually clears previously cached server items`() {
    loadServerItemIntoCache("item-1", "Cached Book")
    assertEquals("item-1", mediaManager.getById("item-1")?.id)

    DeviceManager.serverConnectionConfig = null
    assertTrue(mediaManager.checkResetServerItems())

    assertNull(
            "checkResetServerItems should have cleared the cached server item",
            mediaManager.getById("item-1")
    )
  }

  @Test
  fun `loadLibraryDiscoveryBooksWithAudio delivers an empty list without throwing for an uncached library`() {
    // loadLibraryDiscoveryBooksWithAudio calls cb(listOf()) when the library isn't cached yet,
    // but is missing a `return` afterward: it falls through to
    // `cachedLibraryDiscovery[libraryId]?.filter{...} as List<LibraryItem>`, and casting a null
    // result with `as` (not `as?`) throws instead of short-circuiting. Any caller - Android
    // Auto's discovery shelf - crashes the first time a library's discovery shelf hasn't been
    // populated yet, rather than seeing an empty shelf.
    //
    // A prior version of this test caught the NullPointerException and asserted only that the
    // pre-throw cb(listOf()) had fired - which is always true regardless of whether the bug is
    // present, since the callback fires before the crash. That made the test a false green: it
    // could never fail while the bug existed, and would still pass if the bug were fixed. This
    // version asserts the actual contract (no throw, exactly one callback) and correctly fails.
    var thrown: Throwable? = null
    val delivered = mutableListOf<List<LibraryItem>>()

    try {
      mediaManager.loadLibraryDiscoveryBooksWithAudio("lib-1") { delivered.add(it) }
    } catch (e: Throwable) {
      thrown = e
    }

    assertNull("should not throw for a library with no cached discovery data", thrown)
    assertEquals(1, delivered.size)
    assertTrue(delivered.single().isEmpty())
  }

  @Test
  fun `getById and getFromSearch resolve items loaded through the collections cache`() {
    loadServerItemIntoCache("item-1", "The Great Adventure")

    assertEquals("item-1", mediaManager.getById("item-1")?.id)
    assertEquals("item-1", mediaManager.getFromSearch("great adventure")?.id)
    assertNull(mediaManager.getFromSearch("no such title"))
    assertNull("a different, never-loaded id should not resolve", mediaManager.getById("item-2"))
  }

  @Test
  fun `loadLibrarySeriesWithAudio filters out series with no audiobooks and caches the result`() {
    val withAudio = seriesJson("series-1", "Zeta Series", hasAudio = true)
    val withoutAudio = seriesJson("series-2", "Alpha Series", hasAudio = false)
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply { put("results", JSONArray().put(JSONObject(withAudio)).put(JSONObject(withoutAudio))) }.toString()
            )
    )

    val latch = CountDownLatch(1)
    var result: List<com.audiobookshelf.app.data.LibrarySeriesItem>? = null
    mediaManager.loadLibrarySeriesWithAudio("lib-1") {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertEquals(listOf("series-1"), result?.map { it.id })
    // A second call must be served from the cache - no second request to the server.
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `loadLibrarySeriesWithAudio filter overload matches by uppercase title prefix on a cold cache`() {
    // loadLibrarySeriesWithAudio(libraryId, seriesFilter, cb) kicks off an async server load when
    // the series cache is cold (`loadLibrarySeriesWithAudio(libraryId) {}` - fire and forget), but
    // then immediately reads `cachedLibrarySeries[libraryId]!!` on the very next line, before that
    // async load can possibly have completed. That is a guaranteed NullPointerException on every
    // first call for a library - the paginated/filtered series browse crashes immediately unless
    // the unfiltered list happens to have been loaded first. This documents the expected contract
    // (filtered results, no crash even on a cold cache) and currently fails.
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put(
                              "results",
                              JSONArray()
                                      .put(JSONObject(seriesJson("series-1", "Zeta Series", hasAudio = true)))
                                      .put(JSONObject(seriesJson("series-2", "Alpha Series", hasAudio = true)))
                      )
                    }.toString()
            )
    )

    val latch = CountDownLatch(1)
    var result: List<com.audiobookshelf.app.data.LibrarySeriesItem>? = null
    var thrown: Throwable? = null
    try {
      mediaManager.loadLibrarySeriesWithAudio("lib-1", "ZETA") {
        result = it
        latch.countDown()
      }
    } catch (e: Throwable) {
      thrown = e
    }

    assertNull("should not throw when the series cache is cold", thrown)
    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals(listOf("series-1"), result?.map { it.id })
  }

  @Test
  fun `loadAuthorsWithBooks filters authors with no books and sorts by name`() {
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put(
                              "authors",
                              JSONArray()
                                      .put(JSONObject(authorJson("author-1", "Zeta Author", numBooks = 2)))
                                      .put(JSONObject(authorJson("author-2", "Alpha Author", numBooks = 3)))
                                      .put(JSONObject(authorJson("author-3", "No Books Author", numBooks = 0)))
                      )
                    }.toString()
            )
    )

    val latch = CountDownLatch(1)
    var result: List<com.audiobookshelf.app.data.LibraryAuthorItem>? = null
    mediaManager.loadAuthorsWithBooks("lib-1") {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertEquals(listOf("Alpha Author", "Zeta Author"), result?.map { it.name })
  }

  @Test
  fun `loadAuthorsWithBooks filter overload matches by uppercase name prefix on a cold cache`() {
    // Same defect shape as loadLibrarySeriesWithAudio above: on a cold cache this fires
    // loadAuthorsWithBooks(libraryId) {} (fire-and-forget async) and then immediately reads
    // cachedLibraryAuthors[libraryId]!!.values on the next line - a guaranteed NullPointerException
    // on every first call. Documents the expected contract; currently fails.
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put(
                              "authors",
                              JSONArray()
                                      .put(JSONObject(authorJson("author-1", "Zeta Author", numBooks = 2)))
                                      .put(JSONObject(authorJson("author-2", "Alpha Author", numBooks = 3)))
                      )
                    }.toString()
            )
    )

    val latch = CountDownLatch(1)
    var result: List<com.audiobookshelf.app.data.LibraryAuthorItem>? = null
    var thrown: Throwable? = null
    try {
      mediaManager.loadAuthorsWithBooks("lib-1", "ALPHA") {
        result = it
        latch.countDown()
      }
    } catch (e: Throwable) {
      thrown = e
    }

    assertNull("should not throw when the author cache is cold", thrown)
    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals(listOf("author-2"), result?.map { it.id })
  }

  @Test
  fun `getNextUnfinishedEpisode returns the most recently published unfinished episode`() {
    val older = podcastEpisode("ep-old", publishedAt = 1_000L)
    val newer = podcastEpisode("ep-new", publishedAt = 2_000L)
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", null, null, mutableListOf(), false), null,
                    mutableListOf(), mutableListOf(older, newer), false, 2
            )

    val result = podcast.getNextUnfinishedEpisode("item-1", mediaManager)

    assertEquals("ep-new", result?.id)
  }

  @Test
  fun `getNextUnfinishedEpisode skips episodes with finished server progress`() {
    val newer = podcastEpisode("ep-new", publishedAt = 2_000L)
    val older = podcastEpisode("ep-old", publishedAt = 1_000L)
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", null, null, mutableListOf(), false), null,
                    mutableListOf(), mutableListOf(newer, older), false, 2
            )
    mediaManager.serverUserMediaProgress =
            mutableListOf(
                    com.audiobookshelf.app.data.MediaProgress(
                            "p1", "item-1", "ep-new", 10.0, 1.0, 10.0, true, null, null, 0, 0, null
                    )
            )

    val result = podcast.getNextUnfinishedEpisode("item-1", mediaManager)

    assertEquals("ep-old", result?.id)
  }

  @Test
  fun `getNextUnfinishedEpisode returns null when every episode is finished`() {
    val ep = podcastEpisode("ep-1", publishedAt = 1_000L)
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", null, null, mutableListOf(), false), null,
                    mutableListOf(), mutableListOf(ep), false, 1
            )
    mediaManager.serverUserMediaProgress =
            mutableListOf(
                    com.audiobookshelf.app.data.MediaProgress(
                            "p1", "item-1", "ep-1", 10.0, 1.0, 10.0, true, null, null, 0, 0, null
                    )
            )

    assertNull(podcast.getNextUnfinishedEpisode("item-1", mediaManager))
  }

  @Test
  fun `getNextUnfinishedEpisode tolerates episodes with a null publishedAt`() {
    val noDate = podcastEpisode("ep-no-date", publishedAt = null)
    val dated = podcastEpisode("ep-dated", publishedAt = 1_000L)
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", null, null, mutableListOf(), false), null,
                    mutableListOf(), mutableListOf(noDate, dated), false, 2
            )

    val result = podcast.getNextUnfinishedEpisode("item-1", mediaManager)

    assertNotNull(result)
  }

  private fun podcastEpisode(id: String, publishedAt: Long?) =
          PodcastEpisode(
                  id, 1, null, null, "Episode $id", null, null, null, publishedAt, null,
                  audioTrack(localFileId = "lf-$id"), null, 10.0, 100L, null, null
          )

  private fun seriesJson(id: String, name: String, hasAudio: Boolean): String {
    val tracks =
            if (hasAudio)
                    """[{"index":0,"startOffset":0.0,"duration":10.0,"title":"T","contentUrl":"/t","mimeType":null,"metadata":null,"isLocal":false,"localFileId":null,"serverIndex":0}]"""
            else "[]"
    return """
      {
        "id": "$id", "libraryId": "lib-1", "name": "$name", "description": null,
        "addedAt": 0, "updatedAt": 0, "localLibraryItemId": null,
        "books": [
          {
            "id": "$id-book", "ino": "ino", "libraryId": "lib-1", "folderId": "folder",
            "path": "/book", "relPath": "book", "mtimeMs": 0, "ctimeMs": 0, "birthtimeMs": 0,
            "addedAt": 0, "updatedAt": 0, "isMissing": false, "isInvalid": false, "mediaType": "book",
            "media": {
              "metadata": {"title": "Book", "genres": [], "explicit": false, "subtitle": null},
              "coverPath": null, "tags": [], "tracks": $tracks
            }
          }
        ]
      }
    """.trimIndent()
  }

  private fun authorJson(id: String, name: String, numBooks: Int): String {
    return """
      {
        "id": "$id", "libraryId": "lib-1", "name": "$name", "description": null,
        "imagePath": null, "addedAt": 0, "updatedAt": 0, "numBooks": $numBooks,
        "libraryItems": null, "series": null
      }
    """.trimIndent()
  }

  /** Loads a single server library item into `serverLibraryItems` via a real collections round trip. */
  private fun loadServerItemIntoCache(id: String, title: String) {
    val itemJson = minimalLibraryItemJson(id, title = title)
    val collectionJson =
            JSONObject().apply {
              put("id", "col-1")
              put("libraryId", "lib-1")
              put("name", "Collection")
              put("description", JSONObject.NULL)
              put("books", JSONArray().put(JSONObject(itemJson)))
            }
    server.enqueue(
            MockResponse().setBody(JSONObject().apply { put("results", JSONArray().put(collectionJson)) }.toString())
    )

    val collectionsLatch = CountDownLatch(1)
    mediaManager.loadLibraryCollectionsWithAudio("lib-1") { collectionsLatch.countDown() }
    assertTrue(collectionsLatch.await(5, TimeUnit.SECONDS))

    val booksLatch = CountDownLatch(1)
    var books: List<LibraryItem>? = null
    mediaManager.loadLibraryCollectionBooksWithAudio("lib-1", "col-1") {
      books = it
      booksLatch.countDown()
    }
    assertTrue(booksLatch.await(5, TimeUnit.SECONDS))
    assertNotNull(books)
  }

  private fun minimalLibraryItemJson(id: String, title: String): String {
    return libraryItemJson(id, title, hasTracks = true)
  }

  private fun libraryItemJson(id: String, title: String, authorName: String? = null, hasTracks: Boolean = true): String {
    val tracks =
            if (hasTracks)
                    """[{"index":0,"startOffset":0.0,"duration":10.0,"title":"Track 1","contentUrl":"/track","mimeType":null,"metadata":null,"isLocal":false,"localFileId":null,"serverIndex":0}]"""
            else "[]"
    val authorNameJson = if (authorName != null) "\"$authorName\"" else "null"
    return """
      {
        "id": "$id", "ino": "ino", "libraryId": "lib-1", "folderId": "folder",
        "path": "/book", "relPath": "book", "mtimeMs": 0, "ctimeMs": 0, "birthtimeMs": 0,
        "addedAt": 0, "updatedAt": 0, "isMissing": false, "isInvalid": false, "mediaType": "book",
        "media": {
          "metadata": {"title": "$title", "genres": [], "explicit": false, "subtitle": null, "authorName": $authorNameJson},
          "coverPath": null, "tags": [],
          "tracks": $tracks
        }
      }
    """.trimIndent()
  }

  private fun podcastEpisodeJson(id: String, title: String, publishedAt: Long?) =
          """
      {
        "id": "$id", "index": 1, "episode": null, "episodeType": null, "title": "$title",
        "subtitle": null, "description": null, "pubDate": null, "publishedAt": $publishedAt,
        "audioFile": null,
        "audioTrack": {"index":0,"startOffset":0.0,"duration":10.0,"title":"$title","contentUrl":"/ep","mimeType":null,"metadata":null,"isLocal":false,"localFileId":null,"serverIndex":0},
        "chapters": null, "duration": 10.0, "size": null, "serverEpisodeId": null, "localEpisodeId": null
      }
    """.trimIndent()

  private fun podcastLibraryItemJson(id: String, episodesJson: String): String {
    return """
      {
        "id": "$id", "ino": "ino", "libraryId": "lib-1", "folderId": "folder",
        "path": "/cast", "relPath": "cast", "mtimeMs": 0, "ctimeMs": 0, "birthtimeMs": 0,
        "addedAt": 0, "updatedAt": 0, "isMissing": false, "isInvalid": false, "mediaType": "podcast",
        "media": {
          "metadata": {"title": "Cast", "author": null, "feedUrl": null, "genres": [], "explicit": false},
          "coverPath": null, "tags": [], "episodes": $episodesJson, "autoDownloadEpisodes": false, "numEpisodes": 0
        }
      }
    """.trimIndent()
  }

  @Test
  fun `loadAuthorBooksWithAudio filters to items with audio and caches the result`() {
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put(
                              "results",
                              JSONArray()
                                      .put(JSONObject(libraryItemJson("book-1", "With Audio", hasTracks = true)))
                                      .put(JSONObject(libraryItemJson("book-2", "No Audio", hasTracks = false)))
                      )
                    }.toString()
            )
    )

    val latch = CountDownLatch(1)
    var result: List<LibraryItem>? = null
    mediaManager.loadAuthorBooksWithAudio("lib-1", "author-1") {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals(listOf("book-1"), result?.map { it.id })

    // A second call for the same author must be served from cache, not a second request.
    val cachedLatch = CountDownLatch(1)
    mediaManager.loadAuthorBooksWithAudio("lib-1", "author-1") { cachedLatch.countDown() }
    assertTrue(cachedLatch.await(5, TimeUnit.SECONDS))
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `loadAuthorSeriesBooksWithAudio filters results down to books by the cached author`() {
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put(
                              "authors",
                              JSONArray().put(JSONObject(authorJson("author-1", "Jane Doe", numBooks = 1)))
                      )
                    }.toString()
            )
    )
    val authorsLatch = CountDownLatch(1)
    mediaManager.loadAuthorsWithBooks("lib-1") { authorsLatch.countDown() }
    assertTrue(authorsLatch.await(5, TimeUnit.SECONDS))

    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put(
                              "results",
                              JSONArray()
                                      .put(JSONObject(libraryItemJson("book-1", "By Jane", authorName = "Jane Doe")))
                                      .put(JSONObject(libraryItemJson("book-2", "By Someone Else", authorName = "Someone Else")))
                      )
                    }.toString()
            )
    )

    val latch = CountDownLatch(1)
    var result: List<LibraryItem>? = null
    mediaManager.loadAuthorSeriesBooksWithAudio("lib-1", "author-1", "series-1") {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertEquals(listOf("book-1"), result?.map { it.id })
  }

  @Test
  fun `loadAuthorSeriesBooksWithAudio caches under a key its own lookup never reads, so every call re-fetches`() {
    // Newly discovered defect: the cache read checks "authorId|seriesId" but the write a few
    // lines later stores under plain authorId - so the read key never matches anything the method
    // itself ever wrote, and every call is a guaranteed cache miss. This asserts the intended
    // contract (a second identical call is served from cache) and currently fails.
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put("authors", JSONArray().put(JSONObject(authorJson("author-1", "Jane Doe", numBooks = 1))))
                    }.toString()
            )
    )
    val authorsLatch = CountDownLatch(1)
    mediaManager.loadAuthorsWithBooks("lib-1") { authorsLatch.countDown() }
    assertTrue(authorsLatch.await(5, TimeUnit.SECONDS))

    val itemsJson =
            JSONObject().apply {
              put("results", JSONArray().put(JSONObject(libraryItemJson("book-1", "By Jane", authorName = "Jane Doe"))))
            }.toString()
    server.enqueue(MockResponse().setBody(itemsJson))
    server.enqueue(MockResponse().setBody(itemsJson))

    val firstLatch = CountDownLatch(1)
    mediaManager.loadAuthorSeriesBooksWithAudio("lib-1", "author-1", "series-1") { firstLatch.countDown() }
    assertTrue(firstLatch.await(5, TimeUnit.SECONDS))

    // Measured rather than hard-coded: the setup above already had to load the author list, so the
    // absolute count at this point is not 1 and asserting a literal made this spec fail even once
    // the cache key was fixed. What the defect is actually about is whether the *second* identical
    // call re-fetches, so the assertion below is the delta across it.
    val requestsAfterFirstCall = server.requestCount

    val secondLatch = CountDownLatch(1)
    mediaManager.loadAuthorSeriesBooksWithAudio("lib-1", "author-1", "series-1") { secondLatch.countDown() }
    assertTrue(secondLatch.await(5, TimeUnit.SECONDS))

    assertEquals(
            "a repeat call for the same author+series must be served from cache, issuing no further requests",
            requestsAfterFirstCall,
            server.requestCount
    )
  }

  /**
   * The same cold-cache defect shape as the two filter overloads above, in the one place it
   * survived them: `loadAuthorSeriesBooksWithAudio`'s server callback reads
   * `cachedLibraryAuthors[libraryId]!!` twice (to check for, and then read, the author's name)
   * without the library's author map necessarily having been populated.
   *
   * Every other spec for this method loads the author list first, which is what hides it - but
   * nothing forces that ordering in production. Android Auto can browse straight into an
   * author's series (a resumed browse path, or a direct media-id request from a voice command)
   * without the authors shelf having been opened in this process, and then the `!!` throws inside
   * an OkHttp callback thread.
   *
   * Expected: the callback still fires. The author name is only used to filter by, and the method
   * already has a `?: ""` fallback for a missing author, so an absent cache is recoverable.
   */
  @Test
  fun `loadAuthorSeriesBooksWithAudio survives a cold author cache instead of throwing`() {
    server.enqueue(
            MockResponse().setBody(
                    JSONObject().apply {
                      put("results", JSONArray().put(JSONObject(libraryItemJson("book-1", "By Jane", authorName = "Jane Doe"))))
                    }.toString()
            )
    )

    val latch = CountDownLatch(1)
    var result: List<LibraryItem>? = null
    var thrown: Throwable? = null
    try {
      // No loadAuthorsWithBooks call first - the author cache for "lib-1" is empty.
      mediaManager.loadAuthorSeriesBooksWithAudio("lib-1", "author-1", "series-1") {
        result = it
        latch.countDown()
      }
    } catch (e: Throwable) {
      thrown = e
    }

    assertNull("an unpopulated author cache must not throw out of the browse path", thrown)
    assertTrue("the callback must still fire so the browse request completes", latch.await(5, TimeUnit.SECONDS))
    assertEquals(listOf("book-1"), result?.map { it.id })
  }

  /**
   * `PodcastEpisode.getMediaDescription` calls `android.icu.text.DateFormat.getDateInstance()`
   * when `publishedAt` is non-null - a static factory method that returns `null` under the
   * mockable `android.jar` (same class of gap as `Base64.encodeToString`/`MimeTypeMap
   * .getSingleton()`), so `sdf.format(...)` NPEs immediately after. This is a test-environment
   * gap, not a production bug - `getDateInstance()` returns a real formatter on-device.
   */
  private fun mockDateFormat() {
    mockkStatic(android.icu.text.DateFormat::class)
    val formatter = mockk<android.icu.text.DateFormat>()
    every { formatter.format(any<Date>()) } returns "Jan 1, 2024"
    every { android.icu.text.DateFormat.getDateInstance() } returns formatter
  }

  @Test
  fun `loadPodcastEpisodeMediaBrowserItems returns server episodes as playable media items sorted newest first`() {
    mockDateFormat()
    val episodesJson =
            JSONArray()
                    .put(JSONObject(podcastEpisodeJson("ep-old", "Old Episode", publishedAt = 1_000L)))
                    .put(JSONObject(podcastEpisodeJson("ep-new", "New Episode", publishedAt = 2_000L)))
    server.enqueue(MockResponse().setBody(podcastLibraryItemJson("cast-1", episodesJson.toString())))

    val latch = CountDownLatch(1)
    var result: MutableList<MediaBrowserCompat.MediaItem>? = null
    mediaManager.loadPodcastEpisodeMediaBrowserItems("cast-1", AbsTestEnvironment.mockContext()) {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertEquals(2, result?.size)
    assertEquals("New Episode", result?.get(0)?.description?.title)
    assertEquals("Old Episode", result?.get(1)?.description?.title)
  }

  @Test
  fun `loadPodcastEpisodeMediaBrowserItems returns an empty list for a podcast with no episodes`() {
    server.enqueue(MockResponse().setBody(podcastLibraryItemJson("cast-1", "[]")))

    val latch = CountDownLatch(1)
    var result: MutableList<MediaBrowserCompat.MediaItem>? = null
    mediaManager.loadPodcastEpisodeMediaBrowserItems("cast-1", AbsTestEnvironment.mockContext()) {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertTrue(result!!.isEmpty())
  }

  @Test
  fun `populatePersonalizedDataForAllLibraries routes book discover items into the discovery cache`() {
    mediaManager.serverLibraries = listOf(Library("lib-1", "Lib", mutableListOf(), "database", "book", LibraryStats(0, 0, 0.0, 1)))
    val shelvesJson =
            JSONArray()
                    .put(
                            JSONObject().apply {
                              put("id", "continue-listening"); put("label", "Continue"); put("total", 0); put("type", "book")
                              put("entities", JSONArray())
                            }
                    )
                    .put(
                            JSONObject().apply {
                              put("id", "discover"); put("label", "Discover"); put("total", 1); put("type", "book")
                              put("entities", JSONArray().put(JSONObject(libraryItemJson("book-1", "Discovered"))))
                            }
                    )
    server.enqueue(MockResponse().setBody(JSONObject().apply { put("value", shelvesJson) }.toString()))

    val latch = CountDownLatch(1)
    mediaManager.populatePersonalizedDataForAllLibraries { latch.countDown() }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    val discoveryLatch = CountDownLatch(1)
    var discovered: List<LibraryItem>? = null
    mediaManager.loadLibraryDiscoveryBooksWithAudio("lib-1") {
      discovered = it
      discoveryLatch.countDown()
    }
    assertTrue(discoveryLatch.await(5, TimeUnit.SECONDS))
    assertEquals(
            "the continue-listening shelf must be excluded and only discover entities cached",
            listOf("book-1"),
            discovered?.map { it.id }
    )
  }

  @Test
  fun `populatePersonalizedDataForAllLibraries routes a recent-series shelf into the recent-shelves cache`() {
    mediaManager.serverLibraries = listOf(Library("lib-1", "Lib", mutableListOf(), "database", "book", LibraryStats(0, 0, 0.0, 1)))
    val shelvesJson =
            JSONArray().put(
                    JSONObject().apply {
                      put("id", "recent-series"); put("label", "Recent Series"); put("total", 0); put("type", "series")
                      put("entities", JSONArray())
                    }
            )
    server.enqueue(MockResponse().setBody(JSONObject().apply { put("value", shelvesJson) }.toString()))

    val latch = CountDownLatch(1)
    mediaManager.populatePersonalizedDataForAllLibraries { latch.countDown() }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    val shelfLatch = CountDownLatch(1)
    var shelf: com.audiobookshelf.app.data.LibraryShelfType? = null
    mediaManager.getLibraryRecentShelfByType("lib-1", "series") {
      shelf = it
      shelfLatch.countDown()
    }
    assertTrue(shelfLatch.await(5, TimeUnit.SECONDS))
    assertEquals("recent-series", shelf?.id)
  }
}
