package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioProbeStream(
  val index:Int,
  val codec_name:String,
  val codec_long_name:String,
  val channels:Int,
  val channel_layout:String,
  val duration:Double,
  val bit_rate:Double
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioProbeChapterTags(
  val title:String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioProbeChapter(
  val id:Int,
  val start:Long,
  val end:Long,
  val tags:AudioProbeChapterTags?
) {
  @JsonIgnore
  fun getBookChapter():BookChapter {
    val startS = start / 1000.0
    val endS = end / 1000.0
    val title = tags?.title ?: "Chapter $id"
    return BookChapter(id, startS, endS, title)
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioProbeFormatTags(
  val artist:String?,
  val album:String?,
  val comment:String?,
  val date:String?,
  val genre:String?,
  val title:String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioProbeFormat(
  val filename:String,
  val format_name:String,
  val duration:Double,
  val size:Long,
  val bit_rate:Double,
  val tags:AudioProbeFormatTags
)

@JsonIgnoreProperties(ignoreUnknown = true)
class AudioProbeResult (
  val streams:MutableList<AudioProbeStream>,
  val chapters:MutableList<AudioProbeChapter>,
  val format:AudioProbeFormat) {

  val duration get() = format.duration
  val size get() = format.size
  val title get() = format.tags.title ?: format.filename.split("/").last()
  val artist get() = format.tags.artist ?: ""

  @JsonIgnore
  fun getBookChapters(): List<BookChapter> {
    if (chapters.isEmpty()) return mutableListOf()
    return chapters.map { it.getBookChapter() }
  }
}
