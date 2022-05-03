package com.audiobookshelf.app.data

import android.util.Log
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject

@CapacitorPlugin(name = "AbsDatabase")
class AbsDatabase : Plugin() {
  val tag = "AbsDatabase"
  var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler: ApiHandler

  data class LocalMediaProgressPayload(val value:List<LocalMediaProgress>)
  data class LocalLibraryItemsPayload(val value:List<LocalLibraryItem>)
  data class LocalFoldersPayload(val value:List<LocalFolder>)

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    DeviceManager.dbManager.cleanLocalMediaProgress()
    DeviceManager.dbManager.cleanLocalLibraryItems()
  }

  @PluginMethod
  fun getDeviceData(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var deviceData = DeviceManager.dbManager.getDeviceData()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(deviceData)))
    }
  }

  @PluginMethod
  fun getLocalFolders(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var folders = DeviceManager.dbManager.getAllLocalFolders()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalFoldersPayload(folders))))
    }
  }

  @PluginMethod
  fun getLocalFolder(call:PluginCall) {
    var folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.dbManager.getLocalFolder(folderId)?.let {
        var folderObj = jacksonMapper.writeValueAsString(it)
        call.resolve(JSObject(folderObj))
      } ?: call.resolve()
    }
  }

  @PluginMethod
  fun getLocalLibraryItem(call:PluginCall) {
    var id = call.getString("id", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      var localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(id)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItemByLLId(call:PluginCall) {
    var libraryItemId = call.getString("libraryItemId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      var localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLLId(libraryItemId)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItems(call:PluginCall) {
    var mediaType = call.getString("mediaType", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      var localLibraryItems = DeviceManager.dbManager.getLocalLibraryItems(mediaType)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalLibraryItemsPayload(localLibraryItems))))
    }
  }

  @PluginMethod
  fun getLocalLibraryItemsInFolder(call:PluginCall) {
    var folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      var localLibraryItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(folderId)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalLibraryItemsPayload(localLibraryItems))))
    }
  }

  @PluginMethod
  fun setCurrentServerConnectionConfig(call:PluginCall) {
    var serverConnectionConfigId = call.getString("id", "").toString()
    var serverConnectionConfig = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == serverConnectionConfigId }

    var userId = call.getString("userId", "").toString()
    var username = call.getString("username", "").toString()
    var token = call.getString("token", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      if (serverConnectionConfig == null) { // New Server Connection
        var serverAddress = call.getString("address", "").toString()

        // Create new server connection config
        var sscId = DeviceManager.getBase64Id("$serverAddress@$username")
        var sscIndex = DeviceManager.deviceData.serverConnectionConfigs.size
        serverConnectionConfig = ServerConnectionConfig(sscId, sscIndex, "$serverAddress ($username)", serverAddress, userId, username, token)

        // Add and save
        DeviceManager.deviceData.serverConnectionConfigs.add(serverConnectionConfig!!)
        DeviceManager.deviceData.lastServerConnectionConfigId = serverConnectionConfig?.id
        DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      } else {
        var shouldSave = false
        if (serverConnectionConfig?.username != username || serverConnectionConfig?.token != token) {
          serverConnectionConfig?.userId = userId
          serverConnectionConfig?.username = username
          serverConnectionConfig?.name = "${serverConnectionConfig?.address} (${serverConnectionConfig?.username})"
          serverConnectionConfig?.token = token
          shouldSave = true
        }

        // Set last connection config
        if (DeviceManager.deviceData.lastServerConnectionConfigId != serverConnectionConfigId) {
          DeviceManager.deviceData.lastServerConnectionConfigId = serverConnectionConfigId
          shouldSave = true
        }

        if (shouldSave) DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      }

      DeviceManager.serverConnectionConfig = serverConnectionConfig
      call.resolve(JSObject(jacksonMapper.writeValueAsString(DeviceManager.serverConnectionConfig)))
    }
  }

  @PluginMethod
  fun removeServerConnectionConfig(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()
      DeviceManager.deviceData.serverConnectionConfigs = DeviceManager.deviceData.serverConnectionConfigs.filter { it.id != serverConnectionConfigId } as MutableList<ServerConnectionConfig>
      if (DeviceManager.deviceData.lastServerConnectionConfigId == serverConnectionConfigId) {
        DeviceManager.deviceData.lastServerConnectionConfigId = null
      }
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      if (DeviceManager.serverConnectionConfig?.id == serverConnectionConfigId) {
        DeviceManager.serverConnectionConfig = null
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun logout(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.serverConnectionConfig = null
      DeviceManager.deviceData.lastServerConnectionConfigId = null
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      call.resolve()
    }
  }

  @PluginMethod
  fun getAllLocalMediaProgress(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var localMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalMediaProgressPayload(localMediaProgress))))
    }
  }

  @PluginMethod
  fun removeLocalMediaProgress(call:PluginCall) {
    var localMediaProgressId = call.getString("localMediaProgressId", "").toString()
    DeviceManager.dbManager.removeLocalMediaProgress(localMediaProgressId)
    call.resolve()
  }

  @PluginMethod
  fun syncLocalMediaProgressWithServer(call:PluginCall) {
    if (DeviceManager.serverConnectionConfig == null) {
      Log.e(tag, "syncLocalMediaProgressWithServer not connected to server")
      return call.resolve()
    }
    apiHandler.syncMediaProgress {
      call.resolve(JSObject(jacksonMapper.writeValueAsString(it)))
    }
  }

  @PluginMethod
  fun updateLocalMediaProgressFinished(call:PluginCall) {
    val localLibraryItemId = call.getString("localLibraryItemId", "").toString()
    var localEpisodeId:String? = call.getString("localEpisodeId", "").toString()
    if (localEpisodeId.isNullOrEmpty()) localEpisodeId = null

    val localMediaProgressId = if (localEpisodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"
    val isFinished = call.getBoolean("isFinished", false) == true

    Log.d(tag, "updateLocalMediaProgressFinished $localMediaProgressId | Is Finished:$isFinished")
    var localMediaProgress = DeviceManager.dbManager.getLocalMediaProgress(localMediaProgressId)

    if (localMediaProgress == null) { // Create new local media progress if does not exist
     Log.d(tag, "updateLocalMediaProgressFinished Local Media Progress not found $localMediaProgressId - Creating new")
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)

      if (localLibraryItem == null) {
        return call.resolve(JSObject("{\"error\":\"Library Item not found\"}"))
      }
      if (localLibraryItem.mediaType != "podcast" && !localEpisodeId.isNullOrEmpty()) {
        return call.resolve(JSObject("{\"error\":\"Invalid library item not a podcast\"}"))
      }

      var duration = 0.0
      var podcastEpisode:PodcastEpisode? = null
      if (!localEpisodeId.isNullOrEmpty()) {
          val podcast = localLibraryItem.media as Podcast
        podcastEpisode = podcast.episodes?.find { episode ->
            episode.id == localEpisodeId
          }
        if (podcastEpisode == null) {
          return call.resolve(JSObject("{\"error\":\"Podcast episode not found\"}"))
        }
        duration = podcastEpisode.duration ?: 0.0
      } else {
        val book = localLibraryItem.media as Book
        duration = book.duration ?: 0.0
      }

      val currentTime = System.currentTimeMillis()
      localMediaProgress = LocalMediaProgress(
        id = localMediaProgressId,
        localLibraryItemId = localLibraryItemId,
        localEpisodeId = localEpisodeId,
        duration = duration,
        progress = if (isFinished) 1.0 else 0.0,
        currentTime = 0.0,
        isFinished = isFinished,
        lastUpdate = currentTime,
        startedAt = if (isFinished) currentTime else 0L,
        finishedAt = if (isFinished) currentTime else null,
        serverConnectionConfigId = localLibraryItem.serverConnectionConfigId,
        serverAddress = localLibraryItem.serverAddress,
        serverUserId = localLibraryItem.serverUserId,
        libraryItemId = localLibraryItem.libraryItemId,
        episodeId = podcastEpisode?.serverEpisodeId)
    } else {
      localMediaProgress.updateIsFinished(isFinished)
    }

    // Save local media progress locally
    DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)

    val lmpstring = jacksonMapper.writeValueAsString(localMediaProgress)
    Log.d(tag, "updateLocalMediaProgressFinished: Local Media Progress String $lmpstring")

    // Send update to server media progress is linked to a server and user is logged into that server
    localMediaProgress.serverConnectionConfigId?.let { configId ->
      if (DeviceManager.serverConnectionConfigId == configId) {
        var libraryItemId = localMediaProgress.libraryItemId ?: ""
        var episodeId = localMediaProgress.episodeId ?: ""
        var updatePayload = JSObject()
        updatePayload.put("isFinished", isFinished)
        apiHandler.updateMediaProgress(libraryItemId,episodeId,updatePayload) {
          Log.d(tag, "updateLocalMediaProgressFinished: Updated media progress isFinished on server")
          var jsobj = JSObject()
          jsobj.put("local", true)
          jsobj.put("server", true)
          jsobj.put("localMediaProgress", JSObject(lmpstring))
          call.resolve(jsobj)
        }
      }
    }
    if (localMediaProgress.serverConnectionConfigId == null || DeviceManager.serverConnectionConfigId != localMediaProgress.serverConnectionConfigId) {
      var jsobj = JSObject()
      jsobj.put("local", true)
      jsobj.put("server", false)
      jsobj.put("localMediaProgress", JSObject(lmpstring))
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun updateLocalTrackOrder(call:PluginCall) {
    var localLibraryItemId = call.getString("localLibraryItemId", "") ?: ""
    var localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
    if (localLibraryItem == null) {
      call.resolve()
      return
    }

    var audioTracks = localLibraryItem.media.getAudioTracks() as MutableList

    var tracks:JSArray = call.getArray("tracks") ?: JSArray()
    Log.d(tag, "updateLocalTrackOrder $tracks")

    var index = 1
    var hasUpdates = false
    for (i in 0 until tracks.length()) {
      var track = tracks.getJSONObject(i)
      var localFileId = track.getString("localFileId")

      var existingTrack = audioTracks.find{ it.localFileId == localFileId }
      if (existingTrack != null) {
        Log.d(tag, "Found existing track ${existingTrack.localFileId} that has index ${existingTrack.index} should be index $index")
        if (existingTrack.index != index) hasUpdates = true
        existingTrack.index = index++
      } else {
        Log.e(tag, "Audio track with local file id not found")
      }
    }

    if (hasUpdates) {
      Log.d(tag, "Save library item track orders")
      localLibraryItem.media.setAudioTracks(audioTracks)
      DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
    } else {
      Log.d(tag, "No tracks need to be updated")
      call.resolve()
    }
  }
}
