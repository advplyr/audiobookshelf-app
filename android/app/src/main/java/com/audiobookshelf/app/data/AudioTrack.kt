package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

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

  /**
   * Provides a guaranteed stable and unique identifier for this track.
   *
   * It prioritizes the most stable ID available:
   * 1. The local file ID if the track is on the device.
   * 2. The remote content URL if the track is being streamed.
   * 3. A generated ID based on title and index as a last resort.
   */
  @get:JsonIgnore
  val stableId: String
    get() {
      if (isLocal && !localFileId.isNullOrBlank()) {
        return localFileId!!
      }
      if (contentUrl.isNotBlank()) {
        return contentUrl
      }
      return "${title}_${index}"
    }
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
