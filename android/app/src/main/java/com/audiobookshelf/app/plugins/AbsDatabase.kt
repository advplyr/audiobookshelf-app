package com.audiobookshelf.app.plugins

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@CapacitorPlugin(name = "AbsDatabase")
class AbsDatabase : Plugin() {
  val tag = "AbsDatabase"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler: ApiHandler

  data class LocalMediaProgressPayload(val value:List<LocalMediaProgress>)
  data class LocalLibraryItemsPayload(val value:List<LocalLibraryItem>)
  data class LocalFoldersPayload(val value:List<LocalFolder>)
  data class ServerConnConfigPayload(val id:String?, val index:Int, val name:String?, val userId:String, val username:String, val token:String, val address:String?, val customHeaders:Map<String,String>?)

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    DeviceManager.dbManager.cleanLocalMediaProgress()
    DeviceManager.dbManager.cleanLocalLibraryItems()
    DeviceManager.dbManager.cleanLogs()
  }

  @PluginMethod
  fun getDeviceData(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      val deviceData = DeviceManager.dbManager.getDeviceData()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(deviceData)))
    }
  }

  @PluginMethod
  fun getLocalFolders(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      val folders = DeviceManager.dbManager.getAllLocalFolders()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalFoldersPayload(folders))))
    }
  }

  @PluginMethod
  fun getLocalFolder(call:PluginCall) {
    val folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.dbManager.getLocalFolder(folderId)?.let {
        val folderObj = jacksonMapper.writeValueAsString(it)
        call.resolve(JSObject(folderObj))
      } ?: call.resolve()
    }
  }

  @PluginMethod
  fun getLocalLibraryItem(call:PluginCall) {
    val id = call.getString("id", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(id)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItemByLId(call:PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItemId)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItems(call:PluginCall) {
    val mediaType = call.getString("mediaType", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItems = DeviceManager.dbManager.getLocalLibraryItems(mediaType)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalLibraryItemsPayload(localLibraryItems))))
    }
  }

  @PluginMethod
  fun getLocalLibraryItemsInFolder(call:PluginCall) {
    val folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(folderId)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalLibraryItemsPayload(localLibraryItems))))
    }
  }

  @PluginMethod
  fun setCurrentServerConnectionConfig(call:PluginCall) {
    Log.d(tag, "setCurrentServerConnectionConfig ${call.data}")
    val serverConfigPayload = jacksonMapper.readValue<ServerConnConfigPayload>(call.data.toString())
    var serverConnectionConfig = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == serverConfigPayload.id }

    val userId =  serverConfigPayload.userId
    val username = serverConfigPayload.username
    val token = serverConfigPayload.token

    GlobalScope.launch(Dispatchers.IO) {
      if (serverConnectionConfig == null) { // New Server Connection
        val serverAddress = call.getString("address", "").toString()

        // Create new server connection config
        val sscId = DeviceManager.getBase64Id("$serverAddress@$username")
        val sscIndex = DeviceManager.deviceData.serverConnectionConfigs.size
        serverConnectionConfig = ServerConnectionConfig(sscId, sscIndex, "$serverAddress ($username)", serverAddress, userId, username, token, serverConfigPayload.customHeaders)

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
        if (DeviceManager.deviceData.lastServerConnectionConfigId != serverConfigPayload.id) {
          DeviceManager.deviceData.lastServerConnectionConfigId = serverConfigPayload.id
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
      val serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()
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
      val localMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalMediaProgressPayload(localMediaProgress))))
    }
  }

  @PluginMethod
  fun getLocalMediaProgressForServerItem(call:PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    var episodeId:String? = call.getString("episodeId", "").toString()
    if (episodeId == "") episodeId = null

    GlobalScope.launch(Dispatchers.IO) {
      val allLocalMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress()
      val localMediaProgress = allLocalMediaProgress.find { libraryItemId == it.libraryItemId && (episodeId == null || it.episodeId == episodeId) }

      if (localMediaProgress == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
      }
    }
  }

  @PluginMethod
  fun removeLocalMediaProgress(call:PluginCall) {
    val localMediaProgressId = call.getString("localMediaProgressId", "").toString()
    DeviceManager.dbManager.removeLocalMediaProgress(localMediaProgressId)
    call.resolve()
  }

  @PluginMethod
  fun syncLocalSessionsWithServer(call:PluginCall) {
    if (DeviceManager.serverConnectionConfig == null) {
      AbsLogger.error("[AbsDatabase] syncLocalSessionsWithServer: not connected to server")
      return call.resolve()
    }

    apiHandler.syncLocalMediaProgressForUser {
      val savedSessions = DeviceManager.dbManager.getPlaybackSessions().filter { it.serverConnectionConfigId == DeviceManager.serverConnectionConfigId }

      if (savedSessions.isNotEmpty()) {
        apiHandler.sendSyncLocalSessions(savedSessions) { success, errorMsg ->
          if (!success) {
            call.resolve(JSObject("{\"error\":\"$errorMsg\"}"))
          } else {
            AbsLogger.info("[AbsDatabase] syncLocalSessionsWithServer: Finished sending local playback sessions to server. Removing ${savedSessions.size} saved sessions.")
            // Remove all local sessions
            savedSessions.forEach {
              DeviceManager.dbManager.removePlaybackSession(it.id)
            }
            call.resolve()
          }
        }
      } else {
        AbsLogger.info("[AbsDatabase] syncLocalSessionsWithServer: No saved local playback sessions to send to server.")
        call.resolve()
      }
    }
  }

  // Updates received via web socket
  // This function doesn't need to sync with the server also because this data is coming from the server
  // If sending the localMediaProgressId then update existing media progress
  // If sending localLibraryItemId then save new local media progress
  @PluginMethod
  fun syncServerMediaProgressWithLocalMediaProgress(call:PluginCall) {
    val serverMediaProgress = call.getObject("mediaProgress").toString()
    val localLibraryItemId = call.getString("localLibraryItemId", "").toString()
    var localEpisodeId:String? = call.getString("localEpisodeId", "").toString()
    if (localEpisodeId.isNullOrEmpty()) localEpisodeId = null
    var localMediaProgressId = call.getString("localMediaProgressId") ?: ""

    val mediaProgress =  jacksonMapper.readValue<MediaProgress>(serverMediaProgress)

    if (localMediaProgressId == "") {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
      if (localLibraryItem != null) {
        localMediaProgressId = if (localEpisodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"

        val localMediaProgress = LocalMediaProgress(
          id = localMediaProgressId,
          localLibraryItemId = localLibraryItemId,
          localEpisodeId = localEpisodeId,
          duration = mediaProgress.duration,
          progress = mediaProgress.progress,
          currentTime = mediaProgress.currentTime,
          isFinished = mediaProgress.isFinished,
          ebookLocation = mediaProgress.ebookLocation,
          ebookProgress = mediaProgress.ebookProgress,
          lastUpdate = mediaProgress.lastUpdate,
          startedAt = mediaProgress.startedAt,
          finishedAt = mediaProgress.finishedAt,
          serverConnectionConfigId = localLibraryItem.serverConnectionConfigId,
          serverAddress = localLibraryItem.serverAddress,
          serverUserId = localLibraryItem.serverUserId,
          libraryItemId = localLibraryItem.libraryItemId,
          episodeId = mediaProgress.episodeId)

        Log.d(tag, "syncServerMediaProgressWithLocalMediaProgress: Saving new local media progress $localMediaProgress")
        DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
      } else {
        Log.e(tag, "syncServerMediaProgressWithLocalMediaProgress: Local library item not found")
      }
    } else {
      Log.d(tag, "syncServerMediaProgressWithLocalMediaProgress $localMediaProgressId")
      val localMediaProgress = DeviceManager.dbManager.getLocalMediaProgress(localMediaProgressId)

      if (localMediaProgress == null) {
        Log.w(tag, "syncServerMediaProgressWithLocalMediaProgress Local media progress not found $localMediaProgressId")
        call.resolve()
      } else {
        MediaEventManager.syncEvent(mediaProgress, "Received from webhook event")

        localMediaProgress.updateFromServerMediaProgress(mediaProgress)
        DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
      }
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

      val duration: Double
      var podcastEpisode: PodcastEpisode? = null
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
        ebookLocation = null,
        ebookProgress = null,
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
        val libraryItemId = localMediaProgress.libraryItemId ?: ""
        val episodeId = localMediaProgress.episodeId ?: ""
        val updatePayload = JSObject()
        updatePayload.put("isFinished", isFinished)
        apiHandler.updateMediaProgress(libraryItemId,episodeId,updatePayload) {
          Log.d(tag, "updateLocalMediaProgressFinished: Updated media progress isFinished on server")
          val jsobj = JSObject()
          jsobj.put("local", true)
          jsobj.put("server", true)
          jsobj.put("localMediaProgress", JSObject(lmpstring))
          call.resolve(jsobj)
        }
      }
    }
    if (localMediaProgress.serverConnectionConfigId == null || DeviceManager.serverConnectionConfigId != localMediaProgress.serverConnectionConfigId) {
      val jsobj = JSObject()
      jsobj.put("local", true)
      jsobj.put("server", false)
      jsobj.put("localMediaProgress", JSObject(lmpstring))
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun updateLocalEbookProgress(call:PluginCall) {
    val localLibraryItemId = call.getString("localLibraryItemId", "").toString()
    val ebookLocation = call.getString("ebookLocation", "").toString()
    val ebookProgress = call.getDouble("ebookProgress") ?: 0.0

    val localMediaProgressId = localLibraryItemId
    var localMediaProgress = DeviceManager.dbManager.getLocalMediaProgress(localMediaProgressId)

    if (localMediaProgress == null) {
      Log.d(tag, "updateLocalEbookProgress Local Media Progress not found $localMediaProgressId - Creating new")
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
        ?: return call.resolve(JSObject("{\"error\":\"Library Item not found\"}"))

      val book = localLibraryItem.media as Book

      localMediaProgress = LocalMediaProgress(
        id = localMediaProgressId,
        localLibraryItemId = localLibraryItemId,
        localEpisodeId = null,
        duration = book.duration ?: 0.0,
        progress = 0.0,
        currentTime = 0.0,
        isFinished = false,
        ebookLocation = ebookLocation,
        ebookProgress = ebookProgress,
        lastUpdate = System.currentTimeMillis(),
        startedAt = 0L,
        finishedAt = null,
        serverConnectionConfigId = localLibraryItem.serverConnectionConfigId,
        serverAddress = localLibraryItem.serverAddress,
        serverUserId = localLibraryItem.serverUserId,
        libraryItemId = localLibraryItem.libraryItemId,
        episodeId = null)
    } else {
      localMediaProgress.updateEbookProgress(ebookLocation, ebookProgress)
    }

    // Save local media progress locally
    DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)

    val lmpstring = jacksonMapper.writeValueAsString(localMediaProgress)
    Log.d(tag, "updateLocalEbookProgress: Local Media Progress String $lmpstring")

    val jsobj = JSObject()
    jsobj.put("localMediaProgress", JSObject(lmpstring))
    call.resolve(jsobj)
  }

  @PluginMethod
  fun updateLocalTrackOrder(call:PluginCall) {
    val localLibraryItemId = call.getString("localLibraryItemId", "") ?: ""
    val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
    if (localLibraryItem == null) {
      call.resolve()
      return
    }

    val audioTracks = localLibraryItem.media.getAudioTracks() as MutableList

    val tracks:JSArray = call.getArray("tracks") ?: JSArray()
    Log.d(tag, "updateLocalTrackOrder $tracks")

    var index = 1
    var hasUpdates = false
    for (i in 0 until tracks.length()) {
      val track = tracks.getJSONObject(i)
      val localFileId = track.getString("localFileId")

      val existingTrack = audioTracks.find{ it.localFileId == localFileId }
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

  @PluginMethod
  fun updateDeviceSettings(call:PluginCall) { // Returns device data
    Log.d(tag, "updateDeviceSettings ${call.data}")
    val newDeviceSettings = jacksonMapper.readValue<DeviceSettings>(call.data.toString())

    Handler(Looper.getMainLooper()).post {
      DeviceManager.deviceData.deviceSettings = newDeviceSettings
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)

      // Updates playback actions for media notification (handles media control seek locking setting)
      if (mainActivity.isPlayerNotificationServiceInitialized()) {
        mainActivity.foregroundService.setMediaSessionConnectorPlaybackActions()
      }

      call.resolve(JSObject(jacksonMapper.writeValueAsString(DeviceManager.deviceData)))
    }
  }

  @PluginMethod
  fun getMediaItemHistory(call:PluginCall) { // Returns device data
    Log.d(tag, "getMediaItemHistory ${call.data}")
    val mediaId = call.getString("mediaId") ?: ""

    GlobalScope.launch(Dispatchers.IO) {
      val mediaItemHistory = DeviceManager.dbManager.getMediaItemHistory(mediaId)
      if (mediaItemHistory == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(mediaItemHistory)))
      }
    }
  }
}
