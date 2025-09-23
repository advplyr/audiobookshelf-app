package com.tomesonic.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioTrack(
        var index: Int,
        var startOffset: Double,
        var duration: Double,
        var title: String,
        var contentUrl: String,
        var mimeType: String,
        var metadata: FileMetadata?,
        var isLocal: Boolean,
        var localFileId: String?,
        // TODO: This should no longer be necessary
        var serverIndex: Int? // Need to know if server track index is different
) {

  @get:JsonIgnore
  val startOffsetMs
    get() = (startOffset * 1000L).toLong()
  @get:JsonIgnore
  val durationMs
    get() = (duration * 1000L).toLong()
  @get:JsonIgnore
  val endOffsetMs
    get() = startOffsetMs + durationMs
  @get:JsonIgnore
  val relPath
    get() = metadata?.relPath ?: ""

  @JsonIgnore
  fun getBookChapter(): BookChapter {
    return BookChapter(index + 1, startOffset, startOffset + duration, title)
  }
}
