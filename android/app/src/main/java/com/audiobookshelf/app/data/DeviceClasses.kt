package com.audiobookshelf.app.data

import android.net.Uri
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

data class LocalMediaItem(
  val name: String,
  val simplePath: String,
  val audioTracks:MutableList<AudioTrack>
)
