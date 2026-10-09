package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.data.BookSummary
import com.audiobookshelf.wear.data.Library
import com.audiobookshelf.wear.data.ServerProgress
import com.audiobookshelf.wear.download.Downloads
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
  val context = LocalContext.current
  val nowPlaying by NowPlaying.book.collectAsState()
  val downloads by app.downloads.books.collectAsState()
  val active by remember { Downloads.active(context) }.collectAsState(emptyList())
  val (home, retry) = rememberLoad {
    HomeData(app.api.itemsInProgress(), app.api.audioLibraries(), app.api.allProgress())
  }

  ListScreen(
    edgeButton = {
      EdgeButton(onClick = { nav.navigate(Routes.search(ALL_LIBRARIES)) }) {
        Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize))
      }
    }
  ) {
    header("Audiobookshelf")

    nowPlaying?.let { playing ->
      item {
        BookButton(
          spec = spec,
          title = playing.book.title,
          subtitle = "Now playing",
          cover = if (playing.isLocal) app.downloads.coverFile(playing.book.id) else app.api.coverUrl(playing.book.id, 120),
          onClick = { nav.navigate(Routes.PLAYER) },
          primary = true
        )
      }
    }

    item {
      val subtitle = when {
        active.isNotEmpty() -> "Downloading ${active.size}…"
        downloads.isEmpty() -> "No downloads yet"
        downloads.size == 1 -> "1 book"
        else -> "${downloads.size} books"
      }
      MenuButton(spec, Icons.Default.DownloadDone, "On this watch", subtitle) { nav.navigate(Routes.DOWNLOADS) }
    }

    when (home) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> {
        item { MenuButton(spec, Icons.Default.CloudOff, home.message, "Tap to try again", onClick = retry) }
      }
      is Load.Ready -> {
        val data = home.value
        if (data.continueListening.isNotEmpty()) {
          header("Continue listening")
          items(data.continueListening, key = { it.id }) { book ->
            BookButton(spec, book.title, progressLabel(book, data.progress[book.id]), app.api.coverUrl(book.id, 120), { nav.navigate(Routes.item(book.id)) })
          }
        }
        header("Libraries")
        items(data.libraries, key = { it.id }) { library ->
          MenuButton(spec, Icons.AutoMirrored.Default.LibraryBooks, library.name) { nav.navigate(Routes.library(library.id, library.name)) }
        }
      }
    }

    item { MenuButton(spec, Icons.Default.Settings, "Settings") { nav.navigate(Routes.SETTINGS) } }
  }
}

fun progressLabel(book: BookSummary, progress: ServerProgress?): String = when {
  progress?.isFinished == true -> "Finished"
  progress != null && (progress.progress * 100).roundToInt() > 0 -> "${(progress.progress * 100).roundToInt()}% · ${book.author}"
  else -> book.author
}

@Composable
fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope.MenuButton(
  spec: androidx.wear.compose.material3.lazy.TransformationSpec,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  secondary: String? = null,
  onClick: () -> Unit
) {
  FilledTonalButton(
    onClick = onClick,
    modifier = itemModifier(spec),
    transformation = SurfaceTransformation(spec),
    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
    secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
  )
}
