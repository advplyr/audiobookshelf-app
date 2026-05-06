package com.audiobookshelf.app.managers

/**
 * Pure decision logic for how to handle a persisted DownloadItemPart on app start.
 * Kept Android-free so it can be unit-tested on the JVM.
 */
internal object DownloadRestoreLogic {
  enum class PartRestoreAction {
    /** Part already finished and was moved to its final destination — skip. */
    AlreadyDone,

    /**
     * External (system DownloadManager) part with a known downloadId. Reattach the watcher;
     * DLM is a system service so the download likely kept progressing across the app's death.
     */
    ResumeExternal,

    /**
     * Drop any partial bytes and re-enqueue from scratch. Used for internal (in-process OkHttp)
     * downloads that died with the app, and for external parts that never got an enqueue id.
     */
    ResetAndReenqueue
  }

  fun classifyPart(
    isInternalStorage: Boolean,
    downloadId: Long?,
    completed: Boolean,
    moved: Boolean,
    failed: Boolean
  ): PartRestoreAction {
    // A failed part is never "done" — always retry from scratch.
    if (failed) return PartRestoreAction.ResetAndReenqueue
    // Internal storage writes straight to finalDestinationPath; the SAF-only `moved` flag
    // never gets set, so completion is signaled by `completed` alone.
    if (completed && (moved || isInternalStorage)) return PartRestoreAction.AlreadyDone
    if (isInternalStorage) return PartRestoreAction.ResetAndReenqueue
    if (downloadId != null) return PartRestoreAction.ResumeExternal
    return PartRestoreAction.ResetAndReenqueue
  }
}
