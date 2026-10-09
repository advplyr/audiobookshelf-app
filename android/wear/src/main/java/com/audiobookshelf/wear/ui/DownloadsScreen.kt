package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import coil.compose.AsyncImage
import com.audiobookshelf.wear.download.Downloads

@Composable
fun DownloadsScreen(nav: NavHostController) {
  val app = LocalApp.current
  val context = LocalContext.current
  val books by app.downloads.books.collectAsState()
  val active by remember { Downloads.active(context) }.collectAsState(emptySet())

  ListScreen {
    item { ListHeader { Text("On this watch") } }
    item {
      Text(
        "${formatBytes(books.sumOf { it.sizeBytes })} used · ${formatBytes(app.downloads.freeBytes())} free",
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center
      )
    }
    val inProgress = active - books.map { it.book.id }.toSet()
    if (inProgress.isNotEmpty()) {
      item {
        Text("${inProgress.size} downloading…", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.primary)
      }
    }
    if (books.isEmpty()) {
      item {
        Text(
          "Open a book and tap \"Download to watch\" to listen without your phone.",
          style = MaterialTheme.typography.caption2,
          textAlign = TextAlign.Center
        )
      }
    }
    items(books, key = { it.book.id }) { downloaded ->
      val coverFile = app.downloads.coverFile(downloaded.book.id)
      Chip(
        label = { Text(downloaded.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(formatBytes(downloaded.sizeBytes)) },
        icon = {
          if (coverFile.exists()) {
            AsyncImage(
              model = coverFile,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.size(ChipDefaults.LargeIconSize).clip(RoundedCornerShape(4))
            )
          }
        },
        onClick = { nav.navigate(Routes.item(downloaded.book.id)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}
