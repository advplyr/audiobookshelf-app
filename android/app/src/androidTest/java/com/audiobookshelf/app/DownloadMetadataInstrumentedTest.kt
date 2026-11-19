package com.audiobookshelf.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.server.ApiHandler
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadMetadataInstrumentedTest {
  @Test
  fun truncatedMetadataResponseSettlesTheDownloadPreparation() = verifyFailure("{}", 100)

  @Test
  fun invalidItemMetadataSettlesTheDownloadPreparation() = verifyFailure("""{"id":"fixture"}""", null)

  private fun verifyFailure(body: String, declaredLength: Int?) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val oldConfig = DeviceManager.serverConnectionConfig
    val id = "metadata-test-${UUID.randomUUID()}"
    val finished = CountDownLatch(1)
    val result = AtomicReference<LibraryItem?>()
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
      val config = ServerConnectionConfig(id, 0, "Metadata fixture", "http://127.0.0.1:${server.localPort}",
              null, "account", "tester", "fixture-token", null)
      DeviceManager.deviceData.serverConnectionConfigs.add(config)
      DeviceManager.serverConnectionConfig = config
      try {
        val responder = thread(isDaemon = true) {
          runCatching {
            server.accept().use { socket ->
              socket.getInputStream().bufferedReader().lineSequence().takeWhile { it.isNotBlank() }.toList()
              val bytes = body.toByteArray()
              socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${declaredLength ?: bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
              }
            }
          }
        }
        ApiHandler(context).getLibraryItemWithProgress("fixture", null) {
          result.set(it)
          finished.countDown()
        }
        assertTrue("Metadata failure left download preparation pending", finished.await(5, TimeUnit.SECONDS))
        assertNull(result.get())
        responder.join(1_000)
      } finally {
        DeviceManager.deviceData.serverConnectionConfigs.removeAll { it.id == id }
        DeviceManager.serverConnectionConfig = oldConfig
      }
    }
  }
}
