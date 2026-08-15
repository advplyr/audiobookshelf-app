package com.audiobookshelf.app.managers

import android.net.Uri
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `DownloadItemManager` owns the app-managed download queue - user-visible data loss territory,
 * and once the largest completely untested reachable class in the app.
 *
 * Test-environment fact this suite relies on (verified empirically, see
 * `TESTING.md` §6): `android.os.StatFs`
 * constructs without throwing under the mockable `android.jar`, but `totalBytes`/`availableBytes`
 * both read `0`. That makes `tryReserve()`'s space check always fail, so `checkUpdateDownloadQueue`
 * can never reach `startDownload()` (no real network I/O risk from these tests) - a part that is
 * neither completed nor failed always lands in the `waitingForSpace = true` branch instead.
 *
 * That "pending" state also means `hasWork()` is true, which starts the background watcher
 * coroutine (`startWatchingDownloads`, a `while(true)` loop on a real dispatcher). `destroy()` in
 * `@After` is mandatory for every test, not just cleanup hygiene - without it a pending part
 * leaves a live polling loop running for the rest of the test JVM's life.
 */
class DownloadItemManagerTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var folderScanner: FolderScanner
  private lateinit var emitter: DownloadItemManager.DownloadEventEmitter
  private lateinit var mgr: DownloadItemManager
  private var nextPartId = 0

  @Before
  fun setUp() {
    // IncompleteDownloadCleanup.schedule/cancel reach WorkManager.getInstance(context), which
    // throws IllegalStateException on a host JVM (WorkManager is never initialized here). Any
    // path touching a terminally-failed item's retry, restore, or the manager's own init{} block
    // goes through this object.
    mockkObject(IncompleteDownloadCleanup)
    every { IncompleteDownloadCleanup.schedule(any(), any()) } returns Unit
    every { IncompleteDownloadCleanup.cancel(any(), any()) } returns Unit
    folderScanner = mockk(relaxed = true)
    emitter = mockk(relaxed = true)
    mgr = DownloadItemManager(folderScanner, AbsTestEnvironment.mockContext(), emitter)
  }

  @After
  fun tearDown() {
    mgr.destroy()
    unmockkObject(IncompleteDownloadCleanup)
  }

  private fun part(
          destinationPath: String = "/tmp/abs-dim-test/${nextPartId++}.part",
          completed: Boolean = false,
          failed: Boolean = false,
          isMoving: Boolean = false,
          moved: Boolean = false,
          downloadId: Long? = null
  ) =
          DownloadItemPart(
                  id = "part-${nextPartId++}", downloadItemId = "item-1", filename = "t.mp3", fileSize = 10,
                  destinationPath = destinationPath, finalDestinationPath = "$destinationPath.final",
                  serverPath = "/api/items/x/file", localFolderName = "F", localFolderUrl = "",
                  localFolderId = "internal-1", ebookFile = null, audioTrack = null, episode = null,
                  completed = completed, moved = moved, isMoving = isMoving, failed = failed,
                  uri = mockk<Uri>(), destinationUri = mockk<Uri>(), finalDestinationUri = mockk<Uri>(),
                  completedDestinationUri = null, finalDestinationSubfolder = "s",
                  downloadId = downloadId, lastUpdateTime = null, progress = 0, bytesDownloaded = 0
          )

  private fun downloadItem(id: String, vararg parts: DownloadItemPart) =
          DownloadItem(
                  id = id, libraryItemId = "lib-1", episodeId = null, userMediaProgress = null,
                  serverConnectionConfigId = "srv", serverAddress = "https://example.invalid",
                  serverUserId = "u", mediaType = "book", itemFolderPath = "/tmp/abs-dim-test",
                  localFolder = LocalFolder("internal-1", "F", "", "", "/tmp/abs-dim-test", "internal", "book"),
                  itemTitle = "T", itemSubfolder = "s",
                  media = MediaType(MediaTypeMetadata("T", false), null),
                  downloadItemParts = parts.toMutableList()
          )

  // --- addDownloadItem ---

  @Test
  fun `addDownloadItem adds a new item and notifies the emitter`() {
    val item = downloadItem("item-1", part(completed = true, moved = true))

    mgr.addDownloadItem(item)

    assertEquals(1, mgr.downloadItemQueue.size)
    verify { emitter.onDownloadItem(item) }
  }

  @Test
  fun `addDownloadItem ignores a duplicate id instead of adding it twice`() {
    val item = downloadItem("item-1", part(completed = true, moved = true))
    mgr.addDownloadItem(item)

    mgr.addDownloadItem(downloadItem("item-1", part(completed = true, moved = true)))

    assertEquals(1, mgr.downloadItemQueue.size)
  }

  @Test
  fun `addDownloadItem retries a terminally-failed item by resetting its failed parts`() {
    val failedPart = part(failed = true).apply { retryCount = 3; downloadId = 99L }
    val item = downloadItem("item-1", failedPart).apply { terminalFailureAt = 123_456L }
    mgr.addDownloadItem(item) // first add: no existing item yet, added as-is

    mgr.addDownloadItem(downloadItem("item-1", part(failed = true))) // triggers the retry path

    val retried = mgr.downloadItemQueue.single()
    assertNull("terminalFailureAt should be cleared on retry", retried.terminalFailureAt)
    val retriedPart = retried.downloadItemParts.single()
    assertFalse(retriedPart.failed)
    assertFalse(retriedPart.completed)
    assertNull(retriedPart.downloadId)
    assertEquals(0, retriedPart.retryCount)
  }

  @Test
  fun `addDownloadItem retry only resets parts that were failed`() {
    val healthyPart = part(completed = true, moved = true)
    val failedPart = part(failed = true).apply { downloadId = 42L }
    val item = downloadItem("item-1", healthyPart, failedPart).apply { terminalFailureAt = 1L }
    mgr.addDownloadItem(item)

    mgr.addDownloadItem(downloadItem("item-1", part(failed = true)))

    val retried = mgr.downloadItemQueue.single()
    val stillCompleted = retried.downloadItemParts.find { it.id == healthyPart.id }!!
    assertTrue("a part that was never failed should be untouched by retry", stillCompleted.completed)
    assertTrue(stillCompleted.moved)
  }

  // --- hasWork() ---

  @Test
  fun `hasWork is false when every part is completed and moved`() {
    mgr.addDownloadItem(downloadItem("item-1", part(completed = true, moved = true)))

    assertFalse(mgr.hasWork())
  }

  @Test
  fun `hasWork is false when the only part has permanently failed`() {
    mgr.addDownloadItem(downloadItem("item-1", part(failed = true)))

    assertFalse(mgr.hasWork())
  }

  @Test
  fun `hasWork is true for a part that is neither completed nor failed`() {
    mgr.addDownloadItem(downloadItem("item-1", part()))

    assertTrue(mgr.hasWork())
  }

  @Test
  fun `hasWork is true for a completed part that is still moving`() {
    mgr.addDownloadItem(downloadItem("item-1", part(completed = true, isMoving = true)))

    assertTrue(mgr.hasWork())
  }

  // --- cancelAll ---

  @Test
  fun `cancelAll deletes staging files and clears the queue and db records`() {
    val stagingFile = File.createTempFile("abs-dim-cancel", ".part")
    stagingFile.deleteOnExit()
    assertTrue(stagingFile.exists())
    val item = downloadItem("item-1", part(destinationPath = stagingFile.absolutePath))
    mgr.addDownloadItem(item)
    assertEquals(1, DeviceManager.dbManager.getDownloadItems().size)

    mgr.cancelAll()

    assertFalse("cancelAll should delete the staging file", stagingFile.exists())
    assertTrue(mgr.downloadItemQueue.isEmpty())
    assertFalse(mgr.hasWork())
    assertTrue(DeviceManager.dbManager.getDownloadItems().isEmpty())
  }

  // --- restoreQueue ---

  @Test
  fun `restoreQueue resets transient part state and recomputes bytesDownloaded from disk`() {
    val stagingFile = File.createTempFile("abs-dim-restore", ".part")
    stagingFile.deleteOnExit()
    stagingFile.writeBytes(ByteArray(37))
    val part =
            part(destinationPath = stagingFile.absolutePath, isMoving = true, downloadId = 7L)
                    .apply { waitingForSpace = true }
    val db = DbManager()
    db.saveDownloadItem(downloadItem("restored-1", part))

    mgr.restoreQueue()

    val restoredPart = mgr.downloadItemQueue.single().downloadItemParts.single()
    assertNull(restoredPart.downloadId)
    assertFalse(restoredPart.isMoving)
    assertFalse(restoredPart.failed)
    assertFalse(restoredPart.completed)
    // restoreQueue's own reset sets waitingForSpace = false, but it immediately calls
    // checkUpdateDownloadQueue() afterward, which re-marks a reset (now-pending) part as
    // waiting-for-space since tryReserve() always fails in this environment (see class doc) -
    // so the end-to-end observable result is `true`, not the mid-method reset value.
    assertTrue(restoredPart.waitingForSpace)
    assertEquals(37L, restoredPart.bytesDownloaded)
  }

  @Test
  fun `restoreQueue does not touch parts that have already moved`() {
    val movedPart = part(moved = true, downloadId = 55L).apply { isMoving = true }
    val db = DbManager()
    db.saveDownloadItem(downloadItem("restored-2", movedPart))

    mgr.restoreQueue()

    val restoredPart = mgr.downloadItemQueue.single().downloadItemParts.single()
    assertEquals(55L, restoredPart.downloadId)
    assertTrue("a moved part's isMoving flag should be left alone", restoredPart.isMoving)
  }

  @Test
  fun `restoreQueue does not touch failed parts of a terminally-failed item`() {
    val failedPart = part(failed = true, downloadId = 66L)
    val item = downloadItem("restored-3", failedPart).apply { terminalFailureAt = 999L }
    val db = DbManager()
    db.saveDownloadItem(item)

    mgr.restoreQueue()

    val restoredPart = mgr.downloadItemQueue.single().downloadItemParts.single()
    assertEquals(
            "a failed part of a terminally-failed item should be left alone so it isn't retried automatically",
            66L, restoredPart.downloadId
    )
    assertTrue(restoredPart.failed)
  }

  @Test
  fun `restoreQueue does not re-add items already in the queue`() {
    mgr.addDownloadItem(downloadItem("item-1", part(completed = true, moved = true)))

    mgr.restoreQueue()

    assertEquals(1, mgr.downloadItemQueue.size)
  }

  @Test
  fun `restoreQueue routes a fully finished item through checkDownloadItemFinished`() {
    val db = DbManager()
    db.saveDownloadItem(downloadItem("finished-1", part(completed = true, moved = true)))

    mgr.restoreQueue()

    assertEquals(1, mgr.downloadItemQueue.size)
    verify(timeout = 3_000) { folderScanner.scanDownloadItem(any(), any()) }
  }
}
