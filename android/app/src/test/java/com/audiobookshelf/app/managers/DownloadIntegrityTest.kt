package com.audiobookshelf.app.managers

import com.audiobookshelf.app.support.RecordingDownloadCallback
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Download integrity - the download-integrity issue cluster (see `TESTING.md` §10):
 * [#1838](https://github.com/advplyr/audiobookshelf-app/issues/1838),
 * [#1827](https://github.com/advplyr/audiobookshelf-app/issues/1827),
 * [#1709](https://github.com/advplyr/audiobookshelf-app/issues/1709),
 * [#1479](https://github.com/advplyr/audiobookshelf-app/issues/1479),
 * [#1428](https://github.com/advplyr/audiobookshelf-app/issues/1428). Reported contract:
 *
 * > *Size unknown/zero, partial transfer, lost network, or a blocked retry must not report false
 * > completion, overflow progress, or lose the queue.*
 *
 * `InternalDownloadManagerTest` already covers partial transfer, lost network, and the Range
 * resume/restart/416 matrix. This class covers the part of the row that was left: **what happens
 * when the expected size is unknown**, which is not a hypothetical - `DownloadItemPart` is created
 * with `fileSize = 0` for cover parts, confirmed from the production side in an earlier pass, and
 * `InternalDownloadManager` uses `expectedSize` as its only integrity check
 * (`InternalDownloadManager.kt:98`).
 *
 * Progress clamping is also pinned here. It is already correct in production
 * (`InternalDownloadManager.kt:91-93`); these are regression guards, not defect specs, because
 * "percentage overflow" is one of the row's named failure modes and it should stay fixed.
 */
class DownloadIntegrityTest {
  private lateinit var server: MockWebServer
  private lateinit var downloadDirectory: File

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    downloadDirectory = Files.createTempDirectory("abs-download-integrity").toFile()
  }

  @After
  fun tearDown() {
    server.shutdown()
    downloadDirectory.deleteRecursively()
  }

  private fun download(destination: File, expectedSize: Long): RecordingDownloadCallback {
    val callback = RecordingDownloadCallback()
    InternalDownloadManager(destination, expectedSize, callback, hasAvailableSpace = { true })
            .download(server.url("/download").toString(), "token")
    return callback
  }

  private fun gzipped(content: String): Buffer {
    val bytes = ByteArrayOutputStream()
    GZIPOutputStream(bytes).use { it.write(content.toByteArray()) }
    return Buffer().write(bytes.toByteArray())
  }

  // --- Unknown / zero expected size ------------------------------------------------------------

  /**
   * Characterization. With `expectedSize = 0` and a chunked response there is no total to divide
   * by, so `totalLength` stays `0` and every progress update reports `0`
   * (`InternalDownloadManager.kt:76-92`). The download itself succeeds and the bytes are correct.
   *
   * The consequence is a cover part that sits at 0% for its whole life and then jumps to done -
   * cosmetic, but it is why a stuck-looking download in these reports is not always a stuck
   * download.
   */
  @Test
  fun `an unknown content length downloads correctly but reports zero percent throughout`() {
    server.enqueue(MockResponse().setChunkedBody("abcdefghij", 4))
    val destination = File(downloadDirectory, "cover.part")

    val callback = download(destination, expectedSize = 0)
    callback.awaitCompletion()

    assertFalse(callback.failed)
    assertArrayEquals("abcdefghij".toByteArray(), destination.readBytes())
    assertTrue(
            "expected every progress update to be 0%, got ${callback.progressUpdates.map { it.second }}",
            callback.progressUpdates.all { it.second == 0L }
    )
  }

  @Test
  fun `a zero expected size with a known content length still reports real progress`() {
    server.enqueue(MockResponse().setBody("abcdefghij"))
    val destination = File(downloadDirectory, "cover.part")

    val callback = download(destination, expectedSize = 0)
    callback.awaitCompletion()

    assertFalse(callback.failed)
    assertEquals(100L, callback.progressUpdates.last().second)
  }

  /**
   * **The row's "must not report false completion", reached through the zero-size path.**
   *
   * Inputs: `expectedSize = 0` (a cover part), and the server answers `200 OK` with an HTML error
   * page instead of the file - the shape a reverse proxy in front of the server produces when it
   * serves its own error or login page, and the most common real deployment of audiobookshelf puts
   * one there.
   *
   * Expected: not a completed download. The part is not the file that was asked for.
   *
   * Observed: `onComplete(failed = false)`. When `expectedSize <= 0` the final integrity check
   * `if (expectedSize > 0L && destinationFile.length() != expectedSize)`
   * (`InternalDownloadManager.kt:98`) is skipped entirely, so **no** validation of any kind runs
   * and any 200 body is accepted. The HTML lands on disk named as the cover and
   * `DownloadItemManager.resolveExternalFile` accepts it too, since its zero-size branch only
   * rejects a zero-length file (`DownloadItemManager.kt:485`).
   *
   * This is the mechanism behind the invalid-cover crash traced from the other end in `CoverImageTest`
   * and `FolderScannerTest`: a zero-byte cover is adopted, and this is how a *non*-empty but
   * non-image cover gets adopted as well.
   */
  @Test
  fun `a zero expected size download must not accept an HTML error page as the file`() {
    val errorPage = "<html><body>502 Bad Gateway</body></html>"
    server.enqueue(
            MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody(errorPage)
    )
    val destination = File(downloadDirectory, "cover.part")

    val callback = download(destination, expectedSize = 0)
    callback.awaitCompletion()

    assertTrue(
            "an HTML body served as the file was reported as a completed download",
            callback.failed
    )
  }

  /**
   * The same missing validation, through the compressed-body case the row names explicitly.
   *
   * Inputs: `expectedSize = 0`, and a proxy that ignores the request's `Accept-Encoding: identity`
   * (`InternalDownloadManager.kt:35`) and answers `Content-Encoding: gzip`. OkHttp only decompresses
   * transparently when *it* added the `Accept-Encoding` header; because production sets that header
   * itself, the gzip stream is handed through verbatim.
   *
   * Expected: the file on disk is the file that was requested.
   *
   * Observed: the compressed bytes are written and the download reports success, so the part is
   * silently corrupt. With a non-zero `expectedSize` the length check catches this; the zero-size
   * parts are unprotected.
   */
  @Test
  fun `a gzip-encoded response must not be written to disk compressed`() {
    val content = "the real cover bytes"
    server.enqueue(
            MockResponse().setHeader("Content-Encoding", "gzip").setBody(gzipped(content))
    )
    val destination = File(downloadDirectory, "cover.part")

    val callback = download(destination, expectedSize = 0)
    callback.awaitCompletion()

    assertArrayEquals(
            "the gzip stream was written verbatim instead of the file's real bytes",
            content.toByteArray(),
            destination.readBytes()
    )
  }

  @Test
  fun `a zero-length body for a zero expected size is still reported as complete`() {
    // Characterization, and the direct cause of the invalid-cover crash chain: nothing in the
    // download layer rejects an empty file. FolderScanner is where the length check is missing on
    // the other side (see FolderScannerTest's zero-byte cover spec).
    server.enqueue(MockResponse().setResponseCode(200).setBody(""))
    val destination = File(downloadDirectory, "cover.part")

    val callback = download(destination, expectedSize = 0)
    callback.awaitCompletion()

    assertFalse(callback.failed)
    assertEquals(0L, destination.length())
  }

  // --- Progress bounds -------------------------------------------------------------------------

  @Test
  fun `progress never exceeds one hundred percent when the body is longer than expected`() {
    server.enqueue(MockResponse().setBody("abcdefghijklmnop")) // 16 bytes for an expected 4
    val destination = File(downloadDirectory, "book.part")

    val callback = download(destination, expectedSize = 4)
    callback.awaitCompletion()

    assertTrue(
            "progress overflowed: ${callback.progressUpdates.map { it.second }}",
            callback.progressUpdates.all { it.second in 0L..100L }
    )
    assertTrue("a body longer than the expected size must not be a success", callback.failed)
  }

  @Test
  fun `progress never goes backwards across a resumed download`() {
    val destination = File(downloadDirectory, "book.part")
    destination.parentFile?.mkdirs()
    destination.writeBytes("abcd".toByteArray())
    server.enqueue(
            MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-9/10")
                    .setBody("efghij")
    )

    val callback = download(destination, expectedSize = 10)
    callback.awaitCompletion()

    assertFalse(callback.failed)
    val reported = callback.progressUpdates.map { it.second }
    assertEquals(reported.sorted(), reported)
    assertEquals(100L, reported.last())
  }

  @Test
  fun `reported bytes always match what is actually on disk`() {
    server.enqueue(MockResponse().setBody("abcdefghij"))
    val destination = File(downloadDirectory, "book.part")

    val callback = download(destination, expectedSize = 10)
    callback.awaitCompletion()

    assertEquals(destination.length(), callback.progressUpdates.last().first)
  }

  // --- Completion cardinality ------------------------------------------------------------------

  @Test
  fun `a download interrupted mid-body reports completion exactly once`() {
    server.enqueue(
            MockResponse()
                    .setBody("abcdefghij")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
    )
    val destination = File(downloadDirectory, "book.part")

    val callback = download(destination, expectedSize = 10)
    callback.awaitCompletion()

    callback.assertNoFurtherCompletion()
    assertTrue(callback.failed)
  }
}
