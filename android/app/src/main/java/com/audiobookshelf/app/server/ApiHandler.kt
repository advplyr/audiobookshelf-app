package com.audiobookshelf.app.server

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.models.User
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.plugins.AbsLogger
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EMPTY_REQUEST
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiHandler(var ctx:Context) {
  val tag = "ApiHandler"

  private var defaultClient = OkHttpClient()
  private var pingClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  data class LocalSessionsSyncRequestPayload(val sessions:List<PlaybackSession>, val deviceInfo:DeviceInfo)
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class LocalSessionSyncResult(val id:String, val success:Boolean, val progressSynced:Boolean?, val error:String?)
  data class LocalSessionsSyncResponsePayload(val results:List<LocalSessionSyncResult>)

  private fun getRequest(endpoint:String, httpClient:OkHttpClient?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress
    val token = config?.token ?: DeviceManager.token

    try {
      val request = Request.Builder()
        .url("${address}$endpoint").addHeader("Authorization", "Bearer $token")
        .build()
      makeRequest(request, httpClient, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun postRequest(endpoint:String, payload: JSObject?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress
    val token = config?.token ?: DeviceManager.token
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload?.toString()?.toRequestBody(mediaType) ?: EMPTY_REQUEST
    val requestUrl = "${address}$endpoint"
    Log.d(tag, "postRequest to $requestUrl")
    try {
      val request = Request.Builder().post(requestBody)
        .url(requestUrl).addHeader("Authorization", "Bearer ${token}")
        .build()
      makeRequest(request, null, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun patchRequest(endpoint:String, payload: JSObject, cb: (JSObject) -> Unit) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    try {
      val request = Request.Builder().patch(requestBody)
        .url("${DeviceManager.serverAddress}$endpoint").addHeader("Authorization", "Bearer ${DeviceManager.token}")
        .build()
      makeRequest(request, null, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun makeRequest(request:Request, httpClient:OkHttpClient?, cb: (JSObject) -> Unit) {
    val client = httpClient ?: defaultClient

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()

        val jsobj = JSObject()
        jsobj.put("error", "Failed to connect")
        cb(jsobj)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!it.isSuccessful) {
            val jsobj = JSObject()
            jsobj.put("error", "Unexpected code $response")
            cb(jsobj)
            return
          }

          val bodyString = it.body!!.string()
          if (bodyString == "OK") {
            cb(JSObject())
          } else {
            try {
              var jsonObj = JSObject()
              if (bodyString.startsWith("[")) {
                val array = JSArray(bodyString)
                jsonObj.put("value", array)
              } else {
                jsonObj = JSObject(bodyString)
              }
              cb(jsonObj)
            } catch(je:JSONException) {
              Log.e(tag, "Invalid JSON response ${je.localizedMessage} from body $bodyString")
              val jsobj = JSObject()
              jsobj.put("error", "Invalid response body")
              cb(jsobj)
            }
          }
        }
      }
    })
  }

  fun getCurrentUser(cb: (User?) -> Unit) {
    getRequest("/api/me", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getCurrentUser Failed")
        cb(null)
      } else {
        val user = jacksonMapper.readValue<User>(it.toString())
        cb(user)
      }
    }
  }

  fun getLibraries(cb: (List<Library>) -> Unit) {
    val mapper = jacksonMapper
    getRequest("/api/libraries?include=stats", null,null) {
      val libraries = mutableListOf<Library>()

      var array = JSONArray()
      if (it.has("libraries")) { // TODO: Server 2.2.9 changed to this
        array = it.getJSONArray("libraries")
      } else if (it.has("value")) {
        array = it.getJSONArray("value")
      }

      for (i in 0 until array.length()) {
        libraries.add(mapper.readValue(array.get(i).toString()))
      }
      cb(libraries)
    }
  }

  fun getLibraryPersonalized(libraryItemId:String, cb: (List<LibraryShelfType>?) -> Unit) {
    getRequest("/api/libraries/$libraryItemId/personalized", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryStats Failed")
        cb(null)
      } else {
        val items = mutableListOf<LibraryShelfType>()
        val array = it.getJSONArray("value")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryShelfType>(array.get(i).toString())
          items.add(item)
        }
        cb(items)
      }
    }
  }

  fun getLibraryItem(libraryItemId:String, cb: (LibraryItem?) -> Unit) {
    getRequest("/api/items/$libraryItemId?expanded=1", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItem Failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItemWithProgress(libraryItemId:String, episodeId:String?, cb: (LibraryItem?) -> Unit) {
    var requestUrl = "/api/items/$libraryItemId?expanded=1&include=progress"
    if (!episodeId.isNullOrEmpty()) requestUrl += "&episode=$episodeId"
    getRequest(requestUrl, null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItemWithProgress Failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    getRequest("/api/libraries/$libraryId/items?limit=100&minified=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeries(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/series?minified=1&sort=name&limit=10000", null, null) {
      val items = mutableListOf<LibrarySeriesItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibrarySeriesItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeriesItems(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting items for series")
    val seriesIdBase64 = Base64.encodeToString(seriesId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?minified=1&sort=media.metadata.title&filter=series.${seriesIdBase64}&limit=1000", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibraryAuthors(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/authors", null, null) {
      val items = mutableListOf<LibraryAuthorItem>()
      if (it.has("authors")) {
        val array = it.getJSONArray("authors")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryAuthorItem>(array.get(i).toString())
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryItemsFromAuthor(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting author items")
    val authorIdBase64 = Base64.encodeToString(authorId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?limit=1000&minified=1&filter=authors.${authorIdBase64}&sort=media.metadata.title&collapseseries=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          if (item.collapsedSeries != null) {
            item.collapsedSeries?.libraryId = libraryId
          }
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryCollections(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    Log.d(tag, "Getting collections")
    getRequest("/api/libraries/$libraryId/collections?minified=1&sort=name&limit=1000", null, null) {
      val items = mutableListOf<LibraryCollection>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryCollection>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getSearchResults(libraryId:String, queryString:String, cb: (LibraryItemSearchResultType?) -> Unit) {
    Log.d(tag, "Doing search for library $libraryId")
    getRequest("/api/libraries/$libraryId/search?q=$queryString", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getSearchResults Failed")
        cb(null)
      } else {
        val librarySearchResults = jacksonMapper.readValue<LibraryItemSearchResultType>(it.toString())
        cb(librarySearchResults)
      }
    }
  }

  fun getAllItemsInProgress(cb: (List<ItemInProgress>) -> Unit) {
    getRequest("/api/me/items-in-progress", null, null) {
      val items = mutableListOf<ItemInProgress>()
      if (it.has("libraryItems")) {
        val array = it.getJSONArray("libraryItems")
        for (i in 0 until array.length()) {
          val jsobj = array.get(i) as JSONObject

          val itemInProgress = ItemInProgress.makeFromServerObject(jsobj)
          items.add(itemInProgress)
        }
      }
      cb(items)
    }
  }

  fun playLibraryItem(libraryItemId:String, episodeId:String?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(playItemRequestPayload))

    val endpoint = if (episodeId.isNullOrEmpty()) "/api/items/$libraryItemId/play" else "/api/items/$libraryItemId/play/$episodeId"
    postRequest(endpoint, payload, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "Play Library Item Failed")
        cb(null)
      } else {
        it.put("serverConnectionConfigId", DeviceManager.serverConnectionConfig?.id)
        it.put("serverAddress", DeviceManager.serverAddress)
        val playbackSession = jacksonMapper.readValue<PlaybackSession>(it.toString())
        cb(playbackSession)
      }
    }
  }

  fun sendProgressSync(sessionId:String, syncData: MediaProgressSyncData, cb: (Boolean, String?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(syncData))

    postRequest("/api/session/$sessionId/sync", payload, null) {
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, it.getString("error"))
      } else {
        cb(true, null)
      }
    }
  }

  fun sendLocalProgressSync(playbackSession:PlaybackSession, cb: (Boolean, String?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(playbackSession))

    postRequest("/api/session/local", payload, null) {
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, it.getString("error"))
      } else {
        cb(true, null)
      }
    }
  }

  fun updateMediaProgress(libraryItemId:String,episodeId:String?,updatePayload:JSObject, cb: () -> Unit) {
    Log.d(tag, "updateMediaProgress $libraryItemId $episodeId $updatePayload")
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"
    patchRequest(endpoint,updatePayload) {
      Log.d(tag, "updateMediaProgress patched progress")
      cb()
    }
  }

  fun getMediaProgress(libraryItemId:String, episodeId:String?, serverConnectionConfig:ServerConnectionConfig?, cb: (MediaProgress?) -> Unit) {
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"

    // TODO: Using ping client here allows for shorter timeout (3 seconds), maybe rename or make diff client for requests requiring quicker response
    getRequest(endpoint, pingClient, serverConnectionConfig) {
      if (it.has("error")) {
        Log.e(tag, "getMediaProgress: Failed to get progress")
        cb(null)
      } else {
        val progress = jacksonMapper.readValue<MediaProgress>(it.toString())
        cb(progress)
      }
    }
  }

  fun getPlaybackSession(playbackSessionId:String, cb: (PlaybackSession?) -> Unit) {
    Log.d(tag, "getPlaybackSession for $playbackSessionId for server ${DeviceManager.serverAddress}")
    val endpoint = "/api/session/$playbackSessionId"
    getRequest(endpoint, null, null) {
      val err = it.getString("error")
      if (!err.isNullOrEmpty()) {
        cb(null)
      } else {
        cb(jacksonMapper.readValue<PlaybackSession>(it.toString()))
      }
    }
  }

  fun pingServer(config:ServerConnectionConfig, cb: (Boolean) -> Unit) {
    Log.d(tag, "pingServer: Pinging ${config.address}")
    getRequest("/ping", pingClient, config) {
      val success = it.getString("success")
      if (success.isNullOrEmpty()) {
        Log.d(tag, "pingServer: Ping ${config.address} Failed")
        cb(false)
      } else {
        Log.d(tag, "pingServer: Ping ${config.address} Successful")
        cb(true)
      }
    }
  }

  fun closePlaybackSession(playbackSessionId:String, config:ServerConnectionConfig?, cb: (Boolean) -> Unit) {
    Log.d(tag, "closePlaybackSession: playbackSessionId=$playbackSessionId")
    postRequest("/api/session/$playbackSessionId/close", null, config) {
      cb(true)
    }
  }

  fun authorize(config:ServerConnectionConfig, cb: (MutableList<MediaProgress>?) -> Unit) {
    Log.d(tag, "authorize: Authorizing ${config.address}")
    postRequest("/api/authorize", JSObject(), config) {
      val error = it.getString("error")
      if (!error.isNullOrEmpty()) {
        Log.d(tag, "authorize: Authorize ${config.address} Failed: $error")
        cb(null)
      } else {
        val mediaProgressList:MutableList<MediaProgress> = mutableListOf()
        val user = it.getJSObject("user")
        val mediaProgress = user?.getJSONArray("mediaProgress") ?: JSONArray()
        for (i in 0 until mediaProgress.length()) {
          val mediaProg = jacksonMapper.readValue<MediaProgress>(mediaProgress.getJSONObject(i).toString())
          mediaProgressList.add(mediaProg)
        }
        Log.d(tag, "authorize: Authorize ${config.address} Successful")
        cb(mediaProgressList)
      }
    }
  }

  fun sendSyncLocalSessions(playbackSessions:List<PlaybackSession>, cb: (Boolean, String?) -> Unit) {
    @SuppressLint("HardwareIds")
    val deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    val deviceInfo = DeviceInfo(deviceId, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME)

    val payload = JSObject(jacksonMapper.writeValueAsString(LocalSessionsSyncRequestPayload(playbackSessions, deviceInfo)))
    AbsLogger.info("[ApiHandler] sendSyncLocalSessions: Sending ${playbackSessions.size} saved local playback sessions to server (${DeviceManager.serverConnectionConfigName})")

    postRequest("/api/session/local-all", payload, null) {
      if (!it.getString("error").isNullOrEmpty()) {
        AbsLogger.error("[ApiHandler] sendSyncLocalSessions: Failed to sync local sessions. (${it.getString("error")})")
        cb(false, it.getString("error"))
      } else {
        val response = jacksonMapper.readValue<LocalSessionsSyncResponsePayload>(it.toString())
        response.results.forEach { localSessionSyncResult ->
          Log.d(tag, "Synced session result ${localSessionSyncResult.id}|${localSessionSyncResult.progressSynced}|${localSessionSyncResult.success}")

          playbackSessions.find { ps -> ps.id == localSessionSyncResult.id }?.let { session ->
            if (localSessionSyncResult.progressSynced == true) {
              val syncResult = SyncResult(true, true, "Progress synced on server")
              MediaEventManager.saveEvent(session, syncResult)

              AbsLogger.info("[ApiHandler] sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server, server progress was updated for item ${session.mediaItemId}")
            } else if (!localSessionSyncResult.success) {
              AbsLogger.error("[ApiHandler] sendSyncLocalSessions: Failed to sync session \"${session.displayTitle}\" with server. Error: ${localSessionSyncResult.error}")
            } else {
              AbsLogger.info("[ApiHandler] sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server. Server progress was up-to-date for item ${session.mediaItemId}")
            }
          }
        }
        cb(true, null)
      }
    }
  }

  fun syncLocalMediaProgressForUser(cb: () -> Unit) {
    AbsLogger.info("[ApiHandler] syncLocalMediaProgressForUser: Server connection ${DeviceManager.serverConnectionConfigName}")

    // Get all local media progress for this server
    val allLocalMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress().filter { it.serverConnectionConfigId == DeviceManager.serverConnectionConfigId }
    if (allLocalMediaProgress.isEmpty()) {
      AbsLogger.info("[ApiHandler] syncLocalMediaProgressForUser: No local media progress to sync")
      return cb()
    }

    AbsLogger.info("[ApiHandler] syncLocalMediaProgressForUser: Found ${allLocalMediaProgress.size} local media progress")

    getCurrentUser { user ->
      if (user == null) {
        AbsLogger.error("[ApiHandler] syncLocalMediaProgressForUser: Failed to load user from server (${DeviceManager.serverConnectionConfigName})")
      } else {
        var numLocalMediaProgressUptToDate = 0
        var numLocalMediaProgressUpdated = 0

        // Compare server user progress with local progress
        user.mediaProgress.forEach { mediaProgress ->
          // Get matching local media progress
          allLocalMediaProgress.find { it.isMatch(mediaProgress) }?.let { localMediaProgress ->
            if (mediaProgress.lastUpdate > localMediaProgress.lastUpdate) {
              AbsLogger.info("[ApiHandler] syncLocalMediaProgressForUser: Server progress for item \"${mediaProgress.mediaItemId}\" is more recent than local. Local progress last updated ${localMediaProgress.lastUpdate}, server progress last updated ${mediaProgress.lastUpdate}. Updating local current time ${localMediaProgress.currentTime} to ${mediaProgress.currentTime}")
              localMediaProgress.updateFromServerMediaProgress(mediaProgress)
              MediaEventManager.syncEvent(mediaProgress, "Sync on server connection")
              DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)
              numLocalMediaProgressUpdated++
            } else if (localMediaProgress.lastUpdate > mediaProgress.lastUpdate && localMediaProgress.ebookLocation != null && localMediaProgress.ebookLocation != mediaProgress.ebookLocation) {
              // Patch ebook progress to server
              AbsLogger.info("[ApiHandler] syncLocalMediaProgressForUser: Local progress for ebook item \"${mediaProgress.mediaItemId}\" is more recent than server progress. Local progress last updated ${localMediaProgress.lastUpdate}, server progress last updated ${mediaProgress.lastUpdate}. Sending server request to update ebook progress from ${mediaProgress.ebookProgress} to ${localMediaProgress.ebookProgress}")
              val endpoint = "/api/me/progress/${localMediaProgress.libraryItemId}"
              val updatePayload = JSObject()
              updatePayload.put("ebookLocation", localMediaProgress.ebookLocation)
              updatePayload.put("ebookProgress", localMediaProgress.ebookProgress)
              updatePayload.put("lastUpdate", localMediaProgress.lastUpdate)
              patchRequest(endpoint,updatePayload) {
                AbsLogger.info("[ApiHandler] syncLocalMediaProgressForUser: Successfully updated server ebook progress for item item \"${mediaProgress.mediaItemId}\"")
              }
            } else {
              numLocalMediaProgressUptToDate++
            }
          }
        }

        AbsLogger.info("[ApiHandler] syncLocalMediaProgressForUser: Finishing syncing local media progress with server. $numLocalMediaProgressUptToDate up-to-date, $numLocalMediaProgressUpdated updated")
      }
      cb()
    }
  }
}
