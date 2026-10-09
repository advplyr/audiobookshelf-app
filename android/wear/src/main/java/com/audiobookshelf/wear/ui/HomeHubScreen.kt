package com.audiobookshelf.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.data.BookSummary
import com.audiobookshelf.wear.playback.NowPlaying
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A book on the hub card: what to show and how far along it is. */
private data class HubBook(val book: BookSummary, val progress: Float, val subtitle: String)

/** Downloaded book covers are on the watch; others come from the server. */
fun coverModel(app: WearApp, itemId: String): Any =
  app.downloads.coverFile(itemId).takeIf { it.exists() } ?: app.api.coverUrl(itemId, 160)

/**
 * Home hub: a swipeable card of in-progress books (latest first, tap to resume) between two rows
 * of shortcuts. Nothing scrolls, so turning the bezel or crown also moves between books.
 * Sizes are fractions of the screen so it fits every watch size.
 */
@Composable
fun HomeHubScreen(nav: NavHostController) {
  val app = LocalApp.current
  val nowPlaying by NowPlaying.book.collectAsState()

  val (load, retry) = rememberLoad { loadHubBooks(app) }
  // The book in the player always comes first, so the card opens on what you're listening to
  val books = (load as? Load.Ready)?.value?.let { list ->
    val playing = nowPlaying ?: return@let list
    val current = list.firstOrNull { it.book.id == playing.book.id }
      ?: HubBook(BookSummary(playing.book.id, playing.book.title, playing.book.author, playing.book.duration), 0f, "")
    listOf(current.copy(subtitle = "Now playing")) + list.filterNot { it.book.id == playing.book.id }
  }

  val pagerState = rememberPagerState(pageCount = { books?.size?.coerceAtLeast(1) ?: 1 })
  // Show the latest book again whenever it changes, e.g. after starting a different book
  LaunchedEffect(books?.firstOrNull()?.book?.id) { pagerState.scrollToPage(0) }
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }
  var rotary by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  ScreenScaffold(
    modifier = Modifier
      .onRotaryScrollEvent {
        rotary += it.verticalScrollPixels
        if (abs(rotary) > 60f) {
          val target = (pagerState.currentPage + if (rotary > 0) 1 else -1).coerceIn(0, pagerState.pageCount - 1)
          scope.launch { pagerState.animateScrollToPage(target) }
          rotary = 0f
        }
        true
      }
      .focusRequester(focusRequester)
      .focusable()
  ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val s = maxWidth
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(top = s * 0.12f)
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(s * 0.03f)) {
          HubButton(s * 0.17f, Icons.Default.History, "Continue listening") { nav.navigate(Routes.CONTINUE) }
          HubButton(s * 0.17f, Icons.AutoMirrored.Default.LibraryBooks, "Library") { nav.navigate(Routes.LIBRARIES) }
          HubButton(s * 0.17f, Icons.Default.CollectionsBookmark, "Series") { nav.navigate(Routes.SERIES_LIBRARIES) }
        }
        Spacer(Modifier.height(s * 0.03f))

        Box(Modifier.width(s * 0.86f).height(s * 0.30f)) {
          when {
            load is Load.Loading -> MessageCard("Loading…", null, loading = true) {}
            books == null -> MessageCard("Can't reach the server", "Tap to try again", onClick = retry)
            books.isEmpty() -> MessageCard("Nothing in progress", "Browse library") { nav.navigate(Routes.LIBRARIES) }
            else -> HorizontalPager(state = pagerState, pageSpacing = 8.dp, modifier = Modifier.fillMaxSize()) { page ->
              books.getOrNull(page)?.let { BookCard(it, nav) }
            }
          }
        }
        PageDots(pagerState, books?.size ?: 0, s)
        Spacer(Modifier.height(s * 0.02f))

        Row(horizontalArrangement = Arrangement.spacedBy(s * 0.06f)) {
          HubButton(s * 0.17f, Icons.Default.DownloadDone, "On this watch") { nav.navigate(Routes.DOWNLOADS) }
          HubButton(s * 0.17f, Icons.Default.Settings, "Settings") { nav.navigate(Routes.SETTINGS) }
        }
      }
    }
  }
}

/** In-progress books, latest first. Offline, falls back to downloaded books the watch has listened to. */
private suspend fun loadHubBooks(app: WearApp): List<HubBook> {
  return try {
    val progress = runCatching { app.api.allProgress() }.getOrDefault(emptyMap())
    app.api.itemsInProgress().map { book ->
      HubBook(book, progress[book.id]?.progress?.toFloat() ?: 0f, progressLabel(book, progress[book.id]))
    }
  } catch (e: Exception) {
    app.downloads.books.value
      .mapNotNull { downloaded -> app.progress.get(downloaded.book.id)?.let { downloaded.book to it } }
      .sortedByDescending { (_, local) -> local.updatedAt }
      .map { (book, local) ->
        val fraction = if (book.duration > 0) (local.currentTime / book.duration).toFloat() else 0f
        HubBook(
          BookSummary(book.id, book.title, book.author, book.duration),
          fraction,
          "${(fraction * 100).roundToInt()}% · On watch"
        )
      }
      .ifEmpty { throw e }
  }
}

@Composable
private fun BookCard(item: HubBook, nav: NavHostController) {
  val app = LocalApp.current
  val player = LocalPlayer.current
  val scope = rememberCoroutineScope()
  var starting by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(error) {
    if (error != null) {
      delay(3000)
      error = null
    }
  }

  Card(
    onClick = {
      if (starting) return@Card
      starting = true
      scope.launch {
        try {
          startBook(app, player, nav, item.book.id)
        } catch (e: Exception) {
          error = describeError(e)
        } finally {
          starting = false
        }
      }
    },
    contentPadding = PaddingValues(8.dp),
    modifier = Modifier.fillMaxSize()
  ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val coverSize = maxHeight
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
          Cover(coverModel(app, item.book.id), coverSize)
          if (starting) CircularProgressIndicator(modifier = Modifier.size(coverSize * 0.6f))
        }
        Column(Modifier.padding(start = 8.dp).fillMaxWidth()) {
          Text(item.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(
            error ?: item.subtitle,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          ProgressBar(item.progress, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
      }
    }
  }
}

/** Slim progress bar; the Material one draws large end dots that look odd at this size. */
@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
  Box(modifier.height(3.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
    Box(
      Modifier
        .fillMaxWidth(progress.coerceIn(0f, 1f))
        .height(3.dp)
        .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
  }
}

@Composable
private fun MessageCard(title: String, subtitle: String?, loading: Boolean = false, onClick: () -> Unit) {
  Card(onClick = onClick, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      if (loading) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
      } else {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.primary) }
      }
    }
  }
}

/** Shows which book the card is on. Many books use a "3 / 12" counter instead of dots. */
@Composable
private fun PageDots(state: PagerState, count: Int, screen: Dp) {
  Box(Modifier.height(screen * 0.06f), contentAlignment = Alignment.Center) {
    when {
      count <= 1 -> {}
      count <= 7 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { index ->
          val selected = index == state.currentPage
          Box(
            Modifier
              .size(if (selected) 6.dp else 5.dp)
              .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
          )
        }
      }
      else -> Text(
        "${state.currentPage + 1} / $count",
        style = MaterialTheme.typography.bodyExtraSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun HubButton(size: Dp, icon: ImageVector, label: String, onClick: () -> Unit) {
  FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(size)) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.5f))
  }
}
