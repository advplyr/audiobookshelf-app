package com.audiobookshelf.wear.data

import com.audiobookshelf.wear.testContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgressStoreTest {
  private lateinit var store: ProgressStore

  @Before
  fun setUp() {
    store = ProgressStore(testContext)
  }

  private fun session(id: String, updatedAt: Long, currentTime: Double = 0.0) =
    JSONObject().put("id", id).put("updatedAt", updatedAt).put("currentTime", currentTime)

  @Test
  fun `books and episodes keep separate positions`() {
    store.save(MediaKey("li_1"), 10.0, 100)
    store.save(MediaKey("li_1", "ep_1"), 20.0, 200)

    assertEquals(LocalProgress(10.0, 100), store.get(MediaKey("li_1")))
    assertEquals(LocalProgress(20.0, 200), store.get(MediaKey("li_1", "ep_1")))
    assertNull(store.get(MediaKey("li_1", "ep_2")))
  }

  @Test
  fun `an older report never replaces a newer queued one`() {
    store.addPendingSession(session("s1", updatedAt = 200, currentTime = 50.0))
    store.addPendingSession(session("s1", updatedAt = 100, currentTime = 10.0))

    val queued = store.pendingSessions().single()
    assertEquals(50.0, queued.getDouble("currentTime"), 0.0)
  }

  @Test
  fun `a report replaced while being sent stays queued`() {
    val sending = session("s1", updatedAt = 100)
    store.addPendingSession(sending)
    // Playback continued while the older copy was on its way
    store.addPendingSession(session("s1", updatedAt = 200))

    store.removePendingSessions(listOf(sending))

    assertEquals(200L, store.pendingSessions().single().getLong("updatedAt"))
  }

  @Test
  fun `sent reports are removed`() {
    val sent = session("s1", updatedAt = 100)
    store.addPendingSession(sent)
    store.addPendingSession(session("s2", updatedAt = 100))

    store.removePendingSessions(listOf(sent))

    assertEquals(listOf("s2"), store.pendingSessions().map { it.getString("id") })
  }
}
