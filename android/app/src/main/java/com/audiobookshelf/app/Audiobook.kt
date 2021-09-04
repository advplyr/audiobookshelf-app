package com.audiobookshelf.app

import android.net.Uri
import com.getcapacitor.JSObject

class Audiobook {
  var id:String = "audiobook"
  var token:String = ""
  var playlistUrl:String = ""
  var title:String = "No Title"
  var author:String = "Unknown"
  var series:String = ""
  var cover:String = ""
  var playWhenReady:Boolean = false
  var startTime:Long = 0
  var playbackSpeed:Float = 1f
  var duration:Long = 0

  var hasPlayerLoaded:Boolean = false

  val playlistUri:Uri
  val coverUri:Uri

  constructor(jsondata:JSObject) {
    id = jsondata.getString("id", "audiobook").toString()
    title = jsondata.getString("title", "No Title").toString()
    token = jsondata.getString("token", "").toString()
    author = jsondata.getString("author", "Unknown").toString()
    series = jsondata.getString("series", "").toString()
    cover = jsondata.getString("cover", "").toString()
    playlistUrl = jsondata.getString("playlistUrl", "").toString()
    playWhenReady = jsondata.getBoolean("playWhenReady", false) == true
    startTime = jsondata.getString("startTime", "0")!!.toLong()
    playbackSpeed = jsondata.getDouble("playbackSpeed")!!.toFloat()
    duration = jsondata.getString("duration", "0")!!.toLong()

    playlistUri = Uri.parse(playlistUrl)
    coverUri = Uri.parse(cover)
  }
}
