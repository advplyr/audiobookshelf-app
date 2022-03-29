package com.audiobookshelf.app.server

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiHandler {
  val tag = "ApiHandler"
  private var client = OkHttpClient()
  var ctx: Context
  var serverUrl = ""
  var token = ""
  var storageSharedPreferences: SharedPreferences? = null

  constructor(_ctx: Context) {
    ctx = _ctx
    init()
  }

  fun init() {
    storageSharedPreferences = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    serverUrl = storageSharedPreferences?.getString("serverUrl", "").toString()
    Log.d(tag, "SHARED PREF SERVERURL $serverUrl")
    token = storageSharedPreferences?.getString("token", "").toString()
    Log.d(tag, "SHARED PREF TOKEN $token")
  }

  fun getRequest(endpoint:String, cb: (JSObject) -> Unit) {
    val request = Request.Builder()
      .url("$serverUrl$endpoint").addHeader("Authorization", "Bearer $token")
      .build()
    makeRequest(request, cb)
  }

  fun postRequest(endpoint:String, payload: JSObject, cb: (JSObject) -> Unit) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    val request = Request.Builder().post(requestBody)
      .url("$serverUrl$endpoint").addHeader("Authorization", "Bearer $token")
      .build()
    makeRequest(request, cb)
  }

  fun makeRequest(request:Request, cb: (JSObject) -> Unit) {
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()
        cb(JSObject())
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!response.isSuccessful) throw IOException("Unexpected code $response")
          var bodyString = response.body!!.string()
          var jsonObj = JSObject()
          if (bodyString.startsWith("[")) {
            var array = JSArray(bodyString)
            jsonObj.put("value", array)
          } else {
            jsonObj = JSObject(bodyString)
          }
          cb(jsonObj)
        }
      }
    })
  }

  fun getLibraries(cb: (List<Library>) -> Unit) {
    val mapper = jacksonObjectMapper()
    getRequest("/api/libraries") {
      val libraries = mutableListOf<Library>()
      if (it.has("value")) {
        var array = it.getJSONArray("value")!!
        for (i in 0 until array.length()) {
          val library = mapper.readValue<Library>(array.get(i).toString())
          libraries.add(library)
        }
      }
      cb(libraries)
    }
  }

  fun getLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    val mapper = jacksonObjectMapper()
    getRequest("/api/libraries/$libraryId/items") {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        var array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = mapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun playLibraryItem(libraryItemId:String, cb: (PlaybackSession) -> Unit) {
    val mapper = jacksonObjectMapper()
    var payload = JSObject()
    payload.put("mediaPlayer", "exo-player")
    payload.put("forceDirectPlay", true)

    postRequest("/api/items/$libraryItemId/play", payload) {
      it.put("serverUrl", serverUrl)
      it.put("token", token)
      val playbackSession = mapper.readValue<PlaybackSession>(it.toString())
      cb(playbackSession)
    }
  }
}
