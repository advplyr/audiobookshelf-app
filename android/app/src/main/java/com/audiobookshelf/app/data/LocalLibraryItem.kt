package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalLibraryItem(
  var id:String,
  var serverAddress:String?,
  var libraryItemId:String?,
  var folderId:String,
  var basePath:String,
  var absolutePath:String,
  var contentUrl:String,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var localFiles:MutableList<LocalFile>,
  var coverContentUrl:String?,
  var coverAbsolutePath:String?,
  var isLocal:Boolean
) {
  @JsonIgnore
  fun getDuration():Double {
    var total = 0.0
    var audioTracks = media.getAudioTracks()
    audioTracks.forEach{ total += it.duration }
    return total
  }

  @JsonIgnore
  fun updateFromScan(audioTracks:MutableList<AudioTrack>, _localFiles:MutableList<LocalFile>) {
    media.setAudioTracks(audioTracks)
    localFiles = _localFiles

    if (coverContentUrl != null) {
      if (localFiles.find { it.contentUrl == coverContentUrl } == null) {
        // Cover was removed
        coverContentUrl = null
        coverAbsolutePath = null
        media.coverPath = null
      }
    }
  }

  @JsonIgnore
  fun getPlaybackSession():PlaybackSession {
    var sessionId = "play-${UUID.randomUUID()}"

    var mediaMetadata = media.metadata
    var chapters = if (mediaType == "book") (media as Book).chapters else mutableListOf()
    var authorName = "Unknown"
    if (mediaType == "book") {
      var bookMetadata = mediaMetadata as BookMetadata
      authorName = bookMetadata?.authorName ?: "Unknown"
    }
    return PlaybackSession(sessionId,null,null,null, mediaType, mediaMetadata, chapters, mediaMetadata.title, authorName,null,getDuration(),PLAYMETHOD_LOCAL, media.getAudioTracks() as MutableList<AudioTrack>,0.0,null,this,null,null)
  }

  @JsonIgnore
  fun removeLocalFile(localFileId:String) {
    localFiles.removeIf { it.id == localFileId }
  }
}
