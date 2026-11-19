package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.data.EpisodeSummary
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.data.PodcastDetail
import com.audiobookshelf.wear.data.ServerProgress
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private data class PodcastData(val podcast: PodcastDetail, val progress: Map<String, ServerProgress>)

/** A podcast's episodes, newest first. Tapping one opens it like a book. */
@Composable
fun PodcastScreen(nav: NavHostController, itemId: String) {
  val app = LocalApp.current
  val downloads by app.downloads.books.collectAsState()
  val coverSize = (LocalConfiguration.current.screenWidthDp * 0.3f).dp
  val (data, retry) = rememberLoad(itemId) {
    PodcastData(app.api.podcast(itemId), runCatching { app.api.allProgress() }.getOrDefault(emptyMap()))
  }

  ListScreen {
    item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Cover(coverModel(app, itemId), coverSize) } }
    when (data) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(data.message, retry)
      is Load.Ready -> {
        val podcast = data.value.podcast
        item {
          Text(
            podcast.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = contentModifier(spec).padding(horizontal = 8.dp)
          )
        }
        if (podcast.author.isNotEmpty()) caption(podcast.author)
        if (podcast.episodes.isEmpty()) caption("No episodes")
        items(podcast.episodes, key = { it.id }) { episode ->
          val key = MediaKey(itemId, episode.id)
          val downloaded = downloads.any { it.book.mediaKey == key }
          Button(
            onClick = { nav.navigate(Routes.item(itemId, episode.id)) },
            modifier = itemModifier(spec),
            transformation = SurfaceTransformation(spec),
            colors = ButtonDefaults.filledTonalButtonColors(),
            secondaryLabel = { Text(episodeStatus(episode, data.value.progress[key.key], downloaded), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            label = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
          )
        }
      }
    }
  }
}

/** e.g. "12 Mar 2025 · 42% · On watch" */
fun episodeStatus(episode: EpisodeSummary, progress: ServerProgress?, downloaded: Boolean): String = listOfNotNull(
  episode.publishedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
  when {
    progress?.isFinished == true -> "Finished"
    progress != null && (progress.progress * 100).roundToInt() > 0 -> "${(progress.progress * 100).roundToInt()}%"
    episode.duration > 0 -> formatDuration(episode.duration)
    else -> null
  },
  if (downloaded) "On watch" else null
).joinToString(" · ")
