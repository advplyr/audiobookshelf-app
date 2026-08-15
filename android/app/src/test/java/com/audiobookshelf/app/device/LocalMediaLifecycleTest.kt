package com.audiobookshelf.app.device

import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.EBookFile
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaType
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Local media lifecycle - the local-media-lifecycle issue cluster (see `TESTING.md` §10):
 * [#1680](https://github.com/advplyr/audiobookshelf-app/issues/1680),
 * [#1630](https://github.com/advplyr/audiobookshelf-app/issues/1630),
 * [#1392](https://github.com/advplyr/audiobookshelf-app/issues/1392). Reported contract:
 *
 * > *Starting playback during scan, moving a downloaded book, or using a shared-folder copy must
 * > not expose a stale or orphaned local item.*
 *
 * Two of the row's three named cases already have coverage: `DbManagerCleanupTest` covers
 * "missing/moved file removes or refreshes local record" at the file and track level, and
 * `FolderScannerTest` covers a re-download's cover behaviour. This class covers what was left -
 * **item identity across repeated scans** and the *orphaned item* end state that the per-file
 * cleanup leaves behind.
 *
 * The external/SAF half of the identity policy stays blocked (`DocumentFileCompat`), so the
 * internal-storage identity rule is characterized here and the SAF rule is not asserted.
 */
class LocalMediaLifecycleTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var dir: File
  private lateinit var scanner: FolderScanner
  private lateinit var db: DbManager

  @Before
  fun setUp() {
    AbsTestEnvironment.mockLocalFileStatics()
    dir = Files.createTempDirectory("abs-lifecycle-test").toFile()
    scanner = FolderScanner(AbsTestEnvironment.mockContext())
    db = DbManager()
  }

  @After
  fun tearDown() {
    unmockkAll()
    dir.deleteRecursively()
  }

  private fun audioTrack(index: Int, duration: Double = 60.0) =
          AudioTrack(index, 0.0, duration, "t$index.mp3", "", "audio/mpeg", null, false, null, index)

  private fun part(
          id: String,
          filename: String,
          finalPath: String,
          audioTrack: AudioTrack? = null,
          ebookFile: EBookFile? = null
  ) =
          DownloadItemPart(
                  id = id, downloadItemId = "item-1", filename = filename, fileSize = 10,
                  destinationPath = "$finalPath.part", finalDestinationPath = finalPath,
                  serverPath = "/api/items/x/file", localFolderName = "F", localFolderUrl = "",
                  localFolderId = "internal-1", ebookFile = ebookFile, audioTrack = audioTrack,
                  episode = null, completed = true, moved = true, isMoving = false, failed = false,
                  uri = mockk(relaxed = true), destinationUri = mockk(relaxed = true),
                  finalDestinationUri = mockk(relaxed = true), completedDestinationUri = null,
                  finalDestinationSubfolder = "s", downloadId = null, lastUpdateTime = null,
                  progress = 0, bytesDownloaded = 0
          )

  private fun downloadItem(
          libraryItemId: String = "li-1",
          serverConnectionConfigId: String = "srv-a",
          media: MediaType = book(),
          vararg parts: DownloadItemPart
  ) =
          DownloadItem(
                  id = "item-1", libraryItemId = libraryItemId, episodeId = null,
                  userMediaProgress = null, serverConnectionConfigId = serverConnectionConfigId,
                  serverAddress = "https://x", serverUserId = "u", mediaType = "book",
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

  // --- Identity across repeated scans ----------------------------------------------------------

  @Test
  fun `re-scanning the same download yields one item with a stable id, not a duplicate`() {
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)))

    val first = scanSync(downloadItem(parts = parts))!!
    db.saveLocalLibraryItem(first.localLibraryItem)
    val second = scanSync(downloadItem(parts = parts))!!
    db.saveLocalLibraryItem(second.localLibraryItem)

    assertEquals("local_li-1", first.localLibraryItem.id)
    assertEquals(first.localLibraryItem.id, second.localLibraryItem.id)
    assertEquals(1, db.getLocalLibraryItems().size)
  }

  @Test
  fun `re-scanning does not accumulate duplicate tracks on the existing item`() {
    // The scan/play-interleaving symptom in #1680: a second scan of an already-scanned item must
    // replace its file list rather than append to it, or the book grows a duplicate of every track.
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)))

    val first = scanSync(downloadItem(parts = parts))!!
    db.saveLocalLibraryItem(first.localLibraryItem)
    val second = scanSync(downloadItem(parts = parts))!!

    assertEquals(1, second.localLibraryItem.localFiles.size)
    assertEquals(1, (second.localLibraryItem.media as Book).tracks?.size)
  }

  /**
   * Characterization of the internal-storage identity policy, and the mechanism behind #1392's
   * "shared-folder copy" report.
   *
   * `scanDownloadItem` derives the local id as `"local_${item.libraryItemId}"`
   * (`FolderScanner.kt:280`) and nothing else - **not** the server connection. Two servers that
   * hand out the same library item id (a restored backup, a cloned library, two users of one
   * shared media folder) therefore map to a single local record, and the second download silently
   * takes over the first's item, including its server address and user id.
   *
   * Recorded rather than asserted as a defect: one device connected to one server is the ordinary
   * case, and changing the id scheme would orphan every record already on disk. What matters is
   * that the collision is documented, because it is invisible in the code and looks like data loss
   * from the outside.
   */
  @Test
  fun `two servers sharing a library item id collide on one local record`() {
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)))

    val fromServerA = scanSync(downloadItem(serverConnectionConfigId = "srv-a", parts = parts))!!
    db.saveLocalLibraryItem(fromServerA.localLibraryItem)
    val fromServerB = scanSync(downloadItem(serverConnectionConfigId = "srv-b", parts = parts))!!
    db.saveLocalLibraryItem(fromServerB.localLibraryItem)

    assertEquals(fromServerA.localLibraryItem.id, fromServerB.localLibraryItem.id)
    assertEquals(1, db.getLocalLibraryItems().size)
  }

  // --- The orphaned end state -----------------------------------------------------------------

  /**
   * **Newly found.** The end state the per-file cleanup leaves behind.
   *
   * Inputs: a downloaded book whose audio files have all been deleted from disk (the user cleared
   * storage, moved the folder, or an SD card was removed - #1630's report), followed by
   * `DbManager.cleanLocalLibraryItems`.
   *
   * Expected: the record is removed, or at least marked unplayable. An entry the user can select
   * and that then cannot play is exactly the "stale or orphaned local item" this row is about.
   *
   * Observed: the item survives with **zero** local files and **zero** tracks.
   * `cleanLocalLibraryItems` filters `localFiles`, then filters `tracks` to those with a surviving
   * local file (`DbManager.kt:157-200`), and only ever *deletes* an item when
   * `serverConnectionConfigId == null` (`DbManager.kt:222`) - the unrelated legacy-local-item
   * rule. A server-linked item that has lost every file is written back, emptied, and still
   * appears in `getLocalLibraryItems()`, the library UI, and the Android Auto browse tree.
   *
   * The information needed to decide is already computed: `checkHasTracks()` returns false for
   * exactly this state.
   */
  @Test
  fun `an item that has lost every audio file must not survive as an empty record`() {
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val scanned =
            scanSync(
                    downloadItem(
                            parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)))
                    )
            )!!
    db.saveLocalLibraryItem(scanned.localLibraryItem)
    assertNotNull("guard: the item must exist before its files are removed", db.getLocalLibraryItem("local_li-1"))

    track.delete()
    db.cleanLocalLibraryItems(AbsTestEnvironment.mockContext())

    val survivor = db.getLocalLibraryItem("local_li-1")
    assertTrue(
            "an item with no files and no tracks is unplayable and must not remain selectable; " +
                    "it survived with ${survivor?.localFiles?.size} files and " +
                    "${(survivor?.media as? Book)?.tracks?.size} tracks",
            survivor == null || survivor.media.checkHasTracks()
    )
  }

  @Test
  fun `an item that still has its files is left alone by the cleanup`() {
    // The guard for the spec above: a fix must not start deleting healthy items.
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val scanned =
            scanSync(
                    downloadItem(
                            parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)))
                    )
            )!!
    db.saveLocalLibraryItem(scanned.localLibraryItem)

    db.cleanLocalLibraryItems(AbsTestEnvironment.mockContext())

    val survivor = db.getLocalLibraryItem("local_li-1")
    assertNotNull(survivor)
    assertEquals(1, survivor!!.localFiles.size)
  }

  /**
   * The other guard on the spec above, and the one that matters most: an ebook-only download has
   * **no audio tracks at all**, legitimately and by design. `FolderScanner` rejects a download only
   * when it found neither tracks *nor* an ebook, and `Book.checkHasTracks()` counts `tracks` alone,
   * so it is `false` for every ebook the user has ever downloaded.
   *
   * A cleanup that removes items on `checkHasTracks()` being false therefore deletes the entire
   * ebook library - and because `cleanLocalLibraryItems` is called from `AbsDatabase.load()`, it
   * does so on the next app start, with no user action and nothing to undo it. This spec exists to
   * make that failure mode impossible to reintroduce quietly.
   */
  @Test
  fun `an ebook-only item survives cleanup even though it has no audio tracks`() {
    val ebook = File(dir, "b.epub").apply { writeBytes(ByteArray(4096)) }
    val scanned =
            scanSync(
                    downloadItem(
                            parts = arrayOf(
                                    part(
                                            "p1", "b.epub", ebook.absolutePath,
                                            ebookFile = EBookFile("ino", null, "epub", false, null, null)
                                    )
                            )
                    )
            )!!
    db.saveLocalLibraryItem(scanned.localLibraryItem)
    assertTrue(
            "guard: this fixture must really have no audio tracks, or it proves nothing",
            !scanned.localLibraryItem.media.checkHasTracks()
    )

    db.cleanLocalLibraryItems(AbsTestEnvironment.mockContext())

    val survivor = db.getLocalLibraryItem("local_li-1")
    assertNotNull("a downloaded ebook must not be deleted for having no audio tracks", survivor)
    assertNotNull(
            "the ebook file reference must survive with it",
            (survivor!!.media as Book).ebookFile
    )
  }

  /**
   * The converse: once the ebook's own file is gone, the item really has lost everything and must
   * not linger as an empty, still-selectable entry - the same contract the audio case asserts.
   * Without this, preserving ebook items would just move the orphaned-entry bug rather than fix it.
   */
  @Test
  fun `an ebook item whose file is gone does not survive as an empty record`() {
    val ebook = File(dir, "b.epub").apply { writeBytes(ByteArray(4096)) }
    val scanned =
            scanSync(
                    downloadItem(
                            parts = arrayOf(
                                    part(
                                            "p1", "b.epub", ebook.absolutePath,
                                            ebookFile = EBookFile("ino", null, "epub", false, null, null)
                                    )
                            )
                    )
            )!!
    db.saveLocalLibraryItem(scanned.localLibraryItem)

    ebook.delete()
    db.cleanLocalLibraryItems(AbsTestEnvironment.mockContext())

    val survivor = db.getLocalLibraryItem("local_li-1")
    assertTrue(
            "an item with no tracks and no ebook file left is unplayable and must not remain " +
                    "selectable; it survived with ${(survivor?.media as? Book)?.ebookFile}",
            survivor == null || (survivor.media as Book).ebookFile != null
    )
  }

  @Test
  fun `progress for an item whose files are gone is not silently discarded by the item cleanup`() {
    // Losing the files must not lose the listening position: the item can be re-downloaded, and
    // cleanLocalMediaProgress is the only thing entitled to remove progress records.
    val track = File(dir, "t.mp3").apply { writeBytes(ByteArray(2048)) }
    val scanned =
            scanSync(
                    downloadItem(
                            parts = arrayOf(part("p1", "t.mp3", track.absolutePath, audioTrack = audioTrack(1)))
                    )
            )!!
    db.saveLocalLibraryItem(scanned.localLibraryItem)
    db.saveLocalMediaProgress(
            com.audiobookshelf.app.data.LocalMediaProgress(
                    "local_li-1", "local_li-1", null, 60.0, 0.5, 30.0, false, null, null, 1_000L, 0,
                    null, "srv-a", "https://x", "u", "li-1", null
            )
    )

    track.delete()
    db.cleanLocalLibraryItems(AbsTestEnvironment.mockContext())

    assertEquals(30.0, db.getLocalMediaProgress("local_li-1")!!.currentTime, 0.0)
  }
}
