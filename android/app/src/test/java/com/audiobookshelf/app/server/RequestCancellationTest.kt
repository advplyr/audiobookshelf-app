package com.audiobookshelf.app.server

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCancellationTest {
  private val client = OkHttpClient()
  private fun newCall() = client.newCall(Request.Builder().url("https://example.invalid/").build())

  @Test
  fun cancelStopsTrackedCall() {
    val cancellation = RequestCancellation()
    val call = newCall()

    assertTrue(cancellation.track(call))
    cancellation.cancel()

    assertTrue(call.isCanceled())
  }

  @Test
  fun callAfterCancelIsNotStarted() {
    val cancellation = RequestCancellation()
    cancellation.cancel()

    // e.g. the retry after a token refresh finishing after the sync timed out
    assertFalse(cancellation.track(newCall()))
  }

  @Test
  fun cancelReachesRetryCall() {
    val cancellation = RequestCancellation()
    val first = newCall()
    val retry = newCall()

    cancellation.track(first)
    cancellation.track(retry)
    cancellation.cancel()

    assertTrue(retry.isCanceled())
  }
}
