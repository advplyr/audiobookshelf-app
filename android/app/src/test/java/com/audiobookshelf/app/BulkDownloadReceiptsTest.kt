package com.audiobookshelf.app

import com.audiobookshelf.app.managers.BulkDownloadReceipts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkDownloadReceiptsTest {
  @Test
  fun completionAfterInventoryIsVisibleUntilSubmissionEnds() {
    val receipts = BulkDownloadReceipts()
    val account = "server" to "user"
    val anotherAccount = "server" to "another-user"
    receipts.complete(account, "podcast", "podcast-earlier")
    receipts.begin("bulk", account, setOf("podcast"))
    assertFalse(receipts.contains("bulk", account, "podcast-earlier"))

    receipts.complete(anotherAccount, "podcast", "podcast-foreign")
    receipts.complete(account, "other", "other")
    receipts.complete(account, "podcast", "podcast-episode")
    assertFalse(receipts.contains("bulk", account, "podcast-foreign"))
    assertFalse(receipts.contains("bulk", account, "other"))
    assertTrue(receipts.contains("bulk", account, "podcast-episode"))
    assertFalse(receipts.contains("bulk", anotherAccount, "podcast-episode"))

    receipts.end("bulk")
    assertFalse(receipts.isActive("bulk", account))
    assertFalse(receipts.contains("bulk", account, "podcast-episode"))
  }
}
