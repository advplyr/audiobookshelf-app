package com.audiobookshelf.app.models

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadItemPartTest {
  @Test
  fun `part reports internal storage based on folder id prefix`() {
    assertTrue(part(localFolderId = "internal-app").isInternalStorage)
    assertFalse(part(localFolderId = "saf-external").isInternalStorage)
  }

  @Test
  fun `folder id prefix match is case sensitive and requires the exact prefix`() {
    assertFalse(part(localFolderId = "Internal-app").isInternalStorage)
    assertFalse(part(localFolderId = "not-internal-app").isInternalStorage)
  }

  private fun part(localFolderId: String) =
          DownloadItemPart(
                  id = "part",
                  downloadItemId = "item",
                  filename = "track.mp3",
                  fileSize = 10,
                  destinationPath = "/downloads/item/.track.mp3.part",
                  finalDestinationPath = "/downloads/item/track.mp3",
                  serverPath = "/api/items/item/file",
                  localFolderName = "Test",
                  localFolderUrl = "",
                  localFolderId = localFolderId,
                  ebookFile = null,
                  audioTrack = null,
                  episode = null,
                  completed = false,
                  moved = false,
                  isMoving = false,
                  failed = false,
                  uri = mockk<Uri>(),
                  destinationUri = mockk<Uri>(),
                  finalDestinationUri = mockk<Uri>(),
                  completedDestinationUri = null,
                  finalDestinationSubfolder = "item",
                  downloadId = null,
                  lastUpdateTime = null,
                  progress = 0,
                  bytesDownloaded = 0
          )
}
