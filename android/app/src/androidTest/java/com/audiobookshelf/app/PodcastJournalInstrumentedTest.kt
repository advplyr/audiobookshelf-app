package com.audiobookshelf.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.LocalFile
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.managers.DbManager
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastJournalInstrumentedTest {
  @Test
  fun legacyPodcastAndReplayedDeltaSurviveReadAndTargetedDeletion() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DbManager()
    val id = "instrumented-${UUID.randomUUID()}"
    val directory = File(context.cacheDir, id).apply { check(mkdirs()) }
    try {
      val legacy = LocalLibraryItem(
              id, "internal-podcast", directory.path, directory.path, "", false, "podcast",
              Podcast(PodcastMetadata("Offline podcast", null, null, mutableListOf(), false),
                      null, mutableListOf(), mutableListOf(), false, 0),
              mutableListOf(), null, null, true, "server", "https://example.test", "account", id
      )
      addEpisode(legacy, directory, "legacy")
      database.saveLocalLibraryItem(legacy)

      val staged = database.getLocalPodcastDownloadBase(id)!!
      addEpisode(staged, directory, "legacy", fileId = "replacement")
      database.saveDownloadedPodcastEpisodes(staged, setOf("legacy"))
      addEpisode(staged, directory, "downloaded")
      database.saveDownloadedPodcastEpisodes(staged, setOf("downloaded"))
      database.saveDownloadedPodcastEpisodes(staged, setOf("downloaded"))

      val reopened = DbManager().getLocalLibraryItem(id)!!
      val episodes = (reopened.media as Podcast).episodes!!
      assertEquals(listOf("legacy", "downloaded"), episodes.map { it.serverEpisodeId })
      assertEquals(setOf("replacement", "downloaded"), reopened.localFiles.map { it.id }.toSet())
      assertTrue(reopened.localFiles.all { it.exists(context) })
      assertEquals(id, database.getLocalLibraryItemWithEpisode("local_ep_downloaded")?.libraryItemWrapper?.id)

      assertTrue(File(directory, "downloaded.mp3").delete())

      reopened.media.removeAudioTrack("downloaded")
      reopened.removeLocalFile("downloaded")
      database.saveLocalLibraryItem(reopened)
      val afterDeletion = DbManager().getLocalLibraryItem(id)!!
      assertEquals(listOf("legacy"), (afterDeletion.media as Podcast).episodes!!.map { it.serverEpisodeId })
      assertEquals(listOf("replacement"), afterDeletion.localFiles.map { it.id })

      database.removeLocalLibraryItem(id)
      assertNull(DbManager().getLocalLibraryItem(id))
    } finally {
      database.removeLocalLibraryItem(id)
      directory.deleteRecursively()
    }
  }

  @Test
  fun aThousandEpisodeJournalSurvivesCleanupWithoutLosingDownloads() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DbManager()
    val id = "large-journal-${UUID.randomUUID()}"
    val directory = File(context.cacheDir, id).apply { check(mkdirs()) }
    try {
      database.saveLocalLibraryItem(LocalLibraryItem(
              id, "internal-podcast", directory.path, directory.path, "", false, "podcast",
              Podcast(PodcastMetadata("Large offline podcast", null, null, mutableListOf(), false),
                      null, mutableListOf(), mutableListOf(), false, 0),
              mutableListOf(), null, null, true, "server", "https://example.test", "account", id
      ))
      val started = System.nanoTime()
      repeat(1000) { index ->
        val delta = database.getLocalPodcastDownloadBase(id)!!
        addEpisode(delta, directory, "episode-$index")
        database.saveDownloadedPodcastEpisodes(delta, setOf("episode-$index"))
      }
      val saved = System.nanoTime()
      val item = database.getLocalLibraryItem(id)!!
      assertEquals(1000, (item.media as Podcast).episodes!!.size)
      assertEquals(1000, item.localFiles.size)
      assertTrue(File(directory, "episode-500.mp3").delete())
      val cleanupStarted = System.nanoTime()
      database.cleanLocalLibraryItems(context)
      val cleaned = database.getLocalLibraryItem(id)!!
      val episodes = (cleaned.media as Podcast).episodes!!
      assertEquals(999, episodes.size)
      assertEquals(999, episodes.map { it.id }.toSet().size)
      assertTrue(episodes.none { it.serverEpisodeId == "episode-500" })
      assertEquals(999, cleaned.localFiles.size)
      android.util.Log.i("PodcastJournalTest", "1000 episodes: writesMs=${(saved - started) / 1_000_000}, " +
              "readMs=${(cleanupStarted - saved) / 1_000_000}, cleanupMs=${(System.nanoTime() - cleanupStarted) / 1_000_000}")
    } finally {
      database.removeLocalLibraryItem(id)
      directory.deleteRecursively()
    }
  }

  @Test
  fun filteredQueriesDoNotOpenUnrelatedPodcastJournals() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    DbManager.initialize(context)
    val database = DbManager()
    val id = "filtered-journal-${UUID.randomUUID()}"
    val generation = "test-$id"
    val base = LocalLibraryItem(
            id, "internal-podcast", context.cacheDir.path, context.cacheDir.path, "", false, "podcast",
            Podcast(PodcastMetadata("Unrelated podcast", null, null, mutableListOf(), false),
                    null, mutableListOf(), mutableListOf(), false, 0),
            mutableListOf(), null, null, true, "server", "https://example.test", "account", id
    )
    try {
      io.paperdb.Paper.book("localLibraryItems").write(id, DbManager.LocalPodcastRecord(base, generation))
      // A book-only query must not deserialize even this unreadable episode entry.
      io.paperdb.Paper.book("downloadedPodcastEpisodes-$generation").write("episode", "invalid-entry")
      assertTrue(database.getLocalLibraryItems("book").all { it.mediaType == "book" })
      assertNull(database.getLocalLibraryItemByLId("unmatched-$id"))
    } finally {
      database.removeLocalLibraryItem(id)
    }
  }

  private fun addEpisode(item: LocalLibraryItem, directory: File, id: String, fileId: String = id) {
    val file = File(directory, "$fileId.mp3").apply { writeText("fixture") }
    val uri = Uri.fromFile(file).toString()
    val track = AudioTrack(1, 0.0, 1.0, id, uri, "audio/mpeg", null, true, fileId, 1)
    item.localFiles.add(LocalFile(fileId, file.name, uri, directory.path, file.path, "audio/mpeg", file.length()))
    val episode = PodcastEpisode(id, 1, null, null, id, null, null, null, null, null, track, null, 1.0, file.length(), null, null)
    (item.media as Podcast).addEpisode(track, episode)
  }
}
