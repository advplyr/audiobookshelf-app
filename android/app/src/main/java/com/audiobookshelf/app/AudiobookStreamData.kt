package com.audiobookshelf.app

import android.net.Uri
import com.getcapacitor.JSObject

class AudiobookStreamData {
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

  var isLocal:Boolean = false
  var contentUrl:String = ""

  var hasPlayerLoaded:Boolean = false

  var playlistUri:Uri = Uri.EMPTY
  var coverUri:Uri = Uri.EMPTY
  var contentUri:Uri = Uri.EMPTY // For Local only

  constructor(jsondata:JSObject) {
    id = jsondata.getString("id", "audiobook").toString()
    title = jsondata.getString("title", "No Title").toString()
    token = jsondata.getString("token", "").toString()
    author = jsondata.getString("author", "Unknown").toString()
    series = jsondata.getString("series", "").toString()
    cover = jsondata.getString("cover", "").toString()
    playlistUrl = jsondata.getString("playlistUrl", "").toString()
    playWhenReady = jsondata.getBoolean("playWhenReady", false) == true

    if (jsondata.has("startTime")) {
      startTime = jsondata.getString("startTime", "0")!!.toLong()
    }

    if (jsondata.has("duration")) {
      duration = jsondata.getString("duration", "0")!!.toLong()
    }

    if (jsondata.has("playbackSpeed")) {
      playbackSpeed = jsondata.getDouble("playbackSpeed")!!.toFloat()
    }


    // Local data
    isLocal = jsondata.getBoolean("isLocal", false) == true
    contentUrl = jsondata.getString("contentUrl", "").toString()

    if (playlistUrl != "") {
      playlistUri = Uri.parse(playlistUrl)
    }
    if (cover != "" && cover != null) {
      coverUri = Uri.parse(cover)
    } else {
      coverUri = Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
      cover = coverUri.toString()
    }

    if (contentUrl != "") {
      contentUri = Uri.parse(contentUrl)
    }
  }
}
