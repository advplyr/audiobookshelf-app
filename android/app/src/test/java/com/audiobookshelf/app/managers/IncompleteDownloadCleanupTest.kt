package com.audiobookshelf.app.managers

import android.content.Context
import android.net.Uri
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `IncompleteDownloadCleanup.cleanupExpired` is reachable now that `DeviceManager.dbManager`
 * works under the shared harness, but `deleteItem` unconditionally calls `cancel()`, which reaches
 * `WorkManager.getInstance(context)` and throws on a host JVM - so `IncompleteDownloadCleanup`
 * itself is mocked here the same way `DownloadItemManagerTest` mocks it from the outside.
 */
class IncompleteDownloadCleanupTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager
  private lateinit var context: Context
  private lateinit var appFilesDir: File

  @Before
  fun setUp() {
    db = DbManager()
    appFilesDir = Files.createTempDirectory("abs-idc-test").toFile()
    context = mockk(relaxed = true)
    every { context.filesDir } returns appFilesDir
    every { context.getExternalFilesDir(null) } returns null
    mockkObject(IncompleteDownloadCleanup)
    every { IncompleteDownloadCleanup.cancel(any(), any()) } returns Unit
    every { IncompleteDownloadCleanup.schedule(any(), any()) } returns Unit
  }

  @After
  fun tearDown() {
    unmockkObject(IncompleteDownloadCleanup)
    appFilesDir.deleteRecursively()
  }

  private fun stagingFile(name: String): File {
    val f = File(appFilesDir, name)
    f.writeText("data")
    return f
  }

  private fun part(destinationPath: String, moved: Boolean = false, failed: Boolean = false, isMoving: Boolean = false) =
          DownloadItemPart(
                  id = "part-${destinationPath.hashCode()}", downloadItemId = "item-1", filename = "t.mp3",
                  fileSize = 10, destinationPath = destinationPath, finalDestinationPath = "$destinationPath.final",
                  serverPath = "/api/items/x/file", localFolderName = "F", localFolderUrl = "",
                  localFolderId = "internal-1", ebookFile = null, audioTrack = null, episode = null,
                  completed = moved, moved = moved, isMoving = isMoving, failed = failed,
                  uri = mockk<Uri>(), destinationUri = mockk<Uri>(), finalDestinationUri = mockk<Uri>(),
                  completedDestinationUri = null, finalDestinationSubfolder = "s",
                  downloadId = null, lastUpdateTime = null, progress = 0, bytesDownloaded = 0
          )

  private fun downloadItem(id: String, terminalFailureAt: Long?, vararg parts: DownloadItemPart) =
          DownloadItem(
                  id = id, libraryItemId = "lib-1", episodeId = null, userMediaProgress = null,
                  serverConnectionConfigId = "srv", serverAddress = "https://example.invalid",
                  serverUserId = "u", mediaType = "book", itemFolderPath = appFilesDir.absolutePath,
                  localFolder = LocalFolder("internal-1", "F", "", "", appFilesDir.absolutePath, "internal", "book"),
                  itemTitle = "T", itemSubfolder = "s",
                  media = MediaType(MediaTypeMetadata("T", false), null),
                  downloadItemParts = parts.toMutableList(), terminalFailureAt = terminalFailureAt
          )

  @Test
  fun `items without a terminal failure are left alone`() {
    db.saveDownloadItem(
            downloadItem("item-1", terminalFailureAt = null, part(stagingFile("a.mp3").absolutePath, moved = true))
    )

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertEquals(1, db.getDownloadItems().size)
  }

  @Test
  fun `a recently-failed item is retained until the retention window elapses`() {
    val recentFailure = System.currentTimeMillis() - 1_000L
    db.saveDownloadItem(
            downloadItem("item-1", terminalFailureAt = recentFailure, part(stagingFile("a.mp3").absolutePath, failed = true))
    )

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertEquals(1, db.getDownloadItems().size)
  }

  private val retentionMs = 24L * 60L * 60L * 1000L

  @Test
  fun `an item just inside the 24-hour retention window is retained`() {
    // A few seconds of slack rather than a literal 1ms margin: `now` is re-read inside
    // cleanupExpired, strictly after this line runs, so a true 1ms-from-the-boundary failedAt
    // would flake under real (non-zero) test execution time.
    val justInside = System.currentTimeMillis() - retentionMs + 5_000L
    db.saveDownloadItem(
            downloadItem("item-1", terminalFailureAt = justInside, part(stagingFile("a.mp3").absolutePath, failed = true))
    )

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertEquals(1, db.getDownloadItems().size)
  }

  @Test
  fun `an item exactly 24 hours old is deleted`() {
    val exactlyAtBoundary = System.currentTimeMillis() - retentionMs
    val staging = stagingFile("a.mp3")
    db.saveDownloadItem(
            downloadItem("item-1", terminalFailureAt = exactlyAtBoundary, part(staging.absolutePath, failed = true))
    )

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertTrue(db.getDownloadItems().isEmpty())
  }

  @Test
  fun `an old terminally-failed item with all parts failed is deleted and its staging file removed`() {
    val oldFailure = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
    val staging = stagingFile("a.mp3")
    db.saveDownloadItem(downloadItem("item-1", terminalFailureAt = oldFailure, part(staging.absolutePath, failed = true)))

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertTrue(db.getDownloadItems().isEmpty())
    assertFalse("the staging file should have been deleted", staging.exists())
  }

  @Test
  fun `an old item with a part that is neither moved nor purely failed is not eligible`() {
    val oldFailure = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
    // isMoving = true means the part is mid-transfer, not a clean terminal failure state.
    val stillMoving = part(stagingFile("a.mp3").absolutePath, failed = true, isMoving = true)
    db.saveDownloadItem(downloadItem("item-1", terminalFailureAt = oldFailure, stillMoving))

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertEquals(1, db.getDownloadItems().size)
  }

  @Test
  fun `an old item with all parts moved is deleted and its final internal file removed too`() {
    val oldFailure = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
    val staging = stagingFile("a.mp3")
    val finalFile = File("${staging.absolutePath}.final")
    finalFile.writeText("done")
    db.saveDownloadItem(downloadItem("item-1", terminalFailureAt = oldFailure, part(staging.absolutePath, moved = true)))

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertTrue(db.getDownloadItems().isEmpty())
    assertFalse("the finalized internal file should also be deleted", finalFile.exists())
  }

  @Test
  fun `a file outside the app-owned directory is never deleted`() {
    val oldFailure = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
    val outsideFile = File.createTempFile("abs-idc-outside", ".mp3")
    outsideFile.deleteOnExit()
    db.saveDownloadItem(downloadItem("item-1", terminalFailureAt = oldFailure, part(outsideFile.absolutePath, failed = true)))

    IncompleteDownloadCleanup.cleanupExpired(context)

    assertTrue("the db record should still be removed", db.getDownloadItems().isEmpty())
    assertTrue("but a file outside filesDir/externalFilesDir must not be touched", outsideFile.exists())
  }
}
