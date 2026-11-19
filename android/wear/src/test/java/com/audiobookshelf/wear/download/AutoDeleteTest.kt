package com.audiobookshelf.wear.download

import com.audiobookshelf.wear.data.BookDetail
import com.audiobookshelf.wear.data.DownloadStore
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.data.ServerProgress
import com.audiobookshelf.wear.data.Track
import com.audiobookshelf.wear.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoDeleteTest {
  private lateinit var downloads: DownloadStore
  private var enabled = true
  private var inPlayer: MediaKey? = null
  private lateinit var autoDelete: AutoDelete

  private val book = MediaKey("li_book")
  private val episode = MediaKey("li_pod", "ep_1")

  @Before
  fun setUp() {
    downloads = DownloadStore(testContext)
    autoDelete = AutoDelete(downloads, { enabled }, { inPlayer })
    download(book)
    download(episode)
  }

  private fun download(key: MediaKey) {
    val track = Track(1, "ino_${key.key.hashCode()}", 10.0, 0.0, "/file", "audio/mpeg", ".mp3", 4)
    val detail = BookDetail(key.itemId, "lib", "Title ${key.key}", "Author", 10.0, listOf(track), emptyList(), key.episodeId)
    downloads.bookDir(key).mkdirs()
    downloads.trackFile(key, track).writeBytes(ByteArray(4))
    downloads.coverFile(key).writeBytes(ByteArray(2))
    downloads.markComplete(detail)
  }

  private fun finished(vararg keys: MediaKey) =
    keys.associate { it.key to ServerProgress(10.0, 1.0, isFinished = true, lastUpdate = 1, itemId = it.itemId, episodeId = it.episodeId) }

  @Test
  fun `removes a finished download when the setting is on`() {
    assertTrue(autoDelete.onFinished(book))
    assertFalse(downloads.isDownloaded(book))
    assertFalse(downloads.bookDir(book).exists())
    assertTrue(downloads.isDownloaded(episode))
  }

  @Test
  fun `keeps downloads when the setting is off`() {
    enabled = false
    assertFalse(autoDelete.onFinished(book))
    assertTrue(downloads.isDownloaded(book))
  }

  @Test
  fun `keeps what is loaded in the player`() {
    inPlayer = episode
    assertFalse(autoDelete.onFinished(episode))
    assertTrue(downloads.isDownloaded(episode))
  }

  @Test
  fun `ignores items that are not downloaded`() {
    assertFalse(autoDelete.onFinished(MediaKey("li_other")))
  }

  @Test
  fun `sweep removes only finished downloads`() {
    val unfinished = mapOf(book.key to ServerProgress(1.0, 0.1, isFinished = false, lastUpdate = 1, itemId = book.itemId))

    assertEquals(1, autoDelete.sweep(unfinished + finished(episode)))

    assertTrue(downloads.isDownloaded(book))
    assertFalse(downloads.isDownloaded(episode))
  }

  @Test
  fun `sweep does nothing when the setting is off`() {
    enabled = false
    assertEquals(0, autoDelete.sweep(finished(book, episode)))
    assertEquals(2, downloads.books.value.size)
  }

  @Test
  fun `episode downloads are found by key and share their cover`() {
    assertEquals(episode, downloads.get(episode)?.mediaKey)
    assertEquals(downloads.coverFile(episode), downloads.coverFile(episode.itemId))
  }
}
