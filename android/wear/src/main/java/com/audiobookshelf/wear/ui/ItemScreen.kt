package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonDefaults
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.data.BookDetail
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.data.ServerProgress
import com.audiobookshelf.wear.download.DownloadState
import com.audiobookshelf.wear.download.Downloads
import com.audiobookshelf.wear.playback.NowPlaying
import com.audiobookshelf.wear.playback.PlaybackService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class ItemData(val book: BookDetail, val progress: ServerProgress?)

/** A book, or a single podcast episode when [key] has an episode id. */
@Composable
fun ItemScreen(nav: NavHostController, key: MediaKey) {
  val itemId = key.itemId
  val app = LocalApp.current
  val player = LocalPlayer.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val downloadedBooks by app.downloads.books.collectAsState()
  val downloaded = downloadedBooks.firstOrNull { it.book.mediaKey == key }
  val downloadState by remember(key) { Downloads.state(context, key) }.collectAsState(DownloadState.None)
  val nowPlaying by NowPlaying.book.collectAsState()
  var confirmRemove by remember { mutableStateOf(false) }
  var starting by remember { mutableStateOf(false) }
  var playError by remember { mutableStateOf<String?>(null) }
  var updatingFinished by remember { mutableStateOf(false) }
  val coverSize = (LocalConfiguration.current.screenWidthDp * 0.34f).dp

  val (data, retry) = rememberLoad(key) {
    try {
      ItemData(app.api.playable(key), runCatching { app.api.progress(key) }.getOrNull())
    } catch (e: Exception) {
      // Offline: fall back to the downloaded copy
      ItemData(app.downloads.get(key) ?: throw e, null)
    }
  }
  val ready = data as? Load.Ready
  val isCurrent = nowPlaying?.book?.mediaKey == key
  val isFinished = ready?.value?.progress?.isFinished == true
  val hasProgress = !isFinished && (ready?.value?.progress?.currentTime ?: app.progress.get(key)?.currentTime ?: 0.0) > 0

  fun setFinished(finished: Boolean) {
    updatingFinished = true
    playError = null
    scope.launch {
      try {
        if (finished) {
          // Finished books aren't left in the player
          if (isCurrent) PlaybackService.current?.unload()
          PlaybackService.markFinished(app, key)
        } else {
          app.api.setFinished(key, false)
          app.progress.save(key, 0.0, System.currentTimeMillis())
        }
        retry()
      } catch (e: Exception) {
        playError = if (e is java.io.IOException) "Needs a connection" else describeError(e)
      } finally {
        updatingFinished = false
      }
    }
  }

  fun play() {
    val book = ready?.value?.book ?: return
    starting = true
    playError = null
    scope.launch {
      try {
        startBook(player, nav, book)
      } catch (e: Exception) {
        playError = e.message ?: "Could not start playback"
      } finally {
        starting = false
      }
    }
  }

  ListScreen(
    edgeButton = {
      EdgeButton(onClick = ::play, enabled = ready != null && !starting, buttonSize = EdgeButtonSize.Medium) {
        if (starting) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
          // Small watches only have room for the label
          if (LocalConfiguration.current.screenWidthDp >= 210) {
            Icon(
              Icons.Default.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize).align(Alignment.CenterVertically)
            )
          }
          Text(
            if (isCurrent) "Playing" else if (hasProgress) "Resume" else "Play",
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterVertically)
          )
        }
      }
    }
  ) {
    item {
      val cover = app.downloads.coverFile(itemId).takeIf { it.exists() } ?: app.api.coverUrl(itemId, 300)
      // Not morphed: a shrinking cover would slide under the title while scrolling
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Cover(cover, coverSize) }
    }
    when (data) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(data.message, retry)
      is Load.Ready -> {
        val book = data.value.book
        val progress = data.value.progress
        item {
          Text(
            book.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = contentModifier(spec).padding(horizontal = 8.dp)
          )
        }
        // For an episode this is the podcast
        if (book.author.isNotEmpty()) caption(book.author)
        caption(
          listOfNotNull(
            formatDuration(book.duration),
            when {
              progress?.isFinished == true -> "Finished"
              progress != null && (progress.progress * 100).roundToInt() > 0 -> "${(progress.progress * 100).roundToInt()}%"
              else -> null
            },
            book.chapters.size.takeIf { it > 1 }?.let { "$it chapters" }
          ).joinToString(" · ")
        )
        playError?.let { caption(it) { MaterialTheme.colorScheme.error } }
        item {
          val state = downloadState
          when {
            downloaded != null -> OutlinedButton(
              onClick = { confirmRemove = true },
              enabled = !(isCurrent && nowPlaying?.isLocal == true),
              modifier = itemModifier(spec),
              transformation = SurfaceTransformation(spec),
              icon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
              secondaryLabel = { Text("${formatBytes(downloaded.sizeBytes)} on watch") },
              label = { Text("Remove download") }
            )
            state is DownloadState.Running -> FilledTonalButton(
              onClick = { Downloads.cancel(context, key) },
              modifier = itemModifier(spec),
              transformation = SurfaceTransformation(spec),
              icon = { ProgressRing(state.percent / 100f, ButtonDefaults.IconSize) },
              secondaryLabel = { Text("Tap to cancel") },
              label = { Text("Downloading ${state.percent}%") }
            )
            else -> FilledTonalButton(
              onClick = { Downloads.start(context, key, book.title) },
              modifier = itemModifier(spec),
              transformation = SurfaceTransformation(spec),
              icon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
              secondaryLabel = {
                Text(
                  if (state is DownloadState.Failed) state.message else "${formatBytes(book.totalSize)} · ${formatBytes(app.downloads.freeBytes())} free",
                  maxLines = 2
                )
              },
              label = { Text(if (state is DownloadState.Failed) "Retry download" else "Download") }
            )
          }
        }
        item {
          OutlinedButton(
            onClick = { setFinished(!isFinished) },
            enabled = !updatingFinished,
            modifier = itemModifier(spec),
            transformation = SurfaceTransformation(spec),
            icon = {
              if (updatingFinished) {
                CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
              } else {
                Icon(if (isFinished) Icons.Default.Restore else Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
              }
            },
            secondaryLabel = if (isFinished) ({ Text("Starts over") }) else null,
            label = { Text(if (isFinished) "Mark as not finished" else "Mark as finished") }
          )
        }
      }
    }
  }

  AlertDialog(
    visible = confirmRemove,
    onDismissRequest = { confirmRemove = false },
    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
    title = { Text("Remove download?") },
    text = { Text("Your progress is kept. You can still stream this book.") },
    confirmButton = {
      AlertDialogDefaults.ConfirmButton(onClick = {
        app.downloads.delete(key)
        confirmRemove = false
      })
    },
    dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmRemove = false }) }
  )
}
