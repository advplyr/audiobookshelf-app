package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.download.Downloads

@Composable
fun DownloadsScreen(nav: NavHostController) {
  val app = LocalApp.current
  val context = LocalContext.current
  val books by app.downloads.books.collectAsState()
  val active by remember { Downloads.active(context) }.collectAsState(emptyList())
  val used = books.sumOf { it.sizeBytes }
  val free = app.downloads.freeBytes()

  ListScreen {
    header("On this watch")
    item {
      Column(contentModifier(spec).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          "${formatBytes(used)} · ${formatBytes(free)} free",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        LinearProgressIndicator(
          progress = { if (used + free > 0) used.toFloat() / (used + free) else 0f },
          modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
      }
    }

    val downloading = active.filter { a -> books.none { it.book.mediaKey == a.key } }
    items(downloading, key = { "active-${it.key.key}" }) { download ->
      FilledTonalButton(
        onClick = { nav.navigate(Routes.item(download.key.itemId, download.key.episodeId)) },
        modifier = itemModifier(spec),
        transformation = SurfaceTransformation(spec),
        icon = { ProgressRing(download.percent / 100f, ButtonDefaults.LargeIconSize) },
        secondaryLabel = { Text("Downloading ${download.percent}%") },
        label = { Text(download.title.ifEmpty { "Book" }, maxLines = 2, overflow = TextOverflow.Ellipsis) }
      )
    }

    if (books.isEmpty() && downloading.isEmpty()) {
      caption("Open a book or episode and tap Download to listen without your phone.")
    }
    items(books, key = { it.book.mediaKey.key }) { downloaded ->
      val book = downloaded.book
      BookButton(
        spec = spec,
        title = book.title,
        // Episodes show which podcast they're from
        subtitle = listOfNotNull(book.author.takeIf { book.episodeId != null }, formatBytes(downloaded.sizeBytes)).joinToString(" · "),
        cover = app.downloads.coverFile(book.mediaKey).takeIf { it.exists() },
        onClick = { nav.navigate(Routes.item(book.id, book.episodeId)) }
      )
    }
  }
}
