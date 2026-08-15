package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBookModelTest {
  @Test
  fun `audio track converts seconds and chapter boundaries`() {
    val track = audioTrack(index = 2, startOffset = 1.2349, duration = 2.3459)

    assertEquals(1_234L, track.startOffsetMs)
    assertEquals(2_345L, track.durationMs)
    assertEquals(3_579L, track.endOffsetMs)
    assertEquals(3, track.getBookChapter().id)
    assertEquals(1.2349, track.getBookChapter().start, 0.0)
    assertEquals(3.5808, track.getBookChapter().end, 0.000_001)
    assertEquals("", track.relPath)
  }

  @Test
  fun `book set tracks sorts and rebuilds contiguous offsets`() {
    val late = audioTrack(index = 2, startOffset = 99.0, duration = 3.0)
    val early = audioTrack(index = 1, startOffset = 88.0, duration = 2.0)
    val media = book(tracks = mutableListOf())

    media.setAudioTracks(mutableListOf(late, early))

    assertEquals(listOf(early, late), media.getAudioTracks())
    assertEquals(0.0, early.startOffset, 0.0)
    assertEquals(2.0, late.startOffset, 0.0)
    assertEquals(5.0, media.duration ?: -1.0, 0.0)
  }

  @Test
  fun `book remove track renumbers and rebuilds offsets`() {
    val first = audioTrack(index = 4, duration = 2.0, localFileId = "first")
    val removed = audioTrack(index = 5, duration = 8.0, localFileId = "removed")
    val last = audioTrack(index = 6, duration = 3.0, localFileId = "last")
    val media = book(mutableListOf(first, removed, last))

    media.removeAudioTrack("removed")

    assertEquals(listOf(first, last), media.getAudioTracks())
    assertEquals(listOf(1, 2), media.getAudioTracks().map { it.index })
    assertEquals(listOf(0.0, 2.0), media.getAudioTracks().map { it.startOffset })
    assertEquals(5.0, media.duration ?: -1.0, 0.0)
  }

  @Test
  fun `book add track initializes absent track collection`() {
    val media = book(tracks = null)

    media.addAudioTrack(audioTrack())

    assertEquals(1, media.getAudioTracks().size)
  }

  @Test
  fun `metadata author fallbacks are stable`() {
    assertEquals("Unknown", MediaTypeMetadata("Title", false).getAuthorDisplayName())
    assertEquals("Unknown", bookMetadata().getAuthorDisplayName())
    assertEquals(
            "Unknown",
            PodcastMetadata("Podcast", null, null, mutableListOf(), false).getAuthorDisplayName()
    )
    assertEquals(
            "Host",
            PodcastMetadata("Podcast", "Host", null, mutableListOf(), false).getAuthorDisplayName()
    )
  }

  /**
   * Inputs:   a `BookMetadata` with a populated `authors` collection and no flat `authorName`.
   *
   * Expected: the author's name. The collection is the field the server always sends.
   *
   * Observed: `"Unknown"`. `getAuthorDisplayName()` reads `authorName ?: "Unknown"` and never looks
   *           at `authors` (`DataClasses.kt:238`).
   *
   * Path:     `BookMetadata.getAuthorDisplayName` -> `LibraryItem.authorName` -> every Android Auto
   *           browse label and media description. `authorName` is a flat convenience field the
   *           server adds only in its *minified* and *expanded* serializers; the plain
   *           `toOldJSON()` shape sends the `authors` collection and no `authorName` at all, so
   *           every author renders as "Unknown" for those responses.
   *
   * Found by `GoldenResponseFixtureTest` against a real audiobookshelf 2.36.0 response body - the
   * exact class of defect a hand-written fixture cannot surface, because a hand-written fixture is
   * written to match the model.
   */
  @Test
  fun `author display name falls back to the authors collection when the flat field is absent`() {
    val metadata = bookMetadata().apply { authors = mutableListOf(Author("aut-1", "Terry Goodkind", null)) }

    assertEquals("Terry Goodkind", metadata.getAuthorDisplayName())
  }

  @Test
  fun `author display name joins multiple authors from the collection`() {
    val metadata =
            bookMetadata().apply {
              authors = mutableListOf(Author("aut-1", "Jane Doe", null), Author("aut-2", "John Roe", null))
            }

    assertEquals("Jane Doe, John Roe", metadata.getAuthorDisplayName())
  }

  @Test
  fun `the flat author name still wins when the server provided it`() {
    val metadata =
            bookMetadata().apply {
              authorName = "Flat Name"
              authors = mutableListOf(Author("aut-1", "Collection Name", null))
            }

    assertEquals("Flat Name", metadata.getAuthorDisplayName())
  }

  @Test
  fun `author display name is Unknown when neither the flat field nor the collection has a name`() {
    assertEquals("Unknown", bookMetadata().apply { authors = mutableListOf() }.getAuthorDisplayName())
  }

  @Test
  fun `media progress ids distinguish books and episodes`() {
    assertEquals("book", serverProgress("book", null).mediaItemId)
    assertEquals("book-episode", serverProgress("book", "episode").mediaItemId)
    assertEquals("book", serverProgress("book", "").mediaItemId)
  }

  @Test
  fun `media presence falls back to server counts`() {
    assertFalse(book(tracks = null).checkHasTracks())
    val remoteBook = book(tracks = null).apply { numTracks = 2 }
    assertTrue(remoteBook.checkHasTracks())

    val podcast =
            Podcast(
                    PodcastMetadata("Podcast", null, null, mutableListOf(), false),
                    null,
                    mutableListOf(),
                    null,
                    false,
                    3
            )
    assertTrue(podcast.checkHasTracks())
  }

  private fun serverProgress(libraryItemId: String, episodeId: String?) =
          MediaProgress("id", libraryItemId, episodeId, 10.0, 0.5, 5.0, false, null, null, 0, 0, null)
}
