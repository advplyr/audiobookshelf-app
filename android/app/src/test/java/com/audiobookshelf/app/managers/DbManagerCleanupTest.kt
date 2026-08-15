package com.audiobookshelf.app.managers

import android.content.Context
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.LocalFile
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.plugins.AbsLog
import io.mockk.mockk
import java.io.File
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DbManagerCleanupTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager
  private val ctx = mockk<Context>(relaxed = true)

  @Before
  fun setUp() {
    db = DbManager()
  }

  private fun realFile(): File {
    val f = File.createTempFile("abs-clean", ".mp3")
    f.deleteOnExit()
    return f
  }

  @Test
  fun `removes local files that no longer exist on disk`() {
    val present = realFile()
    val missingPath = "/does/not/exist/${System.nanoTime()}.mp3"
    val item =
            LocalLibraryItem(
                    "item-1", "folder", "/base", "/abs", "", false, "book",
                    book(mutableListOf(audioTrack(0, localFileId = "present"), audioTrack(1, localFileId = "missing"))),
                    mutableListOf(
                            LocalFile("present", "p.mp3", "", "", present.absolutePath, "audio/mpeg", 1),
                            LocalFile("missing", "m.mp3", "", "", missingPath, "audio/mpeg", 1)
                    ),
                    null, null, true, "server", "https://x", "user", "lib-1"
            )
    db.saveLocalLibraryItem(item)

    db.cleanLocalLibraryItems(ctx)

    val back = db.getLocalLibraryItem("item-1")
    assertEquals(listOf("present"), back?.localFiles?.map { it.id })
  }

  @Test
  fun `book tracks without a matching local file are dropped`() {
    val present = realFile()
    val item =
            LocalLibraryItem(
                    "item-2", "folder", "/base", "/abs", "", false, "book",
                    book(mutableListOf(audioTrack(0, localFileId = "lf-1"), audioTrack(1, localFileId = "lf-2"))),
                    mutableListOf(LocalFile("lf-1", "p.mp3", "", "", present.absolutePath, "audio/mpeg", 1)),
                    null, null, true, "server", "https://x", "user", "lib-1"
            )
    db.saveLocalLibraryItem(item)

    db.cleanLocalLibraryItems(ctx)

    val back = db.getLocalLibraryItem("item-2")
    val backBook = back?.media as Book
    assertEquals(listOf("lf-1"), backBook.tracks?.map { it.localFileId })
  }

  @Test
  fun `podcast episodes without a matching local file are dropped`() {
    val present = realFile()
    val keptEpisode =
            PodcastEpisode(
                    "ep-1", 1, null, null, "Kept", null, null, null, null, null,
                    audioTrack(localFileId = "lf-1"), null, 10.0, 1, null, null
            )
    val droppedEpisode =
            PodcastEpisode(
                    "ep-2", 2, null, null, "Dropped", null, null, null, null, null,
                    audioTrack(localFileId = "lf-2"), null, 10.0, 1, null, null
            )
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", null, null, mutableListOf(), false), null,
                    mutableListOf(), mutableListOf(keptEpisode, droppedEpisode), false, 2
            )
    val item =
            LocalLibraryItem(
                    "item-3", "folder", "/base", "/abs", "", false, "podcast", podcast,
                    mutableListOf(LocalFile("lf-1", "p.mp3", "", "", present.absolutePath, "audio/mpeg", 1)),
                    null, null, true, "server", "https://x", "user", "lib-1"
            )
    db.saveLocalLibraryItem(item)

    db.cleanLocalLibraryItems(ctx)

    val back = db.getLocalLibraryItem("item-3")
    val backPodcast = back?.media as Podcast
    assertEquals(listOf("Kept"), backPodcast.episodes?.map { it.title })
  }

  @Test
  fun `missing cover clears both cover fields`() {
    val item =
            LocalLibraryItem(
                    "item-4", "folder", "/base", "/abs", "", false, "book", book(),
                    mutableListOf(), "content://cover", "/no/longer/here.jpg", true, "server",
                    "https://x", "user", "lib-1"
            )
    db.saveLocalLibraryItem(item)

    db.cleanLocalLibraryItems(ctx)

    val back = db.getLocalLibraryItem("item-4")
    assertNull(back?.coverContentUrl)
    assertNull(back?.coverAbsolutePath)
  }

  @Test
  fun `local-only items with no server connection are removed entirely`() {
    val item =
            LocalLibraryItem(
                    "item-5", "folder", "/base", "/abs", "", false, "book", book(),
                    mutableListOf(), null, null, true, null, null, null, null
            )
    db.saveLocalLibraryItem(item)

    db.cleanLocalLibraryItems(ctx)

    assertNull(db.getLocalLibraryItem("item-5"))
  }

  @Test
  fun `healthy items are left untouched`() {
    val present = realFile()
    val item =
            LocalLibraryItem(
                    "item-6", "folder", "/base", "/abs", "", false, "book",
                    book(mutableListOf(audioTrack(0, localFileId = "lf-1"))),
                    mutableListOf(LocalFile("lf-1", "p.mp3", "", "", present.absolutePath, "audio/mpeg", 1)),
                    null, null, true, "server", "https://x", "user", "lib-1"
            )
    db.saveLocalLibraryItem(item)

    db.cleanLocalLibraryItems(ctx)

    val back = db.getLocalLibraryItem("item-6")
    assertEquals(1, back?.localFiles?.size)
    assertEquals(1, (back?.media as Book).tracks?.size)
  }

  @Test
  fun `cleanLocalMediaProgress removes ids not prefixed with local regardless of item match`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "server-progress-1", "local-1", null, 1.0, 0.0, 0.0, false, null, null,
                    0, 0, null, null, null, null, null, null
            )
    )

    db.cleanLocalMediaProgress()

    assertNull(db.getLocalMediaProgress("server-progress-1"))
  }

  @Test
  fun `cleanLocalMediaProgress removes orphaned progress with no matching item`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "local-orphan", "missing-item", null, 1.0, 0.0, 0.0, false, null, null,
                    0, 0, null, null, null, null, null, null
            )
    )

    db.cleanLocalMediaProgress()

    assertNull(db.getLocalMediaProgress("local-orphan"))
  }

  @Test
  fun `cleanLocalMediaProgress removes podcast progress without an episode id`() {
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", null, null, mutableListOf(), false), null,
                    mutableListOf(), mutableListOf(), false, 0
            )
    db.saveLocalLibraryItem(localLibraryItem(id = "local-podcast", media = podcast, mediaType = "podcast"))
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "local-podcast", "local-podcast", null, 1.0, 0.0, 0.0, false, null, null,
                    0, 0, null, null, null, null, null, null
            )
    )

    db.cleanLocalMediaProgress()

    assertNull(db.getLocalMediaProgress("local-podcast"))
  }

  @Test
  fun `cleanLocalMediaProgress keeps progress for a matching book item`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "local-1", "local-1", null, 1.0, 0.0, 0.0, false, null, null,
                    0, 0, null, null, null, null, null, null
            )
    )

    db.cleanLocalMediaProgress()

    assertEquals("local-1", db.getLocalMediaProgress("local-1")?.id)
  }

  @Test
  fun `cleanLogs removes only logs older than the 48-hour retention window`() {
    val now = System.currentTimeMillis()
    val fortyEightHoursMs = 48L * 3_600_000L
    db.saveLog(AbsLog("old", "t", "info", "old", now - fortyEightHoursMs - 1_000))
    db.saveLog(AbsLog("new", "t", "info", "new", now))

    db.cleanLogs()

    // cleanLogs() itself writes an audit log via AbsLogger.info once it removes anything, so the
    // surviving set is "new" plus that fresh audit entry - not just "new" alone.
    val remainingIds = db.getAllLogs().map { it.id }
    assertFalse(remainingIds.contains("old"))
    assertTrue(remainingIds.contains("new"))
    assertEquals(2, remainingIds.size)
  }

  @Test
  fun `cleanLogs is a no-op and logs nothing when nothing is old enough to remove`() {
    db.saveLog(AbsLog("new", "t", "info", "new", System.currentTimeMillis()))

    db.cleanLogs()

    assertEquals(listOf("new"), db.getAllLogs().map { it.id })
  }
}
