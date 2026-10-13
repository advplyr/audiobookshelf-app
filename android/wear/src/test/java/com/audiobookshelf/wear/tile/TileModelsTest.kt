package com.audiobookshelf.wear.tile

import com.audiobookshelf.wear.data.BookDetail
import com.audiobookshelf.wear.data.DownloadedBook
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.download.ActiveDownload
import com.audiobookshelf.wear.testContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileModelsTest {
  private fun entry(id: String, progress: Float = 0.5f, episode: String? = null) = ContinueEntry(MediaKey(id, episode), "Title $id", progress)

  private fun downloaded(id: String, size: Long, episode: String? = null) =
    DownloadedBook(BookDetail(id, "lib", "Title $id", "Author", 100.0, emptyList(), emptyList(), episode), size)

  @Test
  fun `what is playing comes first, once`() {
    val entries = ContinueEntries.build(entry("b"), listOf(entry("a"), entry("b"), entry("c")))

    assertEquals(listOf("b", "a", "c"), entries.map { it.key.itemId })
    assertTrue(entries[0].playing)
    assertEquals(listOf(false, false), entries.drop(1).map { it.playing })
  }

  @Test
  fun `shows at most four`() {
    val entries = ContinueEntries.build(null, (1..6).map { entry("b$it") })
    assertEquals(4, entries.size)
  }

  @Test
  fun `episodes of the same podcast are separate entries`() {
    val entries = ContinueEntries.build(null, listOf(entry("pod", episode = "e1"), entry("pod", episode = "e2")))
    assertEquals(listOf(MediaKey("pod", "e1"), MediaKey("pod", "e2")), entries.map { it.key })
  }

  @Test
  fun `progress fractions stay in range`() {
    assertEquals(0.25f, ContinueEntries.fraction(25.0, 100.0), 0f)
    assertEquals(1f, ContinueEntries.fraction(150.0, 100.0), 0f)
    assertEquals(0f, ContinueEntries.fraction(10.0, 0.0), 0f)
  }

  @Test
  fun `entries survive a save and load`() {
    val entries = listOf(entry("a", 0.1f), entry("pod", 0.9f, episode = "e1"))
    assertEquals(entries, ContinueEntry.listFromJson(ContinueEntry.listToJson(entries)))
  }

  @Test
  fun `summarises downloads`() {
    val books = listOf(downloaded("a", 100), downloaded("b", 300), downloaded("pod", 50, "e1"), downloaded("c", 50))
    val listened = mapOf(MediaKey("b") to 30L, MediaKey("pod", "e1") to 20L, MediaKey("a") to 10L)

    val summary = DownloadsSummary.from(books, emptyList(), freeBytes = 500) { listened[it] }

    assertEquals(4, summary.count)
    assertEquals(500L, summary.bytes)
    assertEquals(0.5f, summary.storageFraction, 0f)
    assertEquals(listOf(MediaKey("b"), MediaKey("pod", "e1"), MediaKey("a")), summary.recent)
    assertEquals(0, summary.activeCount)
  }

  @Test
  fun `running downloads are averaged and finished ones ignored`() {
    val active = listOf(ActiveDownload(MediaKey("x"), "X", 20), ActiveDownload(MediaKey("y"), "Y", 60), ActiveDownload(MediaKey("a"), "A", 100))

    val summary = DownloadsSummary.from(listOf(downloaded("a", 10)), active, freeBytes = 0) { null }

    assertEquals(2, summary.activeCount)
    assertEquals(40, summary.activePercent)
  }

  @Test
  fun `the cache keeps entries and stats`() {
    val cache = TileCache(testContext)
    assertNull(cache.continueEntries)
    assertNull(cache.stats())

    cache.continueEntries = listOf(entry("a"))
    cache.saveStats(JSONObject().put("today", 60), fetchedAt = 1234)

    val reloaded = TileCache(testContext)
    assertEquals(listOf(entry("a")), reloaded.continueEntries)
    assertEquals(60, reloaded.stats()!!.first.getInt("today"))
    assertEquals(1234L, reloaded.stats()!!.second)

    reloaded.clear()
    assertNull(TileCache(testContext).continueEntries)
  }
}
