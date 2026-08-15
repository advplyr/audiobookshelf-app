package com.audiobookshelf.app.managers

import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.BookMetadata
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.LocalFile
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.plugins.AbsLog
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DbManagerPersistenceTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager

  @Before
  fun setUp() {
    db = DbManager()
  }

  @Test
  fun `device data defaults when nothing has been saved`() {
    val data = db.getDeviceData()

    assertTrue(data.serverConnectionConfigs.isEmpty())
    assertNull(data.lastServerConnectionConfigId)
    assertEquals("22:00", data.deviceSettings?.autoSleepTimerStartTime)
  }

  @Test
  fun `book local library item round trips as its concrete subtype`() {
    val item =
            localLibraryItem(
                    id = "local-book",
                    media = book(mutableListOf(audioTrack(0, duration = 12.0)))
            )

    db.saveLocalLibraryItem(item)
    val back = db.getLocalLibraryItem("local-book")

    assertEquals("local-book", back?.id)
    assertTrue("media should deserialize as Book, not the MediaType base class", back?.media is Book)
    assertEquals(1, (back?.media as Book).tracks?.size)
    assertEquals("Unknown", back.media.metadata.getAuthorDisplayName())
  }

  @Test
  fun `podcast local library item round trips episodes as the concrete subtype`() {
    val episode =
            PodcastEpisode(
                    "ep-1", 1, null, null, "Episode 1", null, null, null, null, null,
                    audioTrack(localFileId = "lf-1"), null, 10.0, 100L, null, null
            )
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", "Author", null, mutableListOf(), false),
                    null,
                    mutableListOf(),
                    mutableListOf(episode),
                    false,
                    1
            )
    val item = localLibraryItem(id = "local-podcast", media = podcast, mediaType = "podcast")

    db.saveLocalLibraryItem(item)
    val back = db.getLocalLibraryItem("local-podcast")
    val media = back?.media

    assertTrue(media is Podcast)
    assertEquals(1, (media as Podcast).episodes?.size)
    assertEquals("Episode 1", media.episodes?.single()?.title)
  }

  @Test
  fun `getLocalLibraryItems filters by media type`() {
    db.saveLocalLibraryItems(
            listOf(
                    localLibraryItem(id = "book-1", media = book(), mediaType = "book"),
                    localLibraryItem(
                            id = "podcast-1",
                            media =
                                    Podcast(
                                            PodcastMetadata("Cast", null, null, mutableListOf(), false),
                                            null,
                                            mutableListOf(),
                                            mutableListOf(),
                                            false,
                                            0
                                    ),
                            mediaType = "podcast"
                    )
            )
    )

    assertEquals(listOf("book-1"), db.getLocalLibraryItems("book").map { it.id })
    assertEquals(2, db.getLocalLibraryItems().size)
  }

  @Test
  fun `getLocalLibraryItemByLId finds by server library item id and misses cleanly`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1", libraryItemId = "server-item-1"))

    assertEquals("local-1", db.getLocalLibraryItemByLId("server-item-1")?.id)
    assertNull(db.getLocalLibraryItemByLId("does-not-exist"))
  }

  @Test
  fun `removeLocalFolder cascades only to items in that folder`() {
    val inFolder =
            LocalLibraryItem(
                    "in-folder", "folder-a", "/base", "/abs", "", false, "book", book(),
                    mutableListOf(), null, null, true, "server", "https://x", "user", "lib-1"
            )
    val otherFolder =
            LocalLibraryItem(
                    "other-folder", "folder-b", "/base", "/abs", "", false, "book", book(),
                    mutableListOf(), null, null, true, "server", "https://x", "user", "lib-2"
            )
    db.saveLocalLibraryItems(listOf(inFolder, otherFolder))
    db.saveLocalFolder(LocalFolder("folder-a", "A", "", "", "/a", "internal", "book"))

    db.removeLocalFolder("folder-a")

    assertNull(db.getLocalLibraryItem("in-folder"))
    assertEquals("other-folder", db.getLocalLibraryItem("other-folder")?.id)
    assertNull(db.getLocalFolder("folder-a"))
  }

  @Test
  fun `local media progress round trips and lists`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "progress-1", "local-1", null, 100.0, 0.5, 50.0, false, null, null,
                    1L, 0L, null, null, null, null, null, null
            )
    )

    assertEquals(0.5, db.getLocalMediaProgress("progress-1")?.progress ?: -1.0, 0.0)
    assertEquals(1, db.getAllLocalMediaProgress().size)

    db.removeLocalMediaProgress("progress-1")
    assertNull(db.getLocalMediaProgress("progress-1"))
  }

  @Test
  fun `removeAllLocalMediaProgress clears every entry`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress("p1", "l1", null, 1.0, 0.0, 0.0, false, null, null, 0, 0, null, null, null, null, null, null)
    )
    db.saveLocalMediaProgress(
            LocalMediaProgress("p2", "l2", null, 1.0, 0.0, 0.0, false, null, null, 0, 0, null, null, null, null, null, null)
    )

    db.removeAllLocalMediaProgress()

    assertTrue(db.getAllLocalMediaProgress().isEmpty())
  }

  @Test
  fun `download items round trip and remove`() {
    val item = downloadItemFixture("dl-1")
    db.saveDownloadItem(item)

    assertEquals(listOf("dl-1"), db.getDownloadItems().map { it.id })

    db.removeDownloadItem("dl-1")
    assertTrue(db.getDownloadItems().isEmpty())
  }

  @Test
  fun `playback sessions round trip and remove`() {
    val session = playbackSessionFixture("session-1")
    db.savePlaybackSession(session)

    assertEquals(listOf("session-1"), db.getPlaybackSessions().map { it.id })

    db.removePlaybackSession("session-1")
    assertTrue(db.getPlaybackSessions().isEmpty())
  }

  @Test
  fun `media item history round trips`() {
    val history =
            com.audiobookshelf.app.data.MediaItemHistory(
                    "item-1", "Title", "lib-1", null, false, null, null, null, 0L, mutableListOf()
            )

    db.saveMediaItemHistory(history)

    assertEquals("Title", db.getMediaItemHistory("item-1")?.mediaDisplayTitle)
    assertNull(db.getMediaItemHistory("missing"))
  }

  @Test
  fun `logs round trip sorted by timestamp`() {
    db.saveLog(AbsLog("l2", "tag", "info", "second", 200L))
    db.saveLog(AbsLog("l1", "tag", "info", "first", 100L))

    val logs = db.getAllLogs()

    assertEquals(listOf("first", "second"), logs.map { it.message })

    db.removeAllLogs()
    assertTrue(db.getAllLogs().isEmpty())
  }

  private fun downloadItemFixture(id: String) =
          com.audiobookshelf.app.models.DownloadItem(
                  id = id,
                  libraryItemId = "lib-1",
                  episodeId = null,
                  userMediaProgress = null,
                  serverConnectionConfigId = "server",
                  serverAddress = "https://x",
                  serverUserId = "user",
                  mediaType = "book",
                  itemFolderPath = "/downloads/$id",
                  localFolder = LocalFolder("internal-1", "Internal", "", "", "/downloads", "internal", "book"),
                  itemTitle = "Title",
                  itemSubfolder = id,
                  media = book(),
                  downloadItemParts = mutableListOf()
          )

  private fun playbackSessionFixture(id: String) =
          PlaybackSession(
                  id, "user", "lib-1", null, "book", MediaTypeMetadata("Title", false),
                  DeviceInfo("d", "m", "mo", 35, "1"), emptyList(), "Title", "Author", null,
                  100.0, 3, 1L, 2L, 0L, mutableListOf(), 10.0, null, null, null, "server",
                  "https://x", "exo-player"
          )
}
