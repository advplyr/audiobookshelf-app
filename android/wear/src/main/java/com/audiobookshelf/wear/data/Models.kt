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
  val serverVersion: String?,
  /** Signed in through the phone app: new access tokens come from the phone instead of a refresh token. */
  val linkedToPhone: Boolean = false
) {
  fun toJson(): JSONObject = JSONObject()
    .put("serverAddress", serverAddress)
    .put("username", username)
    .put("userId", userId)
    .put("accessToken", accessToken)
    .put("refreshToken", refreshToken)
    .put("customHeaders", JSONObject(customHeaders))
    .put("serverVersion", serverVersion)
    .put("linkedToPhone", linkedToPhone)

  companion object {
    fun fromJson(json: JSONObject) = Account(
      serverAddress = json.getString("serverAddress"),
      username = json.getString("username"),
      userId = json.optString("userId"),
      accessToken = json.getString("accessToken"),
      refreshToken = json.optStringOrNull("refreshToken"),
      customHeaders = json.optJSONObject("customHeaders")?.toStringMap() ?: emptyMap(),
      serverVersion = json.optStringOrNull("serverVersion"),
      linkedToPhone = json.optBoolean("linkedToPhone", false)
    )
  }
}

/** Server connection details shared by the phone app, used to prefill the watch sign in. */
data class PhoneServerConfig(
  val serverAddress: String,
  val username: String,
  val customHeaders: Map<String, String>
)

/** [icon] is the icon chosen for the library on the server, e.g. "database" (see [com.audiobookshelf.wear.ui.LibraryIcons]). */
data class Library(val id: String, val name: String, val mediaType: String, val icon: String) {
  val isPodcast get() = mediaType == MEDIA_PODCAST
}

const val MEDIA_BOOK = "book"
const val MEDIA_PODCAST = "podcast"

/**
 * Identifies something playable: a book, or one episode of a podcast. [key] is used for progress
 * and downloads on the watch; for books it is just the item id, so older saved data still matches.
 */
data class MediaKey(val itemId: String, val episodeId: String? = null) {
  val key: String get() = if (episodeId == null) itemId else "$itemId/$episodeId"

  companion object {
    fun parse(key: String): MediaKey {
      val slash = key.indexOf('/')
      return if (slash < 0) MediaKey(key) else MediaKey(key.substring(0, slash), key.substring(slash + 1))
    }
  }
}

data class BookSummary(
  val id: String,
  val title: String,
  val author: String,
  val duration: Double,
  /** e.g. "Defiance of the Fall #3" for books in a series */
  val seriesName: String = "",
  /** [MEDIA_BOOK], or [MEDIA_PODCAST] for a podcast or one of its episodes */
  val mediaType: String = MEDIA_BOOK,
  /** Set when this is a single podcast episode; [title] is then the episode and [author] the podcast */
  val episodeId: String? = null
) {
  val mediaKey get() = MediaKey(id, episodeId)

  /** A whole podcast (opens its episode list) rather than something playable. */
  val isPodcast get() = mediaType == MEDIA_PODCAST && episodeId == null

  /** The book's number within a series, e.g. "3", parsed from [seriesName]. */
  val seriesSequence: String? get() = seriesName.substringAfterLast(" #", "").takeIf { it.isNotEmpty() }
}

data class SeriesSummary(
  val id: String,
  val name: String,
  /** In reading order */
  val books: List<BookSummary>
)

data class ServerProgress(
  val currentTime: Double,
  val progress: Double,
  val isFinished: Boolean,
  val lastUpdate: Long,
  val itemId: String = "",
  val episodeId: String? = null
) {
  val mediaKey get() = MediaKey(itemId, episodeId)

  companion object {
    fun fromJson(json: JSONObject) = ServerProgress(
      currentTime = json.optDouble("currentTime", 0.0),
      progress = json.optDouble("progress", 0.0),
      isFinished = json.optBoolean("isFinished", false),
      lastUpdate = json.optLong("lastUpdate", 0L),
      itemId = json.optString("libraryItemId"),
      episodeId = json.optStringOrNull("episodeId")
    )
  }
}

/**
 * Makes a server-supplied value safe to use as a single file name: path separators and anything
 * unusual become "_", and "." or ".." can't result.
 */
fun safeFileName(name: String): String {
  val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
  return if (cleaned.isEmpty() || cleaned.all { it == '.' }) cleaned.replace('.', '_').ifEmpty { "_" } else cleaned
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
  // ino and ext come from the server, so they're cleaned to keep the file inside the book's folder
  val fileName get() = safeFileName("${index}_$ino$ext")

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

/**
 * The user's listening time from `/api/me/listening-stats`, summed up for the stats tile. All
 * times are in seconds; [last7Days] runs oldest first and ends with [today].
 */
data class ListeningStats(
  val today: java.time.LocalDate,
  val todaySeconds: Long,
  val weekSeconds: Long,
  val last7Days: List<Long>,
  val streakDays: Int,
  val totalSeconds: Long
) {
  companion object {
    /** [today] is the watch's date; the server keys days by the date the listening happened. */
    fun from(json: JSONObject, today: java.time.LocalDate): ListeningStats {
      val days = json.optJSONObject("days") ?: JSONObject()
      fun secondsOn(date: java.time.LocalDate) = days.optDouble(date.toString(), 0.0).toLong().coerceAtLeast(0)

      val last7 = (6 downTo 0).map { secondsOn(today.minusDays(it.toLong())) }
      // A streak is still alive today until the day is over, so count back from yesterday if needed
      var day = if (secondsOn(today) > 0) today else today.minusDays(1)
      var streak = 0
      while (secondsOn(day) > 0) {
        streak++
        day = day.minusDays(1)
      }
      return ListeningStats(
        today = today,
        // The server's own "today" can be a different day than the watch's near midnight
        todaySeconds = secondsOn(today),
        weekSeconds = last7.sum(),
        last7Days = last7,
        streakDays = streak,
        totalSeconds = json.optDouble("totalTime", 0.0).toLong()
      )
    }
  }
}

/** One podcast episode in a podcast's episode list. */
data class EpisodeSummary(
  val id: String,
  val title: String,
  /** Milliseconds since the epoch, or 0 when unknown */
  val publishedAt: Long,
  val duration: Double,
  val size: Long
) {
  companion object {
    fun fromServerJson(json: JSONObject): EpisodeSummary {
      val audioFile = json.optJSONObject("audioFile")
      return EpisodeSummary(
        id = json.getString("id"),
        title = json.optString("title"),
        publishedAt = json.optLong("publishedAt", 0L),
        duration = json.optDouble("duration", audioFile?.optDouble("duration", 0.0) ?: 0.0).takeUnless { it.isNaN() } ?: 0.0,
        size = json.optLong("size", audioFile?.optJSONObject("metadata")?.optLong("size", 0L) ?: 0L)
      )
    }
  }
}

/** A podcast with its episodes, newest first. */
data class PodcastDetail(
  val id: String,
  val libraryId: String,
  val title: String,
  val author: String,
  val episodes: List<EpisodeSummary>
) {
  companion object {
    /** Parses the server's expanded podcast library item JSON (/api/items/:id?expanded=1). */
    fun fromServerJson(json: JSONObject): PodcastDetail {
      val media = json.getJSONObject("media")
      val metadata = media.optJSONObject("metadata") ?: JSONObject()
      return PodcastDetail(
        id = json.getString("id"),
        libraryId = json.optString("libraryId"),
        title = metadata.optString("title"),
        author = metadata.optString("author"),
        episodes = (media.optJSONArray("episodes") ?: JSONArray()).objects()
          .map(EpisodeSummary::fromServerJson)
          .sortedByDescending { it.publishedAt }
      )
    }
  }
}

/**
 * Something the player can play: a book, or a single podcast episode ([episodeId] set, one track,
 * [title] is the episode and [author] the podcast).
 */
data class BookDetail(
  val id: String,
  val libraryId: String,
  val title: String,
  val author: String,
  val duration: Double,
  val tracks: List<Track>,
  val chapters: List<Chapter>,
  val episodeId: String? = null
) {
  val mediaKey get() = MediaKey(id, episodeId)
  val mediaType get() = if (episodeId == null) MEDIA_BOOK else MEDIA_PODCAST
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
    .put("episodeId", episodeId ?: JSONObject.NULL)

  companion object {
    fun fromJson(json: JSONObject) = BookDetail(
      id = json.getString("id"),
      libraryId = json.optString("libraryId"),
      title = json.optString("title"),
      author = json.optString("author"),
      duration = json.optDouble("duration", 0.0),
      tracks = json.getJSONArray("tracks").objects().map(Track::fromJson),
      chapters = json.optJSONArray("chapters")?.objects()?.map(Chapter::fromJson) ?: emptyList(),
      episodeId = json.optStringOrNull("episodeId")
    )

    /**
     * Builds a playable episode from the server's expanded podcast item JSON and one entry of its
     * `media.episodes`. The episode's single audio file becomes the only track.
     */
    fun fromEpisodeJson(item: JSONObject, episode: JSONObject): BookDetail {
      val podcastMetadata = item.optJSONObject("media")?.optJSONObject("metadata") ?: JSONObject()
      val audioFile = episode.optJSONObject("audioFile") ?: throw IllegalArgumentException("Episode has no audio file")
      val fileMetadata = audioFile.optJSONObject("metadata") ?: JSONObject()
      val audioTrack = episode.optJSONObject("audioTrack")
      val itemId = item.getString("id")
      val ino = audioFile.getString("ino")
      val duration = audioTrack?.optDouble("duration", Double.NaN)?.takeUnless { it.isNaN() }
        ?: audioFile.optDouble("duration", 0.0)
      val track = Track(
        index = 1,
        ino = ino,
        duration = duration,
        startOffset = 0.0,
        contentUrl = audioTrack?.optStringOrNull("contentUrl") ?: "/api/items/$itemId/file/$ino",
        mimeType = audioTrack?.optStringOrNull("mimeType") ?: audioFile.optString("mimeType", "audio/mpeg"),
        ext = fileMetadata.optString("ext"),
        size = fileMetadata.optLong("size", episode.optLong("size", 0L))
      )
      return BookDetail(
        id = itemId,
        libraryId = item.optString("libraryId"),
        title = episode.optString("title"),
        author = podcastMetadata.optString("title"),
        duration = duration,
        tracks = listOf(track),
        chapters = (episode.optJSONArray("chapters") ?: audioFile.optJSONArray("chapters"))?.objects()?.map(Chapter::fromJson) ?: emptyList(),
        episodeId = episode.getString("id")
      )
    }

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
