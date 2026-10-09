package com.audiobookshelf.wear.data

import org.json.JSONArray
import org.json.JSONObject

data class Account(
  val serverAddress: String,
  val username: String,
  val userId: String,
  val accessToken: String,
  val refreshToken: String?,
  val customHeaders: Map<String, String>,
  val serverVersion: String?
) {
  fun toJson(): JSONObject = JSONObject()
    .put("serverAddress", serverAddress)
    .put("username", username)
    .put("userId", userId)
    .put("accessToken", accessToken)
    .put("refreshToken", refreshToken)
    .put("customHeaders", JSONObject(customHeaders))
    .put("serverVersion", serverVersion)

  companion object {
    fun fromJson(json: JSONObject) = Account(
      serverAddress = json.getString("serverAddress"),
      username = json.getString("username"),
      userId = json.optString("userId"),
      accessToken = json.getString("accessToken"),
      refreshToken = json.optStringOrNull("refreshToken"),
      customHeaders = json.optJSONObject("customHeaders")?.toStringMap() ?: emptyMap(),
      serverVersion = json.optStringOrNull("serverVersion")
    )
  }
}

/** Server connection details shared by the phone app, used to prefill the watch sign in. */
data class PhoneServerConfig(
  val serverAddress: String,
  val username: String,
  val customHeaders: Map<String, String>
)

data class Library(val id: String, val name: String, val mediaType: String)

data class BookSummary(
  val id: String,
  val title: String,
  val author: String,
  val duration: Double
)

data class ServerProgress(
  val currentTime: Double,
  val progress: Double,
  val isFinished: Boolean,
  val lastUpdate: Long
) {
  companion object {
    fun fromJson(json: JSONObject) = ServerProgress(
      currentTime = json.optDouble("currentTime", 0.0),
      progress = json.optDouble("progress", 0.0),
      isFinished = json.optBoolean("isFinished", false),
      lastUpdate = json.optLong("lastUpdate", 0L)
    )
  }
}

data class Track(
  val index: Int,
  val ino: String,
  val duration: Double,
  val startOffset: Double,
  val contentUrl: String,
  val mimeType: String,
  val ext: String,
  val size: Long
) {
  val fileName get() = "${index}_$ino$ext"

  fun toJson(): JSONObject = JSONObject()
    .put("index", index)
    .put("ino", ino)
    .put("duration", duration)
    .put("startOffset", startOffset)
    .put("contentUrl", contentUrl)
    .put("mimeType", mimeType)
    .put("ext", ext)
    .put("size", size)

  companion object {
    fun fromJson(json: JSONObject) = Track(
      index = json.optInt("index"),
      ino = json.getString("ino"),
      duration = json.optDouble("duration", 0.0),
      startOffset = json.optDouble("startOffset", 0.0),
      contentUrl = json.getString("contentUrl"),
      mimeType = json.optString("mimeType", "audio/mpeg"),
      ext = json.optString("ext"),
      size = json.optLong("size", 0L)
    )

    /** Parses a track from the server's expanded book JSON (book.tracks). */
    fun fromServerJson(json: JSONObject): Track {
      val metadata = json.optJSONObject("metadata")
      return Track(
        index = json.optInt("index"),
        ino = json.getString("ino"),
        duration = json.optDouble("duration", 0.0),
        startOffset = json.optDouble("startOffset", 0.0),
        contentUrl = json.getString("contentUrl"),
        mimeType = json.optString("mimeType", "audio/mpeg"),
        ext = metadata?.optString("ext").orEmpty(),
        size = metadata?.optLong("size", 0L) ?: 0L
      )
    }
  }
}

data class Chapter(val start: Double, val end: Double, val title: String) {
  fun toJson(): JSONObject = JSONObject().put("start", start).put("end", end).put("title", title)

  companion object {
    fun fromJson(json: JSONObject) = Chapter(
      start = json.optDouble("start", 0.0),
      end = json.optDouble("end", 0.0),
      title = json.optString("title")
    )
  }
}

data class BookDetail(
  val id: String,
  val libraryId: String,
  val title: String,
  val author: String,
  val duration: Double,
  val tracks: List<Track>,
  val chapters: List<Chapter>
) {
  val totalSize get() = tracks.sumOf { it.size }

  fun chapterAt(time: Double): Chapter? = chapters.lastOrNull { time >= it.start }

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("libraryId", libraryId)
    .put("title", title)
    .put("author", author)
    .put("duration", duration)
    .put("tracks", JSONArray(tracks.map { it.toJson() }))
    .put("chapters", JSONArray(chapters.map { it.toJson() }))

  companion object {
    fun fromJson(json: JSONObject) = BookDetail(
      id = json.getString("id"),
      libraryId = json.optString("libraryId"),
      title = json.optString("title"),
      author = json.optString("author"),
      duration = json.optDouble("duration", 0.0),
      tracks = json.getJSONArray("tracks").objects().map(Track::fromJson),
      chapters = json.optJSONArray("chapters")?.objects()?.map(Chapter::fromJson) ?: emptyList()
    )

    /** Parses the server's expanded library item JSON (/api/items/:id?expanded=1). */
    fun fromServerJson(json: JSONObject): BookDetail {
      val media = json.getJSONObject("media")
      val metadata = media.optJSONObject("metadata") ?: JSONObject()
      val tracks = media.optJSONArray("tracks")?.objects()?.map(Track::fromServerJson) ?: emptyList()
      return BookDetail(
        id = json.getString("id"),
        libraryId = json.optString("libraryId"),
        title = metadata.optString("title"),
        author = metadata.optString("authorName"),
        duration = media.optDouble("duration", tracks.sumOf { it.duration }),
        tracks = tracks,
        chapters = media.optJSONArray("chapters")?.objects()?.map(Chapter::fromJson) ?: emptyList()
      )
    }
  }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.optStringOrNull(key: String): String? =
  if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONObject.toStringMap(): Map<String, String> =
  keys().asSequence().associateWith { optString(it) }
