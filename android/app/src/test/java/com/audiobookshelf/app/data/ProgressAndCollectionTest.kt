package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressAndCollectionTest {
  @Test
  fun `progress percentage rounds canonical input`() {
    assertEquals(0, localProgress(Double.NaN).progressPercent)
    assertEquals(43, localProgress(0.426).progressPercent)
  }

  @Test
  fun `progress percentage handles infinite and out-of-range inputs`() {
    assertEquals(0, localProgress(Double.POSITIVE_INFINITY).progressPercent)
    assertEquals(0, localProgress(-0.2).progressPercent)
    assertEquals(100, localProgress(1.2).progressPercent)
  }

  @Test
  fun `book progress does not match an episode from same library item`() {
    val local = localProgress(episodeId = null)
    val book = serverProgress(episodeId = null)
    val episode = serverProgress(episodeId = "episode")

    assertTrue(local.isMatch(book))
    assertFalse(local.isMatch(episode))
  }

  @Test
  fun `episode progress matches only same episode`() {
    val local = localProgress(episodeId = "one")

    assertTrue(local.isMatch(serverProgress(episodeId = "one")))
    assertFalse(local.isMatch(serverProgress(episodeId = "two")))
  }

  @Test
  fun `playback updates finish state at threshold`() {
    val progress = localProgress()
    progress.updateFromPlaybackSession(playbackSession(mutableListOf(audioTrack(duration = 100.0)), currentTime = 99.0))

    assertTrue(progress.isFinished)
    assertEquals(99.0, progress.currentTime, 0.0)
    assertEquals(progress.lastUpdate, progress.finishedAt)
  }

  @Test
  fun `unfinishing progress clears finished time and resets progress`() {
    val progress = localProgress(1.0)
    progress.isFinished = true
    progress.finishedAt = 10

    progress.updateIsFinished(false)

    assertFalse(progress.isFinished)
    assertEquals(0.0, progress.progress, 0.0)
    assertNull(progress.finishedAt)
  }

  @Test
  fun `empty series metadata produces empty sequence without throwing`() {
    val item = libraryItem(book(series = emptyList()))

    assertEquals("", item.seriesSequence)
    assertEquals(listOf(""), item.seriesSequenceParts)
  }

  @Test
  fun `collection ignores non-book media when counting audiobooks`() {
    val collection =
            LibraryCollection(
                    "collection",
                    "library",
                    "Mixed",
                    null,
                    mutableListOf(
                            libraryItem(book(mutableListOf(audioTrack()))),
                            libraryItem(MediaType(MediaTypeMetadata("Unknown", false), null))
                    )
            )

    assertEquals(2, collection.bookCount)
    assertEquals(1, collection.audiobookCount)
  }

  @Test
  fun `author without count or loaded items reports zero books`() {
    val author = LibraryAuthorItem("author", "library", "Author", null, null, 0, 0, null, null, null)

    assertEquals(0, author.bookCount)
  }

  @Test
  fun `local media item totals duration and size including empty lists`() {
    val empty = LocalMediaItem("id", "Empty", "book", "folder", "", "", "", mutableListOf(), null, mutableListOf(), null, null)
    val populated = LocalMediaItem("id", "Book", "book", "folder", "", "", "", mutableListOf(audioTrack(duration = 1.5), audioTrack(duration = 2.5)), null, mutableListOf(localFile(3), localFile(7)), null, null)

    assertEquals(0.0, empty.getDuration(), 0.0)
    assertEquals(0L, empty.getTotalSize())
    assertEquals(4.0, populated.getDuration(), 0.0)
    assertEquals(10L, populated.getTotalSize())
    assertTrue(populated.getMediaMetadata() is BookMetadata)
  }

  private fun serverProgress(episodeId: String?) =
          MediaProgress("id", "library-item", episodeId, 10.0, 0.5, 5.0, false, null, null, 0, 0, null)

  private fun localFile(size: Long) = LocalFile("id-$size", null, "", "", "/$size", null, size)
}
