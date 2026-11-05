package com.audiobookshelf.app

import android.media.MediaPlayer
import android.net.Uri
import android.os.Process
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePodcastTransferTest {
  @Test
  fun restoresPersistedEpisodeAndOpensDownloadedAudioAfterServerStops() = verifyTransfer(false)

  @Test
  fun restoredDownloadUsesSavedConnectionWhenAnotherServerIsActive() = verifyTransfer(true)

  private fun verifyTransfer(switchActiveConnection: Boolean) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    assertTrue("Run the transfer test on an isolated debug installation", database.getDownloadItems().isEmpty())

    val id = "transfer-test-${UUID.randomUUID()}"
    val downloadId = "$id-episode"
    val directory = File(context.filesDir, id).apply { check(mkdirs()) }
    val audio = wave()
    val finalFile = File(directory, "episode.wav")
    val staging = File(directory, "episode.wav.part").apply { writeBytes(audio.copyOf(40)) }
    val completed = CountDownLatch(1)
    val authorization = AtomicReference<String>()
    val event = AtomicReference<JSObject>()
    val oldConfig = DeviceManager.serverConnectionConfig
    val sharedFolderName = InstrumentationRegistry.getArguments().getString("sharedFolder")
    val sharedFolder = sharedFolderName?.let { name ->
      database.getAllLocalFolders().single { it.name == name && it.mediaType == "podcast" }
    }
    var sharedTestDirectory: DocumentFile? = null
    val manager = DownloadItemManager(FolderScanner(context), context, object : DownloadItemManager.DownloadEventEmitter {
      override fun onDownloadItem(downloadItem: DownloadItem) {}
      override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
      override fun onQueueChanged(hasWork: Boolean) {}
      override fun onDownloadItemComplete(jsobj: JSObject) {
        if (jsobj.optString("libraryItemId") == downloadId) {
          event.set(jsobj)
          completed.countDown()
        }
      }
    })
    try {
      ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
        val address = "http://127.0.0.1:${server.localPort}"
        val config = ServerConnectionConfig(id, 0, "Test", address, null, "account", "tester", "fixture-token", null)
        DeviceManager.deviceData.serverConnectionConfigs.add(config)
        DeviceManager.serverConnectionConfig = config
        val responder = thread(isDaemon = true) {
          runCatching {
            server.accept().use { socket ->
              val headers = socket.getInputStream().bufferedReader().lineSequence().takeWhile { it.isNotBlank() }.toList()
              authorization.set(headers.firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim())
              socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${audio.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(audio)
                flush()
              }
            }
          }
        }
        val targetFile = if (sharedFolder == null) finalFile else {
          val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(sharedFolder.contentUrl)))
          val targetDirectory = requireNotNull(root.createDirectory(id))
          sharedTestDirectory = targetDirectory
          val old = requireNotNull(targetDirectory.createFile("audio/wav", "episode.wav"))
          requireNotNull(context.contentResolver.openOutputStream(old.uri)).use { it.write(byteArrayOf(1, 2, 3)) }
          File(sharedFolder.absolutePath, "$id/episode.wav")
        }
        val item = episodeDownload(id, config, staging, targetFile, audio.size, sharedFolder)
        database.saveDownloadItem(item)
        if (switchActiveConnection) {
          val other = ServerConnectionConfig("other-$id", 1, "Other", "http://127.0.0.1:1", null,
                  "other-account", "other-user", "other-token", null)
          DeviceManager.deviceData.serverConnectionConfigs.add(other)
          DeviceManager.serverConnectionConfig = other
        }
        manager.restoreQueue()
        manager.resumeWork()
        assertTrue("Restored episode did not finish", completed.await(20, TimeUnit.SECONDS))
        responder.join(2_000)
      }

      assertEquals("Bearer fixture-token", authorization.get())
      if (switchActiveConnection) assertEquals("other-$id", DeviceManager.serverConnectionConfigId)
      val localId = event.get().optString("localLibraryItemId")
      assertTrue(localId.isNotBlank())
      if (sharedFolder == null) assertEquals("local_$id", localId)
      assertNull(database.getDownloadItems().firstOrNull { it.id == downloadId })
      val local = database.getLocalLibraryItem(localId)!!
      val offlineEpisode = (local.media as Podcast).episodes!!.single()
      assertEquals("episode", offlineEpisode.serverEpisodeId)
      val uri = Uri.parse(offlineEpisode.audioTrack!!.contentUrl)
      val localBytes = if (sharedFolder == null) finalFile.readBytes()
      else requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
      assertArrayEquals(audio, localBytes)
      sharedTestDirectory?.let { assertEquals(1, it.listFiles().size) }
      val player = MediaPlayer()
      try {
        player.setDataSource(context, uri)
        player.prepare()
        assertEquals(1000, player.duration)
        player.start()
      } finally {
        player.release()
      }
    } finally {
      manager.destroy()
      database.removeDownloadItem(downloadId)
      database.removeLocalLibraryItem(event.get()?.optString("localLibraryItemId")?.takeIf { it.isNotBlank() } ?: "local_$id")
      sharedTestDirectory?.delete()
      DeviceManager.deviceData.serverConnectionConfigs.removeAll { it.id == id || it.id == "other-$id" }
      DeviceManager.serverConnectionConfig = oldConfig
      directory.deleteRecursively()
    }
  }

  @Test
  fun persistCompletedEpisodeCheckpoint() {
    val id = checkpointId()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    assertTrue("Run on an isolated debug installation", database.getDownloadItems().isEmpty())
    val directory = File(context.filesDir, id).apply { check(mkdirs()) }
    val audio = wave()
    val finalFile = File(directory, "episode.wav").apply { writeBytes(audio) }
    val config = ServerConnectionConfig(id, 0, "Checkpoint", "http://127.0.0.1:1", null,
            "account", "tester", "unused", null)
    val item = episodeDownload(id, config, File(directory, "episode.wav.part"), finalFile, audio.size)
    item.downloadItemParts.single().apply {
      completed = true
      moved = true
      bytesDownloaded = audio.size.toLong()
    }
    database.saveDownloadItem(item)
    FolderScanner(context).scanDownloadItem(item) { result ->
      database.saveDownloadedPodcastEpisodes(requireNotNull(result).localLibraryItem, setOf("episode"))
    }
    File(directory, "checkpoint-pid").writeText(Process.myPid().toString())
    assertEquals(item.id, database.getDownloadItems().single().id)
  }

  @Test
  fun replaysCompletedEpisodeInAnotherProcessWithoutDuplication() {
    val id = checkpointId()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    val directory = File(context.filesDir, id)
    val downloadId = "$id-episode"
    val completed = CountDownLatch(1)
    val event = AtomicReference<JSObject>()
    val manager = DownloadItemManager(FolderScanner(context), context, object : DownloadItemManager.DownloadEventEmitter {
      override fun onDownloadItem(downloadItem: DownloadItem) {}
      override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
      override fun onQueueChanged(hasWork: Boolean) {}
      override fun onDownloadItemComplete(jsobj: JSObject) {
        if (jsobj.optString("libraryItemId") == downloadId) {
          event.set(jsobj)
          completed.countDown()
        }
      }
    })
    try {
      assertNotEquals(File(directory, "checkpoint-pid").readText().toInt(), Process.myPid())
      assertEquals(downloadId, database.getDownloadItems().single().id)
      manager.restoreQueue()
      assertTrue("Restored completion was not replayed", completed.await(10, TimeUnit.SECONDS))
      assertEquals("local_$id", event.get().optString("localLibraryItemId"))
      assertTrue(database.getDownloadItems().isEmpty())
      val item = database.getLocalLibraryItem("local_$id")!!
      assertEquals("episode", (item.media as Podcast).episodes!!.single().serverEpisodeId)
      assertArrayEquals(wave(), File(directory, "episode.wav").readBytes())
    } finally {
      manager.destroy()
      database.removeDownloadItem(downloadId)
      database.removeLocalLibraryItem("local_$id")
      directory.deleteRecursively()
    }
  }

  @Test
  fun journalCommitFailureKeepsCompletedEpisodeForReplay() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    assertTrue("Run on an isolated debug installation", database.getDownloadItems().isEmpty())
    val id = "journal-replay-${UUID.randomUUID()}"
    val localId = "local_$id"
    val directory = File(context.filesDir, id).apply { check(mkdirs()) }
    val finalFile = File(directory, "episode.wav").apply { writeBytes(wave()) }
    val config = ServerConnectionConfig(id, 0, "Journal replay", "http://127.0.0.1:1", null,
            "account", "tester", "unused", null)
    val item = episodeDownload(id, config, File(directory, "episode.wav.part"), finalFile, wave().size)
    item.downloadItemParts.single().apply {
      completed = true
      moved = true
    }
    val indexed = CountDownLatch(1)
    val idle = CountDownLatch(1)
    val completions = AtomicInteger()
    val manager = DownloadItemManager(FolderScanner(context), context, object : DownloadItemManager.DownloadEventEmitter {
      override fun onDownloadItem(downloadItem: DownloadItem) {}
      override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
      override fun onQueueChanged(hasWork: Boolean) {
        if (!hasWork) idle.countDown()
      }
      override fun onDownloadItemComplete(jsobj: JSObject) {
        completions.incrementAndGet()
        indexed.countDown()
      }
    })
    val generation = "test-$id"
    try {
      FolderScanner(context).scanDownloadItem(item) { result ->
        Paper.book("localLibraryItems").write(localId,
                DbManager.LocalPodcastRecord(requireNotNull(result).localLibraryItem, generation))
      }
      // Simulate an unreadable journal entry at the new persistence boundary.
      Paper.book("downloadedPodcastEpisodes-$generation").write("local_ep_episode", "invalid-entry")
      database.saveDownloadItem(item)
      manager.restoreQueue()
      assertTrue("Failed commit did not leave the finalizer", idle.await(10, TimeUnit.SECONDS))
      assertEquals(0, completions.get())
      assertEquals(item.id, database.getDownloadItems().single().id)
      assertArrayEquals(wave(), finalFile.readBytes())

      Paper.book("downloadedPodcastEpisodes-$generation").delete("local_ep_episode")
      assertTrue(manager.retryDownloadItem(item.id))
      assertTrue("Completed episode could not be committed on replay", indexed.await(10, TimeUnit.SECONDS))
      assertEquals(1, completions.get())
      assertTrue(database.getDownloadItems().isEmpty())
      assertEquals("episode", (database.getLocalLibraryItem(localId)!!.media as Podcast).episodes!!.single().serverEpisodeId)
      assertArrayEquals(wave(), finalFile.readBytes())
    } finally {
      manager.destroy()
      database.removeDownloadItem(item.id)
      database.removeLocalLibraryItem(localId)
      Paper.book("downloadedPodcastEpisodes-$generation").destroy()
      directory.deleteRecursively()
    }
  }

  private fun checkpointId(): String {
    val token = InstrumentationRegistry.getArguments().getString("checkpoint")
    assumeTrue("Run the two checkpoint methods in separate instrumentation processes", token != null)
    require(token!!.matches(Regex("[a-zA-Z0-9-]{1,60}")))
    return "restart-test-$token"
  }

  private fun episodeDownload(
          id: String, config: ServerConnectionConfig, staging: File, finalFile: File, size: Int,
          sharedFolder: LocalFolder? = null
  ): DownloadItem {
    val folder = sharedFolder ?: LocalFolder("internal-podcast", "Internal", "", "", "", "internal", "podcast")
    val media = Podcast(PodcastMetadata("Fixture", null, null, mutableListOf(), false),
            null, mutableListOf(), mutableListOf(), false, 0)
    val track = AudioTrack(1, 0.0, 1.0, "Episode", "/episode.wav", "audio/wav", null, false, null, 1)
    val episode = PodcastEpisode("episode", 1, null, null, "Episode", null, null, null,
            null, null, track, null, 1.0, size.toLong(), null, null)
    val downloadId = "$id-episode"
    val part = DownloadItemPart.make(downloadId, finalFile.name, size.toLong(), staging, finalFile,
            id, "/episode.wav", folder, null, track, episode)
    return DownloadItem(downloadId, id, episode.id, null, config.id, config.address, config.userId,
            "podcast", finalFile.parentFile!!.path, folder, "Fixture", id, media, mutableListOf(part))
  }

  private fun wave(): ByteArray {
    val samples = 8_000
    return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray())
      putInt(36 + samples * 2)
      put("WAVEfmt ".toByteArray())
      putInt(16)
      putShort(1)
      putShort(1)
      putInt(samples)
      putInt(samples * 2)
      putShort(2)
      putShort(16)
      put("data".toByteArray())
      putInt(samples * 2)
    }.array()
  }
}
