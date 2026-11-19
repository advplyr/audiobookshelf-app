package com.audiobookshelf.app.managers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loads a download queue saved by app 0.14.2 (`upgrade/0.14.2/li_upgrade.pt`, written by the
 * 0.14.2 models through Paper) and checks the current code restores it and resumes the partial
 * file instead of crashing or starting over. The fixture's part paths live under
 * [FIXTURE_ROOT]: a 1000-byte track with 600 bytes staged, and a finished 50-byte cover.
 *
 * To regenerate it, check out the 0.14.2 release (609f9d6) and run a Robolectric test there that
 * builds the same DownloadItem with the 0.14.2 constructors, saves it with
 * `DbManager().saveDownloadItem(item)`, and copies `filesDir/downloadItems/li_upgrade.pt` here.
 * It must be written by the old models: saving it with the current ones would not test an upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueUpgradeTest {
  private lateinit var context: Application
  private var manager: DownloadItemManager? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    File(FIXTURE_ROOT).deleteRecursively()
    Paper.init(context)
    DbManager.initialize(context)
    // Paper caches each book's folder process-wide, so write where it reads rather than filesDir.
    val book = File(Paper.book("downloadItems").path)
    book.deleteRecursively()
    book.mkdirs()
    javaClass.classLoader!!.getResourceAsStream("upgrade/0.14.2/li_upgrade.pt")!!.use { input ->
      File(book, "li_upgrade.pt").outputStream().use { input.copyTo(it) }
    }
  }

  @After
  fun tearDown() {
    manager?.destroy()
    File(FIXTURE_ROOT).deleteRecursively()
  }

  @Test
  fun oldQueueEntryLoadsWithDefaultsForNewFields() {
    val item = DbManager().getDownloadItems().single()
    val track = item.downloadItemParts.first { it.filename == "track1.mp3" }

    assertEquals("li_upgrade", item.id)
    assertNull(item.networkMode)
    assertEquals(600L, track.bytesDownloaded)
    assertEquals(2, track.retryCount)
    assertEquals(0L, track.nextAttemptAt)
    assertNull(track.etag)
    assertNull(track.lastModified)
    assertFalse(track.waitingForNetwork)
    assertFalse(track.waitingForWifi)
  }

  @Test
  fun restoreResumesThePartialFileInsteadOfDeletingIt() {
    val stagedTrack = File("$FIXTURE_ROOT/staging/track1.mp3.part").apply {
      parentFile!!.mkdirs()
      writeBytes(ByteArray(600) { 1 })
    }
    File("$FIXTURE_ROOT/downloads/cover.jpg").apply {
      parentFile!!.mkdirs()
      writeBytes(ByteArray(50) { 2 })
    }

    val restored = mutableListOf<DownloadItem>()
    manager = DownloadItemManager(FolderScanner(context), context, Emitter(restored)).also { it.restoreQueue() }

    val item = restored.single()
    val track = item.downloadItemParts.first { it.filename == "track1.mp3" }
    val cover = item.downloadItemParts.first { it.filename == "cover.jpg" }
    assertTrue("staging file must survive the upgrade", stagedTrack.isFile)
    assertEquals(600L, stagedTrack.length())
    assertEquals(600L, track.bytesDownloaded)
    assertEquals(60L, track.progress)
    assertEquals(0, track.retryCount)
    assertNull(track.downloadId)
    assertFalse(track.failed)
    assertFalse(track.completed)
    assertTrue(cover.moved)
    assertTrue(manager!!.hasWork())

    // The plugin serializes parts with Jackson for the frontend; the new fields must survive it.
    val json = JSObject(jacksonObjectMapper().writeValueAsString(track))
    assertEquals("queued", json.getString("status"))
    assertEquals(0L, json.getLong("nextAttemptAt"))
  }

  @Test
  fun missingStagingFileRestartsFromZero() {
    val restored = mutableListOf<DownloadItem>()
    manager = DownloadItemManager(FolderScanner(context), context, Emitter(restored)).also { it.restoreQueue() }

    val track = restored.single().downloadItemParts.first { it.filename == "track1.mp3" }
    assertEquals(0L, track.bytesDownloaded)
    assertEquals(0L, track.progress)
    assertFalse(track.failed)
  }

  private class Emitter(private val restored: MutableList<DownloadItem>) : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) {
      restored.add(downloadItem)
    }

    override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}

    override fun onDownloadItemComplete(jsobj: JSObject) {}

    override fun onQueueChanged(hasWork: Boolean) {}
  }

  private companion object {
    const val FIXTURE_ROOT = "/tmp/abs-upgrade-fixture"
  }
}
