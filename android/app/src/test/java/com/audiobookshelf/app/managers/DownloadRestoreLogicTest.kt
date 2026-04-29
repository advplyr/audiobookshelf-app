package com.audiobookshelf.app.managers

import com.audiobookshelf.app.managers.DownloadRestoreLogic.PartRestoreAction
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRestoreLogicTest {
  private fun classify(
    isInternalStorage: Boolean = false,
    downloadId: Long? = null,
    completed: Boolean = false,
    moved: Boolean = false,
    failed: Boolean = false
  ) = DownloadRestoreLogic.classifyPart(isInternalStorage, downloadId, completed, moved, failed)

  @Test
  fun externalPart_completedAndMoved_isAlreadyDone() {
    assertEquals(
      PartRestoreAction.AlreadyDone,
      classify(downloadId = 7L, completed = true, moved = true)
    )
  }

  @Test
  fun internalPart_completed_isAlreadyDone_evenWithoutMovedFlag() {
    // Internal downloads never set `moved` because they write directly to the final path.
    // Treating them as ResetAndReenqueue caused completed parts to be re-downloaded after restore.
    assertEquals(
      PartRestoreAction.AlreadyDone,
      classify(isInternalStorage = true, completed = true)
    )
  }

  @Test
  fun internalPart_inFlight_resetsAndReenqueues() {
    // OkHttp call dies with the process; partial bytes can't be resumed.
    assertEquals(
      PartRestoreAction.ResetAndReenqueue,
      classify(isInternalStorage = true, downloadId = 1L)
    )
  }

  @Test
  fun internalPart_completedButFailed_retries() {
    // OkHttp marks `completed=true` even on transport failure; the `failed` flag separates them.
    assertEquals(
      PartRestoreAction.ResetAndReenqueue,
      classify(isInternalStorage = true, completed = true, failed = true)
    )
  }

  @Test
  fun externalPart_withDownloadId_resumesFromSystemDownloadManager() {
    assertEquals(
      PartRestoreAction.ResumeExternal,
      classify(downloadId = 42L)
    )
  }

  @Test
  fun externalPart_completedButNotMoved_stillResumes() {
    // The download finished but the move callback was killed; let the watcher retry the move.
    assertEquals(
      PartRestoreAction.ResumeExternal,
      classify(downloadId = 42L, completed = true)
    )
  }

  @Test
  fun externalPart_failedRequest_retriesEvenWithDownloadId() {
    assertEquals(
      PartRestoreAction.ResetAndReenqueue,
      classify(downloadId = 42L, failed = true)
    )
  }

  @Test
  fun externalPart_neverEnqueued_resetsAndReenqueues() {
    // Process died between addDownloadItem persisting and processDownloadItemParts running.
    assertEquals(
      PartRestoreAction.ResetAndReenqueue,
      classify()
    )
  }
}
