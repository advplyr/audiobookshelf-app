package com.audiobookshelf.app.plugins

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.util.Log
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.server.ApiHandler
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
  private var jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var downloadManager: DownloadManager
  lateinit var apiHandler: ApiHandler
  lateinit var folderScanner: FolderScanner
  lateinit var downloadItemManager: DownloadItemManager

  private val clientEventEmitter =
          (object : DownloadItemManager.DownloadEventEmitter {
            override fun onDownloadItem(downloadItem: DownloadItem) {
              notifyListeners(
                      "onDownloadItem",
                      JSObject(jacksonMapper.writeValueAsString(downloadItem))
              )
            }
            override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {
              notifyListeners(
                      "onDownloadItemPartUpdate",
                      JSObject(jacksonMapper.writeValueAsString(downloadItemPart))
              )
            }
            override fun onDownloadItemComplete(jsobj: JSObject) {
              notifyListeners("onItemDownloadComplete", jsobj)
            }
          })

  override fun load() {
    mainActivity = (activity as MainActivity)
    downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    folderScanner = FolderScanner(mainActivity)
    apiHandler = ApiHandler(mainActivity)
    downloadItemManager =
            DownloadItemManager(downloadManager, folderScanner, mainActivity, clientEventEmitter)
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    val libraryItemId = call.data.getString("libraryItemId").toString()
    var episodeId = call.data.getString("episodeId").toString()
    if (episodeId == "null") episodeId = ""
    var localFolderId = call.data.getString("localFolderId", "").toString()
    Log.d(
            tag,
            "Download library item $libraryItemId to folder $localFolderId / episode: $episodeId"
    )

    val downloadId = if (episodeId.isEmpty()) libraryItemId else "$libraryItemId-$episodeId"
    if (downloadItemManager.downloadItemQueue.find { it.id == downloadId } != null) {
      Log.d(tag, "Download already started for this media entity $downloadId")
      return call.resolve(
              JSObject("{\"error\":\"Download already started for this media entity\"}")
      )
    }

    apiHandler.getLibraryItemWithProgress(libraryItemId, episodeId) { libraryItem ->
      if (libraryItem == null) {
        call.resolve(JSObject("{\"error\":\"Server request failed\"}"))
      } else {
        Log.d(tag, "Got library item from server ${libraryItem.id}")

        if (localFolderId == "") {
          localFolderId = "internal-${libraryItem.mediaType}"
        }
        var localFolder = DeviceManager.dbManager.getLocalFolder(localFolderId)

        if (localFolder == null && localFolderId.startsWith("internal-")) {
          Log.d(tag, "Creating new App Storage internal LocalFolder $localFolderId")
          localFolder =
                  LocalFolder(
                          localFolderId,
                          "Internal App Storage",
                          "",
                          "",
                          "",
                          "",
                          "internal",
                          libraryItem.mediaType
                  )
          DeviceManager.dbManager.saveLocalFolder(localFolder)
        }

        if (localFolder != null) {
          if (episodeId.isNotEmpty() && libraryItem.mediaType != "podcast") {
            Log.e(tag, "Library item is not a podcast but episode was requested")
            call.resolve(JSObject("{\"error\":\"Invalid library item not a podcast\"}"))
          } else if (episodeId.isNotEmpty()) {
            val podcast = libraryItem.media as Podcast
            val episode =
                    podcast.episodes?.find { podcastEpisode -> podcastEpisode.id == episodeId }
            if (episode == null) {
              call.resolve(JSObject("{\"error\":\"Invalid podcast episode not found\"}"))
            } else {
              startLibraryItemDownload(libraryItem, localFolder, episode)
              call.resolve()
            }
          } else {
            startLibraryItemDownload(libraryItem, localFolder, null)
            call.resolve()
          }
        } else {
          call.resolve(JSObject("{\"error\":\"Local Folder Not Found\"}"))
        }
      }
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
  private fun cleanStringForFileSystem(str: String): String {
    val reservedCharacters = listOf("?", "\"", "*", "|", "/", "\\", "<", ">")
    var newTitle = str
    newTitle = newTitle.replace(":", " -") // Special case replace : with -

    reservedCharacters.forEach { newTitle = newTitle.replace(it, "") }
    return newTitle
  }

  private fun startLibraryItemDownload(
          libraryItem: LibraryItem,
          localFolder: LocalFolder,
          episode: PodcastEpisode?
  ) {
    val isInternal = localFolder.id.startsWith("internal-")

    val tempFolderPath =
            if (isInternal) "${mainActivity.filesDir}/downloads/${libraryItem.id}"
            else "${mainActivity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)}"

    Log.d(tag, "downloadCacheDirectory=$tempFolderPath")

    val itemFolderPath =
            if (isInternal) "$tempFolderPath"
            else
                    "${localFolder.absolutePath}/${cleanStringForFileSystem(libraryItem.media.metadata.title)}"

    val downloadItemId = if (episode != null) "${libraryItem.id}-${episode.id}" else libraryItem.id
    val titleCleaned = cleanStringForFileSystem(libraryItem.media.metadata.title)
    val author =
            if (episode == null)
                    cleanStringForFileSystem(libraryItem.media.metadata.getAuthorDisplayName())
            else titleCleaned
    val itemSubfolder = if (episode == null) "$author/$titleCleaned" else "$titleCleaned"
    val downloadItem =
            DownloadItem(
                    downloadItemId,
                    libraryItem.id,
                    episode?.id,
                    libraryItem.userMediaProgress,
                    DeviceManager.serverConnectionConfig?.id ?: "",
                    DeviceManager.serverAddress,
                    DeviceManager.serverUserId,
                    libraryItem.mediaType,
                    itemFolderPath,
                    localFolder,
                    titleCleaned,
                    itemSubfolder,
                    libraryItem.media,
                    mutableListOf()
            )

    if (libraryItem.mediaType == "book") {
      val book = libraryItem.media as Book
      book.ebookFile?.let { ebookFile ->
        val serverPath = "/api/items/${libraryItem.id}/file/${ebookFile.ino}/download"
        val destinationFilename = getFilenameFromRelPath(ebookFile.metadata?.relPath ?: "")
        val destinationFile = File("$tempFolderPath/$destinationFilename")
        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        checkAndDeleteFile(destinationFile, finalDestinationFile)
        val downloadItemPart =
                DownloadItemPart.make(
                        downloadItem.id,
                        destinationFilename,
                        ebookFile.metadata?.size ?: 0,
                        destinationFile,
                        finalDestinationFile,
                        itemSubfolder,
                        serverPath,
                        localFolder,
                        ebookFile,
                        null,
                        null
                )
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      val audioFiles = book.audioFiles ?: mutableListOf()
      libraryItem.media.getAudioTracks().forEach { audioTrack ->
        val audioFileIno = audioFiles.find { it.metadata.path == audioTrack.metadata?.path }?.ino

        val serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
        val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)
        val destinationFile = File("$tempFolderPath/$destinationFilename")
        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        checkAndDeleteFile(destinationFile, finalDestinationFile)
        val downloadItemPart =
                DownloadItemPart.make(
                        downloadItem.id,
                        destinationFilename,
                        audioTrack.metadata?.size ?: 0,
                        destinationFile,
                        finalDestinationFile,
                        itemSubfolder,
                        serverPath,
                        localFolder,
                        null,
                        audioTrack,
                        null
                )
        downloadItem.downloadItemParts.add(downloadItemPart)
      }
    } else {
      val audioTrack = episode?.audioTrack
      val audioFileIno = episode?.audioFile?.ino
      val serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
      val destinationFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")
      val destinationFile = File("$tempFolderPath/$destinationFilename")
      val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      checkAndDeleteFile(destinationFile, finalDestinationFile)
      val downloadItemPart =
              DownloadItemPart.make(
                      downloadItem.id,
                      destinationFilename,
                      audioTrack?.metadata?.size ?: 0,
                      destinationFile,
                      finalDestinationFile,
                      titleCleaned,
                      serverPath,
                      localFolder,
                      null,
                      audioTrack,
                      episode
              )
      downloadItem.downloadItemParts.add(downloadItemPart)
    }

    if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
      val coverLibraryFile =
              libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
      val serverPath = "/api/items/${libraryItem.id}/cover"
      val destinationFilename = if (episode == null) "cover-${libraryItem.id}.jpg" else "cover.jpg"
      val destinationFile = File("$tempFolderPath/$destinationFilename")
      val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      checkAndDeleteFile(destinationFile, finalDestinationFile)
      val downloadItemPart =
              DownloadItemPart.make(
                      downloadItem.id,
                      destinationFilename,
                      coverLibraryFile?.metadata?.size ?: 0,
                      destinationFile,
                      finalDestinationFile,
                      titleCleaned,
                      serverPath,
                      localFolder,
                      null,
                      null,
                      null
              )
      downloadItem.downloadItemParts.add(downloadItemPart)
    }

    if (downloadItem.downloadItemParts.isNotEmpty()) {
      downloadItemManager.addDownloadItem(downloadItem)
    }
  }

  /* Delete files if they already exist */
  private fun checkAndDeleteFile(destinationFile: File, finalDestinationFile: File) {

    if (destinationFile.exists()) {
      Log.d(tag, "TEMP file already exists, removing it from ${destinationFile.absolutePath}")
      destinationFile.delete()
    }

    if (finalDestinationFile.exists()) {
      Log.d(tag, "File already exists, removing it from ${finalDestinationFile.absolutePath}")
      finalDestinationFile.delete()
    }
  }
}
