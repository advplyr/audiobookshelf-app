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
