package com.audiobookshelf.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat

import android.util.Log
import com.audiobookshelf.app.device.DeviceManager
import com.getcapacitor.JSObject
import okhttp3.*
import org.json.JSONArray
import java.io.IOException

class AudiobookManager {
  var tag = "AudiobookManager"

  var hasLoaded = false
  var isLoading = false
  var ctx: Context
  private var client:OkHttpClient

  var audiobooks:MutableList<Audiobook> = mutableListOf()
  var audiobooksInProgress:MutableList<Audiobook> = mutableListOf()

  constructor(_ctx:Context, _client:OkHttpClient) {
    ctx = _ctx
    client = _client
  }

  fun loadCategories(cb: (() -> Unit)) {
    var url = "${DeviceManager.serverAddress}/api/libraries/main/categories"
    val request = Request.Builder()
      .url(url).addHeader("Authorization", "Bearer ${DeviceManager.token}")
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
                var audiobook = Audiobook(abjsobj, DeviceManager.serverAddress, DeviceManager.token)
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
    if (DeviceManager.serverAddress == "" || DeviceManager.token == "") {
      Log.d(tag, "Load Audiobooks: No Server or Token set")
      cb()
      return
    } else if (!DeviceManager.serverAddress.startsWith("http")) {
      Log.e(tag, "Load Audiobooks: Invalid server url ${DeviceManager.serverAddress}")
      cb()
      return
    }

    // First load currently reading
    loadCategories() {
      // Then load all
      var url = "${DeviceManager.serverAddress}/api/libraries/main/books/all?sort=book.title"
      val request = Request.Builder()
        .url(url).addHeader("Authorization", "Bearer ${DeviceManager.token}")
        .build()

      client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          Log.d(tag, "Load Audiobooks: FAILURE TO CONNECT")
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
              var audiobook = Audiobook(jsobj, DeviceManager.serverAddress, DeviceManager.token)

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
    return null
  }

  // Used for media browser loadChildren, fallback to using the samples if no audiobooks are there
  fun getAudiobooksMediaMetadata() : List<MediaMetadataCompat> {
    var mediaMetadata:MutableList<MediaMetadataCompat> = mutableListOf()
    if (audiobooks.isEmpty()) {

    } else {
      audiobooks.forEach { mediaMetadata.add(it.toMediaMetadata()) }
    }
    return mediaMetadata
  }
  // Used for media browser loadChildren, fallback to using the samples if no audiobooks are there
  fun getDownloadedAudiobooksMediaMetadata() : List<MediaMetadataCompat> {
    var mediaMetadata:MutableList<MediaMetadataCompat> = mutableListOf()
    if (audiobooks.isEmpty()) {

    } else {
      audiobooks.forEach { if (it.isDownloaded) { mediaMetadata.add(it.toMediaMetadata()) } }
    }
    return mediaMetadata
  }
}
