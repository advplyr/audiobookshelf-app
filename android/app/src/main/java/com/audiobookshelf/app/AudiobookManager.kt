package com.audiobookshelf.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat

import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.jeep.plugin.capacitor.capacitordatastoragesqlite.CapacitorDataStorageSqlite
import okhttp3.*
import java.io.IOException

class AudiobookManager {
  var tag = "AudiobookManager"

  interface OnStreamData {
    fun onStreamReady(asd:AudiobookStreamData)
  }

  var hasLoaded = false
  var isLoading = false
  var ctx: Context
  var serverUrl = ""
  var token = ""
  private var client:OkHttpClient

  var audiobooks:MutableList<Audiobook> = mutableListOf()

  constructor(_ctx:Context, _client:OkHttpClient) {
    ctx = _ctx
    client = _client
  }

  fun init() {
   var sharedPreferences = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    serverUrl = sharedPreferences.getString("serverUrl", null).toString()
    Log.d(tag, "SHARED PREF SERVERURL $serverUrl")
    token = sharedPreferences.getString("token", null).toString()
    Log.d(tag, "SHARED PREF TOKEN $token")
  }

  fun loadAudiobooks(cb: (() -> Unit)) {
    if (serverUrl == "" || token == "") {
      Log.d(tag, "No Server or Token set")
      cb()
      return
    }

    var url = "$serverUrl/api/library/main/audiobooks"
    val request = Request.Builder()
      .url(url).addHeader("Authorization", "Bearer $token")
      .build()

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()
        cb()
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          var bodyString = response.body!!.string()
          var json = JSArray(bodyString)
          var totalBooks = json.length() - 1
          for (i in 0..totalBooks) {
            var abobj = json.get(i)
            var jsobj = JSObject(abobj.toString())
            jsobj.put("isDownloaded", false)
            var audiobook = Audiobook(jsobj, serverUrl, token)

            if (audiobook.isMissing || audiobook.isInvalid) {
              Log.d(tag, "Audiobook ${audiobook.book.title} is missing or invalid")
            } else if (audiobook.numTracks <= 0) {
              Log.d(tag, "Audiobook ${audiobook.book.title} has audio tracks")
            } else {
              var audiobookExists = audiobooks.find { it.id == audiobook.id }
              if (audiobookExists == null) {
                audiobooks.add(audiobook)
              } else {
                Log.d(tag, "Audiobook already there from downloaded")
              }
            }
          }
          Log.d(tag, "${audiobooks.size} Audiobooks Loaded")
          cb()
        }
      }
    })
  }

  fun fetchAudiobooks(result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    var url = "$serverUrl/api/library/main/audiobooks"
    val request = Request.Builder()
      .url(url).addHeader("Authorization", "Bearer $token")
      .build()

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          var bodyString = response.body!!.string()
          var json = JSArray(bodyString)
          var totalBooks = json.length() - 1
          for (i in 0..totalBooks) {
            var abobj = json.get(i)
            var jsobj = JSObject(abobj.toString())
            jsobj.put("isDownloaded", false)
            var audiobook = Audiobook(jsobj, serverUrl, token)

            if (audiobook.isMissing || audiobook.isInvalid) {
              Log.d(tag, "Audiobook ${audiobook.book.title} is missing or invalid")
            } else if (audiobook.numTracks <= 0) {
              Log.d(tag, "Audiobook ${audiobook.book.title} has audio tracks")
            } else {
              var audiobookExists = audiobooks.find { it.id == audiobook.id }
              if (audiobookExists == null) {
                audiobooks.add(audiobook)
              } else {
                Log.d(tag, "Audiobook already there from downloaded")
              }
            }
          }
          Log.d(tag, "${audiobooks.size} Audiobooks Loaded")

          val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
          audiobooks.forEach {
            var builder = MediaDescriptionCompat.Builder()
              .setMediaId(it.id)
              .setTitle(it.book.title)
              .setSubtitle(it.book.authorFL)
              .setMediaUri(null)
              .setIconUri(it.getCover())

            val extras = Bundle()
            if (it.isDownloaded) {
              extras.putLong(
                MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
                MediaDescriptionCompat.STATUS_DOWNLOADED)
            }
//            extras.putInt(
//              MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
//              MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
            builder.setExtras(extras)

            var mediaDescription = builder.build()
            var newMediaItem = MediaBrowserCompat.MediaItem(mediaDescription, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
            mediaItems.add(newMediaItem)

          }
          Log.d(tag, "AudiobookManager: Sending ${mediaItems.size} Audiobooks")
          result.sendResult(mediaItems)
        }
      }
    })
  }

  fun load() {
    isLoading = true
    hasLoaded = true

    var db = CapacitorDataStorageSqlite(ctx)
    db.openStore("storage", "downloads", false, "no-encryption", 1)
    var keyvalues = db.keysvalues()
    keyvalues.forEach {
      Log.d(tag, "keyvalue ${it.getString("key")} | ${it.getString("value")}")

      var dlobj = JSObject(it.getString("value"))
      var abobj = dlobj.getJSObject("audiobook")!!
      abobj.put("isDownloaded", true)
      abobj.put("contentUrl", dlobj.getString("contentUrl", "").toString())
      abobj.put("filename", dlobj.getString("filename", "").toString())
      abobj.put("folderUrl", dlobj.getString("folderUrl", "").toString())
      abobj.put("downloadFolderUrl", dlobj.getString("downloadFolderUrl", "").toString())
      abobj.put("localCoverUrl", dlobj.getString("coverUrl", "").toString())
      abobj.put("localCover", dlobj.getString("cover", "").toString())

      var audiobook = Audiobook(abobj, serverUrl, token)
      audiobooks.add(audiobook)
    }
  }

  fun openStream(audiobook:Audiobook, streamListener:OnStreamData) {
    var url = "$serverUrl/api/audiobook/${audiobook.id}/stream"
    val request = Request.Builder()
      .url(url).addHeader("Authorization", "Bearer $token")
      .build()

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        e.printStackTrace()
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          var bodyString = response.body!!.string()
          var stream = JSObject(bodyString)
          var startTime = stream.getDouble("startTime")
          var streamUrl = stream.getString("streamUrl", "").toString()

          var startTimeLong = (startTime * 1000).toLong()

          var abStreamDataObj = JSObject()
          abStreamDataObj.put("id", audiobook.id)
          abStreamDataObj.put("playlistUrl", "$serverUrl$streamUrl")
          abStreamDataObj.put("title", audiobook.book.title)
          abStreamDataObj.put("author", audiobook.book.authorFL)
          abStreamDataObj.put("token", token)
          abStreamDataObj.put("cover", audiobook.getCover())
          abStreamDataObj.put("duration", audiobook.getDurationLong())
          abStreamDataObj.put("startTime", startTimeLong)
          abStreamDataObj.put("playbackSpeed", 1)
          abStreamDataObj.put("playWhenReady", true)
          abStreamDataObj.put("isLocal", false)

          var audiobookStreamData = AudiobookStreamData(abStreamDataObj)

          Handler(Looper.getMainLooper()).post() {
            Log.d(tag, "Stream Ready on Main Looper")
            streamListener.onStreamReady(audiobookStreamData)
          }

          Log.d(tag, "Init Player Stream")
        }
      }
    })
  }

  fun initLocalPlay(audiobook:Audiobook):AudiobookStreamData {

    var abStreamDataObj = JSObject()
    abStreamDataObj.put("id", audiobook.id)
    abStreamDataObj.put("contentUrl", audiobook.contentUrl)
    abStreamDataObj.put("title", audiobook.book.title)
    abStreamDataObj.put("author", audiobook.book.authorFL)
    abStreamDataObj.put("token", null)
    abStreamDataObj.put("cover", audiobook.getCover())
    abStreamDataObj.put("duration", audiobook.getDurationLong())
    abStreamDataObj.put("startTime", 0)
    abStreamDataObj.put("playbackSpeed", 1)
    abStreamDataObj.put("playWhenReady", true)
    abStreamDataObj.put("isLocal", true)

    var audiobookStreamData = AudiobookStreamData(abStreamDataObj)
    return audiobookStreamData
  }
}
