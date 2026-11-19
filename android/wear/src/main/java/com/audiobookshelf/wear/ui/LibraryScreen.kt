package com.audiobookshelf.wear.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonDefaults
import androidx.wear.compose.material3.Icon
import com.audiobookshelf.wear.data.BookSummary
import com.audiobookshelf.wear.data.ServerProgress
import kotlinx.coroutines.launch

const val ALL_LIBRARIES = "all"

@Composable
fun LibraryScreen(nav: NavHostController, libraryId: String, name: String) {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  val books = remember { mutableStateListOf<BookSummary>() }
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
        val (results, count) = app.api.libraryItems(libraryId, page)
        if (page == 0) progress = runCatching { app.api.allProgress() }.getOrDefault(emptyMap())
        books.addAll(results)
        total = count
        page++
      } catch (e: Exception) {
        error = e.message ?: "Could not load library"
      } finally {
        loading = false
      }
    }
  }

  LaunchedEffect(libraryId) { if (books.isEmpty()) loadMore() }

  ListScreen(
    edgeButton = {
      EdgeButton(onClick = { nav.navigate(Routes.search(libraryId)) }) {
        Icon(Icons.Default.Search, contentDescription = "Search $name", modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize))
      }
    }
  ) {
    header(name.ifEmpty { "Library" })
    if (total > 0) caption(if (books.firstOrNull()?.isPodcast == true) "$total podcasts" else "$total books")
    items(books.toList(), key = { it.id }) { book ->
      BookButton(spec, book.title, progressLabel(book, progress.of(book)), app.api.coverUrl(book.id, 120), { nav.navigate(Routes.open(book)) })
    }
    when {
      loading -> loadingItem()
      error != null -> errorItem(error!!, ::loadMore)
      books.size < total -> item { MenuButton(spec, Icons.Default.ExpandMore, "Show more", "${total - books.size} more", ::loadMore) }
    }
  }
}

@Composable
fun SearchScreen(nav: NavHostController, libraryId: String) {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  var query by rememberSaveable { mutableStateOf("") }
  var results by remember { mutableStateOf<Load<List<BookSummary>>?>(null) }

  fun search(text: String) {
    query = text
    if (text.isBlank()) return
    results = Load.Loading
    scope.launch {
      results = try {
        val libraryIds = if (libraryId == ALL_LIBRARIES) app.api.audioLibraries().map { it.id } else listOf(libraryId)
        Load.Ready(libraryIds.flatMap { app.api.search(it, text) }.distinctBy { it.id })
      } catch (e: Exception) {
        Load.Failed(e.message ?: "Search failed")
      }
    }
  }

  val input = rememberTextInput(::search)

  LaunchedEffect(Unit) {
    // Open the keyboard straight away the first time; re-run the search when coming back from a book
    if (query.isBlank()) input("Title or author") else if (results == null) search(query)
  }

  ListScreen(
    edgeButton = {
      EdgeButton(onClick = { input("Title or author") }) {
        Icon(Icons.Default.Search, contentDescription = "New search", modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize))
      }
    }
  ) {
    header(if (query.isBlank()) "Search" else "“$query”")
    when (val r = results) {
      null -> caption("Search by title or author")
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(r.message) { search(query) }
      is Load.Ready -> {
        if (r.value.isEmpty()) caption("Nothing found")
        items(r.value, key = { it.id }) { book ->
          BookButton(spec, book.title, book.author, app.api.coverUrl(book.id, 120), { nav.navigate(Routes.open(book)) })
        }
      }
    }
  }
}
