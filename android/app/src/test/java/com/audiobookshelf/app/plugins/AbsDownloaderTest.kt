package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.AudioFile
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.EBookFile
import com.audiobookshelf.app.data.FileMetadata
import com.audiobookshelf.app.data.LibraryFile
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.data.libraryItem
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.services.DownloadServiceHost
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `AbsDownloader`'s `lateinit var`s (`mainActivity`/`apiHandler`/`downloadItemManager`) are only
 * set by `load()`, which needs a real Capacitor `Bridge` - but they can be injected directly,
 * making the plugin reachable without it. `startLibraryItemDownload` (the download-manifest
 * builder) is private, so these tests drive it through the public `downloadLibraryItem` entry
 * point and intercept the final `DownloadServiceHost.enqueue` call to capture the `DownloadItem`
 * it built, the same way other suites `mockkObject` a collaborator that reaches `WorkManager`.
 *
 * This is also where P0's "disabled size check" defect originates: every cover part's `fileSize`
 * defaults to `coverLibraryFile?.metadata?.size ?: 0`, which is `0` whenever `libraryFiles` has no
 * entry matching `media.coverPath` - see the "cover part defaults to a zero file size" tests below.
 */
class AbsDownloaderTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var plugin: AbsDownloader
  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler: ApiHandler
  private lateinit var downloadItemManager: DownloadItemManager
  private lateinit var dir: File
  private val enqueued = slot<DownloadItem>()

  @Before
  fun setUp() {
    AbsTestEnvironment.mockLocalFileStatics()
    dir = Files.createTempDirectory("abs-downloader-test").toFile()
    DeviceManager.serverConnectionConfig = ServerConnectionConfig(
            "server", 0, "Test", "https://example.invalid", "2.17.0", "user", "username", "tok", null
    )

    mockkObject(DownloadServiceHost)
    every { DownloadServiceHost.enqueue(any(), capture(enqueued)) } returns Unit

    mainActivity = mockk(relaxed = true)
    every { mainActivity.filesDir } returns dir

    apiHandler = mockk(relaxed = true)
    downloadItemManager = DownloadItemManager(
            mockk<FolderScanner>(relaxed = true), AbsTestEnvironment.mockContext(),
            mockk<DownloadItemManager.DownloadEventEmitter>(relaxed = true)
    )

    plugin = AbsDownloader()
    plugin.mainActivity = mainActivity
    plugin.apiHandler = apiHandler
    plugin.downloadItemManager = downloadItemManager
  }

  @After
  fun tearDown() {
    downloadItemManager.destroy()
    unmockkObject(DownloadServiceHost)
    unmockkAll()
    dir.deleteRecursively()
  }

  private fun callFor(libraryItemId: String, episodeId: String? = null, localFolderId: String = ""): PluginCall {
    val data = JSObject()
    data.put("libraryItemId", libraryItemId)
    if (episodeId != null) data.put("episodeId", episodeId)
    data.put("localFolderId", localFolderId)
    val call = mockk<PluginCall>(relaxed = true)
    every { call.data } returns data
    return call
  }

  private fun stubServerItem(item: com.audiobookshelf.app.data.LibraryItem) {
    every { apiHandler.getLibraryItemWithProgress(any(), any(), any()) } answers {
      thirdArg<(com.audiobookshelf.app.data.LibraryItem?) -> Unit>().invoke(item)
    }
  }

  private fun audioTrack(index: Int, path: String, duration: Double = 60.0) =
          AudioTrack(index, 0.0, duration, "Track $index", "", "audio/mpeg",
                  FileMetadata("t$index.mp3", "mp3", path, path, 100L), false, null, index)

  @Test
  fun `downloadLibraryItem builds one part per audio track`() {
    val trackA = audioTrack(1, "track1.mp3")
    val trackB = audioTrack(2, "track2.mp3")
    val book = book(tracks = mutableListOf(trackA, trackB))
    book.audioFiles = mutableListOf(
            AudioFile(1, "ino-1", FileMetadata("t1.mp3", "mp3", "track1.mp3", "track1.mp3", 100L)),
            AudioFile(2, "ino-2", FileMetadata("t2.mp3", "mp3", "track2.mp3", "track2.mp3", 100L))
    )
    val item = libraryItem(media = book).apply { id = "li-1" }
    stubServerItem(item)

    plugin.downloadLibraryItem(callFor("li-1"))

    assertTrue(enqueued.isCaptured)
    val audioParts = enqueued.captured.downloadItemParts.filter { it.audioTrack != null }
    assertEquals(2, audioParts.size)
  }

  @Test
  fun `downloadLibraryItem adds a cover part with the raw suffix when the book has a coverPath`() {
    val track = audioTrack(1, "track1.mp3")
    val book = book(tracks = mutableListOf(track)).apply { coverPath = "cover.jpg" }
    val item = libraryItem(media = book).apply {
      id = "li-1"
      libraryFiles = mutableListOf(LibraryFile("ino-cover", FileMetadata("cover.jpg", "jpg", "cover.jpg", "cover.jpg", 2048L)))
    }
    stubServerItem(item)

    plugin.downloadLibraryItem(callFor("li-1"))

    assertTrue(enqueued.isCaptured)
    val coverPart = enqueued.captured.downloadItemParts.find { it.serverPath.endsWith("/cover") }
    assertTrue("expected a cover part to be added", coverPart != null)
    assertEquals("cover-li-1.jpg", coverPart!!.filename)
    assertEquals(2048L, coverPart.fileSize)
    assertTrue("cover downloads must request the raw (untranscoded) asset", coverPart.uri.toString().endsWith("?raw=1"))
  }

  @Test
  fun `downloadLibraryItem's cover part defaults to a zero file size when no libraryFiles entry matches`() {
    // This is the defect P0 traces back to: a fileSize of 0 disables the one size check
    // (resolveExternalFile's file.length() != part.fileSize) that could have caught a truncated
    // cover download.
    val track = audioTrack(1, "track1.mp3")
    val book = book(tracks = mutableListOf(track)).apply { coverPath = "cover.jpg" }
    val item = libraryItem(media = book).apply { id = "li-1"; libraryFiles = mutableListOf() }
    stubServerItem(item)

    plugin.downloadLibraryItem(callFor("li-1"))

    val coverPart = enqueued.captured.downloadItemParts.find { it.serverPath.endsWith("/cover") }
    assertEquals(0L, coverPart!!.fileSize)
  }

  @Test
  fun `downloadLibraryItem omits the cover part when the book has no coverPath`() {
    val track = audioTrack(1, "track1.mp3")
    val book = book(tracks = mutableListOf(track)).apply { coverPath = null }
    val item = libraryItem(media = book).apply { id = "li-1" }
    stubServerItem(item)

    plugin.downloadLibraryItem(callFor("li-1"))

    assertFalse(enqueued.captured.downloadItemParts.any { it.serverPath.endsWith("/cover") })
  }

  @Test
  fun `downloadLibraryItem adds an ebook part when the book has an ebookFile`() {
    val track = audioTrack(1, "track1.mp3")
    val book = book(tracks = mutableListOf(track)).apply {
      ebookFile = EBookFile("ino-ebook", FileMetadata("book.epub", "epub", "book.epub", "book.epub", 4096L), "epub", false, null, null)
    }
    val item = libraryItem(media = book).apply { id = "li-1" }
    stubServerItem(item)

    plugin.downloadLibraryItem(callFor("li-1"))

    val ebookPart = enqueued.captured.downloadItemParts.find { it.ebookFile != null }
    assertTrue("expected an ebook part to be added", ebookPart != null)
    assertEquals(4096L, ebookPart!!.fileSize)
  }

  @Test
  fun `downloadLibraryItem for a podcast episode adds one audio part and a cover part named cover_jpg`() {
    val episodeTrack = audioTrack(1, "ep1.mp3")
    val podcast = Podcast(PodcastMetadata("Cast", null, null, mutableListOf(), false), "cover.jpg", mutableListOf(), null, false, 0)
    val episode = PodcastEpisode(
            "ep-1", 1, null, null, "Episode 1", null, null, null, null,
            AudioFile(1, "ino-ep", FileMetadata("ep1.mp3", "mp3", "ep1.mp3", "ep1.mp3", 500L)),
            episodeTrack, null, 60.0, null, null, null
    )
    podcast.episodes = mutableListOf(episode)
    val item = libraryItem(media = podcast, mediaType = "podcast").apply { id = "li-1" }
    stubServerItem(item)

    plugin.downloadLibraryItem(callFor("li-1", episodeId = "ep-1"))

    assertTrue(enqueued.isCaptured)
    val parts = enqueued.captured.downloadItemParts
    assertEquals(1, parts.count { it.audioTrack != null })
    val coverPart = parts.find { it.serverPath.endsWith("/cover") }
    assertTrue("expected a cover part for the podcast episode download", coverPart != null)
    assertEquals("cover.jpg", coverPart!!.filename)
  }

  @Test
  fun `downloadLibraryItem resolves an error and does not enqueue when the server request fails`() {
    every { apiHandler.getLibraryItemWithProgress(any(), any(), any()) } answers {
      thirdArg<(com.audiobookshelf.app.data.LibraryItem?) -> Unit>().invoke(null)
    }
    val call = callFor("li-1")

    plugin.downloadLibraryItem(call)

    assertFalse(enqueued.isCaptured)
    val resolved = slot<JSObject>()
    io.mockk.verify { call.resolve(capture(resolved)) }
    assertTrue(resolved.captured.toString().contains("error"))
  }

  @Test
  fun `downloadLibraryItem does not start a second download for an id already queued`() {
    val existing = DownloadItem(
            "li-1", "li-1", null, null, "srv", "https://example.invalid", "user", "book",
            "$dir/li-1", LocalFolder("internal-book", "F", "", "", "", "internal", "book"),
            "T", "T", book(), mutableListOf()
    )
    downloadItemManager.downloadItemQueue.add(existing)
    val call = callFor("li-1")

    plugin.downloadLibraryItem(call)

    assertFalse("must not call the server for an id already in the queue", enqueued.isCaptured)
  }

  @Test
  fun `downloadLibraryItem creates a new internal LocalFolder when none exists for internal downloads`() {
    val track = audioTrack(1, "track1.mp3")
    val item = libraryItem(media = book(tracks = mutableListOf(track))).apply { id = "li-1" }
    stubServerItem(item)
    assertNull(DeviceManager.dbManager.getLocalFolder("internal-book"))

    plugin.downloadLibraryItem(callFor("li-1", localFolderId = ""))

    assertTrue(enqueued.isCaptured)
    assertTrue(DeviceManager.dbManager.getLocalFolder("internal-book") != null)
  }
}
