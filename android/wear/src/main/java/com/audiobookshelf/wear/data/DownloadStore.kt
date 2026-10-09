package com.audiobookshelf.wear.data

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

data class DownloadedBook(val book: BookDetail, val sizeBytes: Long)

/**
 * Downloaded books live in app storage as `books/<itemId>/`. The `book.json` file is written last,
 * so its presence marks a complete download.
 */
class DownloadStore(context: Context) {
  private val root = File(context.filesDir, "books").apply { mkdirs() }
  private val _books = MutableStateFlow(scan())
  val books: StateFlow<List<DownloadedBook>> = _books.asStateFlow()

  fun bookDir(itemId: String) = File(root, itemId)
  fun trackFile(itemId: String, track: Track) = File(bookDir(itemId), track.fileName)
  fun coverFile(itemId: String) = File(bookDir(itemId), "cover.jpg")

  fun get(itemId: String): BookDetail? = _books.value.firstOrNull { it.book.id == itemId }?.book

  fun isDownloaded(itemId: String) = get(itemId) != null

  fun markComplete(book: BookDetail) {
    File(bookDir(book.id), META_FILE).writeText(book.toJson().toString())
    refresh()
  }

  fun delete(itemId: String) {
    bookDir(itemId).deleteRecursively()
    refresh()
  }

  fun freeBytes(): Long = StatFs(root.path).availableBytes

  fun usedBytes(): Long = _books.value.sumOf { it.sizeBytes }

  fun refresh() {
    _books.value = scan()
  }

  private fun scan(): List<DownloadedBook> {
    return root.listFiles().orEmpty().mapNotNull { dir ->
      val meta = File(dir, META_FILE)
      if (!meta.exists()) return@mapNotNull null
      try {
        DownloadedBook(BookDetail.fromJson(JSONObject(meta.readText())), dir.walk().sumOf { it.length() })
      } catch (e: Exception) {
        Log.e("DownloadStore", "Corrupt download in ${dir.name}", e)
        null
      }
    }.sortedBy { it.book.title.lowercase() }
  }

  companion object {
    private const val META_FILE = "book.json"
  }
}
