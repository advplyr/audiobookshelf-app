package com.audiobookshelf.app.plugins

import android.os.Environment
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.services.DownloadServiceHost
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.util.UUID

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
    bulkManifestCache.close()
    synchronized(bulkInventoryIds) {
      bulkInventoryClosed = true
      bulkInventoryIds.forEach(downloadItemManager::endBulkInventory)
      bulkInventoryIds.clear()
    }
    DownloadServiceHost.detachBridge()
    super.handleOnDestroy()
  }

  @PluginMethod
  fun setDownloadNotificationStrings(call: PluginCall) {
    DownloadServiceHost.setNotificationStrings(
            mainActivity,
            call.getString("preparing") ?: "Preparing downloads",
            call.getString("downloadingFile") ?: "Downloading {0}",
            call.getString("waitingForStorage") ?: "Waiting for available storage",
            call.getString("downloads") ?: "Downloads",
            call.getString("cancel") ?: "Cancel")
    call.resolve()
  }

  /** Replays restored queue items when the frontend subscribes to download events. */
  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  override fun addListener(call: PluginCall) {
    super.addListener(call)
    if (call.getString("eventName") == "onDownloadItem" && ::downloadItemManager.isInitialized) {
      downloadItemManager.queuedItems().forEach(clientEventEmitter::onDownloadItem)
    }
  }

  private val bulkManifestFolder by lazy {
    File(mainActivity.cacheDir, "bulk-download-manifests")
  }
  private val bulkManifestCache by lazy { BulkPodcastManifestCache(bulkManifestFolder) }

  @PluginMethod
  fun bulkPodcastManifest(call: PluginCall) {
    try {
      when (call.getString("action")) {
        "stage" -> {
          val data = call.getObject("manifest") ?: return call.reject("Missing podcast manifest")
          call.resolve(JSObject().put("id", bulkManifestCache.stage(data.toString())))
        }
        "consume" -> {
          val id = call.getString("id") ?: return call.reject("Missing manifest identifier")
          call.resolve(JSObject(bulkManifestCache.consume(id)))
        }
        "discard" -> {
          val ids = call.getArray("ids") ?: return call.reject("Missing manifest identifiers")
          bulkManifestCache.discard((0 until ids.length()).map { ids.getString(it) })
          call.resolve()
        }
        else -> call.reject("Unknown podcast manifest action")
      }
    } catch (e: Exception) {
      call.reject("Could not access podcast download manifest", e)
    }
  }

  private val bulkInventoryIds = mutableSetOf<String>()
  private var bulkInventoryClosed = false

  @PluginMethod
  fun getBulkOfflineItems(call: PluginCall) {
    var inventoryId: String? = null
    try {
      val values = call.getArray("libraryItemIds") ?: return call.reject("Missing library item IDs")
      val mediaType = call.getString("mediaType") ?: return call.reject("Missing media type")
      val connectionId = call.getString("connectionId") ?: return call.reject("Missing server connection")
      val userId = call.getString("userId") ?: return call.reject("Missing server account")
      val config = DeviceManager.serverConnectionConfig
      if (config?.id != connectionId || config.userId != userId) return call.reject("Download account changed")
      val oldUserId = call.getString("oldUserId")
      val ids = (0 until values.length()).mapTo(mutableSetOf()) { values.getString(it) }
      val id = UUID.randomUUID().toString()
      synchronized(bulkInventoryIds) {
        if (bulkInventoryClosed) return call.reject("Download bridge is closed")
        downloadItemManager.beginBulkInventory(id, connectionId to userId, ids)
        bulkInventoryIds.add(id)
        inventoryId = id
      }
      val items = DeviceManager.dbManager.getBulkOfflineItems(mainActivity, ids, mediaType, connectionId to userId, oldUserId)
      call.resolve(JSObject().put("inventoryId", id).put("items", com.getcapacitor.JSArray(jacksonMapper.writeValueAsString(items))))
    } catch (e: Exception) {
      inventoryId?.let(::releaseBulkInventory)
      call.reject("Could not read offline download inventory", e)
    }
  }

  private fun releaseBulkInventory(id: String) {
    synchronized(bulkInventoryIds) {
      if (bulkInventoryIds.remove(id)) downloadItemManager.endBulkInventory(id)
    }
  }

  @PluginMethod
  fun releaseBulkOfflineItems(call: PluginCall) {
    call.getString("inventoryId")?.let(::releaseBulkInventory)
    call.resolve()
  }

  @PluginMethod
  fun getCancellationGeneration(call: PluginCall) {
    call.resolve(JSObject().put("generation", DownloadServiceHost.cancellationGeneration))
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    val libraryItemId = call.data.getString("libraryItemId").toString()
    var episodeId = call.data.getString("episodeId").toString()
    if (episodeId == "null") episodeId = ""
    var localFolderId = call.data.getString("localFolderId", "").toString()
    AbsLogger.info(tag, "Requested download for item $libraryItemId${if (episodeId.isEmpty()) "" else " / episode $episodeId"}")

    val expectedConnectionId = call.getString("expectedConnectionId")
    val expectedUserId = call.getString("expectedUserId")
    val isBulk = expectedConnectionId != null
    fun connectionMatches(): Boolean {
      val config = DeviceManager.serverConnectionConfig
      return !isBulk || (config?.id == expectedConnectionId && config?.userId == expectedUserId)
    }
    if (!connectionMatches()) {
      resolveDownloadCall(call, "Download account changed")
      return
    }

    val downloadId = if (episodeId.isEmpty()) libraryItemId else "$libraryItemId-$episodeId"
    val expectedAccount = expectedConnectionId?.let { it to expectedUserId.orEmpty() }
    val generation = if (isBulk) call.getInt("cancellationGeneration", -1) else null
    val inventoryId = if (isBulk) call.getString("inventoryId") else null
    DownloadServiceHost.retryExisting(mainActivity, downloadId, expectedAccount, generation, inventoryId) { result ->
      if (!connectionMatches()) {
        resolveDownloadCall(call, "Download account changed")
        return@retryExisting
      }
      when (result) {
        DownloadServiceHost.ExistingDownloadResult.RETRIED -> call.resolve()
        DownloadServiceHost.ExistingDownloadResult.ACTIVE -> {
          AbsLogger.info(tag, "Download already active for $downloadId")
          val response = JSObject().put("error", "Download already started for this media entity")
          if (isBulk) response.put("alreadyQueued", true)
          call.resolve(response)
        }
        DownloadServiceHost.ExistingDownloadResult.CANCELLED ->
                resolveDownloadCall(call, DownloadServiceHost.CANCELLED)
        DownloadServiceHost.ExistingDownloadResult.ACCOUNT_CHANGED ->
                resolveDownloadCall(call, "Download account changed")
        DownloadServiceHost.ExistingDownloadResult.SERVICE_START_FAILED ->
                call.resolve(JSObject("{\"error\":\"Unable to start the Android download service\"}"))
        DownloadServiceHost.ExistingDownloadResult.NOT_FOUND -> {
          val prepare: (LibraryItem?) -> Unit = { libraryItem ->
            try {
              if (!connectionMatches()) {
                resolveDownloadCall(call, "Download account changed")
              } else if (libraryItem == null || libraryItem.id != libraryItemId) {
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
                } else if (isBulk && libraryItem.mediaType == "podcast" && episodeId.isBlank()) {
                  resolveDownloadCall(call, "Podcast download requires an episode")
                } else if (episodeId.isNotEmpty()) {
                  val podcast = libraryItem.media as Podcast
                  val episode = podcast.episodes?.find { it.id == episodeId }
                  if (episode == null) {
                    call.resolve(JSObject("{\"error\":\"Invalid podcast episode not found\"}"))
                  } else if (isBulk && (episode.audioFile?.ino.isNullOrBlank() || episode.audioTrack?.relPath.isNullOrBlank())) {
                    resolveDownloadCall(call, "Podcast episode has no downloadable audio")
                  } else {
                    startLibraryItemDownload(libraryItem, localFolder, episode, expectedAccount, generation, inventoryId, call.getString("existingPodcastPath")) { error -> resolveDownloadCall(call, error) }
                  }
                } else {
                  startLibraryItemDownload(libraryItem, localFolder, null, expectedAccount, generation, inventoryId, null) { error -> resolveDownloadCall(call, error) }
                }
              }
            } catch (e: Exception) {
              AbsLogger.error(tag, "Could not prepare download: ${e.message}")
              resolveDownloadCall(call, "Could not prepare download")
            }
          }
          val snapshot = if (isBulk && episodeId.isNotEmpty()) call.getObject("podcastSnapshot") else null
          if (snapshot == null && isBulk && episodeId.isNotEmpty()) {
            resolveDownloadCall(call, "Bulk podcast download requires episode metadata")
          } else if (snapshot == null) {
            apiHandler.getLibraryItemWithProgress(libraryItemId, episodeId, prepare)
          } else {
            val item = try {
              jacksonMapper.readValue(snapshot.toString(), LibraryItem::class.java)
            } catch (e: Exception) {
              AbsLogger.error(tag, "Invalid podcast snapshot: ${e.message}")
              null
            }
            prepare(item)
          }
        }
      }
    }
  }

  private fun resolveDownloadCall(call: PluginCall, error: String?) {
    if (error == null) call.resolve()
    else {
      val response = JSObject().put("error", error)
      if (error == DownloadServiceHost.ALREADY_QUEUED) response.put("alreadyQueued", true)
      if (error == DownloadServiceHost.CANCELLED || error == "Download account changed") response.put("submissionStopped", true)
      call.resolve(response)
    }
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
          expectedAccount: Pair<String, String>?,
          generation: Int?,
          inventoryId: String?,
          existingPodcastPath: String?,
          callback: (String?) -> Unit
  ) {
    val isInternal = localFolder.id.startsWith("internal-")
    val config = DeviceManager.serverConnectionConfig
    val isBulk = expectedAccount != null
    if (expectedAccount != null && (config?.id != expectedAccount.first || config?.userId != expectedAccount.second)) {
      callback("Download account changed")
      return
    }
    if (isBulk && isInternal && episode == null) {
      val existing = DeviceManager.dbManager.getLocalLibraryItem("local_${libraryItem.id}")
      if (existing != null && (existing.serverConnectionConfigId != config?.id || existing.serverUserId != config?.userId)) {
        callback("Offline copy belongs to another account")
        return
      }
    }
    val itemKey = UUID.nameUUIDFromBytes("${config?.id}:${config?.userId}:${libraryItem.id}".toByteArray()).toString()
    fun distinctFilename(filename: String, key: String): String =
            if (isBulk || !isInternal && episode != null) "${UUID.nameUUIDFromBytes("$itemKey:$key".toByteArray())}.${File(filename).extension}" else filename

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
      val itemSubfolder = if (isBulk) "Audiobookshelf-$itemKey" else "$bookAuthor/$bookTitle"
      val itemFolderPath = if (isInternal) finalInternalFolderPath else "${localFolder.absolutePath}/$itemSubfolder"
      val downloadItem = DownloadItem(libraryItem.id, libraryItem.id, null, libraryItem.userMediaProgress,config?.id ?: "", config?.address ?: "", config?.userId ?: "", libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, itemSubfolder, libraryItem.media, mutableListOf())

      val book = libraryItem.media as Book
      val audioFileInosByPath = book.audioFiles.orEmpty().asReversed().associate { it.metadata.path to it.ino }
      if (isBulk && tracks.any { it.relPath.isBlank() || audioFileInosByPath[it.metadata?.path].isNullOrBlank() }) {
        callback("Audiobook contains a track without downloadable audio")
        return
      }
      if (isBulk && book.ebookFile?.ino?.isBlank() == true) {
        callback("Book contains an ebook without a downloadable file")
        return
      }
      book.ebookFile?.let { ebookFile ->
        val fileSize = ebookFile.metadata?.size ?: 0
        val serverPath = "/api/items/${libraryItem.id}/file/${ebookFile.ino}/download"
        val relPath = ebookFile.metadata?.relPath.orEmpty()
        val filename = if (isBulk && relPath.isBlank()) "ebook.${ebookFile.ebookFormat}" else getFilenameFromRelPath(relPath)
        val destinationFilename = distinctFilename(filename, "ebook:${ebookFile.ino}")
        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,ebookFile,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      // Create download item part for each audio track
      tracks.forEach { audioTrack ->
        val fileSize = audioTrack.metadata?.size ?: 0

        // TODO: Currently file ino is only stored on AudioFile. This should be updated server side to be in FileMetadata or on the AudioTrack
        val audioFileIno = audioFileInosByPath[audioTrack.metadata?.path]

        val serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
        val destinationFilename = distinctFilename(getFilenameFromRelPath(audioTrack.relPath), "track:${audioTrack.index}:$audioFileIno")

        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,audioTrack,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (!isBulk && !libraryItem.media.coverPath.isNullOrEmpty()) {
          val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
          val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

          val serverPath = "/api/items/${libraryItem.id}/cover"
          val destinationFilename = distinctFilename("cover-${libraryItem.id}.jpg", "cover")
          val destinationFile = File("$tempFolderPath/$destinationFilename.part")
          val finalDestinationFile = File("$itemFolderPath/$destinationFilename")

          val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, coverFileSize,  destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }

        DownloadServiceHost.enqueue(mainActivity, downloadItem, generation, inventoryId, callback)
      } else callback("No downloadable files found")
    } else {
      // Podcast episode download
      val podcastTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val audioTrack = episode?.audioTrack
      val audioFileIno = episode?.audioFile?.ino
      val fileSize = audioTrack?.metadata?.size ?: 0

      AbsLogger.info(tag, "Queueing podcast episode download")
      val existingPath = when {
        isInternal -> null
        isBulk -> existingPodcastPath
        else -> DeviceManager.dbManager.getLocalLibraryItems("podcast").firstOrNull {
          it.libraryItemId == libraryItem.id && it.folderId == localFolder.id &&
                  it.serverConnectionConfigId == config?.id && it.serverUserId == config?.userId
        }?.absolutePath
      }
      val rootPrefix = localFolder.absolutePath.trimEnd('/') + "/"
      val existingSubfolder = existingPath?.takeIf { it.startsWith(rootPrefix) }?.removePrefix(rootPrefix)
              ?.takeIf { it.isNotBlank() && '/' !in it && it != "." && it != ".." }
      val itemSubfolder = if (isInternal && !isBulk) podcastTitle else existingSubfolder ?: "Audiobookshelf-$itemKey"
      val itemFolderPath = if (isInternal) finalInternalFolderPath else "${localFolder.absolutePath}/$itemSubfolder"
      val downloadItemId = "${libraryItem.id}-${episode?.id}"
      // Each episode part already carries its own metadata. Keep bulk queue entries small.
      val podcastMedia = if (isBulk) libraryItem.media.getLocalCopy() else libraryItem.media
      val downloadItem = DownloadItem(downloadItemId, libraryItem.id, episode?.id, libraryItem.userMediaProgress, config?.id ?: "", config?.address ?: "", config?.userId ?: "", libraryItem.mediaType, itemFolderPath, localFolder, podcastTitle, itemSubfolder, podcastMedia, mutableListOf())

      var serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
      var destinationFilename = distinctFilename(getFilenameFromRelPath(audioTrack?.relPath ?: ""), "episode:${episode?.id}")

      var destinationFile = File("$tempFolderPath/$destinationFilename.part")
      var finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      var downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,audioTrack,episode)
      downloadItem.downloadItemParts.add(downloadItemPart)

      // Bulk episode availability depends only on its audio file.
      if (!isBulk && !libraryItem.media.coverPath.isNullOrEmpty()) {
        val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
        val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

        serverPath = "/api/items/${libraryItem.id}/cover"
        destinationFilename = distinctFilename("cover.jpg", "cover")

        destinationFile = File("$tempFolderPath/$destinationFilename.part")
        finalDestinationFile = File("$itemFolderPath/$destinationFilename")

        downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,coverFileSize,destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

        DownloadServiceHost.enqueue(mainActivity, downloadItem, generation, inventoryId, callback)
    }
  }
}
