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
 * Downloaded books live in app storage as `books/<itemId>/` and podcast episodes as
 * `books/<itemId>__<episodeId>/`. The `book.json` file is written last, so its presence marks a
 * complete download.
 */
class DownloadStore(context: Context, private val onChanged: () -> Unit = {}) {
  private val root = File(context.filesDir, "books").apply { mkdirs() }
  private val _books = MutableStateFlow(scan())
  val books: StateFlow<List<DownloadedBook>> = _books.asStateFlow()

  // Ids come from the server, so they're cleaned to keep every download inside [root]
  fun bookDir(key: MediaKey) = File(root, if (key.episodeId == null) safeFileName(key.itemId) else "${safeFileName(key.itemId)}$EPISODE_SEPARATOR${safeFileName(key.episodeId)}")
  fun trackFile(key: MediaKey, track: Track) = File(bookDir(key), track.fileName)
  fun coverFile(key: MediaKey) = File(bookDir(key), COVER_FILE)

  /**
   * A downloaded cover for the item: the book's own, or the one saved with any downloaded episode of
   * the podcast. The returned file may not exist.
   */
  fun coverFile(itemId: String): File {
    val own = coverFile(MediaKey(itemId))
    if (own.exists()) return own
    return root.listFiles { file -> file.name.startsWith(safeFileName(itemId) + EPISODE_SEPARATOR) }.orEmpty()
      .map { File(it, COVER_FILE) }
      .firstOrNull { it.exists() } ?: own
  }

  fun get(key: MediaKey): BookDetail? = _books.value.firstOrNull { it.book.mediaKey == key }?.book

  fun isDownloaded(key: MediaKey) = get(key) != null

  fun markComplete(book: BookDetail) {
    File(bookDir(book.mediaKey), META_FILE).writeText(book.toJson().toString())
    refresh()
  }

  fun delete(key: MediaKey) {
    bookDir(key).deleteRecursively()
    refresh()
  }

  fun freeBytes(): Long = StatFs(root.path).availableBytes

  fun usedBytes(): Long = _books.value.sumOf { it.sizeBytes }

  fun refresh() {
    _books.value = scan()
    onChanged()
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
    private const val COVER_FILE = "cover.jpg"
    private const val EPISODE_SEPARATOR = "__"
  }
}
