package com.audiobookshelf.app.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.MediaProgressSyncData
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
  var storageSharedPreferences: SharedPreferences? = null

  constructor(_ctx: Context) {
    ctx = _ctx
  }

  fun getRequest(endpoint:String, cb: (JSObject) -> Unit) {
    val request = Request.Builder()
      .url("${DeviceManager.serverAddress}$endpoint").addHeader("Authorization", "Bearer ${DeviceManager.token}")
      .build()
    makeRequest(request, cb)
  }

  fun postRequest(endpoint:String, payload: JSObject, cb: (JSObject) -> Unit) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    val request = Request.Builder().post(requestBody)
      .url("${DeviceManager.serverAddress}$endpoint").addHeader("Authorization", "Bearer ${DeviceManager.token}")
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

  fun getLibraryItem(libraryItemId:String, cb: (LibraryItem) -> Unit) {
    getRequest("/api/items/$libraryItemId?expanded=1") {
      val libraryItem = jacksonObjectMapper().readValue<LibraryItem>(it.toString())
      cb(libraryItem)
    }
  }

  fun getLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    getRequest("/api/libraries/$libraryId/items") {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        var array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonObjectMapper().readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun playLibraryItem(libraryItemId:String, episodeId:String, forceTranscode:Boolean, cb: (PlaybackSession) -> Unit) {
    val mapper = jacksonObjectMapper()
    var payload = JSObject()
    payload.put("mediaPlayer", "exo-player")

    // Only if direct play fails do we force transcode
    // TODO: Fallback to transcode
    if (!forceTranscode) payload.put("forceDirectPlay", true)
    else payload.put("forceTranscode", true)

    val endpoint = if (episodeId.isNullOrEmpty()) "/api/items/$libraryItemId/play" else "/api/items/$libraryItemId/play/$episodeId"
    postRequest(endpoint, payload) {
      it.put("serverConnectionConfigId", DeviceManager.serverConnectionConfig?.id)
      it.put("serverAddress", DeviceManager.serverAddress)
      val playbackSession = mapper.readValue<PlaybackSession>(it.toString())
      cb(playbackSession)
    }
  }

  fun sendProgressSync(sessionId:String, syncData: MediaProgressSyncData, cb: () -> Unit) {
    var payload = JSObject(jacksonObjectMapper().writeValueAsString(syncData))

    postRequest("/api/session/$sessionId/sync", payload) {
      cb()
    }
  }
}
