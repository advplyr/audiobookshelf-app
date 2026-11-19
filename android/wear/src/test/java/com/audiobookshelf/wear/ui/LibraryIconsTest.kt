package com.audiobookshelf.wear.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Keeps the watch's library icons in step with the phone app's (tests run from `android/wear`). */
class LibraryIconsTest {
  private val repo = File("../..")

  /** Icon names a library can have, from `libraryIcons` in the phone app's store. */
  private fun phoneIcons(): List<String> {
    val line = File(repo, "store/globals.js").readLines().first { it.trim().startsWith("libraryIcons:") }
    return Regex("'([a-z0-9-]+)'").findAll(line).map { it.groupValues[1] }.toList()
  }

  /** Codepoint per icon name, from the phone app's icon stylesheet. */
  private fun phoneCodepoints(): Map<String, Int> =
    Regex("""\.icon-([a-z0-9-]+):before \{\s*content: "\\([0-9a-f]+)"""")
      .findAll(File(repo, "assets/absicons.css").readText())
      .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }

  @Test
  fun `every icon the phone offers draws the same glyph`() {
    val codepoints = phoneCodepoints()
    val icons = phoneIcons()
    assertEquals(17, icons.size)
    assertEquals(icons.toSet(), LibraryIcons.names)
    for (icon in icons) {
      assertEquals(icon, Character.toChars(codepoints.getValue(icon)).concatToString(), LibraryIcons.glyph(icon))
    }
  }

  @Test
  fun `unknown or missing icons show the Audiobookshelf logo`() {
    val logo = LibraryIcons.glyph("audiobookshelf")
    assertEquals(logo, LibraryIcons.glyph(""))
    assertEquals(logo, LibraryIcons.glyph("not-an-icon"))
  }

  @Test
  fun `bundled font is the phone app's icon font`() {
    assertArrayEquals(
      File(repo, "static/fonts/absicons/absicons.ttf").readBytes(),
      File("src/main/res/font/absicons.ttf").readBytes()
    )
  }
}
