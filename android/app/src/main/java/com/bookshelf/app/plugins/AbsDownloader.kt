package com.bookshelf.app.plugins

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.bookshelf.app.MainActivity
import com.bookshelf.app.data.*
import com.bookshelf.app.device.DeviceManager
import com.bookshelf.app.device.FolderScanner
import com.bookshelf.app.models.DownloadItem
import com.bookshelf.app.models.DownloadItemPart
import com.bookshelf.app.server.ApiHandler
import com.bookshelf.app.managers.DownloadItemManager
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
  lateinit var downloadManager: DownloadManager
  lateinit var apiHandler: ApiHandler
  lateinit var folderScanner: FolderScanner
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
  })

  override fun load() {
    mainActivity = (activity as MainActivity)
    downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    folderScanner = FolderScanner(mainActivity)
    apiHandler = ApiHandler(mainActivity)
    downloadItemManager = DownloadItemManager(downloadManager, folderScanner, mainActivity, clientEventEmitter)
    Log.d(tag, "Build SDK ${Build.VERSION.SDK_INT}")
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    val libraryItemId = call.data.getString("libraryItemId").toString()
    var episodeId = call.data.getString("episodeId").toString()
    if (episodeId == "null") episodeId = ""
    val localFolderId = call.data.getString("localFolderId").toString()
    Log.d(tag, "Download library item $libraryItemId to folder $localFolderId / episode: $episodeId")

    val downloadId = if (episodeId.isEmpty()) libraryItemId else "$libraryItemId-$episodeId"
    if (downloadItemManager.downloadItemQueue.find { it.id == downloadId } != null) {
      Log.d(tag, "Download already started for this media entity $downloadId")
      return call.resolve(JSObject("{\"error\":\"Download already started for this media entity\"}"))
    }

    apiHandler.getLibraryItemWithProgress(libraryItemId, episodeId) { libraryItem ->
      if (libraryItem == null) {
        call.resolve(JSObject("{\"error\":\"Server request failed\"}"))
      } else {
        Log.d(tag, "Got library item from server ${libraryItem.id}")

        val localFolder = DeviceManager.dbManager.getLocalFolder(localFolderId)
        if (localFolder != null) {

          if (episodeId.isNotEmpty() && libraryItem.mediaType != "podcast") {
            Log.e(tag, "Library item is not a podcast but episode was requested")
            call.resolve(JSObject("{\"error\":\"Invalid library item not a podcast\"}"))
          } else if (episodeId.isNotEmpty()) {
            val podcast = libraryItem.media as Podcast
            val episode = podcast.episodes?.find { podcastEpisode ->
              podcastEpisode.id == episodeId
            }
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

  // Clean folder path so it can be used in URL
  private fun cleanRelPath(relPath: String): String {
    val cleanedRelPath = relPath.replace("\\", "/").replace("%", "%25").replace("#", "%23")
    return if (cleanedRelPath.startsWith("/")) cleanedRelPath.substring(1) else cleanedRelPath
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

  private fun startLibraryItemDownload(libraryItem: LibraryItem, localFolder: LocalFolder, episode:PodcastEpisode?) {
    val tempFolderPath = mainActivity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    Log.d(tag, "downloadCacheDirectory=$tempFolderPath")

    if (libraryItem.mediaType == "book") {
      val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)
      val bookAuthor = cleanStringForFileSystem(libraryItem.media.metadata.getAuthorDisplayName())

      val tracks = libraryItem.media.getAudioTracks()
      Log.d(tag, "Starting library item download with ${tracks.size} tracks")
      val itemSubfolder = "$bookAuthor/$bookTitle"
      val itemFolderPath = "${localFolder.absolutePath}/$itemSubfolder"
      val downloadItem = DownloadItem(libraryItem.id, libraryItem.id, null, libraryItem.userMediaProgress,DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, itemSubfolder, libraryItem.media, mutableListOf())

      // Create download item part for each audio track
      tracks.forEach { audioTrack ->
        val fileSize = audioTrack.metadata?.size ?: 0
        val serverPath = "/s/item/${libraryItem.id}/${cleanRelPath(audioTrack.relPath)}"
        val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)
        Log.d(tag, "Audio File Server Path $serverPath | AF RelPath ${audioTrack.relPath} | LocalFolder Path ${localFolder.absolutePath} | DestName $destinationFilename")

        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename")

        if (destinationFile.exists()) {
          Log.d(tag, "TEMP Audio file already exists, removing it from ${destinationFile.absolutePath}")
          destinationFile.delete()
        }

        if (finalDestinationFile.exists()) {
          Log.d(tag, "Audio file already exists, removing it from ${finalDestinationFile.absolutePath}")
          finalDestinationFile.delete()
        }

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,audioTrack,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
          val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
          val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

          val serverPath = "/api/items/${libraryItem.id}/cover"
          val destinationFilename = "cover-${libraryItem.id}.jpg"
          val destinationFile = File("$tempFolderPath/$destinationFilename")
          val finalDestinationFile = File("$itemFolderPath/$destinationFilename")

          if (destinationFile.exists()) {
            Log.d(tag, "TEMP Audio file already exists, removing it from ${destinationFile.absolutePath}")
            destinationFile.delete()
          }

          if (finalDestinationFile.exists()) {
            Log.d(tag, "Cover already exists, removing it from ${finalDestinationFile.absolutePath}")
            finalDestinationFile.delete()
          }

          val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, coverFileSize,  destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }

        downloadItemManager.addDownloadItem(downloadItem)
      }
    } else {
      // Podcast episode download
      val podcastTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val audioTrack = episode?.audioTrack
      val fileSize = audioTrack?.metadata?.size ?: 0

      Log.d(tag, "Starting podcast episode download")
      val itemFolderPath = localFolder.absolutePath + "/" + podcastTitle
      val downloadItemId = "${libraryItem.id}-${episode?.id}"
      val downloadItem = DownloadItem(downloadItemId, libraryItem.id, episode?.id, libraryItem.userMediaProgress, DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, podcastTitle, podcastTitle, libraryItem.media, mutableListOf())

      var serverPath = "/s/item/${libraryItem.id}/${cleanRelPath(audioTrack?.relPath ?: "")}"
      var destinationFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")
      Log.d(tag, "Audio File Server Path $serverPath | AF RelPath ${audioTrack?.relPath} | LocalFolder Path ${localFolder.absolutePath} | DestName $destinationFilename")

      var destinationFile = File("$tempFolderPath/$destinationFilename")
      var finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      if (finalDestinationFile.exists()) {
        Log.d(tag, "Audio file already exists, removing it from ${finalDestinationFile.absolutePath}")
        finalDestinationFile.delete()
      }

      var downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,fileSize, destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,audioTrack,episode)
      downloadItem.downloadItemParts.add(downloadItemPart)

      if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
        val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
        val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

        serverPath = "/api/items/${libraryItem.id}/cover"
        destinationFilename = "cover.jpg"

        destinationFile = File("$tempFolderPath/$destinationFilename")
        finalDestinationFile = File("$itemFolderPath/$destinationFilename")

        if (finalDestinationFile.exists()) {
          Log.d(tag, "Podcast cover already exists - not downloading cover again")
        } else {
          downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,coverFileSize,destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }
      }

      downloadItemManager.addDownloadItem(downloadItem)
    }
  }
}
