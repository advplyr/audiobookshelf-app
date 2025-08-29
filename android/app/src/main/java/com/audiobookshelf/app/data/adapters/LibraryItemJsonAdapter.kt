package com.audiobookshelf.app.data.adapters

import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.CollapsedSeries
import com.audiobookshelf.app.data.LibraryFile
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.internal.Util
import java.io.IOException
import java.lang.UnsupportedOperationException

class LibraryItemJsonAdapter(
    moshi: Moshi,
) : JsonAdapter<LibraryItem>() {
  private val options: JsonReader.Options = JsonReader.Options.of("id", "ino", "libraryId",
      "folderId", "path", "relPath", "mtimeMs", "ctimeMs", "birthtimeMs", "addedAt", "updatedAt",
      "lastScan", "scanVersion", "isMissing", "isInvalid", "mediaType", "media", "libraryFiles",
      "userMediaProgress", "collapsedSeries", "localLibraryItemId", "recentEpisode")

  private val stringAdapter: JsonAdapter<String> = moshi.adapter(String::class.java, emptySet(),
      "id")

  private val longAdapter: JsonAdapter<Long> = moshi.adapter(Long::class.java, emptySet(),
      "mtimeMs")

  private val nullableLongAdapter: JsonAdapter<Long?> = moshi.adapter(Long::class.javaObjectType,
      emptySet(), "lastScan")

  private val nullableStringAdapter: JsonAdapter<String?> = moshi.adapter(String::class.java,
      emptySet(), "scanVersion")

  private val booleanAdapter: JsonAdapter<Boolean> = moshi.adapter(Boolean::class.java, emptySet(),
      "isMissing")

  private val mediaTypeAdapter: JsonAdapter<MediaType> = moshi.adapter(
      MediaType::class.java,
      emptySet(), "media")

  private val bookAdapter: JsonAdapter<Book> = moshi.adapter(
    Book::class.java,
    emptySet(), "media")

  private val podcastAdapter: JsonAdapter<Podcast> = moshi.adapter(
    Podcast::class.java,
    emptySet(), "media")

  private val nullableMutableListOfLibraryFileAdapter: JsonAdapter<MutableList<LibraryFile>?> =
      moshi.adapter(
          Types.newParameterizedType(MutableList::class.java, LibraryFile::class.java),
      emptySet(), "libraryFiles")

  private val nullableMediaProgressAdapter: JsonAdapter<MediaProgress?> =
      moshi.adapter(MediaProgress::class.java, emptySet(), "userMediaProgress")

  private val nullableCollapsedSeriesAdapter: JsonAdapter<CollapsedSeries?> =
      moshi.adapter(CollapsedSeries::class.java, emptySet(), "collapsedSeries")

  private val nullablePodcastEpisodeAdapter: JsonAdapter<PodcastEpisode?> =
      moshi.adapter(PodcastEpisode::class.java, emptySet(), "recentEpisode")

  override fun toString(): String = buildString(33) {
      append("GeneratedJsonAdapter(").append("LibraryItem").append(')') }

  @Throws(IOException::class)
  fun getMediaType(reader: JsonReader): String? {
    reader.setFailOnUnknown(false)
    while (reader.hasNext()) {
      if (reader.nextName() == "mediaType")
        return reader.nextString()
      else
        reader.skipValue()
    }
    return null
  }


  override fun fromJson(reader: JsonReader): LibraryItem {
    var id: String? = null
    var ino: String? = null
    var libraryId: String? = null
    var folderId: String? = null
    var path: String? = null
    var relPath: String? = null
    var mtimeMs: Long? = null
    var ctimeMs: Long? = null
    var birthtimeMs: Long? = null
    var addedAt: Long? = null
    var updatedAt: Long? = null
    var lastScan: Long? = null
    var scanVersion: String? = null
    var isMissing: Boolean? = null
    var isInvalid: Boolean? = null
    var mediaType: String? = null
    var media: MediaType? = null
    var libraryFiles: MutableList<LibraryFile>? = null
    var userMediaProgress: MediaProgress? = null
    var collapsedSeries: CollapsedSeries? = null
    var localLibraryItemId: String? = null
    var recentEpisode: PodcastEpisode? = null
    reader.beginObject()

    val typeDescriptor = getMediaType(reader.peekJson())

    while (reader.hasNext()) {
      when (reader.selectName(options)) {
        0 -> id = stringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("id", "id", reader)
        1 -> ino = stringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("ino", "ino", reader)
        2 -> libraryId = stringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("libraryId",
            "libraryId", reader)
        3 -> folderId = stringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("folderId",
            "folderId", reader)
        4 -> path = stringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("path", "path",
            reader)
        5 -> relPath = stringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("relPath",
            "relPath", reader)
        6 -> mtimeMs = longAdapter.fromJson(reader) ?: throw Util.unexpectedNull("mtimeMs",
            "mtimeMs", reader)
        7 -> ctimeMs = longAdapter.fromJson(reader) ?: throw Util.unexpectedNull("ctimeMs",
            "ctimeMs", reader)
        8 -> birthtimeMs = longAdapter.fromJson(reader) ?: throw Util.unexpectedNull("birthtimeMs",
            "birthtimeMs", reader)
        9 -> addedAt = longAdapter.fromJson(reader) ?: throw Util.unexpectedNull("addedAt",
            "addedAt", reader)
        10 -> updatedAt = longAdapter.fromJson(reader) ?: throw Util.unexpectedNull("updatedAt",
            "updatedAt", reader)
        11 -> lastScan = nullableLongAdapter.fromJson(reader)
        12 -> scanVersion = nullableStringAdapter.fromJson(reader)
        13 -> isMissing = booleanAdapter.fromJson(reader) ?: throw Util.unexpectedNull("isMissing",
            "isMissing", reader)
        14 -> isInvalid = booleanAdapter.fromJson(reader) ?: throw Util.unexpectedNull("isInvalid",
            "isInvalid", reader)
        15 -> mediaType = stringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("mediaType",
            "mediaType", reader)
        16 -> media = when (typeDescriptor) {
                "book" -> bookAdapter
                "podcast" -> podcastAdapter
                else -> throw UnsupportedOperationException("mediaType must be book or podcast")
              }.fromJson(reader)?: throw Util.unexpectedNull("media", "media", reader)
        17 -> libraryFiles = nullableMutableListOfLibraryFileAdapter.fromJson(reader)
        18 -> userMediaProgress = nullableMediaProgressAdapter.fromJson(reader)
        19 -> collapsedSeries = nullableCollapsedSeriesAdapter.fromJson(reader)
        20 -> localLibraryItemId = nullableStringAdapter.fromJson(reader)
        21 -> recentEpisode = nullablePodcastEpisodeAdapter.fromJson(reader)
        -1 -> {
          // Unknown name, skip it.
          reader.skipName()
          reader.skipValue()
        }
      }
    }
    reader.endObject()
    return LibraryItem(
        id = id ?: throw Util.missingProperty("id", "id", reader),
        ino = ino ?: throw Util.missingProperty("ino", "ino", reader),
        libraryId = libraryId ?: throw Util.missingProperty("libraryId", "libraryId", reader),
        folderId = folderId ?: throw Util.missingProperty("folderId", "folderId", reader),
        path = path ?: throw Util.missingProperty("path", "path", reader),
        relPath = relPath ?: throw Util.missingProperty("relPath", "relPath", reader),
        mtimeMs = mtimeMs ?: throw Util.missingProperty("mtimeMs", "mtimeMs", reader),
        ctimeMs = ctimeMs ?: throw Util.missingProperty("ctimeMs", "ctimeMs", reader),
        birthtimeMs = birthtimeMs ?: throw Util.missingProperty(
            "birthtimeMs", "birthtimeMs",
            reader
        ),
        addedAt = addedAt ?: throw Util.missingProperty("addedAt", "addedAt", reader),
        updatedAt = updatedAt ?: throw Util.missingProperty("updatedAt", "updatedAt", reader),
        lastScan = lastScan,
        scanVersion = scanVersion,
        isMissing = isMissing ?: throw Util.missingProperty("isMissing", "isMissing", reader),
        isInvalid = isInvalid ?: throw Util.missingProperty("isInvalid", "isInvalid", reader),
        mediaType = mediaType ?: throw Util.missingProperty("mediaType", "mediaType", reader),
        media = media ?: throw Util.missingProperty("media", "media", reader),
        libraryFiles = libraryFiles,
        userMediaProgress = userMediaProgress,
        collapsedSeries = collapsedSeries,
        localLibraryItemId = localLibraryItemId,
        recentEpisode = recentEpisode
    )
  }

  override fun toJson(writer: JsonWriter, value_: LibraryItem?): Unit {
    if (value_ == null) {
      throw NullPointerException("value_ was null! Wrap in .nullSafe() to write nullable values.")
    }
    writer.beginObject()
    writer.name("id")
    stringAdapter.toJson(writer, value_.id)
    writer.name("ino")
    stringAdapter.toJson(writer, value_.ino)
    writer.name("libraryId")
    stringAdapter.toJson(writer, value_.libraryId)
    writer.name("folderId")
    stringAdapter.toJson(writer, value_.folderId)
    writer.name("path")
    stringAdapter.toJson(writer, value_.path)
    writer.name("relPath")
    stringAdapter.toJson(writer, value_.relPath)
    writer.name("mtimeMs")
    longAdapter.toJson(writer, value_.mtimeMs)
    writer.name("ctimeMs")
    longAdapter.toJson(writer, value_.ctimeMs)
    writer.name("birthtimeMs")
    longAdapter.toJson(writer, value_.birthtimeMs)
    writer.name("addedAt")
    longAdapter.toJson(writer, value_.addedAt)
    writer.name("updatedAt")
    longAdapter.toJson(writer, value_.updatedAt)
    writer.name("lastScan")
    nullableLongAdapter.toJson(writer, value_.lastScan)
    writer.name("scanVersion")
    nullableStringAdapter.toJson(writer, value_.scanVersion)
    writer.name("isMissing")
    booleanAdapter.toJson(writer, value_.isMissing)
    writer.name("isInvalid")
    booleanAdapter.toJson(writer, value_.isInvalid)
    writer.name("mediaType")
    stringAdapter.toJson(writer, value_.mediaType)
    writer.name("media")
    when (value_.mediaType) {
      "book"    -> bookAdapter.toJson(writer, value_.media as Book)
      "podcast" -> podcastAdapter.toJson(writer, value_.media as Podcast)
      else      -> mediaTypeAdapter.toJson(writer, value_.media)
    }
    writer.name("libraryFiles")
    nullableMutableListOfLibraryFileAdapter.toJson(writer, value_.libraryFiles)
    writer.name("userMediaProgress")
    nullableMediaProgressAdapter.toJson(writer, value_.userMediaProgress)
    writer.name("collapsedSeries")
    nullableCollapsedSeriesAdapter.toJson(writer, value_.collapsedSeries)
    writer.name("localLibraryItemId")
    nullableStringAdapter.toJson(writer, value_.localLibraryItemId)
    writer.name("recentEpisode")
    nullablePodcastEpisodeAdapter.toJson(writer, value_.recentEpisode)
    writer.endObject()
  }
}
