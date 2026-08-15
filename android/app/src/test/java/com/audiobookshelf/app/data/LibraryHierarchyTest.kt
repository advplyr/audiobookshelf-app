package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryHierarchyTest {
  @Test
  fun `series audiobook count is zero when books list is absent or empty`() {
    val noBooks = LibrarySeriesItem("series", "library", "Series", null, 0, 0, null, null)
    val emptyBooks = LibrarySeriesItem("series", "library", "Series", null, 0, 0, mutableListOf(), null)

    assertEquals(0, noBooks.audiobookCount)
    assertEquals(0, emptyBooks.audiobookCount)
  }

  @Test
  fun `series audiobook count only includes books with tracks`() {
    val withTracks = libraryItem(book(mutableListOf(audioTrack())))
    val withoutTracks = libraryItem(book(tracks = null))
    val series =
            LibrarySeriesItem(
                    "series",
                    "library",
                    "Series",
                    null,
                    0,
                    0,
                    mutableListOf(withTracks, withoutTracks),
                    null
            )

    assertEquals(1, series.audiobookCount)
  }

  @Test
  fun `author book count prefers explicit numBooks over loaded items`() {
    val author =
            LibraryAuthorItem("author", "library", "Author", null, null, 0, 0, 5, mutableListOf(libraryItem()), null)

    assertEquals(5, author.bookCount)
  }

  @Test
  fun `author book count falls back to loaded item count when numBooks is null`() {
    val author =
            LibraryAuthorItem(
                    "author",
                    "library",
                    "Author",
                    null,
                    null,
                    0,
                    0,
                    null,
                    mutableListOf(libraryItem(), libraryItem()),
                    null
            )

    assertEquals(2, author.bookCount)
  }

  @Test
  fun `collapsed series exposes name as title and item count as numBooks`() {
    val series = CollapsedSeries("series", "library", "My Series", "1", mutableListOf("a", "b", "c"))

    assertEquals("My Series", series.title)
    assertEquals(3, series.numBooks)
  }

  @Test
  fun `library item title and author delegate to media metadata by default`() {
    val item = libraryItem(book())

    assertEquals("Book", item.title)
    assertEquals("Unknown", item.authorName)
  }

  @Test
  fun `library item title prefers collapsed series title when present`() {
    val item = libraryItem(book())
    item.collapsedSeries = CollapsedSeries("series", "library", "Collapsed Title", null, mutableListOf("a"))

    assertEquals("Collapsed Title", item.title)
  }
}
