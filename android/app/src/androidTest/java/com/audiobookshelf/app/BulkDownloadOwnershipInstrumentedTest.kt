package com.audiobookshelf.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import io.paperdb.Paper
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkDownloadOwnershipInstrumentedTest {
  @Test
  fun migratedPodcastKeepsItsJournalAndAcceptsMissingEpisodes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    val id = "migrated-podcast-${UUID.randomUUID()}"
    val directory = File(context.cacheDir, id).apply { check(mkdirs()) }
    try {
      val legacy = localItem(id, directory, podcast(), "old-user")
      addEpisode(legacy, directory, "ep-0")
      database.saveLocalLibraryItem(legacy)
      val pending = database.getLocalPodcastDownloadBase(legacy.id)!!
      addEpisode(pending, directory, "ep-1")
      database.saveDownloadedPodcastEpisodes(pending, setOf("ep-1"))
      val before = Paper.book("localLibraryItems").read<DbManager.LocalPodcastRecord>(legacy.id)!!
      val keys = Paper.book("downloadedPodcastEpisodes-${before.episodeGeneration}").allKeys.toSet()

      val inventory = database.getBulkOfflineItems(context, setOf(id), "podcast", "server" to "new-user", "old-user").single()
      assertEquals("new-user", inventory.serverUserId)
      assertEquals(setOf("ep-0", "ep-1"), inventory.episodeIds)
      val after = Paper.book("localLibraryItems").read<DbManager.LocalPodcastRecord>(legacy.id)!!
      assertEquals(before.episodeGeneration, after.episodeGeneration)
      assertEquals(keys, Paper.book("downloadedPodcastEpisodes-${after.episodeGeneration}").allKeys.toSet())

      var scan: FolderScanner.DownloadItemScanResult? = null
      FolderScanner(context).scanDownloadItem(download(id, directory, podcast(), "ep-2")) { scan = it }
      assertNotNull("Native ownership guard rejected the migrated podcast", scan)
      database.saveDownloadedPodcastEpisodes(scan!!.localLibraryItem, setOf("ep-2"))
      val reopened = database.getLocalLibraryItem(legacy.id)!!
      assertEquals("new-user", reopened.serverUserId)
      assertEquals(setOf("ep-0", "ep-1", "ep-2"), (reopened.media as Podcast).episodes!!.map { it.serverEpisodeId }.toSet())
    } finally {
      database.removeLocalLibraryItem("local_$id")
      directory.deleteRecursively()
    }
  }

  @Test
  fun migratedBookWithMissingFilesAcceptsReplacementTracks() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    val id = "migrated-book-${UUID.randomUUID()}"
    val directory = File(context.cacheDir, id).apply { check(mkdirs()) }
    try {
      val media = book()
      val missing = File(directory, "missing.mp3")
      val uri = Uri.fromFile(missing).toString()
      media.tracks!!.add(AudioTrack(1, 0.0, 1.0, "Missing", uri, "audio/mpeg", null, true, "missing", 1))
      val legacy = localItem(id, directory, media, "old-user")
      legacy.localFiles.add(LocalFile("missing", missing.name, uri, directory.path, missing.path, "audio/mpeg", 7))
      database.saveLocalLibraryItem(legacy)

      val inventory = database.getBulkOfflineItems(context, setOf(id), "book", "server" to "new-user", "old-user").single()
      assertFalse(inventory.hasOfflineBook)
      val normalized = database.getLocalLibraryItem(legacy.id)!!
      assertEquals("server", normalized.serverConnectionConfigId)
      assertEquals("new-user", normalized.serverUserId)

      var scan: FolderScanner.DownloadItemScanResult? = null
      FolderScanner(context).scanDownloadItem(download(id, directory, book(), null)) { scan = it }
      assertNotNull("Native ownership guard rejected the migrated book", scan)
      database.saveLocalLibraryItem(scan!!.localLibraryItem)
      assertTrue(database.getBulkOfflineItems(context, setOf(id), "book").single().hasOfflineBook)
    } finally {
      database.removeLocalLibraryItem("local_$id")
      directory.deleteRecursively()
    }
  }

  @Test
  fun migrationAliasDoesNotClaimUnrelatedAccountsConnectionsOrItems() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    val directory = File(context.cacheDir, "ownership-${UUID.randomUUID()}").apply { check(mkdirs()) }
    val foreign = localItem("foreign-${UUID.randomUUID()}", directory, podcast(), "unrelated-user")
    val anotherServer = localItem("another-${UUID.randomUUID()}", directory, podcast(), "old-user").apply {
      serverConnectionConfigId = "another-server"
    }
    val unselected = localItem("unselected-${UUID.randomUUID()}", directory, podcast(), "old-user")
    val items = listOf(foreign, anotherServer, unselected)
    try {
      items.forEach(database::saveLocalLibraryItem)
      val ids = setOf(foreign.libraryItemId!!, anotherServer.libraryItemId!!)
      database.getBulkOfflineItems(context, ids, "podcast", "server" to "new-user", "old-user")
      assertEquals("unrelated-user", database.getLocalLibraryItem(foreign.id)!!.serverUserId)
      assertEquals("old-user", database.getLocalLibraryItem(anotherServer.id)!!.serverUserId)
      assertEquals("old-user", database.getLocalLibraryItem(unselected.id)!!.serverUserId)
      for (item in listOf(foreign, anotherServer)) {
        var scan: FolderScanner.DownloadItemScanResult? = null
        FolderScanner(context).scanDownloadItem(download(item.libraryItemId!!, directory, podcast(), "ep")) { scan = it }
        assertNull("Native ownership guard accepted an unrelated record", scan)
      }
    } finally {
      items.forEach { database.removeLocalLibraryItem(it.id) }
      directory.deleteRecursively()
    }
  }

  private fun podcast() = Podcast(PodcastMetadata("Migrated podcast", null, null, mutableListOf(), false),
          null, mutableListOf(), mutableListOf(), false, 0)

  private fun book() = Book(BookMetadata(
          title = "Migrated book", subtitle = null, authors = null, narrators = null, genres = mutableListOf(),
          publishedYear = null, publishedDate = null, publisher = null, description = null, isbn = null, asin = null,
          language = null, explicit = false, authorName = null, authorNameLF = null, narratorName = null,
          seriesName = null, series = null), null, emptyList(), emptyList(), emptyList(), mutableListOf(), null, 0, 0.0, 0)

  private fun localItem(id: String, directory: File, media: MediaType, userId: String) = LocalLibraryItem(
          "local_$id", if (media is Podcast) "internal-podcast" else "internal-book", directory.path, directory.path,
          "", false, if (media is Podcast) "podcast" else "book", media, mutableListOf(), null, null, true,
          "server", "https://example.test", userId, id)

  private fun addEpisode(item: LocalLibraryItem, directory: File, id: String) {
    val file = File(directory, "$id.mp3").apply { writeText("fixture") }
    val uri = Uri.fromFile(file).toString()
    val track = AudioTrack(1, 0.0, 1.0, id, uri, "audio/mpeg", null, true, id, 1)
    item.localFiles.add(LocalFile(id, file.name, uri, directory.path, file.path, "audio/mpeg", file.length()))
    val episode = PodcastEpisode(id, 1, null, null, id, null, null, null, null, null, track, null, 1.0, file.length(), null, null)
    (item.media as Podcast).addEpisode(track, episode)
  }

  private fun download(id: String, directory: File, media: MediaType, episodeId: String?): DownloadItem {
    val mediaType = if (media is Podcast) "podcast" else "book"
    val folder = LocalFolder("internal-$mediaType", "Internal", "", "", "", "internal", mediaType)
    val file = File(directory, "new-${episodeId ?: id}.mp3").apply { writeText("new audio") }
    val track = AudioTrack(1, 0.0, 1.0, "Track", "/track.mp3", "audio/mpeg", null, false, null, 1)
    val episode = episodeId?.let { PodcastEpisode(it, 1, null, null, it, null, null, null, null, null, track, null, 1.0, file.length(), null, null) }
    val part = DownloadItemPart.make(id, file.name, file.length(), File(directory, "staging.part"), file,
            "Audiobookshelf-$id", "/track.mp3", folder, null, track, episode)
    return DownloadItem(id, id, episodeId, null, "server", "https://example.test", "new-user", mediaType,
            directory.path, folder, "Migrated media", "Audiobookshelf-$id", media, mutableListOf(part))
  }
}
