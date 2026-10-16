package com.audiobookshelf.app.managers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.paperdb.Paper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the resume protocol against a server that answers the way Express `res.download`
 * does (the audiobookshelf server): 206 for a matching range, 200 when If-Range no longer
 * matches, and 416 with the total size when the range starts at the end of the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InternalDownloadManagerTest {
  private lateinit var server: MockWebServer
  private lateinit var staging: File
  private val content = Random(42).nextBytes(64 * 1024)
  private val etag = "W/\"10000-abc\""
  private val lastModified = "Wed, 15 Oct 2026 10:00:00 GMT"

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    Paper.init(context)
    DbManager.initialize(context)
    server = MockWebServer().apply { start() }
    staging = File(context.cacheDir, "track.mp3.part").apply { delete() }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun freshDownloadWritesFileAndReportsValidators() {
    server.enqueue(full(content))
    val result = download(expectedSize = content.size.toLong())

    assertEquals("ok", result.outcome)
    assertArrayEquals(content, staging.readBytes())
    assertEquals(content.size.toLong(), result.size)
    assertEquals(etag, result.etag)
    assertEquals(lastModified, result.lastModified)
    val request = server.takeRequest()
    assertNull(request.getHeader("Range"))
    assertEquals("Bearer token", request.getHeader("Authorization"))
  }

  @Test
  fun resumesFromStagedBytesWithIfRange() {
    staging.writeBytes(content.copyOfRange(0, 20_000))
    server.enqueue(partial(content, from = 20_000))
    val result = download(expectedSize = content.size.toLong(), etag = etag)

    assertEquals("ok", result.outcome)
    assertArrayEquals(content, staging.readBytes())
    val request = server.takeRequest()
    assertEquals("bytes=20000-", request.getHeader("Range"))
    assertEquals(etag, request.getHeader("If-Range"))
  }

  @Test
  fun fallsBackToLastModifiedWhenThereIsNoEtag() {
    staging.writeBytes(content.copyOfRange(0, 1_000))
    server.enqueue(partial(content, from = 1_000))
    download(expectedSize = content.size.toLong(), lastModified = lastModified)

    assertEquals(lastModified, server.takeRequest().getHeader("If-Range"))
  }

  @Test
  fun prefersLastModifiedOverAWeakEtag() {
    // If-Range requires strong comparison, so strict proxies would reject the weak ETag.
    staging.writeBytes(content.copyOfRange(0, 1_000))
    server.enqueue(partial(content, from = 1_000))
    download(expectedSize = content.size.toLong(), etag = etag, lastModified = lastModified)

    assertEquals(lastModified, server.takeRequest().getHeader("If-Range"))
  }

  @Test
  fun prefersAStrongEtag() {
    staging.writeBytes(content.copyOfRange(0, 1_000))
    server.enqueue(partial(content, from = 1_000))
    download(expectedSize = content.size.toLong(), etag = "\"strong-1\"", lastModified = lastModified)

    assertEquals("\"strong-1\"", server.takeRequest().getHeader("If-Range"))
  }

  @Test
  fun changedServerFileReplacesThePartialInsteadOfAppending() {
    staging.writeBytes(content.copyOfRange(0, 20_000))
    val replacement = Random(7).nextBytes(50_000)
    // If-Range no longer matches, so the server ignores the range and sends the new file.
    server.enqueue(full(replacement))
    val result = download(expectedSize = content.size.toLong(), etag = etag)

    assertEquals("ok", result.outcome)
    assertArrayEquals(replacement, staging.readBytes())
    assertEquals(replacement.size.toLong(), result.size)
  }

  @Test
  fun rangeAtEndOfFileCompletesWithoutDownloading() {
    staging.writeBytes(content)
    server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${content.size}"))
    val result = download(expectedSize = content.size.toLong(), etag = etag)

    assertEquals("ok", result.outcome)
    assertArrayEquals(content, staging.readBytes())
    assertEquals(1, server.requestCount)
  }

  @Test
  fun invalidRangeRestartsFromZero() {
    // Staged more bytes than the server file has, e.g. the file was replaced by a smaller one.
    staging.writeBytes(Random(1).nextBytes(content.size + 500))
    server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${content.size}"))
    server.enqueue(full(content))
    val result = download(expectedSize = content.size.toLong(), etag = etag)

    assertEquals("ok", result.outcome)
    assertArrayEquals(content, staging.readBytes())
    server.takeRequest()
    val restart = server.takeRequest()
    assertNull(restart.getHeader("Range"))
    assertNull(restart.getHeader("If-Range"))
  }

  @Test
  fun changedTotalSizeWithoutValidatorRestartsFromZero() {
    staging.writeBytes(content.copyOfRange(0, 20_000))
    val grown = content + Random(3).nextBytes(10)
    // No validator was saved, so the server honours the range against the changed file.
    server.enqueue(partial(grown, from = 20_000))
    server.enqueue(full(grown))
    val result = download(expectedSize = content.size.toLong())

    assertEquals("ok", result.outcome)
    assertArrayEquals(grown, staging.readBytes())
    server.takeRequest()
    assertNull(server.takeRequest().getHeader("Range"))
  }

  @Test
  fun droppedConnectionKeepsReceivedBytesForTheNextAttempt() {
    server.enqueue(full(content).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
    val first = download(expectedSize = content.size.toLong())

    assertEquals("failed", first.outcome)
    val received = staging.length()
    assertTrue("expected a partial file, had $received bytes", received in 1 until content.size)

    server.takeRequest()
    server.enqueue(partial(content, from = received.toInt()))
    val second = download(expectedSize = content.size.toLong(), etag = first.etag)

    assertEquals("ok", second.outcome)
    assertArrayEquals(content, staging.readBytes())
    assertEquals("bytes=$received-", server.takeRequest().getHeader("Range"))
  }

  @Test
  fun missingFileIsAPermanentFailure() {
    server.enqueue(MockResponse().setResponseCode(404))
    assertEquals("permanent", download(expectedSize = 10).outcome)
  }

  @Test
  fun forbiddenIsAPermanentFailure() {
    server.enqueue(MockResponse().setResponseCode(403))
    assertEquals("permanent", download(expectedSize = 10).outcome)
  }

  @Test
  fun serverErrorIsRetryable() {
    server.enqueue(MockResponse().setResponseCode(503))
    assertEquals("failed", download(expectedSize = 10).outcome)
  }

  @Test
  fun unauthorizedAsksForATokenRefresh() {
    server.enqueue(MockResponse().setResponseCode(401))
    assertEquals("auth", download(expectedSize = 10).outcome)
  }

  @Test
  fun truncatedResponseIsRetryable() {
    // Content-Length promises more than the body delivers before the connection closes.
    server.enqueue(full(content).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END).setBody(Buffer().write(content.copyOfRange(0, 100))).setHeader("Content-Length", content.size))
    assertEquals("failed", download(expectedSize = content.size.toLong()).outcome)
  }

  private fun full(body: ByteArray) =
          MockResponse()
                  .setResponseCode(200)
                  .setHeader("ETag", etag)
                  .setHeader("Last-Modified", lastModified)
                  .setHeader("Accept-Ranges", "bytes")
                  .setBody(Buffer().write(body))

  private fun partial(body: ByteArray, from: Int) =
          MockResponse()
                  .setResponseCode(206)
                  .setHeader("ETag", etag)
                  .setHeader("Last-Modified", lastModified)
                  .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                  .setBody(Buffer().write(body.copyOfRange(from, body.size)))

  private fun download(expectedSize: Long, etag: String? = null, lastModified: String? = null): Recorder {
    val recorder = Recorder()
    InternalDownloadManager(staging, expectedSize, etag, lastModified, recorder) { true }
            .download(server.url("/api/items/li_1/file/1/download").toString(), "token")
    recorder.await()
    return recorder
  }

  private class Recorder : DownloadItemManager.InternalProgressCallback {
    private val done = CountDownLatch(1)
    @Volatile var outcome: String? = null
    @Volatile var size: Long? = null
    @Volatile var etag: String? = null
    @Volatile var lastModified: String? = null

    override fun onSizeResolved(totalBytes: Long) {
      size = totalBytes
    }

    override fun onValidatorResolved(etag: String?, lastModified: String?) {
      this.etag = etag
      this.lastModified = lastModified
    }

    override fun onProgress(totalBytesWritten: Long, progress: Long) {}

    override fun onComplete(failed: Boolean) = finish(if (failed) "failed" else "ok")

    override fun onPermanentFailure(reason: String) = finish("permanent")

    override fun onAuthError() = finish("auth")

    private fun finish(result: String) {
      outcome = result
      done.countDown()
    }

    fun await() {
      assertTrue("download did not finish", done.await(20, TimeUnit.SECONDS))
    }
  }
}
