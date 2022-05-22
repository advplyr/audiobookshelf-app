package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.*

data class ServerConnectionConfig(
  var id:String,
  var index:Int,
  var name:String,
  var address:String,
  var userId:String,
  var username:String,
  var token:String
)

data class DeviceData(
  var serverConnectionConfigs:MutableList<ServerConnectionConfig>,
  var lastServerConnectionConfigId:String?,
  var currentLocalPlaybackSession:PlaybackSession? // Stored to open up where left off for local media
) {
  @JsonIgnore
  fun getLastServerConnectionConfig():ServerConnectionConfig? {
    return lastServerConnectionConfigId?.let { lsccid ->
      return serverConnectionConfigs.find { it.id == lsccid }
    }
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFile(
  var id:String,
  var filename:String?,
  var contentUrl:String,
  var basePath:String,
  var absolutePath:String,
  var simplePath:String,
  var mimeType:String?,
  var size:Long
) {
  @JsonIgnore
  fun isAudioFile():Boolean {
    if (mimeType == "application/octet-stream") return true
    if (mimeType == "video/mp4") return true
    return mimeType?.startsWith("audio") == true
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFolder(
  var id:String,
  var name:String,
  var contentUrl:String,
  var basePath:String,
  var absolutePath:String,
  var simplePath:String,
  var storageType:String,
  var mediaType:String
)

@JsonTypeInfo(use= JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
  JsonSubTypes.Type(LibraryItem::class),
  JsonSubTypes.Type(LocalLibraryItem::class)
)
open class LibraryItemWrapper()
