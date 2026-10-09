package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import coil.compose.AsyncImage
import com.audiobookshelf.wear.data.BookDetail
import com.audiobookshelf.wear.data.ServerProgress
import com.audiobookshelf.wear.download.DownloadState
import com.audiobookshelf.wear.download.Downloads
import com.audiobookshelf.wear.playback.NowPlaying
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class ItemData(val book: BookDetail, val progress: ServerProgress?)

@Composable
fun ItemScreen(nav: NavHostController, itemId: String) {
  val app = LocalApp.current
  val player = LocalPlayer.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val downloadedBooks by app.downloads.books.collectAsState()
  val downloaded = downloadedBooks.firstOrNull { it.book.id == itemId }
  val downloadState by remember(itemId) { Downloads.state(context, itemId) }.collectAsState(DownloadState.None)
  val nowPlaying by NowPlaying.book.collectAsState()
  var confirmRemove by remember { mutableStateOf(false) }
  var starting by remember { mutableStateOf(false) }
  var playError by remember { mutableStateOf<String?>(null) }

  val (data, retry) = rememberLoad(itemId) {
    try {
      ItemData(app.api.item(itemId), runCatching { app.api.progress(itemId) }.getOrNull())
    } catch (e: Exception) {
      // Offline: fall back to the downloaded copy
      ItemData(app.downloads.get(itemId) ?: throw e, null)
    }
  }

  ListScreen {
    item {
      val cover = app.downloads.coverFile(itemId).takeIf { downloaded != null && it.exists() } ?: app.api.coverUrl(itemId, 300)
      AsyncImage(
        model = cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
      )
    }
    when (data) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(data.message, retry)
      is Load.Ready -> {
        val book = data.value.book
        val progress = data.value.progress
        item { Text(book.title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
        item {
          val progressText = when {
            progress?.isFinished == true -> " · Finished"
            progress != null && progress.progress > 0 -> " · ${(progress.progress * 100).roundToInt()}%"
            else -> ""
          }
          Text(
            "${book.author}\n${formatDuration(book.duration)}$progressText",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center
          )
        }
        item {
          val isCurrent = nowPlaying?.book?.id == book.id
          Chip(
            label = { Text(if (starting) "Starting…" else if (isCurrent) "Open player" else if ((progress?.currentTime ?: 0.0) > 0) "Resume" else "Play") },
            secondaryLabel = { Text(playError ?: if (downloaded != null) "From watch" else "Streaming") },
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            enabled = !starting,
            onClick = {
              starting = true
              playError = null
              scope.launch {
                try {
                  if (!isCurrent) player.play(book)
                  nav.navigate(Routes.PLAYER)
                } catch (e: Exception) {
                  playError = e.message ?: "Could not start playback"
                } finally {
                  starting = false
                }
              }
            },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth()
          )
        }
        item {
          val state = downloadState
          when {
            downloaded != null -> Chip(
              label = { Text(if (confirmRemove) "Tap again to remove" else "Remove download") },
              secondaryLabel = { Text(formatBytes(downloaded.sizeBytes)) },
              icon = { Icon(Icons.Default.Delete, contentDescription = null) },
              enabled = !(nowPlaying?.book?.id == itemId && nowPlaying?.isLocal == true),
              onClick = {
                if (confirmRemove) app.downloads.delete(itemId) else confirmRemove = true
              },
              colors = ChipDefaults.secondaryChipColors(),
              modifier = Modifier.fillMaxWidth()
            )
            state is DownloadState.Running -> Chip(
              label = { Text("Downloading ${state.percent}%") },
              secondaryLabel = { Text("Tap to cancel") },
              icon = { Icon(Icons.Default.Close, contentDescription = null) },
              onClick = { Downloads.cancel(context, itemId) },
              colors = ChipDefaults.secondaryChipColors(),
              modifier = Modifier.fillMaxWidth()
            )
            else -> Chip(
              label = { Text(if (state is DownloadState.Failed) "Retry download" else "Download to watch") },
              secondaryLabel = {
                Text(if (state is DownloadState.Failed) state.message else "${formatBytes(book.totalSize)} · ${formatBytes(app.downloads.freeBytes())} free")
              },
              icon = { Icon(Icons.Default.Download, contentDescription = null) },
              onClick = { Downloads.start(context, book.id, book.title) },
              colors = ChipDefaults.secondaryChipColors(),
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
        if (book.chapters.isNotEmpty()) {
          item { Text("${book.chapters.size} chapters", style = MaterialTheme.typography.caption3) }
        }
      }
    }
  }
}
