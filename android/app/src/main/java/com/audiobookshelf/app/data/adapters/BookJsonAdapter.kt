package com.audiobookshelf.app.data.adapters

import com.audiobookshelf.app.data.AudioFile
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.BookChapter
import com.audiobookshelf.app.data.BookMetadata
import com.audiobookshelf.app.data.EBookFile
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.internal.Util

class BookJsonAdapter(
  moshi: Moshi,
) : JsonAdapter<Book>() {

  private val options: JsonReader.Options = JsonReader.Options.of("metadata", "coverPath", "tags",
      "audioFiles", "chapters", "tracks", "ebookFile", "size", "duration", "numTracks")

  private val bookMetadataAdapter: JsonAdapter<BookMetadata> =
      moshi.adapter(BookMetadata::class.java, emptySet(), "metadata")

  private val nullableStringAdapter: JsonAdapter<String?> = moshi.adapter(String::class.java,
      emptySet(), "coverPath")

  private val listOfStringAdapter: JsonAdapter<List<String>> =
      moshi.adapter(
        Types.newParameterizedType(List::class.java, String::class.java), emptySet(),
      "tags")

  private val nullableListOfAudioFileAdapter: JsonAdapter<List<AudioFile>?> =
      moshi.adapter(
        Types.newParameterizedType(List::class.java, AudioFile::class.java), emptySet(),
      "audioFiles")

  private val nullableListOfBookChapterAdapter: JsonAdapter<List<BookChapter>?> =
      moshi.adapter(
        Types.newParameterizedType(List::class.java, BookChapter::class.java),
      emptySet(), "chapters")

  private val nullableMutableListOfAudioTrackAdapter: JsonAdapter<MutableList<AudioTrack>?> =
      moshi.adapter(
        Types.newParameterizedType(MutableList::class.java, AudioTrack::class.java),
      emptySet(), "tracks")

  private val nullableEBookFileAdapter: JsonAdapter<EBookFile?> =
      moshi.adapter(EBookFile::class.java, emptySet(), "ebookFile")

  private val nullableLongAdapter: JsonAdapter<Long?> = moshi.adapter(Long::class.javaObjectType,
      emptySet(), "size")

  private val nullableDoubleAdapter: JsonAdapter<Double?> =
      moshi.adapter(Double::class.javaObjectType, emptySet(), "duration")

  private val nullableIntAdapter: JsonAdapter<Int?> = moshi.adapter(Int::class.javaObjectType,
      emptySet(), "numTracks")

  override fun toString(): String = buildString(26) {
      append("GeneratedJsonAdapter(").append("Book").append(')') }

  override fun fromJson(reader: JsonReader): Book {
    var metadata: BookMetadata? = null
    var coverPath: String? = null
    var tags: List<String>? = null
    var audioFiles: List<AudioFile>? = null
    var chapters: List<BookChapter>? = null
    var tracks: MutableList<AudioTrack>? = null
    var ebookFile: EBookFile? = null
    var size: Long? = null
    var duration: Double? = null
    var numTracks: Int? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.selectName(options)) {
        0 -> metadata = bookMetadataAdapter.fromJson(reader) ?:
            throw Util.unexpectedNull("metadata", "metadata", reader)
        1 -> coverPath = nullableStringAdapter.fromJson(reader)
        2 -> tags = listOfStringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("tags",
            "tags", reader)
        3 -> audioFiles = nullableListOfAudioFileAdapter.fromJson(reader)
        4 -> chapters = nullableListOfBookChapterAdapter.fromJson(reader)
        5 -> tracks = nullableMutableListOfAudioTrackAdapter.fromJson(reader)
        6 -> ebookFile = nullableEBookFileAdapter.fromJson(reader)
        7 -> size = nullableLongAdapter.fromJson(reader)
        8 -> duration = nullableDoubleAdapter.fromJson(reader)
        9 -> numTracks = nullableIntAdapter.fromJson(reader)
        -1 -> {
          // Unknown name, skip it.
          reader.skipName()
          reader.skipValue()
        }
      }
    }
    reader.endObject()
    return Book(
      metadata = metadata ?: throw Util.missingProperty("metadata", "metadata", reader),
      coverPath = coverPath,
      tags = tags ?: throw Util.missingProperty("tags", "tags", reader),
      audioFiles = audioFiles,
      chapters = chapters,
      tracks = tracks,
      ebookFile = ebookFile,
      size = size,
      duration = duration,
      numTracks = numTracks
    )
  }

  override fun toJson(writer: JsonWriter, value_: Book?): Unit {
    if (value_ == null) {
      throw NullPointerException("value_ was null! Wrap in .nullSafe() to write nullable values.")
    }
    writer.beginObject()
    writer.name("metadata")
    bookMetadataAdapter.toJson(writer, value_.metadata as BookMetadata)
    writer.name("coverPath")
    nullableStringAdapter.toJson(writer, value_.coverPath)
    writer.name("tags")
    listOfStringAdapter.toJson(writer, value_.tags)
    writer.name("audioFiles")
    nullableListOfAudioFileAdapter.toJson(writer, value_.audioFiles)
    writer.name("chapters")
    nullableListOfBookChapterAdapter.toJson(writer, value_.chapters)
    writer.name("tracks")
    nullableMutableListOfAudioTrackAdapter.toJson(writer, value_.tracks)
    writer.name("ebookFile")
    nullableEBookFileAdapter.toJson(writer, value_.ebookFile)
    writer.name("size")
    nullableLongAdapter.toJson(writer, value_.size)
    writer.name("duration")
    nullableDoubleAdapter.toJson(writer, value_.duration)
    writer.name("numTracks")
    nullableIntAdapter.toJson(writer, value_.numTracks)
    writer.endObject()
  }
}
