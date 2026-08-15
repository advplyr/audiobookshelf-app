package com.audiobookshelf.app.managers

import com.audiobookshelf.app.support.RecordingDownloadCallback
import java.io.File
import java.nio.file.Files
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InternalDownloadManagerTest {
  private lateinit var server: MockWebServer
  private lateinit var downloadDirectory: File

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    downloadDirectory = Files.createTempDirectory("abs-download-test").toFile()
  }

  @After
  fun tearDown() {
    server.shutdown()
    downloadDirectory.deleteRecursively()
  }

  @Test
  fun `truncated response is reported as failed and preserves staging file`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("ab"))
    val destination = File(downloadDirectory, "book.part")
    val callback = download(destination, expectedSize = 4)

    callback.awaitCompletion()

    assertTrue(callback.failed)
    assertArrayEquals("ab".toByteArray(), destination.readBytes())
  }

  @Test
  fun `network failure is reported as failed`() {
    server.enqueue(
            MockResponse()
                    .setBody("abcdef")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
    )
    val destination = File(downloadDirectory, "book.part")
    val callback = RecordingDownloadCallback()

    InternalDownloadManager(destination, 6, callback, hasAvailableSpace = { true })
            .download(server.url("/interrupted").toString(), "token")

    callback.awaitCompletion()

    assertTrue(callback.failed)
    assertTrue(destination.exists())
    assertTrue(destination.length() < 6)
  }

  @Test
  fun `network recovery resumes staging file with a range request`() {
    server.enqueue(
            MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 2-3/4")
                    .setBody("cd")
    )
    val destination = File(downloadDirectory, "book.part").apply { writeText("ab") }
    val callback = download(destination, expectedSize = 4)

    callback.awaitCompletion()

    assertFalse(callback.failed)
    assertArrayEquals("abcd".toByteArray(), destination.readBytes())
    assertEquals("bytes=2-", server.takeRequest().getHeader("Range"))
    assertEquals(100L, callback.progressUpdates.last().second)
  }

  @Test
  fun `server ignoring range restarts staging file instead of appending`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("abcd"))
    val destination = File(downloadDirectory, "book.part").apply { writeText("ab") }
    val callback = download(destination, expectedSize = 4)

    callback.awaitCompletion()

    assertFalse(callback.failed)
    assertArrayEquals("abcd".toByteArray(), destination.readBytes())
    assertEquals("bytes=2-", server.takeRequest().getHeader("Range"))
  }

  @Test
  fun `invalid content range fails without overwriting partial data`() {
    server.enqueue(
            MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 1-2/4")
                    .setBody("cd")
    )
    val destination = File(downloadDirectory, "book.part").apply { writeText("ab") }
    val callback = download(destination, expectedSize = 4)

    callback.awaitCompletion()

    assertTrue(callback.failed)
    assertArrayEquals("ab".toByteArray(), destination.readBytes())
  }

  @Test
  fun `range not satisfiable succeeds when staging file is already complete`() {
    server.enqueue(MockResponse().setResponseCode(416))
    val destination = File(downloadDirectory, "book.part").apply { writeText("abcd") }
    val callback = download(destination, expectedSize = 4)

    callback.awaitCompletion()

    assertFalse(callback.failed)
    assertEquals(listOf(4L to 100L), callback.progressUpdates)
  }

  @Test
  fun `http error reports failure exactly once`() {
    server.enqueue(MockResponse().setResponseCode(500))
    val callback = download(File(downloadDirectory, "book.part"), expectedSize = 4)

    callback.awaitCompletion()

    assertTrue(callback.failed)
    assertEquals(1, callback.completionCount)
  }

  @Test
  fun `storage rejection fails without consuming full response`() {
    server.enqueue(MockResponse().setBody("abcd"))
    val destination = File(downloadDirectory, "book.part")
    val callback = download(destination, expectedSize = 4, hasAvailableSpace = { false })

    callback.awaitCompletion()

    assertTrue(callback.failed)
    assertEquals(0L, destination.length())
  }

  @Test
  fun `download request sends token without placing it in url`() {
    server.enqueue(MockResponse().setBody("abcd"))
    val callback = download(File(downloadDirectory, "book.part"), expectedSize = 4)

    callback.awaitCompletion()
    val request = server.takeRequest()

    assertFalse(callback.failed)
    assertEquals("Bearer token", request.getHeader("Authorization"))
    assertEquals("identity", request.getHeader("Accept-Encoding"))
    assertFalse(request.path.orEmpty().contains("token"))
  }

  @Test
  fun `download has no mechanism to carry a server's configured custom headers`() {
    // ServerConnectionConfig.customHeaders exists specifically so users can configure headers a
    // reverse proxy in front of their server requires (see AbsDatabase's ServerConnConfigPayload,
    // and the equivalent ApiHandler gap already covered in ApiHandlerEdgeCaseTest). The gap is
    // worse here than in ApiHandler: DownloadItemManager.startDownload already resolves the
    // active ServerConnectionConfig (`activeConfig`) to pull the auth `token` before calling
    // `.download(serverUrl(item, part), token)`, but never reads `activeConfig.customHeaders` from
    // that same object to forward it - `download(url, token)`'s signature has no parameter for
    // extra headers at all, so there is no way for a caller to supply them even if it tried. Every
    // app-managed download for a server behind a header-requiring proxy is silently sent without
    // it. This is a request-shape gap (the header is simply never asked for), not a wrong value,
    // so it's characterized here as the exact fixed header set `download()` is capable of sending.
    server.enqueue(MockResponse().setBody("abcd"))
    val callback = download(File(downloadDirectory, "book.part"), expectedSize = 4)

    callback.awaitCompletion()
    val request = server.takeRequest()

    // Asserted as "the custom header is absent, and the two headers download() *can* send are
    // present". An earlier version asserted the exact header set including OkHttp's own Host,
    // Connection and User-Agent, which made an OkHttp upgrade a test failure for a reason that
    // has nothing to do with the contract under test.
    assertNull(
            "download() has no parameter through which a custom header could be supplied",
            request.getHeader("X-Proxy-Auth")
    )
    assertEquals("Bearer token", request.getHeader("Authorization"))
    assertEquals("identity", request.getHeader("Accept-Encoding"))
  }

  // --- Transport cases beyond the Range matrix -------------------------------------------------

  /**
   * A redirect on the download URL. Reverse proxies in front of an audiobookshelf server redirect
   * routinely (http->https, a path rewrite), and OkHttp follows them by default - what matters is
   * that the `Authorization` header survives the hop to the *same* host, or every download behind
   * such a proxy 401s.
   */
  @Test
  fun `a same-host redirect is followed with the authorization header intact`() {
    server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/moved/book.mp3")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("abcd"))
    val destination = File(downloadDirectory, "book.part")

    val callback = download(destination, expectedSize = 4)
    callback.awaitCompletion()

    assertFalse(callback.failed)
    assertArrayEquals("abcd".toByteArray(), destination.readBytes())
    server.takeRequest() // the original request
    val followed = server.takeRequest()
    assertEquals("/moved/book.mp3", followed.path)
    assertEquals("Bearer token", followed.getHeader("Authorization"))
  }

  /**
   * A 401 mid-transfer. The token expired between queueing and downloading - a real case for a
   * long queue. The failure must be reported and, critically, the 401's response body must not be
   * left on disk as though it were the file.
   */
  @Test
  fun `a 401 is reported as a failure and its body is not kept as the file`() {
    server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":"invalid token"}""")
    )
    val destination = File(downloadDirectory, "book.part")

    val callback = download(destination, expectedSize = 4)
    callback.awaitCompletion()

    assertTrue("an unauthorized download is not a completed download", callback.failed)
    assertEquals(
            "the error body must not be written to the destination",
            0L,
            destination.length()
    )
  }

  /**
   * The oversize check on the *resume* path. `DownloadIntegrityTest` covers a body longer than
   * expected on a fresh download; resuming adds the staging file's existing length to whatever
   * arrives, so the overflow can come from the sum rather than from the response alone.
   */
  @Test
  fun `a resumed download whose total exceeds the expected size fails`() {
    server.enqueue(
            MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 2-9/4")
                    .setBody("cdefghij") // 2 existing + 8 = 10 bytes for an expected 4
    )
    val destination = File(downloadDirectory, "book.part").apply { writeText("ab") }

    val callback = download(destination, expectedSize = 4)
    callback.awaitCompletion()

    assertTrue("a resume that overshoots the expected size must not report success", callback.failed)
  }

  /**
   * Storage is checked *before* the body is consumed, not after. `storage rejection fails without
   * consuming full response` already asserts the destination stays empty; this pins the stronger
   * property that the space check is consulted at all on a chunked body of unknown length, which
   * is the shape where a late check would already have written the whole file.
   */
  @Test
  fun `storage availability is consulted before a chunked body is written`() {
    server.enqueue(MockResponse().setChunkedBody("abcdefghij", 4))
    val destination = File(downloadDirectory, "book.part")
    var consulted = false

    val callback = RecordingDownloadCallback()
    InternalDownloadManager(
                    destination,
                    0,
                    callback,
                    hasAvailableSpace = {
                      consulted = true
                      false
                    }
            )
            .download(server.url("/download").toString(), "token")
    callback.awaitCompletion()

    assertTrue("hasAvailableSpace must be consulted even when the length is unknown", consulted)
    assertTrue(callback.failed)
    assertEquals(0L, destination.length())
  }

  private fun download(
          destination: File,
          expectedSize: Long,
          hasAvailableSpace: () -> Boolean = { true }
  ): RecordingDownloadCallback {
    val callback = RecordingDownloadCallback()
    InternalDownloadManager(
                    destination,
                    expectedSize,
                    callback,
                    hasAvailableSpace = hasAvailableSpace
            )
            .download(server.url("/download").toString(), "token")
    return callback
  }
}
