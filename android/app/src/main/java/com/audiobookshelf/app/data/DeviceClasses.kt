package com.audiobookshelf.app.data

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.getcapacitor.JSObject

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
  var name: String,
  var contentUrl:String,
  var simplePath: String,
  var absolutePath:String,
  var audioTracks:MutableList<AudioTrack>,
  var localFiles:MutableList<LocalFile>,
  var coverPath:String?
)

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
