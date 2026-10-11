package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonDefaults
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.data.BookSummary
import com.audiobookshelf.wear.data.SeriesSummary
import com.audiobookshelf.wear.data.ServerProgress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Every in-progress book and podcast episode, latest first. */
@Composable
fun ContinueListeningScreen(nav: NavHostController) {
  val app = LocalApp.current
  val (data, retry) = rememberLoad { app.api.itemsInProgress() to app.api.allProgress() }

  ListScreen {
    header("Continue listening")
    when (data) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(data.message, retry)
      is Load.Ready -> {
        val (books, progress) = data.value
        if (books.isEmpty()) caption("Nothing in progress")
        items(books, key = { it.mediaKey.key }) { book ->
          BookButton(spec, book.title, progressLabel(book, progress.of(book)), coverModel(app, book.id), { nav.navigate(Routes.open(book)) })
        }
      }
    }
  }
}

/**
 * Picks a library for browsing books and podcasts, or series (book libraries only). With a single
 * library it goes straight there.
 */
@Composable
fun LibraryPickerScreen(nav: NavHostController, forSeries: Boolean) {
  val app = LocalApp.current
  val (libraries, retry) = rememberLoad { app.api.audioLibraries().filter { !forSeries || !it.isPodcast } }
  val ownRoute = if (forSeries) Routes.SERIES_LIBRARIES else Routes.LIBRARIES

  fun open(id: String, name: String) = if (forSeries) Routes.series(id, name) else Routes.library(id, name)

  LaunchedEffect(libraries) {
    val only = (libraries as? Load.Ready)?.value?.singleOrNull() ?: return@LaunchedEffect
    nav.navigate(open(only.id, only.name)) { popUpTo(ownRoute) { inclusive = true } }
  }

  ListScreen(
    edgeButton = {
      EdgeButton(onClick = { nav.navigate(Routes.search(ALL_LIBRARIES)) }) {
        Icon(Icons.Default.Search, contentDescription = "Search all libraries", modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize))
      }
    }
  ) {
    header(if (forSeries) "Series" else "Library")
    when (libraries) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(libraries.message, retry)
      is Load.Ready -> items(libraries.value, key = { it.id }) { library ->
        MenuButton(spec, { LibraryIcon(library.icon) }, library.name) { nav.navigate(open(library.id, library.name)) }
      }
    }
  }
}

/**
 * The book to listen to next in a series: the one you're partway through (most recently played),
 * otherwise the first unfinished book after the last finished one. Null when the series is done.
 */
private fun upNext(books: List<BookSummary>, progress: Map<String, ServerProgress>): BookSummary? {
  fun finished(book: BookSummary) = progress[book.id]?.isFinished == true
  books.filter { !finished(it) && (progress[it.id]?.progress ?: 0.0) > 0 }
    .maxByOrNull { progress[it.id]!!.lastUpdate }
    ?.let { return it }
  val lastFinished = books.indexOfLast(::finished)
  return books.drop(lastFinished + 1).firstOrNull { !finished(it) } ?: books.firstOrNull { !finished(it) }
}

private fun bookNumber(book: BookSummary, books: List<BookSummary>): String =
  book.seriesSequence ?: (books.indexOf(book) + 1).toString()

@Composable
fun SeriesListScreen(nav: NavHostController, libraryId: String, libraryName: String) {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  val series = remember { mutableStateListOf<SeriesSummary>() }
  var progress by remember { mutableStateOf<Map<String, ServerProgress>>(emptyMap()) }
  var page by remember { mutableIntStateOf(0) }
  var total by remember { mutableIntStateOf(-1) }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  fun loadMore() {
    if (loading) return
    loading = true
    error = null
    scope.launch {
      try {
        if (page == 0) progress = runCatching { app.api.allProgress() }.getOrDefault(emptyMap())
        val (results, count) = app.api.librarySeries(libraryId, page)
        series.addAll(results)
        total = count
        page++
      } catch (e: Exception) {
        error = describeError(e)
      } finally {
        loading = false
      }
    }
  }

  LaunchedEffect(libraryId) { if (series.isEmpty()) loadMore() }

  ListScreen {
    header("Series")
    caption(libraryName)
    val visible = series.toList().mapNotNull { s -> s.books.takeIf { it.isNotEmpty() }?.let { s to it } }
    items(visible, key = { it.first.id }) { (s, books) ->
      val next = upNext(books, progress)
      BookButton(
        spec = spec,
        title = s.name,
        subtitle = if (next == null) "Finished · ${books.size} books" else "Book ${bookNumber(next, books)} of ${books.size}",
        cover = coverModel(app, (next ?: books.first()).id),
        onClick = { nav.navigate(Routes.seriesDetail(libraryId, s.id, s.name)) }
      )
    }
    when {
      loading -> loadingItem()
      error != null -> errorItem(error!!, ::loadMore)
      series.size < total -> item { MenuButton(spec, Icons.Default.ExpandMore, "Show more", null, ::loadMore) }
      total == 0 -> caption("No series in this library")
    }
  }
}

@Composable
fun SeriesDetailScreen(nav: NavHostController, libraryId: String, seriesId: String, name: String) {
  val app = LocalApp.current
  val player = LocalPlayer.current
  val scope = rememberCoroutineScope()
  var starting by remember { mutableStateOf(false) }
  var playError by remember { mutableStateOf<String?>(null) }
  val (data, retry) = rememberLoad(seriesId) { app.api.seriesBooks(libraryId, seriesId) to app.api.allProgress() }

  val ready = (data as? Load.Ready)?.value
  val books = ready?.first.orEmpty()
  val progress = ready?.second.orEmpty()
  val next = upNext(books, progress)

  ListScreen(
    edgeButton = {
      EdgeButton(
        onClick = {
          val book = next ?: return@EdgeButton
          starting = true
          playError = null
          scope.launch {
            try {
              startBook(app, player, nav, book.mediaKey)
            } catch (e: Exception) {
              playError = describeError(e)
            } finally {
              starting = false
            }
          }
        },
        enabled = next != null && !starting,
        buttonSize = EdgeButtonSize.Medium
      ) {
        when {
          starting -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
          next == null -> Text("Finished")
          else -> {
            val started = (progress[next.id]?.progress ?: 0.0) > 0
            Text("${if (started) "Resume" else "Start"} #${bookNumber(next, books)}", maxLines = 1, softWrap = false)
          }
        }
      }
    }
  ) {
    header(name)
    when (data) {
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(data.message, retry)
      is Load.Ready -> {
        caption("${books.size} books")
        playError?.let { caption(it) { MaterialTheme.colorScheme.error } }
        items(books, key = { it.id }) { book ->
          val bookProgress = progress[book.id]
          val status = when {
            bookProgress?.isFinished == true -> "Finished"
            book == next -> if ((bookProgress?.progress ?: 0.0) > 0) "Up next · ${(bookProgress!!.progress * 100).roundToInt()}%" else "Up next"
            bookProgress != null && bookProgress.progress > 0 -> "${(bookProgress.progress * 100).roundToInt()}%"
            else -> book.author
          }
          Button(
            onClick = { nav.navigate(Routes.item(book.id)) },
            modifier = itemModifier(spec),
            transformation = SurfaceTransformation(spec),
            // The book to listen to next stands out
            colors = if (book == next) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
            icon = { Cover(coverModel(app, book.id), ButtonDefaults.LargeIconSize) },
            secondaryLabel = { Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            label = { Text("#${bookNumber(book, books)}  ${book.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
          )
        }
      }
    }
  }
}
