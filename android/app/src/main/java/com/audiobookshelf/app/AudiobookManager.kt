package com.audiobookshelf.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper

import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.jeep.plugin.capacitor.capacitordatastoragesqlite.CapacitorDataStorageSqlite
import okhttp3.*
import org.json.JSONArray
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

  lateinit var localMediaManager:LocalMediaManager

  var audiobooks:MutableList<Audiobook> = mutableListOf()
  var audiobooksInProgress:MutableList<Audiobook> = mutableListOf()

  constructor(_ctx:Context, _client:OkHttpClient) {
    ctx = _ctx
    client = _client

    localMediaManager = LocalMediaManager(ctx)
  }

  fun init() {
   var sharedPreferences = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    serverUrl = sharedPreferences.getString("serverUrl", "").toString()
    Log.d(tag, "SHARED PREF SERVERURL $serverUrl")
    token = sharedPreferences.getString("token", "").toString()
    Log.d(tag, "SHARED PREF TOKEN $token")
  }

  fun loadCategories(cb: (() -> Unit)) {
    Log.d(tag, "LOAD Categories $serverUrl | $token")
    var url = "$serverUrl/api/libraries/main/categories"
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
          var results = JSONArray(bodyString)
//          var results = resJson.getJSONArray("results")

          var totalShelves = results.length() - 1
          Log.d(tag, "Got categories $totalShelves")
          for (i in 0..totalShelves) {
            var shelfobj = results.get(i)
            var jsobj = JSObject(shelfobj.toString())
            var shelfId = jsobj.getString("id", "")
            Log.d(tag, "Category shelf id $shelfId")
            if (shelfId == "continue-reading") {
              var entities = jsobj.getJSONArray("entities")
              var totalEntities = entities.length() - 1
              Log.d(tag, "Shelf total entities $totalEntities")
              for (y in 0..totalEntities) {
                var abobj = entities.get(y)
                Log.d(tag, "Shelf category ab id $y = ${abobj.toString()}")
                var abjsobj = JSObject(abobj.toString())
                abjsobj.put("isDownloaded", false)
                var audiobook = Audiobook(abjsobj, serverUrl, token)
                if (audiobook.isMissing || audiobook.isInvalid || audiobook.numTracks <= 0) {
                  Log.d(tag, "Not an audiobook or invalid/missing")
                } else {
                  var audiobookExists = audiobooksInProgress.find { it.id == audiobook.id }
                  if (audiobookExists == null) {
                    audiobooksInProgress.add(audiobook)
                  }
                }
              }
            }
          }
          Log.d(tag, "${audiobooksInProgress.size} Audiobooks In Progress Loaded")
          cb()
        }
      }
    })
  }

  fun loadAudiobooks(cb: (() -> Unit)) {
    Log.d(tag, "LOAD AUDIBOOOSK $serverUrl | $token")
    if (serverUrl == "" || token == "") {
      Log.d(tag, "No Server or Token set")
      cb()
      return
    } else if (!serverUrl.startsWith("http")) {
      Log.e(tag, "Invalid server url $serverUrl")
      cb()
      return
    }

    // First load currently reading
    loadCategories() {
      // Then load all
      var url = "$serverUrl/api/libraries/main/books/all"
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
            var resJson = JSObject(bodyString)
            var results = resJson.getJSONArray("results")

            var totalBooks = results.length() - 1
            for (i in 0..totalBooks) {
              var abobj = results.get(i)
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
  }

  fun load() {
    isLoading = true
    hasLoaded = true

    localMediaManager.loadLocalAudio()

    var db = CapacitorDataStorageSqlite(ctx)
    db.openStore("storage", "downloads", false, "no-encryption", 1)
    var keyvalues = db.keysvalues()
    keyvalues.forEach {
      Log.d(tag, "keyvalue ${it.getString("key")} | ${it.getString("value")}")

      var dlobj = JSObject(it.getString("value"))
      if (dlobj.has("audiobook")) {
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
  }

  fun openStream(audiobook:Audiobook, streamListener:OnStreamData) {
    var url = "$serverUrl/api/books/${audiobook.id}/stream"
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

  fun initDownloadPlay(audiobook:Audiobook):AudiobookStreamData {
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

  fun initLocalPlay(local: LocalMediaManager.LocalAudio):AudiobookStreamData {
    var abStreamDataObj = JSObject()
    abStreamDataObj.put("id", local.id)
    abStreamDataObj.put("contentUrl", local.uri.toString())
    abStreamDataObj.put("title", local.name)
    abStreamDataObj.put("author", "")
    abStreamDataObj.put("token", null)
    abStreamDataObj.put("cover", local.coverUri)
    abStreamDataObj.put("duration", local.duration)
    abStreamDataObj.put("startTime", 0)
    abStreamDataObj.put("playbackSpeed", 1)
    abStreamDataObj.put("playWhenReady", true)
    abStreamDataObj.put("isLocal", true)

    var audiobookStreamData = AudiobookStreamData(abStreamDataObj)
    return audiobookStreamData
  }

  private fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1..rhsLength-1) {
      newCost[0] = i

      for (j in 1..lhsLength-1) {
        val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

        val costReplace = cost[j - 1] + match
        val costInsert = cost[j] + 1
        val costDelete = newCost[j - 1] + 1

        newCost[j] = Math.min(Math.min(costInsert, costDelete), costReplace)
      }

      val swap = cost
      cost = newCost
      newCost = swap
    }

    return cost[lhsLength - 1]
  }

  fun searchForAudiobook(query:String):Audiobook? {
    var closestDistance = 99
    var closestMatch:Audiobook? = null
    audiobooks.forEach {
      var dist = levenshtein(it.book.title, query)
      Log.d(tag, "LEVENSHTEIN $dist")
      if (dist < closestDistance) {
        closestDistance = dist
        closestMatch = it
      }
    }
    if (closestMatch != null) {
      Log.d(tag, "Closest Search is ${closestMatch?.book?.title} with distance $closestDistance")
      if (closestDistance < 2) {
        return closestMatch
      }
      return null
    }
    return null
  }

  fun getFirstAudiobook():Audiobook? {
    if (audiobooks.isEmpty()) return null
    return audiobooks[0]
  }

  fun getFirstLocal(): LocalMediaManager.LocalAudio? {
    if (localMediaManager.localAudioFiles.isEmpty()) return null
    return localMediaManager.localAudioFiles[0]
  }
}
