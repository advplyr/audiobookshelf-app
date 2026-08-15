package com.audiobookshelf.app.models

import android.net.Uri
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadItemTest {
  /**
   * Instance state, not companion state. It was a `companion object var` that no test reset, so it
   * carried across every class in the shared Gradle JVM - harmless here (the ids only have to be
   * distinct within one item) but exactly the shared-mutable-static pattern the rest of this suite
   * exists to police.
   */
  private var nextPartId = 0

  @Test
  fun `download finishes only when every part completed without failure or move`() {
    assertTrue(downloadItem(part(completed = true), part(completed = true)).isDownloadFinished)
    assertFalse(downloadItem(part(completed = true), part(completed = true, failed = true)).isDownloadFinished)
    assertFalse(downloadItem(part(completed = true), part(completed = true, isMoving = true)).isDownloadFinished)
  }

  /**
   * Settles the product rule the Android test plan left open: `isDownloadFinished` is
   * `!parts.any { ... }`, so an item with no parts is vacuously finished, whereas the pre-overhaul
   * branch's test expected an empty item *not* to be finished. The plan asked for that difference
   * to be decided and documented rather than silently ported.
   *
   * Decision: vacuously finished is correct, and this spec pins it. `DownloadItemManager` drives an
   * item to completion by watching this flag, so an empty item that reported "not finished" would
   * sit in the queue forever with nothing left that could ever complete it - a permanently stuck
   * entry. Reporting it finished lets the normal completion path retire it immediately, which is
   * the desired handling of "there was nothing to download".
   */
  @Test
  fun `an item with no parts is finished rather than stuck in the queue`() {
    assertTrue(downloadItem().isDownloadFinished)
  }

  @Test
  fun `next parts skips completed failed and active parts`() {
    val queuedFirst = part()
    val active = part(downloadId = 7)
    val queuedSecond = part()
    val item =
            downloadItem(
                    part(completed = true),
                    part(failed = true),
                    queuedFirst,
                    active,
                    queuedSecond
            )

    assertEquals(listOf(queuedFirst, queuedSecond), item.getNextDownloadItemParts(3))
  }

  @Test
  fun `zero queue limit selects no parts`() {
    val item = downloadItem(part())

    assertTrue(item.getNextDownloadItemParts(0).isEmpty())
  }

  @Test
  fun `negative queue limit selects no parts`() {
    val item = downloadItem(part())

    assertTrue(item.getNextDownloadItemParts(-1).isEmpty())
  }

  private fun downloadItem(vararg parts: DownloadItemPart) =
          DownloadItem(
                  id = "item",
                  libraryItemId = "library-item",
                  episodeId = null,
                  userMediaProgress = null,
                  serverConnectionConfigId = "server",
                  serverAddress = "https://example.invalid",
                  serverUserId = "user",
                  mediaType = "book",
                  itemFolderPath = "/downloads/item",
                  localFolder =
                          LocalFolder(
                                  id = "internal-test",
                                  name = "Test",
                                  contentUrl = "",
                                  basePath = "",
                                  absolutePath = "/downloads",
                                  storageType = "internal",
                                  mediaType = "book"
                          ),
                  itemTitle = "Test item",
                  itemSubfolder = "item",
                  media = MediaType(MediaTypeMetadata("Test item", false), null),
                  downloadItemParts = parts.toMutableList()
          )

  private fun part(
          completed: Boolean = false,
          failed: Boolean = false,
          isMoving: Boolean = false,
          downloadId: Long? = null
  ) =
          DownloadItemPart(
                  id = "part-${nextPartId++}",
                  downloadItemId = "item",
                  filename = "track.mp3",
                  fileSize = 10,
                  destinationPath = "/downloads/item/.track.mp3.part",
                  finalDestinationPath = "/downloads/item/track.mp3",
                  serverPath = "/api/items/item/file",
                  localFolderName = "Test",
                  localFolderUrl = "",
                  localFolderId = "internal-test",
                  ebookFile = null,
                  audioTrack = null,
                  episode = null,
                  completed = completed,
                  moved = completed,
                  isMoving = isMoving,
                  failed = failed,
                  uri = mockk<Uri>(),
                  destinationUri = mockk<Uri>(),
                  finalDestinationUri = mockk<Uri>(),
                  completedDestinationUri = null,
                  finalDestinationSubfolder = "item",
                  downloadId = downloadId,
                  lastUpdateTime = null,
                  progress = if (completed) 100 else 0,
                  bytesDownloaded = if (completed) 10 else 0
          )
}
