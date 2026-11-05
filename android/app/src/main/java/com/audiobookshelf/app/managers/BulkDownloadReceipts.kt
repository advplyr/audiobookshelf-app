package com.audiobookshelf.app.managers

/** Accessed under the download queue lock so completion and enqueue see the same state. */
internal class BulkDownloadReceipts {
  private data class Submission(
          val account: Pair<String, String>,
          val libraryItemIds: Set<String>,
          val completedIds: MutableSet<String> = mutableSetOf()
  )

  private val submissions = mutableMapOf<String, Submission>()

  // Begin before reading offline inventory; end tracking when submission finishes.
  fun begin(id: String, account: Pair<String, String>, libraryItemIds: Set<String>) {
    submissions[id] = Submission(account, libraryItemIds)
  }

  fun end(id: String) {
    submissions.remove(id)
  }

  fun complete(account: Pair<String, String>, libraryItemId: String, downloadId: String) {
    for (submission in submissions.values) {
      if (submission.account == account && libraryItemId in submission.libraryItemIds) {
        submission.completedIds.add(downloadId)
      }
    }
  }

  fun isActive(id: String?, account: Pair<String, String>): Boolean =
          submissions[id]?.account == account

  // Detects completion after the snapshot began so enqueue does not submit the same download again.
  fun contains(id: String?, account: Pair<String, String>, downloadId: String): Boolean {
    val submission = submissions[id] ?: return false
    return submission.account == account && downloadId in submission.completedIds
  }
}
