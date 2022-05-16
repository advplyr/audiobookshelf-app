package com.bookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/*
  Used as a helper class to generate LocalLibraryItem from scan results
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalMediaItem(
  var id:String,
  var name: String,
  var mediaType:String,
  var folderId:String,
  var contentUrl:String,
  var simplePath: String,
  var basePath:String,
  var absolutePath:String,
  var audioTracks:MutableList<AudioTrack>,
  var localFiles:MutableList<LocalFile>,
  var coverContentUrl:String?,
  var coverAbsolutePath:String?
) {

  @JsonIgnore
  fun getDuration():Double {
    var total = 0.0
    audioTracks.forEach{ total += it.duration }
    return total
  }

  @JsonIgnore
  fun getTotalSize():Long {
    var total = 0L
    localFiles.forEach { total += it.size }
    return total
  }

  @JsonIgnore
  fun getMediaMetadata():MediaTypeMetadata {
    return if (mediaType == "book") {
      BookMetadata(name,null, mutableListOf(), mutableListOf(), mutableListOf(),null,null,null,null,null,null,null,false,null,null,null,null)
    } else {
      PodcastMetadata(name,null,null, mutableListOf())
    }
  }

  @JsonIgnore
  fun getAudiobookChapters():List<BookChapter> {
    if (mediaType != "book" || audioTracks.isEmpty()) return mutableListOf()
    if (audioTracks.size == 1) { // Single track audiobook look for chapters from ffprobe
      return audioTracks[0].audioProbeResult?.getBookChapters() ?: mutableListOf()
    }
    // Multi-track make chapters from tracks
    return audioTracks.map { it.getBookChapter() }
  }

  @JsonIgnore
  fun getLocalLibraryItem():LocalLibraryItem {
    var mediaMetadata = getMediaMetadata()
    if (mediaType == "book") {
      var chapters = getAudiobookChapters()
      var book = Book(mediaMetadata as BookMetadata, coverAbsolutePath, mutableListOf(), mutableListOf(), chapters,audioTracks,getTotalSize(),getDuration())
      return LocalLibraryItem(id, folderId, basePath,absolutePath, contentUrl,  false,mediaType, book, localFiles, coverContentUrl, coverAbsolutePath,true,null,null,null,null)
    } else {
      var podcast = Podcast(mediaMetadata as PodcastMetadata, coverAbsolutePath, mutableListOf(), mutableListOf(), false)
      podcast.setAudioTracks(audioTracks) // Builds episodes from audio tracks
      return LocalLibraryItem(id, folderId, basePath,absolutePath, contentUrl, false, mediaType, podcast,localFiles,coverContentUrl, coverAbsolutePath, true, null,null,null,null)
    }
  }
}
