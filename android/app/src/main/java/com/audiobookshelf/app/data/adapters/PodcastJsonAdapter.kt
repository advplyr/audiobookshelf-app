package com.audiobookshelf.app.data.adapters

import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.internal.Util

class PodcastJsonAdapter(
    moshi: Moshi,
) : JsonAdapter<Podcast>() {
  private val options: JsonReader.Options = JsonReader.Options.of("metadata", "coverPath", "tags",
      "episodes", "autoDownloadEpisodes", "numEpisodes")

  private val podcastMetadataAdapter: JsonAdapter<PodcastMetadata> =
      moshi.adapter(PodcastMetadata::class.java, emptySet(), "metadata")

  private val nullableStringAdapter: JsonAdapter<String?> = moshi.adapter(String::class.java,
      emptySet(), "coverPath")

  private val mutableListOfStringAdapter: JsonAdapter<MutableList<String>> =
      moshi.adapter(
          Types.newParameterizedType(MutableList::class.java, String::class.java),
      emptySet(), "tags")

  private val nullableMutableListOfPodcastEpisodeAdapter: JsonAdapter<MutableList<PodcastEpisode>?>
      = moshi.adapter(
      Types.newParameterizedType(MutableList::class.java,
      PodcastEpisode::class.java), emptySet(), "episodes")

  private val booleanAdapter: JsonAdapter<Boolean> = moshi.adapter(Boolean::class.java, emptySet(),
      "autoDownloadEpisodes")

  private val nullableIntAdapter: JsonAdapter<Int?> = moshi.adapter(Int::class.javaObjectType,
      emptySet(), "numEpisodes")

  override fun toString(): String = buildString(29) {
      append("GeneratedJsonAdapter(").append("Podcast").append(')') }

  override fun fromJson(reader: JsonReader): Podcast {
    var metadata: PodcastMetadata? = null
    var coverPath: String? = null
    var tags: MutableList<String>? = null
    var episodes: MutableList<PodcastEpisode>? = null
    var autoDownloadEpisodes: Boolean? = null
    var numEpisodes: Int? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.selectName(options)) {
        0 -> metadata = podcastMetadataAdapter.fromJson(reader) ?:
            throw Util.unexpectedNull("metadata", "metadata", reader)
        1 -> coverPath = nullableStringAdapter.fromJson(reader)
        2 -> tags = mutableListOfStringAdapter.fromJson(reader) ?: throw Util.unexpectedNull("tags",
            "tags", reader)
        3 -> episodes = nullableMutableListOfPodcastEpisodeAdapter.fromJson(reader)
        4 -> autoDownloadEpisodes = booleanAdapter.fromJson(reader) ?:
            throw Util.unexpectedNull("autoDownloadEpisodes", "autoDownloadEpisodes", reader)
        5 -> numEpisodes = nullableIntAdapter.fromJson(reader)
        -1 -> {
          // Unknown name, skip it.
          reader.skipName()
          reader.skipValue()
        }
      }
    }
    reader.endObject()
    return Podcast(
        metadata = metadata ?: throw Util.missingProperty("metadata", "metadata", reader),
        coverPath = coverPath,
        tags = tags ?: throw Util.missingProperty("tags", "tags", reader),
        episodes = episodes,
        autoDownloadEpisodes = autoDownloadEpisodes
            ?: throw Util.missingProperty("autoDownloadEpisodes", "autoDownloadEpisodes", reader),
        numEpisodes = numEpisodes
    )
  }

  override fun toJson(writer: JsonWriter, value_: Podcast?): Unit {
    if (value_ == null) {
      throw NullPointerException("value_ was null! Wrap in .nullSafe() to write nullable values.")
    }
    writer.beginObject()
    writer.name("metadata")
    podcastMetadataAdapter.toJson(writer, value_.metadata as PodcastMetadata)
    writer.name("coverPath")
    nullableStringAdapter.toJson(writer, value_.coverPath)
    writer.name("tags")
    mutableListOfStringAdapter.toJson(writer, value_.tags)
    writer.name("episodes")
    nullableMutableListOfPodcastEpisodeAdapter.toJson(writer, value_.episodes)
    writer.name("autoDownloadEpisodes")
    booleanAdapter.toJson(writer, value_.autoDownloadEpisodes)
    writer.name("numEpisodes")
    nullableIntAdapter.toJson(writer, value_.numEpisodes)
    writer.endObject()
  }
}
