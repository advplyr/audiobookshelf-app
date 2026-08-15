package com.audiobookshelf.app.player

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import com.audiobookshelf.app.data.ItemInProgress
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryStats
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.data.libraryItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `BrowseTree` builds the Android Auto browse hierarchy; icon lookups go through `media/icons.kt`. */
class BrowseTreeTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    mockkStatic(Uri::class)
    every { Uri.parse(any()) } returns mockk(relaxed = true)
    val resources = mockk<Resources>(relaxed = true)
    context = mockk()
    every { context.resources } returns resources
    every { resources.getResourcePackageName(any()) } returns "com.audiobookshelf.app"
    every { resources.getResourceTypeName(any()) } returns "drawable"
    every { resources.getResourceEntryName(any()) } returns "entry"
  }

  @After
  fun tearDown() {
    unmockkStatic(Uri::class)
  }

  private fun library(id: String, numAudioFiles: Int?) =
          Library(
                  id, "Library $id", mutableListOf(), "database", "book",
                  numAudioFiles?.let { LibraryStats(0, 0, 0.0, it) }
          )

  @Test
  fun `an empty tree only exposes the downloads root`() {
    val tree = BrowseTree(context, emptyList(), emptyList(), recentsLoaded = false)

    val root = tree[AUTO_BROWSE_ROOT]

    assertEquals(1, root?.size)
    assertNull(tree[LIBRARIES_ROOT])
    assertNull(tree[RECENTLY_ROOT])
    assertNull(tree[CONTINUE_ROOT])
  }

  @Test
  fun `items in progress add a continue-listening entry to the root`() {
    val itemInProgress = ItemInProgress(libraryItem(book()), null, 0L, false)

    val tree = BrowseTree(context, listOf(itemInProgress), emptyList(), recentsLoaded = false)

    val root = tree[AUTO_BROWSE_ROOT]
    assertEquals(2, root?.size) // continue-listening + downloads
  }

  @Test
  fun `libraries populate the libraries root and the root menu`() {
    val tree = BrowseTree(context, emptyList(), listOf(library("lib-1", 5)), recentsLoaded = false)

    assertEquals(1, tree[LIBRARIES_ROOT]?.size)
    // root: libraries entry + downloads entry (no recents since recentsLoaded = false)
    assertEquals(2, tree[AUTO_BROWSE_ROOT]?.size)
    assertNull(tree[RECENTLY_ROOT])
  }

  @Test
  fun `a library with zero audio files is skipped from the libraries root`() {
    val tree =
            BrowseTree(
                    context, emptyList(),
                    listOf(library("lib-empty", 0), library("lib-full", 5)),
                    recentsLoaded = false
            )

    assertEquals(1, tree[LIBRARIES_ROOT]?.size)
  }

  @Test
  fun `a library with unknown stats is not skipped`() {
    val tree = BrowseTree(context, emptyList(), listOf(library("lib-1", null)), recentsLoaded = false)

    assertEquals(1, tree[LIBRARIES_ROOT]?.size)
  }

  @Test
  fun `recentsLoaded true adds a recent-books root and a recent root menu entry`() {
    val tree =
            BrowseTree(context, emptyList(), listOf(library("lib-1", 5)), recentsLoaded = true)

    assertEquals(1, tree[RECENTLY_ROOT]?.size)
    // root: recent entry + libraries entry + downloads entry
    assertEquals(3, tree[AUTO_BROWSE_ROOT]?.size)
  }

  @Test
  fun `unknown media ids resolve to null`() {
    val tree = BrowseTree(context, emptyList(), emptyList(), recentsLoaded = false)

    assertTrue(tree["__NOT_A_REAL_ID__"] == null)
  }
}
