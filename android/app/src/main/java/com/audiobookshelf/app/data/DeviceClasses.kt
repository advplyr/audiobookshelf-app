package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.*

data class ServerConnectionConfig(
  var id:String,
  var index:Int,
  var name:String,
  var address:String,
  var username:String,
  var token:String
)

data class DeviceData(
  var serverConnectionConfigs:MutableList<ServerConnectionConfig>,
  var lastServerConnectionConfigId:String?,
  var localLibraryItemIdPlaying:String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalLibraryItem(
  var id:String,
  var libraryItemId:String?,
  var folderId:String,
  var absolutePath:String,
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
  fun updateFromScan(audioTracks:List<AudioTrack>, _localFiles:MutableList<LocalFile>) {
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
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalMediaItem(
  var id:String,
  var name: String,
  var mediaType:String,
  var folderId:String,
  var contentUrl:String,
  var simplePath: String,
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
      return LocalLibraryItem(id, null, folderId, absolutePath,  false,mediaType, book, localFiles, coverContentUrl, coverAbsolutePath,true)
    } else {
      var podcast = Podcast(mediaMetadata as PodcastMetadata, coverAbsolutePath, mutableListOf(), mutableListOf(), false)
      return LocalLibraryItem(id, null, folderId, absolutePath, false, mediaType, podcast,localFiles,coverContentUrl, coverAbsolutePath, true)
    }
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFile(
  var id:String,
  var filename:String?,
  var contentUrl:String,
  var absolutePath:String,
  var simplePath:String,
  var mimeType:String?,
  var size:Long
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFolder(
  var id:String,
  var name:String?,
  var contentUrl:String,
  var absolutePath:String,
  var simplePath:String,
  var storageType:String,
  var mediaType:String
)
