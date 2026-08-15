package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MediaEventManagerTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager

  @Before
  fun setUp() {
    db = DbManager()
  }

  private fun session(
          id: String = "session-1",
          libraryItemId: String? = "item-1",
          episodeId: String? = null,
          displayTitle: String? = "Title"
  ) =
          PlaybackSession(
                  id, "user-1", libraryItemId, episodeId, "book", MediaTypeMetadata("Title", false),
                  DeviceInfo("d", "m", "mo", 35, "1"), emptyList(), displayTitle, "Author", null,
                  100.0, 3, 1L, 2L, 0L, mutableListOf(), 10.0, null, null, null, "server",
                  "https://x", "exo-player"
          )

  @Test
  fun `first playback event creates media item history for the session`() {
    MediaEventManager.playEvent(session())

    val history = db.getMediaItemHistory("item-1")
    assertEquals(1, history?.events?.size)
    assertEquals("Play", history?.events?.first()?.name)
    assertEquals("Playback", history?.events?.first()?.type)
  }

  @Test
  fun `subsequent events for the same session append to the same history`() {
    val s = session()
    MediaEventManager.playEvent(s)
    MediaEventManager.pauseEvent(s, null)

    val history = db.getMediaItemHistory("item-1")
    assertEquals(listOf("Play", "Pause"), history?.events?.map { it.name })
  }

  @Test
  fun `stop save finished and seek events are all recorded with the Playback type`() {
    val s = session()
    MediaEventManager.stopEvent(s, null)
    MediaEventManager.saveEvent(s, null)
    MediaEventManager.finishedEvent(s, null)
    MediaEventManager.seekEvent(s, null)

    val history = db.getMediaItemHistory("item-1")
    assertEquals(listOf("Stop", "Save", "Finished", "Seek"), history?.events?.map { it.name })
    assertTrue(history?.events?.all { it.type == "Playback" } == true)
  }

  @Test
  fun `sync result fields are mapped onto the recorded event`() {
    val syncResult = SyncResult(true, false, "server rejected sync")

    MediaEventManager.pauseEvent(session(), syncResult)

    val event = db.getMediaItemHistory("item-1")?.events?.first()
    assertEquals(true, event?.serverSyncAttempted)
    assertEquals(false, event?.serverSyncSuccess)
    assertEquals("server rejected sync", event?.serverSyncMessage)
  }

  @Test
  fun `absent sync result defaults serverSyncAttempted to false`() {
    MediaEventManager.pauseEvent(session(), null)

    val event = db.getMediaItemHistory("item-1")?.events?.first()
    assertEquals(false, event?.serverSyncAttempted)
    assertNull(event?.serverSyncSuccess)
  }

  @Test
  fun `missing display title falls back to Unset on history creation`() {
    MediaEventManager.playEvent(session(displayTitle = null))

    assertEquals("Unset", db.getMediaItemHistory("item-1")?.mediaDisplayTitle)
  }

  @Test
  fun `missing library item id falls back to empty string as the history id`() {
    MediaEventManager.playEvent(session(libraryItemId = null))

    // mediaItemId is "" when libraryItemId is null and there's no episode, so history is filed
    // under the empty-string id.
    assertEquals(1, db.getMediaItemHistory("")?.events?.size)
  }

  @Test
  fun `syncEvent does not create history when none exists yet`() {
    val progress =
            MediaProgress("p1", "item-1", null, 10.0, 0.5, 5.0, false, null, null, 0, 0, null)

    MediaEventManager.syncEvent(progress, "no history yet")

    assertNull(db.getMediaItemHistory("item-1"))
  }

  @Test
  fun `syncEvent appends to existing history for the same media item`() {
    MediaEventManager.playEvent(session())
    val progress =
            MediaProgress("p1", "item-1", null, 10.0, 0.5, 5.0, false, null, null, 0, 0, null)

    MediaEventManager.syncEvent(progress, "caught up")

    val history = db.getMediaItemHistory("item-1")
    assertEquals(listOf("Play", "Sync"), history?.events?.map { it.name })
    assertEquals("Sync", history?.events?.last()?.type)
  }

  @Test
  fun `client event emitter is notified exactly once per event`() {
    val emitter = mockk<PlayerNotificationService.ClientEventEmitter>(relaxed = true)
    MediaEventManager.clientEventEmitter = emitter

    MediaEventManager.playEvent(session())

    verify(exactly = 1) { emitter.onMediaItemHistoryUpdated(any()) }
  }
}
