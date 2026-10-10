package com.audiobookshelf.wear.tile

import com.audiobookshelf.wear.data.DownloadedBook
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.download.ActiveDownload
import org.json.JSONArray
import org.json.JSONObject

/** A book or episode on the Continue listening tile. [progress] is 0..1. */
data class ContinueEntry(val key: MediaKey, val title: String, val progress: Float, val playing: Boolean = false) {
  fun toJson(): JSONObject = JSONObject().put("key", key.key).put("title", title).put("progress", progress.toDouble())

  companion object {
    fun fromJson(json: JSONObject) =
      ContinueEntry(MediaKey.parse(json.getString("key")), json.optString("title"), json.optDouble("progress", 0.0).toFloat())

    fun listToJson(entries: List<ContinueEntry>) = JSONArray(entries.map { it.toJson() })

    fun listFromJson(json: JSONArray) = (0 until json.length()).map { fromJson(json.getJSONObject(it)) }
  }
}

object ContinueEntries {
  const val LIMIT = 4

  /** What's in the player first, then the rest of the in-progress list, without repeats. */
  fun build(nowPlaying: ContinueEntry?, inProgress: List<ContinueEntry>, limit: Int = LIMIT): List<ContinueEntry> {
    val playing = nowPlaying?.copy(playing = true)
    return (listOfNotNull(playing) + inProgress.filter { it.key != playing?.key })
      .distinctBy { it.key }
      .take(limit)
  }

  fun fraction(currentTime: Double, duration: Double): Float =
    if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0).toFloat() else 0f
}

/** What the On this watch tile shows. */
data class DownloadsSummary(
  val count: Int,
  val bytes: Long,
  val freeBytes: Long,
  /** Up to [COVERS] downloads, most recently listened first */
  val recent: List<MediaKey>,
  /** Downloads still running, with their average progress */
  val activeCount: Int,
  val activePercent: Int
) {
  /** Share of the watch's available space the downloads use, for the storage ring. */
  val storageFraction: Float get() = if (bytes + freeBytes > 0) bytes.toFloat() / (bytes + freeBytes) else 0f

  companion object {
    const val COVERS = 3

    /** [lastListened] gives when each download was last listened to on the watch, if ever. */
    fun from(
      books: List<DownloadedBook>,
      active: List<ActiveDownload>,
      freeBytes: Long,
      lastListened: (MediaKey) -> Long?
    ): DownloadsSummary {
      // A finished download can still be listed as active for a moment
      val running = active.filter { a -> books.none { it.book.mediaKey == a.key } }
      return DownloadsSummary(
        count = books.size,
        bytes = books.sumOf { it.sizeBytes },
        freeBytes = freeBytes,
        recent = books.map { it.book.mediaKey }.sortedByDescending { lastListened(it) ?: 0L }.take(COVERS),
        activeCount = running.size,
        activePercent = if (running.isEmpty()) 0 else running.sumOf { it.percent } / running.size
      )
    }
  }
}
