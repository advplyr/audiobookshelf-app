package com.audiobookshelf.app.data

import android.net.Uri
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
  val start:Int,
  val end:Int,
  val tags:AudioProbeChapterTags
)

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
}
