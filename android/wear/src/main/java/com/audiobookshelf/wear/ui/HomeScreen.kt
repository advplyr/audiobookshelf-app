package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import coil.compose.AsyncImage
import com.audiobookshelf.wear.data.BookSummary
import com.audiobookshelf.wear.data.Library
import com.audiobookshelf.wear.data.ServerProgress
import com.audiobookshelf.wear.playback.NowPlaying
import kotlin.math.roundToInt

private data class HomeData(
  val continueListening: List<BookSummary>,
  val libraries: List<Library>,
  val progress: Map<String, ServerProgress>
)

@Composable
fun HomeScreen(nav: NavHostController) {
  val app = LocalApp.current
  val nowPlaying by NowPlaying.book.collectAsState()
  val downloads by app.downloads.books.collectAsState()
  val (home, retry) = rememberLoad {
    HomeData(app.api.itemsInProgress(), app.api.libraries().filter { it.mediaType == "book" }, app.api.allProgress())
  }

  ListScreen {
    item { ListHeader { Text("Audiobookshelf") } }

    nowPlaying?.let { playing ->
      item {
        Chip(
          label = { Text(playing.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          secondaryLabel = { Text("Now playing") },
          icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
          onClick = { nav.navigate(Routes.PLAYER) },
          colors = ChipDefaults.primaryChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    item {
      Chip(
        label = { Text("Downloads") },
        secondaryLabel = { Text("${downloads.size} on watch") },
        icon = { Icon(Icons.Default.DownloadDone, contentDescription = null) },
        onClick = { nav.navigate(Routes.DOWNLOADS) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }

    when (home) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> {
        item { Text("Offline. Downloads still work.") }
        errorItem(home.message, retry)
      }
      is Load.Ready -> {
        val data = home.value
        if (data.continueListening.isNotEmpty()) {
          item { ListHeader { Text("Continue listening") } }
          bookChips(data.continueListening, data.progress) { nav.navigate(Routes.item(it.id)) }
        }
        item { ListHeader { Text("Libraries") } }
        items(data.libraries, key = { it.id }) { library ->
          Chip(
            label = { Text(library.name) },
            icon = { Icon(Icons.AutoMirrored.Default.LibraryBooks, contentDescription = null) },
            onClick = { nav.navigate(Routes.library(library.id)) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth()
          )
        }
        item {
          Chip(
            label = { Text("Search") },
            secondaryLabel = { Text("All libraries") },
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            onClick = { nav.navigate(Routes.search(ALL_LIBRARIES)) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }

    item {
      Chip(
        label = { Text("Settings") },
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        onClick = { nav.navigate(Routes.SETTINGS) },
        colors = ChipDefaults.childChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

fun ScalingLazyListScope.bookChips(
  books: List<BookSummary>,
  progress: Map<String, ServerProgress> = emptyMap(),
  onClick: (BookSummary) -> Unit
) {
  items(books, key = { it.id }) { book ->
    BookChip(book, progress[book.id]) { onClick(book) }
  }
}

@Composable
fun BookChip(book: BookSummary, progress: ServerProgress?, onClick: () -> Unit) {
  val app = LocalApp.current
  val detail = when {
    progress?.isFinished == true -> "Finished"
    progress != null && progress.progress > 0 -> "${(progress.progress * 100).roundToInt()}% · ${book.author}"
    else -> book.author
  }
  Chip(
    label = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    secondaryLabel = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    icon = {
      AsyncImage(
        model = app.api.coverUrl(book.id, 120),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(ChipDefaults.LargeIconSize).clip(RoundedCornerShape(4))
      )
    },
    onClick = onClick,
    colors = ChipDefaults.secondaryChipColors(),
    modifier = Modifier.fillMaxWidth()
  )
}
