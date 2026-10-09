package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
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
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.audiobookshelf.wear.data.BookSummary
import com.audiobookshelf.wear.data.ServerProgress
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(nav: NavHostController, libraryId: String) {
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

  ListScreen {
    item { ListHeader { Text("Library") } }
    item {
      Chip(
        label = { Text("Search") },
        icon = { Icon(Icons.Default.Search, contentDescription = null) },
        onClick = { nav.navigate(Routes.search(libraryId)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
    bookChips(books, progress) { nav.navigate(Routes.item(it.id)) }
    when {
      loading -> loadingItem()
      error != null -> errorItem(error!!, ::loadMore)
      books.size < total -> item {
        Chip(label = { Text("Load more") }, onClick = ::loadMore, colors = ChipDefaults.childChipColors())
      }
    }
  }
}

const val ALL_LIBRARIES = "all"

@Composable
fun SearchScreen(nav: NavHostController, libraryId: String) {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  var query by rememberSaveable { mutableStateOf("") }
  var results by remember { mutableStateOf<Load<List<BookSummary>>?>(null) }

  fun search() {
    if (query.isBlank()) return
    results = Load.Loading
    scope.launch {
      results = try {
        val libraryIds = if (libraryId == ALL_LIBRARIES) app.api.libraries().filter { it.mediaType == "book" }.map { it.id } else listOf(libraryId)
        Load.Ready(libraryIds.flatMap { app.api.search(it, query.trim()) }.distinctBy { it.id })
      } catch (e: Exception) {
        Load.Failed(e.message ?: "Search failed")
      }
    }
  }

  // Re-run the search when coming back from a book
  LaunchedEffect(Unit) { if (query.isNotBlank() && results == null) search() }

  ListScreen {
    item { ListHeader { Text("Search") } }
    item { WearTextField(query, { query = it }, "Title or author", onDone = ::search) }
    when (val r = results) {
      null -> {}
      is Load.Loading -> loadingItem()
      is Load.Failed -> errorItem(r.message, ::search)
      is Load.Ready -> {
        if (r.value.isEmpty()) item { Text("No books found") }
        bookChips(r.value) { nav.navigate(Routes.item(it.id)) }
      }
    }
  }
}
