package com.audiobookshelf.app.data

import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalLibraryItem(
  var id:String,
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
  var isLocal:Boolean,
  // If local library item is linked to a server item
  var serverConnectionConfigId:String?,
  var serverAddress:String?,
  var serverUserId:String?,
  var libraryItemId:String?
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
  fun getPlaybackSession(episodeId:String):PlaybackSession {
    var sessionId = "play-${UUID.randomUUID()}"

    val mediaProgressId = if (episodeId.isNullOrEmpty()) id else "$id-$episodeId"
    var mediaProgress = DeviceManager.dbManager.getLocalMediaProgress(mediaProgressId)
    var currentTime = mediaProgress?.currentTime ?: 0.0

    // TODO: Clean up add mediaType methods for displayTitle and displayAuthor
    var mediaMetadata = media.metadata
    var chapters = if (mediaType == "book") (media as Book).chapters else mutableListOf()
    var authorName = "Unknown"
    if (mediaType == "book") {
      var bookMetadata = mediaMetadata as BookMetadata
      authorName = bookMetadata?.authorName ?: "Unknown"
    }

    var episodeIdNullable = if (episodeId.isNullOrEmpty()) null else episodeId
    var dateNow = System.currentTimeMillis()
    return PlaybackSession(sessionId,serverUserId,libraryItemId,episodeIdNullable, mediaType, mediaMetadata, chapters, mediaMetadata.title, authorName,null,getDuration(),PLAYMETHOD_LOCAL,dateNow,0L,0L, media.getAudioTracks() as MutableList<AudioTrack>,currentTime,null,this,serverConnectionConfigId, serverAddress)
  }

  @JsonIgnore
  fun removeLocalFile(localFileId:String) {
    localFiles.removeIf { it.id == localFileId }
  }
}
