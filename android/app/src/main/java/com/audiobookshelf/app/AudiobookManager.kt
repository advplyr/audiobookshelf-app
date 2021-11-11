package com.audiobookshelf.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat

import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.jeep.plugin.capacitor.capacitordatastoragesqlite.CapacitorDataStorageSqlite
import okhttp3.*
import java.io.IOException
import java.net.URL

class AudiobookManager {
  var tag = "AudiobookManager"

  var hasLoaded = false
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

  fun fetchAudiobooks(result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    var url = "$serverUrl/api/library/main/audiobooks"
    Log.d(tag, "RUNNING SAMPLER $url")
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
//          for ((name, value) in response.headers) {
//            Log.d(tag, "HEADER $name: $value")
//          }

          var bodyString = response.body!!.string()
          var json = JSArray(bodyString)
          var totalBooks = json.length() - 1
          for (i in 0..totalBooks) {
            var abobj = json.get(i)
            var jsobj = JSObject(abobj.toString())
            var audiobook = Audiobook(jsobj)
            audiobooks.add(audiobook)
            Log.d(tag, "Audiobook: ${audiobook.toString()}")
          }
          Log.d(tag, "Audiobooks Loaded")

          val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
          audiobooks.forEach {
            var builder = MediaDescriptionCompat.Builder()
              .setMediaId(it.id)
              .setTitle(it.book.title)
              .setSubtitle(it.book.authorFL)
              .setMediaUri(it.fallbackUri)
              .setIconUri(it.fallbackCover)

            var mediaDescription = builder.build()
            var newMediaItem = MediaBrowserCompat.MediaItem(mediaDescription, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
            mediaItems.add(newMediaItem)

          }
          Log.d(tag, "AudiobookManager: Sending ${mediaItems.size} Aduiobooks")
          result.sendResult(mediaItems)
        }
      }
    })
  }

  fun load() {
    hasLoaded = true

    var db = CapacitorDataStorageSqlite(ctx)
    db.openStore("storage", "downloads", false, "no-encryption", 1)
    Log.d(tag, "CHECK IF DB IS OPEN ${db.isStoreOpen("storage")}")
    var keyvalues = db.keysvalues()
    Log.d(tag, "KEY VALUES $keyvalues")
    keyvalues.forEach { Log.d(tag, "keyvalue ${it.getString("key")} | ${it.getString("value")}") }
  }
}
