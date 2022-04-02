package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.*

data class ServerConfig(
  var id:String,
  var index:Int,
  var name:String,
  var address:String,
  var username:String,
  var token:String
)

data class DeviceData(
  var serverConfigs:MutableList<ServerConfig>,
  var lastServerConfigId:String?
)

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
  var coverPath:String?
) {

  @JsonIgnore
  fun getDuration():Double {
    var total = 0.0
    audioTracks.forEach{ total += it.duration }
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
  fun getPlaybackSession():PlaybackSession {
    var sessionId = "play-${UUID.randomUUID()}"

    var mediaMetadata = getMediaMetadata()
    return PlaybackSession(sessionId,null,null,null,null,mediaType,mediaMetadata,null,getDuration(),PLAYMETHOD_LOCAL,audioTracks,0.0,null,this,null,null)
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
