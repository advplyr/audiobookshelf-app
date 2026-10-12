package com.audiobookshelf.app.server

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOutcomeTest {
  @Test
  fun successAndUnreadableBodyAreApplied() {
    assertEquals(SyncOutcome.APPLIED, syncOutcomeOf(null, false))
    assertEquals(SyncOutcome.APPLIED, syncOutcomeOf(RESPONSE_BODY_READ_FAILED, false))
  }

  @Test
  fun rejectedOrUnsentIsNotApplied() {
    assertEquals(SyncOutcome.NOT_APPLIED, syncOutcomeOf("Unexpected code 401", true))
    assertEquals(SyncOutcome.NOT_APPLIED, syncOutcomeOf("Request cancelled", true))
  }

  @Test
  fun onlyClientErrorsProveTheSyncWasNotApplied() {
    assertTrue(isRejectedBeforeApplying(401))
    assertTrue(isRejectedBeforeApplying(404))
    // The server or a proxy can fail after the update was saved
    assertFalse(isRejectedBeforeApplying(500))
    assertFalse(isRejectedBeforeApplying(502))
    assertFalse(isRejectedBeforeApplying(504))
    assertEquals(SyncOutcome.UNKNOWN, syncOutcomeOf("Unexpected code 502", false))
  }

  @Test
  fun failureAfterSendingIsUnknown() {
    // e.g. timeout or dropped connection: the server may have applied it, so its listening time
    // must not be resent
    assertEquals(SyncOutcome.UNKNOWN, syncOutcomeOf("Failed to connect", false))
  }

  @Test
  fun onlyConnectionSetupFailuresAreBeforeSending() {
    assertTrue(ConnectException().isBeforeRequestSent())
    assertTrue(UnknownHostException().isBeforeRequestSent())
    assertFalse(SocketTimeoutException().isBeforeRequestSent())
    assertFalse(InterruptedIOException("timeout").isBeforeRequestSent())
  }
}
