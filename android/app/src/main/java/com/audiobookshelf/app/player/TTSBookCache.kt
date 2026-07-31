package com.audiobookshelf.app.player

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.TTSBook
import com.audiobookshelf.app.data.TTSBookSummary
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Disk cache of extracted ebook text (TTSBook) so the native TTS player can
 * start a book without the WebView - e.g. picked from Android Auto.
 *
 * Layout: filesDir/tts-cache/<libraryItemId>.json (full book)
 *         filesDir/tts-cache/<libraryItemId>.meta.json (TTSBookSummary)
 * Oldest books (by lastAccessed) are evicted over MAX_BOOKS/MAX_TOTAL_BYTES.
 */
class TTSBookCache(val context: Context) {
  private val tag = "TTSBookCache"

  companion object {
    const val MAX_BOOKS = 20
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024 // 50 MB
  }

  private val jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private val cacheDir: File
    get() {
      val dir = File(context.filesDir, "tts-cache")
      if (!dir.exists()) dir.mkdirs()
      return dir
    }

  private fun bookFile(libraryItemId: String) = File(cacheDir, "${sanitizeId(libraryItemId)}.json")
  private fun metaFile(libraryItemId: String) = File(cacheDir, "${sanitizeId(libraryItemId)}.meta.json")

  private fun sanitizeId(libraryItemId: String): String {
    return libraryItemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
  }

  fun save(book: TTSBook) {
    try {
      val summary = TTSBookSummary(
        libraryItemId = book.libraryItemId,
        serverAddress = book.serverAddress,
        title = book.title,
        author = book.author,
        language = book.language,
        ebookFormat = book.ebookFormat,
        totalChars = book.totalChars,
        lastAccessed = System.currentTimeMillis()
      )
      bookFile(book.libraryItemId).writeText(jacksonMapper.writeValueAsString(book))
      metaFile(book.libraryItemId).writeText(jacksonMapper.writeValueAsString(summary))
      evictOverLimit()
    } catch (e: Exception) {
      Log.e(tag, "Failed to save book ${book.libraryItemId}", e)
    }
  }

  fun load(libraryItemId: String): TTSBook? {
    return try {
      val file = bookFile(libraryItemId)
      if (!file.exists()) return null
      val book = jacksonMapper.readValue(file.readText(), TTSBook::class.java)
      touch(libraryItemId)
      book
    } catch (e: Exception) {
      Log.e(tag, "Failed to load book $libraryItemId", e)
      null
    }
  }

  fun has(libraryItemId: String): Boolean {
    return bookFile(libraryItemId).exists()
  }

  fun list(): List<TTSBookSummary> {
    val summaries = mutableListOf<TTSBookSummary>()
    cacheDir.listFiles { file -> file.name.endsWith(".meta.json") }?.forEach { file ->
      try {
        summaries.add(jacksonMapper.readValue(file.readText(), TTSBookSummary::class.java))
      } catch (e: Exception) {
        Log.e(tag, "Failed to read summary ${file.name}", e)
      }
    }
    return summaries.sortedByDescending { it.lastAccessed }
  }

  fun remove(libraryItemId: String) {
    bookFile(libraryItemId).delete()
    metaFile(libraryItemId).delete()
  }

  private fun touch(libraryItemId: String) {
    try {
      val file = metaFile(libraryItemId)
      if (!file.exists()) return
      val summary = jacksonMapper.readValue(file.readText(), TTSBookSummary::class.java)
      summary.lastAccessed = System.currentTimeMillis()
      file.writeText(jacksonMapper.writeValueAsString(summary))
    } catch (e: Exception) {
      Log.e(tag, "Failed to touch $libraryItemId", e)
    }
  }

  private fun evictOverLimit() {
    try {
      val summaries = list() // newest first
      var totalBytes = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
      summaries.forEachIndexed { index, summary ->
        if (index >= MAX_BOOKS || totalBytes > MAX_TOTAL_BYTES) {
          val size = bookFile(summary.libraryItemId).length() + metaFile(summary.libraryItemId).length()
          Log.d(tag, "Evicting cached book ${summary.libraryItemId} ($size bytes)")
          remove(summary.libraryItemId)
          totalBytes -= size
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Failed to evict cache", e)
    }
  }
}
