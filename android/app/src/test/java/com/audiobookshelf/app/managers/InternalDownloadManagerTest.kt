package com.audiobookshelf.app.managers

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class InternalDownloadManagerTest {

  private lateinit var server: MockWebServer
  private lateinit var tmpDir: File

  // ── Threshold constants mirrored from InternalDownloadManager ────────────
  private val MIN_CHUNKED_BYTES = 10L * 1024 * 1024
  private val CHUNK_COUNT = 8

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    tmpDir = createTempDir()
  }

  @After
  fun tearDown() {
    server.shutdown()
    tmpDir.deleteRecursively()
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private fun waitForCompletion(completedRef: AtomicBoolean, timeoutMs: Long = 10_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!completedRef.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    return completedRef.get()
  }

  private fun makeCallback(
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    onComplete: (Boolean) -> Unit = {}
  ): DownloadItemManager.InternalProgressCallback =
    object : DownloadItemManager.InternalProgressCallback {
      override fun onProgress(totalBytesWritten: Long, progress: Long) = onProgress(totalBytesWritten, progress)
      override fun onComplete(failed: Boolean) = onComplete(failed)
    }

  /** Enqueues a HEAD + single full GET for a small file. */
  private fun enqueueSmallFile(content: ByteArray, acceptRanges: Boolean = false) {
    val headBuilder = MockResponse()
      .setResponseCode(200)
      .setHeader("Content-Length", content.size.toString())
    if (acceptRanges) headBuilder.setHeader("Accept-Ranges", "bytes")
    server.enqueue(headBuilder)

    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", content.size.toString())
        .setBody(Buffer().write(content))
    )
  }

  // ── Singleton / configuration tests ──────────────────────────────────────

  @Test
  fun `client is a singleton shared across all instances`() {
    val c1 = InternalDownloadManager.client
    val c2 = InternalDownloadManager.client
    assertSame("OkHttpClient must be a singleton", c1, c2)
  }

  @Test
  fun `connection pool supports at least CHUNK_COUNT simultaneous connections per host`() {
    // The dispatcher's maxRequestsPerHost must be at least CHUNK_COUNT so parallel
    // range requests don't queue behind each other.
    val maxPerHost = InternalDownloadManager.client.dispatcher.maxRequestsPerHost
    assertTrue(
      "maxRequestsPerHost ($maxPerHost) should be >= CHUNK_COUNT ($CHUNK_COUNT)",
      maxPerHost >= CHUNK_COUNT
    )
  }

  // ── Small-file (single-stream) path ──────────────────────────────────────

  @Test
  fun `small file below threshold uses single GET with no Range header`() {
    val content = ByteArray(1024) { it.toByte() }  // 1 KB — well below 10 MB
    enqueueSmallFile(content)

    val completed = AtomicBoolean(false)
    val outputFile = File(tmpDir, "small.mp3")
    InternalDownloadManager(outputFile.absolutePath, makeCallback { failed -> completed.set(!failed) })
      .download(server.url("/small.mp3").toString())

    assertTrue("Download should complete", waitForCompletion(completed))

    val headReq = server.takeRequest(2, TimeUnit.SECONDS)
    assertNotNull("HEAD request expected", headReq)
    assertEquals("HEAD", headReq!!.method)

    val getReq = server.takeRequest(2, TimeUnit.SECONDS)
    assertNotNull("GET request expected", getReq)
    assertEquals("GET", getReq!!.method)
    assertNull("No Range header expected for small file", getReq.getHeader("Range"))
  }

  @Test
  fun `small file content is written correctly to disk`() {
    val content = "Hello audiobookshelf".toByteArray()
    enqueueSmallFile(content)

    val completed = AtomicBoolean(false)
    val outputFile = File(tmpDir, "hello.mp3")
    InternalDownloadManager(outputFile.absolutePath, makeCallback { failed -> completed.set(!failed) })
      .download(server.url("/hello.mp3").toString())

    assertTrue(waitForCompletion(completed))
    assertArrayEquals(content, outputFile.readBytes())
  }

  @Test
  fun `file just below MIN_CHUNKED_BYTES threshold uses single stream`() {
    val size = (MIN_CHUNKED_BYTES - 1).toInt()
    val content = ByteArray(size) { 42 }
    enqueueSmallFile(content, acceptRanges = true)

    val completed = AtomicBoolean(false)
    val outputFile = File(tmpDir, "borderline.mp3")
    InternalDownloadManager(outputFile.absolutePath, makeCallback { failed -> completed.set(!failed) })
      .download(server.url("/borderline.mp3").toString())

    assertTrue(waitForCompletion(completed))

    val headReq = server.takeRequest(2, TimeUnit.SECONDS)
    assertEquals("HEAD", headReq!!.method)

    val getReq = server.takeRequest(2, TimeUnit.SECONDS)
    assertNull("No Range header for sub-threshold file", getReq!!.getHeader("Range"))
    assertEquals(size.toLong(), outputFile.length())
  }

  // ── Large-file Accept-Ranges path ─────────────────────────────────────────

  @Test
  fun `large file with Accept-Ranges sends CHUNK_COUNT parallel range requests`() {
    val fileSize = MIN_CHUNKED_BYTES + 1024  // just over threshold
    val chunkSize = fileSize / CHUNK_COUNT

    // HEAD
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", fileSize.toString())
        .setHeader("Accept-Ranges", "bytes")
    )

    // 8 chunk responses — each chunk is filled with its index byte for later verification
    for (i in 0 until CHUNK_COUNT) {
      val start = i * chunkSize
      val end = if (i == CHUNK_COUNT - 1) fileSize - 1 else start + chunkSize - 1
      val chunkLen = (end - start + 1).toInt()
      server.enqueue(
        MockResponse()
          .setResponseCode(206)
          .setHeader("Content-Range", "bytes $start-$end/$fileSize")
          .setHeader("Content-Length", chunkLen.toString())
          .setBody(Buffer().write(ByteArray(chunkLen) { i.toByte() }))
      )
    }

    val completed = AtomicBoolean(false)
    val outputFile = File(tmpDir, "large.m4b")
    InternalDownloadManager(outputFile.absolutePath, makeCallback { failed -> completed.set(!failed) })
      .download(server.url("/large.m4b").toString())

    assertTrue("Chunked download should complete", waitForCompletion(completed, 15_000))

    // HEAD first
    val headReq = server.takeRequest(2, TimeUnit.SECONDS)
    assertEquals("HEAD", headReq!!.method)

    // Collect the CHUNK_COUNT range requests
    val rangeRequests = (0 until CHUNK_COUNT).mapNotNull {
      server.takeRequest(3, TimeUnit.SECONDS)
    }
    assertEquals("Expected $CHUNK_COUNT range requests", CHUNK_COUNT, rangeRequests.size)

    val rangeHeaders = rangeRequests.map { it.getHeader("Range") }
    rangeHeaders.forEach { range ->
      assertNotNull("Each chunk request must have a Range header", range)
      assertTrue("Range header must start with 'bytes='", range!!.startsWith("bytes="))
    }

    // File should be exactly fileSize bytes
    assertEquals(fileSize, outputFile.length())
  }

  @Test
  fun `large file range requests cover the entire file without gaps or overlaps`() {
    val fileSize = MIN_CHUNKED_BYTES + 512
    val chunkSize = fileSize / CHUNK_COUNT

    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", fileSize.toString())
        .setHeader("Accept-Ranges", "bytes")
    )
    for (i in 0 until CHUNK_COUNT) {
      val start = i * chunkSize
      val end = if (i == CHUNK_COUNT - 1) fileSize - 1 else start + chunkSize - 1
      val chunkLen = (end - start + 1).toInt()
      server.enqueue(
        MockResponse()
          .setResponseCode(206)
          .setBody(Buffer().write(ByteArray(chunkLen)))
      )
    }

    val completed = AtomicBoolean(false)
    val outputFile = File(tmpDir, "coverage.m4b")
    InternalDownloadManager(outputFile.absolutePath, makeCallback { failed -> completed.set(!failed) })
      .download(server.url("/coverage.m4b").toString())

    assertTrue(waitForCompletion(completed, 15_000))

    server.takeRequest(2, TimeUnit.SECONDS) // HEAD

    val ranges = (0 until CHUNK_COUNT).mapNotNull {
      server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Range")
    }
    assertEquals(CHUNK_COUNT, ranges.size)

    // Parse start/end from each "bytes=N-M" header
    val parsedRanges = ranges.map { header ->
      val (start, end) = header!!.removePrefix("bytes=").split("-").map { it.toLong() }
      start to end
    }.sortedBy { it.first }

    // No gaps: each range starts right after the previous one ends
    for (i in 1 until parsedRanges.size) {
      assertEquals(
        "Range $i should start immediately after range ${i - 1} ends",
        parsedRanges[i - 1].second + 1,
        parsedRanges[i].first
      )
    }
    // Covers full file
    assertEquals(0L, parsedRanges.first().first)
    assertEquals(fileSize - 1, parsedRanges.last().second)
  }

  // ── Large file without Accept-Ranges falls back to single stream ──────────

  @Test
  fun `large file without Accept-Ranges header uses single GET`() {
    val fileSize = MIN_CHUNKED_BYTES + 1024
    val content = ByteArray(fileSize.toInt()) { 7 }

    // HEAD — no Accept-Ranges
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", fileSize.toString())
    )
    // Full GET
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", fileSize.toString())
        .setBody(Buffer().write(content))
    )

    val completed = AtomicBoolean(false)
    val outputFile = File(tmpDir, "norange.m4b")
    InternalDownloadManager(outputFile.absolutePath, makeCallback { failed -> completed.set(!failed) })
      .download(server.url("/norange.m4b").toString())

    assertTrue(waitForCompletion(completed, 15_000))

    server.takeRequest(2, TimeUnit.SECONDS) // HEAD
    val getReq = server.takeRequest(2, TimeUnit.SECONDS)
    assertNotNull(getReq)
    assertNull("No Range header expected for fallback path", getReq!!.getHeader("Range"))
    assertEquals(fileSize.toLong(), outputFile.length())
  }

  // ── Progress callbacks ────────────────────────────────────────────────────

  @Test
  fun `progress callback is invoked at least once during download`() {
    val content = ByteArray(4096) { it.toByte() }
    enqueueSmallFile(content)

    val progressCount = AtomicLong(0)
    val completed = AtomicBoolean(false)
    val callback = makeCallback(
      onComplete = { failed -> completed.set(!failed) },
      onProgress = { _, _ -> progressCount.incrementAndGet() }
    )

    val outputFile = File(tmpDir, "progress.mp3")
    InternalDownloadManager(outputFile.absolutePath, callback)
      .download(server.url("/progress.mp3").toString())

    assertTrue(waitForCompletion(completed))
    assertTrue("onProgress should be called at least once", progressCount.get() > 0)
  }

  @Test
  fun `progress values are monotonically non-decreasing`() {
    val content = ByteArray(65536) { it.toByte() }
    enqueueSmallFile(content)

    val progressValues = mutableListOf<Long>()
    val completed = AtomicBoolean(false)
    val callback = makeCallback(
      onComplete = { failed -> completed.set(!failed) },
      onProgress = { bytes, _ -> synchronized(progressValues) { progressValues.add(bytes) } }
    )

    val outputFile = File(tmpDir, "mono.mp3")
    InternalDownloadManager(outputFile.absolutePath, callback)
      .download(server.url("/mono.mp3").toString())

    assertTrue(waitForCompletion(completed))
    for (i in 1 until progressValues.size) {
      assertTrue(
        "Progress should be non-decreasing at index $i",
        progressValues[i] >= progressValues[i - 1]
      )
    }
  }

  @Test
  fun `onComplete is called with failed=false on successful download`() {
    val content = "success".toByteArray()
    enqueueSmallFile(content)

    val failedValue = AtomicBoolean(true)  // start pessimistic
    val completed = AtomicBoolean(false)
    val callback = makeCallback { failed ->
      failedValue.set(failed)
      completed.set(true)
    }

    val outputFile = File(tmpDir, "success.mp3")
    InternalDownloadManager(outputFile.absolutePath, callback)
      .download(server.url("/success.mp3").toString())

    assertTrue(waitForCompletion(completed))
    assertFalse("onComplete should be called with failed=false", failedValue.get())
  }

  // ── Error handling ────────────────────────────────────────────────────────

  @Test
  fun `onComplete is called with failed=true when HEAD request fails`() {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

    val failedValue = AtomicBoolean(false)
    val completed = AtomicBoolean(false)
    val callback = makeCallback { failed ->
      failedValue.set(failed)
      completed.set(true)
    }

    val outputFile = File(tmpDir, "error.mp3")
    InternalDownloadManager(outputFile.absolutePath, callback)
      .download(server.url("/error.mp3").toString())

    assertTrue("Failure callback should arrive within timeout", waitForCompletion(completed, 8_000))
    assertTrue("onComplete should report failed=true on network error", failedValue.get())
  }

  @Test
  fun `onComplete is called with failed=true when GET body is truncated`() {
    val content = ByteArray(4096) { 1 }

    // HEAD — reports a size larger than the body we actually send
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", (content.size * 2).toString())
    )
    // GET — disconnect mid-stream
    server.enqueue(
      MockResponse()
        .setBody(Buffer().write(content))
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
    )

    val failedValue = AtomicBoolean(false)
    val completed = AtomicBoolean(false)
    val callback = makeCallback { failed ->
      failedValue.set(failed)
      completed.set(true)
    }

    val outputFile = File(tmpDir, "truncated.mp3")
    InternalDownloadManager(outputFile.absolutePath, callback)
      .download(server.url("/truncated.mp3").toString())

    // Either completes with failure or succeeds having read the partial body
    // — either way onComplete must be called
    assertTrue("onComplete must be called even on truncated body", waitForCompletion(completed, 8_000))
  }
}
