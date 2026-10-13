package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.R

/**
 * The icons a library can have on the server, drawn with the same `absicons` font as the phone app
 * (`res/font/absicons.ttf`, copied from `static/fonts/absicons`). Names and codepoints match
 * `libraryIcons` in `store/globals.js` and `assets/absicons.css`.
 */
object LibraryIcons {
  private const val FALLBACK = "audiobookshelf"

  private val codepoints = mapOf(
    "database" to 0xe906,
    "audiobookshelf" to 0xe900,
    "books-1" to 0xe905,
    "books-2" to 0xe920,
    "book-1" to 0xe91f,
    "microphone-1" to 0xe902,
    "microphone-3" to 0xe91e,
    "radio" to 0xe903,
    "podcast" to 0xe904,
    "rss" to 0xea9b,
    "headphones" to 0xe910,
    "music" to 0xe911,
    "file-picture" to 0xe927,
    "rocket" to 0xe9a5,
    "power" to 0xe9b5,
    "star" to 0xe9d9,
    "heart" to 0xe9da
  )

  val names: Set<String> get() = codepoints.keys

  /** The glyph for [icon]; unknown icons show the Audiobookshelf logo, like the phone app. */
  fun glyph(icon: String): String = Character.toChars(codepoints[icon] ?: codepoints.getValue(FALLBACK)).concatToString()
}

private val absIcons = FontFamily(Font(R.font.absicons))

/** A library's icon, sized and colored like the other button icons. */
@Composable
fun LibraryIcon(icon: String, modifier: Modifier = Modifier, size: Dp = ButtonDefaults.IconSize) {
  // Sized in dp so the system font size setting doesn't scale the icon. The glyphs fill the whole
  // font box, while Material icons leave a 2dp margin in 24dp, so draw them at 5/6 to match.
  val fontSize = with(LocalDensity.current) { (size * 5 / 6).toSp() }
  Box(modifier.size(size), contentAlignment = Alignment.Center) {
    Text(LibraryIcons.glyph(icon), fontFamily = absIcons, fontSize = fontSize, color = LocalContentColor.current, maxLines = 1)
  }
}
