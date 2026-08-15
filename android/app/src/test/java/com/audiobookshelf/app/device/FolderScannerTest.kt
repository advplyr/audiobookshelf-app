package com.audiobookshelf.app.device

import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.EBookFile
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `FolderScanner.scanDownloadItem`'s internal-storage branch never touches SAF/`DocumentFile` -
 * `createLocalFile` uses plain `java.io.File` for `item.isInternalStorage` items - so, contrary to
 * an earlier assessment, it is fully reachable on the host JVM once three third-party
 * static calls it transitively reaches (`Base64.encodeToString`, `Environment
 * .getExternalStorageDirectory()`, `MimeTypeMap.getSingleton()`) are stubbed. See
 * `TESTING.md` §6 for the full known-null list.
 *
 * This class is also where the reported cover-image crash's root cause lives: the `else ->` branch
 * of `scanParts`'s `when` (a part that is neither an audio track nor an ebook file) adopts any file
 * that merely `exists()` as the cover, including a zero-byte one - see the crash-adjacent test
 * below.
 */
class FolderScannerTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var dir: File
  private lateinit var scanner: FolderScanner
  private lateinit var db: DbManager

  @Before
  fun setUp() {
    AbsTestEnvironment.mockLocalFileStatics()
    dir = Files.createTempDirectory("abs-folderscanner-test").toFile()
    scanner = FolderScanner(AbsTestEnvironment.mockContext())
    db = DbManager()
  }

  @After
  fun tearDown() {
    unmockkAll()
    dir.deleteRecursively()
  }

  private fun part(
          id: String,
          filename: String,
          finalPath: String,
          audioTrack: AudioTrack? = null,
          ebookFile: EBookFile? = null,
          episode: PodcastEpisode? = null
  ) =
          DownloadItemPart(
                  id = id, downloadItemId = "item-1", filename = filename, fileSize = 10,
                  destinationPath = "$finalPath.part", finalDestinationPath = finalPath,
                  serverPath = "/api/items/x/file", localFolderName = "F", localFolderUrl = "",
                  localFolderId = "internal-1", ebookFile = ebookFile, audioTrack = audioTrack,
                  episode = episode, completed = true, moved = true, isMoving = false, failed = false,
                  uri = mockk(relaxed = true), destinationUri = mockk(relaxed = true),
                  finalDestinationUri = mockk(relaxed = true), completedDestinationUri = null,
                  finalDestinationSubfolder = "s", downloadId = null, lastUpdateTime = null,
                  progress = 0, bytesDownloaded = 0
          )

  private fun downloadItem(
          libraryItemId: String = "li-1",
          episodeId: String? = null,
          mediaType: String = "book",
          media: MediaType = book(),
          userMediaProgress: MediaProgress? = null,
          vararg parts: DownloadItemPart
  ) =
          DownloadItem(
                  id = "item-1", libraryItemId = libraryItemId, episodeId = episodeId,
                  userMediaProgress = userMediaProgress, serverConnectionConfigId = "srv",
                  serverAddress = "https://x", serverUserId = "u", mediaType = mediaType,
                  itemFolderPath = dir.absolutePath,
                  localFolder = LocalFolder("internal-1", "F", "", "", dir.absolutePath, "internal", "book"),
                  itemTitle = "T", itemSubfolder = "s", media = media,
                  downloadItemParts = parts.toMutableList()
          )

  private fun scanSync(item: DownloadItem): FolderScanner.DownloadItemScanResult? {
    val latch = CountDownLatch(1)
    var out: FolderScanner.DownloadItemScanResult? = null
    scanner.scanDownloadItem(item) { out = it; latch.countDown() }
    assertTrue("scanDownloadItem's callback was never invoked", latch.await(5, TimeUnit.SECONDS))
    return out
  }

  private fun audioTrack(index: Int, duration: Double = 60.0) =
          AudioTrack(index, 0.0, duration, "t$index.mp3", "", "audio/mpeg", null, false, null, index)

  @Test
  fun `an internal-storage scan builds a new local library item with audio tracks reindexed from 1`() {
    val trackA = File(dir, "b.mp3").apply { writeBytes(ByteArray(2048)) }
    val trackB = File(dir, "a.mp3").apply { writeBytes(ByteArray(1024)) }
    // Deliberately reversed server indices to prove scanParts re-derives index/startOffset by
    // sorted order rather than trusting the manifest's index directly.
    val item = downloadItem(
            parts = arrayOf(
                    part("p-b", "b.mp3", trackA.absolutePath, audioTrack = audioTrack(2, duration = 30.0)),
                    part("p-a", "a.mp3", trackB.absolutePath, audioTrack = audioTrack(1, duration = 20.0))
            )
    )

    val result = scanSync(item)

    assertNotNull(result)
    val tracks = result!!.localLibraryItem.media.getAudioTracks().sortedBy { it.index }
    assertEquals(listOf(1, 2), tracks.map { it.index })
    assertEquals(listOf(0.0, 20.0), tracks.map { it.startOffset })
  }

  @Test
  fun `a scan reuses a previously-persisted local library item instead of creating a new one`() {
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val item = downloadItem(parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1))))

    val first = scanSync(item)!!
    val persistedId = first.localLibraryItem.id
    assertEquals(persistedId, db.getLocalLibraryItem(persistedId)?.id)

    // Second scan of the same libraryItemId must resolve the same persisted record, not fabricate
    // a fresh one (which would orphan the first).
    val second = scanSync(item)!!

    assertEquals(persistedId, second.localLibraryItem.id)
  }

  @Test
  fun `a part whose file never landed on disk is skipped without failing the whole scan`() {
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val missing = File(dir, "ghost.mp3") // never written
    val item = downloadItem(
            parts = arrayOf(
                    part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)),
                    part("p-ghost", "ghost.mp3", missing.absolutePath, audioTrack = audioTrack(2))
            )
    )

    val result = scanSync(item)

    assertNotNull(result)
    assertEquals(1, result!!.localLibraryItem.media.getAudioTracks().size)
  }

  @Test
  fun `an ebook part sets the book's ebookFile`() {
    val ebook = File(dir, "book.epub").apply { writeBytes(ByteArray(4096)) }
    val item = downloadItem(
            media = book(tracks = mutableListOf()),
            parts = arrayOf(
                    part("p-ebook", "book.epub", ebook.absolutePath,
                            ebookFile = EBookFile("ino", null, "epub", false, null, null))
            )
    )

    val result = scanSync(item)

    assertNotNull(result)
    val savedBook = result!!.localLibraryItem.media as Book
    assertNotNull(savedBook.ebookFile)
    assertEquals("epub", savedBook.ebookFile?.ebookFormat)
  }

  @Test
  fun `a podcast episode audio part is added to the podcast's episode list`() {
    val track = File(dir, "ep1.mp3").apply { writeBytes(ByteArray(2048)) }
    val podcast = Podcast(PodcastMetadata("Cast", null, null, mutableListOf(), false), null, mutableListOf(), null, false, 0)
    val episode = PodcastEpisode(
            "ep-server-1", 1, null, null, "Episode 1", null, null, null, null, null, null, null, 60.0, null, null, null
    )
    val item = downloadItem(
            mediaType = "podcast", media = podcast, episodeId = "ep-server-1",
            parts = arrayOf(part("p-ep", "ep1.mp3", track.absolutePath, audioTrack = audioTrack(1), episode = episode))
    )

    val result = scanSync(item)

    assertNotNull(result)
    val savedPodcast = result!!.localLibraryItem.media as Podcast
    assertEquals(1, savedPodcast.episodes?.size)
    assertEquals("Episode 1", savedPodcast.episodes?.first()?.title)
  }

  @Test
  fun `userMediaProgress for an episode is saved under the composed local-item-and-episode id`() {
    val track = File(dir, "ep1.mp3").apply { writeBytes(ByteArray(2048)) }
    val podcast = Podcast(PodcastMetadata("Cast", null, null, mutableListOf(), false), null, mutableListOf(), null, false, 0)
    val episode = PodcastEpisode(
            "ep-server-1", 1, null, null, "Episode 1", null, null, null, null, null, null, null, 60.0, null, null, null
    )
    val progress = MediaProgress(
            "server-progress-id", "li-1", "ep-server-1", 60.0, 0.5, 30.0, false, null, null, 1000L, 0L, null
    )
    val item = downloadItem(
            mediaType = "podcast", media = podcast, episodeId = "ep-server-1", userMediaProgress = progress,
            parts = arrayOf(part("p-ep", "ep1.mp3", track.absolutePath, audioTrack = audioTrack(1), episode = episode))
    )

    val result = scanSync(item)

    assertNotNull(result)
    val localItem = result!!.localLibraryItem
    val localEpisodeId = (localItem.media as Podcast).episodes?.first()?.id
    val expectedProgressId = "${localItem.id}-$localEpisodeId"
    assertEquals(expectedProgressId, result.localMediaProgress?.id)
    assertNotNull("the progress record must actually be persisted, not just returned", db.getLocalMediaProgress(expectedProgressId))
  }

  @Test
  fun `a scan with no audio tracks and no ebook reports null instead of a half-built item`() {
    val cover = File(dir, "cover.jpg").apply { writeBytes(ByteArray(512)) }
    val item = downloadItem(parts = arrayOf(part("p-cover", "cover.jpg", cover.absolutePath)))

    val result = scanSync(item)

    assertNull(result)
  }

  @Test
  fun `does not adopt a zero-byte cover file as coverContentUrl`() {
    // Root cause of the reported cover-image crash (see CoverImageTest): createLocalFile's only
    // guard is File.exists(), and a zero-byte file (truncated download, HTTP error body written to
    // disk) exists. This spec is expected to fail until FolderScanner also checks file length.
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val emptyCover = File(dir, "cover.jpg").apply { writeBytes(ByteArray(0)) }
    val item = downloadItem(
            parts = arrayOf(
                    part("p-audio", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)),
                    part("p-cover", "cover.jpg", emptyCover.absolutePath)
            )
    )

    val result = scanSync(item)

    assertNotNull(result)
    assertNull(
            "a zero-byte cover file must not be adopted as a valid cover",
            result!!.localLibraryItem.coverContentUrl
    )
  }

  /**
   * Characterization. Planned in an earlier pass as "currently inferred, not proven", left
   * unwritten there, and landed here.
   *
   * A re-download of an item that already has a local record, whose cover part this time resolves
   * to nothing (zero-byte, or gone), leaves the **previous scan's `coverContentUrl` in place**:
   * `scanParts` only ever assigns `coverContentUrl` inside the `else ->` branch it reaches when
   * `createLocalFile` returned non-null (`FolderScanner.kt:180-182`), and there is no branch that
   * clears it. The existing record is loaded and mutated, not rebuilt
   * (`scanDownloadItem`, `:281-283`).
   *
   * Whether that is right is genuinely arguable - keeping a working cover from the previous scan is
   * better than blanking it, but it also means a cover that has become unreadable on disk stays
   * referenced. So this is recorded as observed behaviour rather than asserted as a contract; what
   * matters is that a change to it is visible. The inference is now proven either way.
   */
  @Test
  fun `a re-download whose cover fails to resolve keeps the previous scan's cover`() {
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val goodCover = File(dir, "cover.jpg").apply { writeBytes(ByteArray(512)) }
    val firstScan =
            scanSync(
                    downloadItem(
                            parts = arrayOf(
                                    part("p-audio", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)),
                                    part("p-cover", "cover.jpg", goodCover.absolutePath)
                            )
                    )
            )
    val originalCover = firstScan!!.localLibraryItem.coverContentUrl
    assertNotNull("guard: the first scan must actually have adopted a cover", originalCover)
    db.saveLocalLibraryItem(firstScan.localLibraryItem)

    // Re-download into the same item folder; this time the cover part lands as a zero-byte file.
    goodCover.writeBytes(ByteArray(0))
    val secondScan =
            scanSync(
                    downloadItem(
                            parts = arrayOf(
                                    part("p-audio", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)),
                                    part("p-cover", "cover.jpg", goodCover.absolutePath)
                            )
                    )
            )

    assertEquals(
            "the stale cover from the previous scan is retained, not cleared",
            originalCover,
            secondScan!!.localLibraryItem.coverContentUrl
    )
  }

  @Test
  fun `the resulting local library item is persisted to the database`() {
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val item = downloadItem(parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1))))

    val result = scanSync(item)

    assertNotNull(result)
    assertNotNull(db.getLocalLibraryItem(result!!.localLibraryItem.id))
  }
}
