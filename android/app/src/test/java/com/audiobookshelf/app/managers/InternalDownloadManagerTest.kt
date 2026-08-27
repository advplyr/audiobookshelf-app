package com.audiobookshelf.app.managers

import java.io.Closeable
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalDownloadManagerTest {
  private var server: TestHttpServer? = null

  @After
  fun tearDown() {
    server?.close()
  }

  @Test
  fun exactKnownStagingFileCompletesWithoutHttpRequest() {
    val destination = Files.createTempFile("abs-complete", ".part").toFile()
    destination.writeBytes(byteArrayOf(1, 2, 3, 4))
    val callback = RecordingCallback()

    InternalDownloadManager(destination, 4L, callback) { true }
            .download("http://127.0.0.1:1/download", "token")

    assertTrue(callback.completed.await(1, TimeUnit.SECONDS))
    assertFalse(callback.failed.get())
    assertEquals(4L, destination.length())
    destination.delete()
  }

  @Test
  fun unknownSizeFullFileIsAcceptedFrom416ContentRange() {
    server = TestHttpServer { _, _ ->
      response(416, headers = listOf("Content-Range: bytes */4"))
    }
    val destination = Files.createTempFile("abs-unknown", ".part").toFile()
    destination.writeBytes(byteArrayOf(1, 2, 3, 4))
    val callback = RecordingCallback()

    InternalDownloadManager(destination, 0L, callback) { true }
            .download(server!!.url, "token")

    assertTrue(callback.completed.await(3, TimeUnit.SECONDS))
    assertFalse(callback.failed.get())
    assertEquals("bytes=4-", server!!.requests.single()["range"])
    destination.delete()
  }

  @Test
  fun partialFileResumesWithRangeDuringLiveRetry() {
    server = TestHttpServer { _, _ ->
      response(206, byteArrayOf(3, 4), listOf("Content-Range: bytes 2-3/4"))
    }
    val destination = Files.createTempFile("abs-partial", ".part").toFile()
    destination.writeBytes(byteArrayOf(1, 2))
    val callback = RecordingCallback()

    InternalDownloadManager(destination, 4L, callback) { true }
            .download(server!!.url, "token")

    assertTrue(callback.completed.await(3, TimeUnit.SECONDS))
    assertFalse(callback.failed.get())
    assertEquals("bytes=2-", server!!.requests.single()["range"])
    assertTrue(destination.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    destination.delete()
  }

  @Test
  fun stale416RestartsOnceFromByteZero() {
    server = TestHttpServer { index, _ ->
      if (index == 0) response(416, headers = listOf("Content-Range: bytes */2"))
      else response(200, byteArrayOf(9, 8))
    }
    val destination = Files.createTempFile("abs-stale", ".part").toFile()
    destination.writeBytes(byteArrayOf(1, 2, 3, 4))
    val callback = RecordingCallback()

    InternalDownloadManager(destination, 0L, callback) { true }
            .download(server!!.url, "token")

    assertTrue(callback.completed.await(3, TimeUnit.SECONDS))
    assertFalse(callback.failed.get())
    assertEquals(2, server!!.requests.size)
    assertEquals("bytes=4-", server!!.requests[0]["range"])
    assertNull(server!!.requests[1]["range"])
    assertTrue(destination.readBytes().contentEquals(byteArrayOf(9, 8)))
    destination.delete()
  }

  private class RecordingCallback : DownloadItemManager.InternalProgressCallback {
    val completed = CountDownLatch(1)
    val failed = AtomicBoolean(true)

    override fun onProgress(totalBytesWritten: Long, progress: Long) = Unit

    override fun onComplete(failed: Boolean) {
      this.failed.set(failed)
      completed.countDown()
    }
  }

  private class TestHttpServer(
          private val responder: (Int, Map<String, String>) -> ByteArray
  ) : Closeable {
    private val socket = ServerSocket(0)
    val requests = Collections.synchronizedList(mutableListOf<Map<String, String>>())
    val url = "http://127.0.0.1:${socket.localPort}/download"
    private val thread = Thread {
      while (!socket.isClosed) {
        try {
          socket.accept().use { connection ->
            val reader = connection.getInputStream().bufferedReader()
            reader.readLine()
            val headers = mutableMapOf<String, String>()
            while (true) {
              val line = reader.readLine() ?: break
              if (line.isEmpty()) break
              val separator = line.indexOf(':')
              if (separator > 0) {
                headers[line.substring(0, separator).lowercase()] =
                        line.substring(separator + 1).trim()
              }
            }
            val index = requests.size
            requests.add(headers)
            connection.getOutputStream().use { output ->
              output.write(responder(index, headers))
              output.flush()
            }
          }
        } catch (_: Exception) {
          if (!socket.isClosed) throw IllegalStateException("Test HTTP server failed")
        }
      }
    }.apply {
      isDaemon = true
      start()
    }

    override fun close() {
      socket.close()
      thread.join(1_000L)
    }
  }

  companion object {
    private fun response(
            status: Int,
            body: ByteArray = byteArrayOf(),
            headers: List<String> = emptyList()
    ): ByteArray {
      val reason =
              when (status) {
                200 -> "OK"
                206 -> "Partial Content"
                else -> "Range Not Satisfiable"
              }
      val head = buildString {
        append("HTTP/1.1 $status $reason\r\n")
        headers.forEach { append("$it\r\n") }
        append("Content-Length: ${body.size}\r\n")
        append("Connection: close\r\n\r\n")
      }.toByteArray()
      return head + body
    }
  }
}
