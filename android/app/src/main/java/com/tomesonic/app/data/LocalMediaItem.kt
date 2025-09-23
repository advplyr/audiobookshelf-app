package com.tomesonic.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/*
 Used as a helper class to generate LocalLibraryItem from scan results
*/

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalMediaItem(
        var id: String,
        var name: String,
        var mediaType: String,
        var folderId: String,
        var contentUrl: String,
        var simplePath: String,
        var basePath: String,
        var absolutePath: String,
        var audioTracks: MutableList<AudioTrack>,
        var ebookFile: EBookFile?,
        var localFiles: MutableList<LocalFile>,
        var coverContentUrl: String?,
        var coverAbsolutePath: String?
) {

  @JsonIgnore
  fun getDuration(): Double {
    var total = 0.0
    audioTracks.forEach { total += it.duration }
    return total
  }

  @JsonIgnore
  fun getTotalSize(): Long {
    var total = 0L
    localFiles.forEach { total += it.size }
    return total
  }

  @JsonIgnore
  fun getMediaMetadata(): MediaTypeMetadata {
    return if (mediaType == "book") {
      BookMetadata(
              name,
              null,
              mutableListOf(),
              mutableListOf(),
              mutableListOf(),
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              null,
              null,
              null,
              null,
              null
      )
    } else {
      PodcastMetadata(name, null, null, mutableListOf(), false)
    }
  }
}
