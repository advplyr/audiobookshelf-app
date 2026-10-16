package com.audiobookshelf.app.plugins

import android.app.AlertDialog
import android.os.Environment
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.managers.DownloadNetworkMonitor
import com.audiobookshelf.app.managers.DownloadNetworkPolicy
import com.audiobookshelf.app.services.DownloadServiceHost
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File

@CapacitorPlugin(name = "AbsDownloader")
class AbsDownloader : Plugin() {
  private val tag = "AbsDownloader"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler: ApiHandler
  lateinit var downloadItemManager: DownloadItemManager

  private val clientEventEmitter = (object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem:DownloadItem) {
      notifyListeners("onDownloadItem", JSObject(jacksonMapper.writeValueAsString(downloadItem)))
    }
    override fun onDownloadItemPartUpdate(downloadItemPart:DownloadItemPart) {
      notifyListeners("onDownloadItemPartUpdate", JSObject(jacksonMapper.writeValueAsString(downloadItemPart)))
    }
    override fun onDownloadItemComplete(jsobj:JSObject) {
      notifyListeners("onItemDownloadComplete", jsobj)
    }
    override fun onQueueChanged(hasWork: Boolean) {
      notifyListeners("onQueueChanged", JSObject().put("hasWork", hasWork))
    }
  })

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)
    downloadItemManager = DownloadServiceHost.ensure(mainActivity)
    DownloadServiceHost.attachBridge(mainActivity, clientEventEmitter)
  }

  override fun handleOnDestroy() {
    DownloadServiceHost.detachBridge()
    super.handleOnDestroy()
  }

  /** Picks downloads back up when the app returns, e.g. after Android stopped the service. */
  override fun handleOnResume() {
    super.handleOnResume()
    DownloadServiceHost.resumeIfNeeded(mainActivity)
  }

  @PluginMethod
  fun setDownloadNotificationStrings(call: PluginCall) {
    DownloadServiceHost.setNotificationStrings(
            mainActivity,
            call.getString("preparing") ?: "Preparing downloads",
            call.getString("downloadingFile") ?: "Downloading {0}",
            call.getString("waitingForStorage") ?: "Waiting for available storage",
            call.getString("waitingForNetwork") ?: "Waiting for network connection",
            call.getString("waitingForWifi") ?: "Waiting for Wi-Fi",
            call.getString("retrying") ?: "Connection lost; retrying {0} shortly",
            call.getString("cellularApprovalTitle") ?: "Downloads paused on mobile data",
            call.getString("cellularApprovalText") ?: "{0} download(s) are waiting for Wi-Fi",
            call.getString("useMobileData") ?: "Use mobile data",
            call.getString("downloads") ?: "Downloads",
            call.getString("cancel") ?: "Cancel")
    call.resolve()
  }

  @PluginMethod
  fun cancelDownloadItem(call: PluginCall) {
    val downloadItemId = call.getString("downloadItemId") ?: return call.reject("downloadItemId is required")
    DownloadServiceHost.cancelItem(mainActivity, downloadItemId) { cancelled ->
      call.resolve(JSObject().put("cancelled", cancelled))
    }
  }

  @PluginMethod
  fun cancelAllDownloads(call: PluginCall) {
    DownloadServiceHost.cancelAll(mainActivity)
    call.resolve()
  }

  /** Starts an item's backed-off parts immediately instead of waiting for the retry delay. */
  @PluginMethod
  fun retryDownloadNow(call: PluginCall) {
    val downloadItemId = call.getString("downloadItemId") ?: return call.reject("downloadItemId is required")
    DownloadServiceHost.retryNow(mainActivity, downloadItemId) { retried ->
      call.resolve(JSObject().put("retried", retried))
    }
  }

  /** Lets one queued download use mobile data. */
  @PluginMethod
  fun allowCellularDownload(call: PluginCall) {
    val downloadItemId = call.getString("downloadItemId") ?: return call.reject("downloadItemId is required")
    DownloadServiceHost.allowCellular(mainActivity, downloadItemId)
    call.resolve()
  }

  /**
   * Decides which networks a new download may use. On a metered network with the "ask first"
   * policy this shows a three-way dialog (download now / wait for Wi-Fi / cancel), which the
   * Capacitor Dialog plugin cannot. Resolves with `networkMode`, or `cancelled: true`.
   */
  @PluginMethod
  fun resolveDownloadNetworkMode(call: PluginCall) {
    val setting = DeviceManager.deviceData.deviceSettings?.downloadUsingCellular ?: DownloadUsingCellularSetting.ALWAYS
    val metered = DownloadNetworkMonitor.isMeteredNow(mainActivity)
    fun resolveMode(mode: String) = call.resolve(JSObject().put("networkMode", mode).put("metered", metered))
    val initialMode = DownloadNetworkPolicy.initialMode(setting, metered)
    when {
      initialMode != null -> resolveMode(initialMode)
      else ->
              mainActivity.runOnUiThread {
                var resolved = false
                fun finish(mode: String?) {
                  if (resolved) return
                  resolved = true
                  if (mode == null) call.resolve(JSObject().put("cancelled", true)) else resolveMode(mode)
                }
                AlertDialog.Builder(mainActivity)
                        .setTitle(call.getString("title") ?: "Download on mobile data?")
                        .setMessage(call.getString("message") ?: "You are on a metered connection.")
                        .setPositiveButton(call.getString("downloadNow") ?: "Download now") { _, _ ->
                          finish(DownloadItem.NETWORK_MODE_CELLULAR)
                        }
                        .setNeutralButton(call.getString("waitForWifi") ?: "Wait for Wi-Fi") { _, _ ->
                          finish(DownloadItem.NETWORK_MODE_WIFI_ONLY)
                        }
                        .setNegativeButton(call.getString("cancel") ?: "Cancel") { _, _ -> finish(null) }
                        .setOnCancelListener { finish(null) }
                        .show()
              }
    }
  }

  /** Replays restored queue items when the frontend subscribes to download events. */
  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  override fun addListener(call: PluginCall) {
    super.addListener(call)
    if (call.getString("eventName") == "onDownloadItem" && ::downloadItemManager.isInitialized) {
      downloadItemManager.downloadItemQueue.forEach { item ->
        notifyListeners("onDownloadItem", JSObject(jacksonMapper.writeValueAsString(item)))
      }
    }
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    val libraryItemId = call.data.getString("libraryItemId").toString()
    var episodeId = call.data.getString("episodeId").toString()
    if (episodeId == "null") episodeId = ""
    var localFolderId = call.data.getString("localFolderId", "").toString()
    val networkMode = call.getString("networkMode")
    AbsLogger.info(tag, "Requested download for item $libraryItemId${if (episodeId.isEmpty()) "" else " / episode $episodeId"}")

    val downloadId = if (episodeId.isEmpty()) libraryItemId else "$libraryItemId-$episodeId"
    DownloadServiceHost.retryExisting(mainActivity, downloadId, networkMode) { result ->
      when (result) {
        DownloadServiceHost.ExistingDownloadResult.RETRIED -> call.resolve()
        DownloadServiceHost.ExistingDownloadResult.ACTIVE -> {
          AbsLogger.info(tag, "Download already active for $downloadId")
          call.resolve(JSObject("{\"error\":\"Download already started for this media entity\"}"))
        }
        DownloadServiceHost.ExistingDownloadResult.SERVICE_START_FAILED ->
                call.resolve(JSObject("{\"error\":\"Unable to start the Android download service\"}"))
        DownloadServiceHost.ExistingDownloadResult.NOT_FOUND -> {
          apiHandler.getLibraryItemWithProgress(libraryItemId, episodeId) { libraryItem ->
            if (libraryItem == null) {
              call.resolve(JSObject("{\"error\":\"Server request failed\"}"))
            } else {
              AbsLogger.info(tag, "Preparing download for ${libraryItem.id}")

              if (localFolderId == "") localFolderId = "internal-${libraryItem.mediaType}"
              var localFolder = DeviceManager.dbManager.getLocalFolder(localFolderId)
              if (localFolder == null && localFolderId.startsWith("internal-")) {
                AbsLogger.info(tag, "Creating internal download folder $localFolderId")
                localFolder = LocalFolder(localFolderId, "Internal App Storage", "", "", "", "internal", libraryItem.mediaType)
                DeviceManager.dbManager.saveLocalFolder(localFolder)
              }

              if (localFolder == null) {
                call.resolve(JSObject("{\"error\":\"Local Folder Not Found\"}"))
              } else if (episodeId.isNotEmpty() && libraryItem.mediaType != "podcast") {
                call.resolve(JSObject("{\"error\":\"Invalid library item not a podcast\"}"))
              } else if (episodeId.isNotEmpty()) {
                val podcast = libraryItem.media as Podcast
                val episode = podcast.episodes?.find { it.id == episodeId }
                if (episode == null) {
                  call.resolve(JSObject("{\"error\":\"Invalid podcast episode not found\"}"))
                } else {
                  startLibraryItemDownload(libraryItem, localFolder, episode, networkMode) { error -> resolveDownloadCall(call, error) }
                }
              } else {
                startLibraryItemDownload(libraryItem, localFolder, null, networkMode) { error -> resolveDownloadCall(call, error) }
              }
            }
          }
        }
      }
    }
  }

  private fun resolveDownloadCall(call: PluginCall, error: String?) {
    if (error == null) call.resolve()
    else call.resolve(JSObject().put("error", error))
  }

  // Item filenames could be the same if they are in sub-folders, this will make them unique
  private fun getFilenameFromRelPath(relPath: String): String {
    var cleanedRelPath = relPath.replace("\\", "_").replace("/", "_")
    cleanedRelPath = cleanStringForFileSystem(cleanedRelPath)
    return if (cleanedRelPath.startsWith("_")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  // Replace characters that cant be used in the file system
  // Reserved characters: ?:\"*|/\\<>
  private fun cleanStringForFileSystem(str:String):String {
    val reservedCharacters = listOf("?", "\"", "*", "|", "/", "\\", "<", ">")
    var newTitle = str
    newTitle = newTitle.replace(":", " -") // Special case replace : with -

    reservedCharacters.forEach {
      newTitle = newTitle.replace(it, "")
    }
    return newTitle
  }

  private fun startLibraryItemDownload(
          libraryItem: LibraryItem,
          localFolder: LocalFolder,
          episode: PodcastEpisode?,
          networkMode: String?,
          callback: (String?) -> Unit
  ) {
    val isInternal = localFolder.id.startsWith("internal-")

    val finalInternalFolderPath = "${mainActivity.filesDir}/downloads/${libraryItem.id}"
    val tempFolderPath =
            if (isInternal) {
              "${mainActivity.filesDir}/download-staging/${libraryItem.id}"
            } else {
              "${mainActivity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: mainActivity.filesDir}/download-staging/${libraryItem.id}"
            }


    if (libraryItem.mediaType == "book") {
      val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)
      val bookAuthor = cleanStringForFileSystem(libraryItem.media.metadata.getAuthorDisplayName())

      val tracks = libraryItem.media.getAudioTracks()
      AbsLogger.info(tag, "Queueing library item download with ${tracks.size} files")
      val itemSubfolder = "$bookAuthor/$bookTitle"
      val itemFolderPath = if (isInternal) finalInternalFolderPath else "${localFolder.absolutePath}/$itemSubfolder"
      val downloadItem = DownloadItem(libraryItem.id, libraryItem.id, null, libraryItem.userMediaProgress,DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, itemSubfolder, libraryItem.media, mutableListOf())

      val book = libraryItem.media as Book
      book.ebookFile?.let { ebookFile ->
        val fileSize = ebookFile.metadata?.size ?: 0
        val serverPath = "/api/items/${libraryItem.id}/file/${ebookFile.ino}/download"
        val destinationFilename = getFilenameFromRelPath(ebookFile.metadata?.relPath ?: "")
        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,ebookFile,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      // Create download item part for each audio track
      val audioFiles = (libraryItem.media as Book).audioFiles ?: mutableListOf()
      tracks.forEach { audioTrack ->
        val fileSize = audioTrack.metadata?.size ?: 0

        // TODO: Currently file ino is only stored on AudioFile. This should be updated server side to be in FileMetadata or on the AudioTrack
        val audioFileIno = audioFiles.find { it.metadata.path == audioTrack.metadata?.path }?.ino

        val serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
        val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)

        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,audioTrack,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
          val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
          val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

          val serverPath = "/api/items/${libraryItem.id}/cover"
          val destinationFilename = "cover-${libraryItem.id}.jpg"
          val destinationFile = File("$tempFolderPath/$destinationFilename.part")
          val finalDestinationFile = File("$itemFolderPath/$destinationFilename")

          val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, coverFileSize,  destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }

        downloadItem.networkMode = networkMode
        DownloadServiceHost.enqueue(mainActivity, downloadItem, callback)
      } else callback("No downloadable files found")
    } else {
      // Podcast episode download
      val podcastTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val audioTrack = episode?.audioTrack
      val audioFileIno = episode?.audioFile?.ino
      val fileSize = audioTrack?.metadata?.size ?: 0

      AbsLogger.info(tag, "Queueing podcast episode download")
      val itemFolderPath = if (isInternal) finalInternalFolderPath else "${localFolder.absolutePath}/$podcastTitle"
      val downloadItemId = "${libraryItem.id}-${episode?.id}"
      val downloadItem = DownloadItem(downloadItemId, libraryItem.id, episode?.id, libraryItem.userMediaProgress, DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, podcastTitle, podcastTitle, libraryItem.media, mutableListOf())

      var serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
      var destinationFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")

      var destinationFile = File("$tempFolderPath/$destinationFilename.part")
      var finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      var downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,fileSize, destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,audioTrack,episode)
      downloadItem.downloadItemParts.add(downloadItemPart)

      if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
        val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
        val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

        serverPath = "/api/items/${libraryItem.id}/cover"
        destinationFilename = "cover.jpg"

        destinationFile = File("$tempFolderPath/$destinationFilename.part")
        finalDestinationFile = File("$itemFolderPath/$destinationFilename")

        downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,coverFileSize,destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

        downloadItem.networkMode = networkMode
        DownloadServiceHost.enqueue(mainActivity, downloadItem, callback)
    }
  }
}
