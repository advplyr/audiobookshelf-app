package com.audiobookshelf.app.data

import android.content.Context
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryItemTest {
  @Test
  fun `getDuration sums local audio track durations`() {
    val item = localLibraryItem(media = book(mutableListOf(audioTrack(0, duration = 2.0), audioTrack(1, duration = 3.0))))

    assertEquals(5.0, item.getDuration(), 0.0)
  }

  @Test
  fun `getDuration is zero when no tracks are present`() {
    val item = localLibraryItem(media = book(tracks = null))

    assertEquals(0.0, item.getDuration(), 0.0)
  }

  @Test
  fun `updateFromScan clears cover when it is no longer among local files`() {
    val item = localLibraryItem(coverContentUrl = "content://cover")
    item.coverAbsolutePath = "/local/cover.jpg"

    item.updateFromScan(mutableListOf(), mutableListOf(LocalFile("f1", "other", "content://other", "", "/other", null, 1)))

    assertNull(item.coverContentUrl)
    assertNull(item.coverAbsolutePath)
    assertNull(item.media.coverPath)
  }

  @Test
  fun `updateFromScan keeps cover when it is still among local files`() {
    val item = localLibraryItem(coverContentUrl = "content://cover")

    item.updateFromScan(mutableListOf(), mutableListOf(LocalFile("f1", "cover", "content://cover", "", "/cover", null, 1)))

    assertEquals("content://cover", item.coverContentUrl)
  }

  @Test
  fun `hasTracks is false when there are no audio tracks`() {
    val ctx = mockk<Context>()
    val item = localLibraryItem(media = book(tracks = mutableListOf()))

    assertFalse(item.hasTracks(ctx, null))
  }

  @Test
  fun `hasTracks is false when track metadata is missing`() {
    val ctx = mockk<Context>()
    val track = audioTrack().copy(metadata = null)
    val item = localLibraryItem(media = book(mutableListOf(track)))

    assertFalse(item.hasTracks(ctx, null))
  }

  @Test
  fun `hasTracks reflects whether the backing file still exists on disk`() {
    val ctx = mockk<Context>()
    val temp = File.createTempFile("abs-track", ".mp3")
    temp.deleteOnExit()
    val metadata = FileMetadata(temp.name, "mp3", temp.absolutePath, temp.name, temp.length())
    val track = audioTrack().copy(contentUrl = temp.absolutePath, metadata = metadata)
    val item = localLibraryItem(media = book(mutableListOf(track)))

    assertTrue(item.hasTracks(ctx, null))

    temp.delete()
    assertFalse(item.hasTracks(ctx, null))
  }
}
