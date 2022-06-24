package com.bookshelf.app.plugins

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.callback.FileCallback
import com.anggrayudi.storage.file.*
import com.anggrayudi.storage.media.FileDescription
import com.bookshelf.app.MainActivity
import com.bookshelf.app.data.*
import com.bookshelf.app.device.DeviceManager
import com.bookshelf.app.device.FolderScanner
import com.bookshelf.app.server.ApiHandler
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@CapacitorPlugin(name = "AbsDownloader")
class AbsDownloader : Plugin() {
  private val tag = "AbsDownloader"
  var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var downloadManager: DownloadManager
  lateinit var apiHandler: ApiHandler
  lateinit var folderScanner: FolderScanner

  data class DownloadItemPart(
    val id: String,
    val filename: String,
    val finalDestinationPath:String,
    val itemTitle: String,
    val serverPath: String,
    val localFolderName: String,
    val localFolderUrl: String,
    val localFolderId: String,
    val audioTrack: AudioTrack?,
    val episode:PodcastEpisode?,
    var completed:Boolean,
    var moved:Boolean,
    var failed:Boolean,
    @JsonIgnore val uri: Uri,
    @JsonIgnore val destinationUri: Uri,
    @JsonIgnore val finalDestinationUri: Uri,
    var downloadId: Long?,
    var progress: Long
  ) {
    companion object {
      fun make(filename:String, destinationFile:File, finalDestinationFile:File, itemTitle:String, serverPath:String, localFolder:LocalFolder, audioTrack:AudioTrack?, episode:PodcastEpisode?) :DownloadItemPart {
        val destinationUri = Uri.fromFile(destinationFile)
        val finalDestinationUri = Uri.fromFile(finalDestinationFile)

        var downloadUrl = "${DeviceManager.serverAddress}${serverPath}?token=${DeviceManager.token}"
        if (serverPath.endsWith("/cover")) downloadUrl += "&format=jpeg" // For cover images force to jpeg
        val downloadUri = Uri.parse(downloadUrl)
        Log.d("DownloadItemPart", "Audio File Destination Uri: $destinationUri | Final Destination Uri: $finalDestinationUri | Download URI $downloadUri")
        return DownloadItemPart(
          id = DeviceManager.getBase64Id(finalDestinationFile.absolutePath),
          filename = filename, finalDestinationFile.absolutePath,
          itemTitle = itemTitle,
          serverPath = serverPath,
          localFolderName = localFolder.name,
          localFolderUrl = localFolder.contentUrl,
          localFolderId = localFolder.id,
          audioTrack = audioTrack,
          episode = episode,
          completed = false,
          moved = false,
          failed = false,
          uri = downloadUri,
          destinationUri = destinationUri,
          finalDestinationUri = finalDestinationUri,
          downloadId = null,
          progress = 0
        )
      }
    }

    @JsonIgnore
    fun getDownloadRequest(): DownloadManager.Request {
      val dlRequest = DownloadManager.Request(uri)
      dlRequest.setTitle(filename)
      dlRequest.setDescription("Downloading to $localFolderName for book $itemTitle")
      dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      dlRequest.setDestinationUri(destinationUri)
      return dlRequest
    }
  }

  data class DownloadItem(
    val id: String,
    val libraryItemId:String,
    val episodeId:String?,
    val userMediaProgress:MediaProgress?,
    val serverConnectionConfigId:String,
    val serverAddress:String,
    val serverUserId:String,
    val mediaType: String,
    val itemFolderPath:String,
    val localFolder: LocalFolder,
    val itemTitle: String,
    val media:MediaType,
    val downloadItemParts: MutableList<DownloadItemPart>
  )

  var downloadQueue: MutableList<DownloadItem> = mutableListOf()

  override fun load() {
    mainActivity = (activity as MainActivity)
    downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    folderScanner = FolderScanner(mainActivity)
    apiHandler = ApiHandler(mainActivity)

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
    if (downloadQueue.find { it.id == downloadId } != null) {
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

    if (libraryItem.mediaType == "book") {
      val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val tracks = libraryItem.media.getAudioTracks()
      Log.d(tag, "Starting library item download with ${tracks.size} tracks")
      val itemFolderPath = localFolder.absolutePath + "/" + bookTitle
      val downloadItem = DownloadItem(libraryItem.id, libraryItem.id, null, libraryItem.userMediaProgress,DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, libraryItem.media, mutableListOf())

      // Create download item part for each audio track
      tracks.forEach { audioTrack ->
        val serverPath = "/s/item/${libraryItem.id}/${cleanRelPath(audioTrack.relPath)}"
        val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)
        Log.d(tag, "Audio File Server Path $serverPath | AF RelPath ${audioTrack.relPath} | LocalFolder Path ${localFolder.absolutePath} | DestName ${destinationFilename}")

        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename")

        if (finalDestinationFile.exists()) {
          Log.d(tag, "Audio file already exists, removing it from ${finalDestinationFile.absolutePath}")
          finalDestinationFile.delete()
        }

        val downloadItemPart = DownloadItemPart.make(destinationFilename,destinationFile,finalDestinationFile,bookTitle,serverPath,localFolder,audioTrack,null)
        downloadItem.downloadItemParts.add(downloadItemPart)

        val dlRequest = downloadItemPart.getDownloadRequest()
        val downloadId = downloadManager.enqueue(dlRequest)
        downloadItemPart.downloadId = downloadId
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
          val serverPath = "/api/items/${libraryItem.id}/cover"
          val destinationFilename = "cover.jpg"
          val destinationFile = File("$tempFolderPath/$destinationFilename")
          val finalDestinationFile = File("$itemFolderPath/$destinationFilename")

          if (finalDestinationFile.exists()) {
            Log.d(tag, "Cover already exists, removing it from ${finalDestinationFile.absolutePath}")
            finalDestinationFile.delete()
          }

          val downloadItemPart = DownloadItemPart.make(destinationFilename,destinationFile,finalDestinationFile,bookTitle,serverPath,localFolder,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)

          val dlRequest = downloadItemPart.getDownloadRequest()
          val downloadId = downloadManager.enqueue(dlRequest)
          downloadItemPart.downloadId = downloadId
        }

        downloadQueue.add(downloadItem)
        startWatchingDownloads(downloadItem)
        DeviceManager.dbManager.saveDownloadItem(downloadItem)
      }
    } else {
      // Podcast episode download
      val podcastTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val audioTrack = episode?.audioTrack
      Log.d(tag, "Starting podcast episode download")
      val itemFolderPath = localFolder.absolutePath + "/" + podcastTitle
      val downloadItemId = "${libraryItem.id}-${episode?.id}"
      val downloadItem = DownloadItem(downloadItemId, libraryItem.id, episode?.id, libraryItem.userMediaProgress, DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, podcastTitle, libraryItem.media, mutableListOf())

      var serverPath = "/s/item/${libraryItem.id}/${cleanRelPath(audioTrack?.relPath ?: "")}"
      var destinationFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")
      Log.d(tag, "Audio File Server Path $serverPath | AF RelPath ${audioTrack?.relPath} | LocalFolder Path ${localFolder.absolutePath} | DestName ${destinationFilename}")

      var destinationFile = File("$tempFolderPath/$destinationFilename")
      var finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      if (finalDestinationFile.exists()) {
        Log.d(tag, "Audio file already exists, removing it from ${finalDestinationFile.absolutePath}")
        finalDestinationFile.delete()
      }

      var downloadItemPart = DownloadItemPart.make(destinationFilename,destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,audioTrack,episode)
      downloadItem.downloadItemParts.add(downloadItemPart)

      var dlRequest = downloadItemPart.getDownloadRequest()
      var downloadId = downloadManager.enqueue(dlRequest)
      downloadItemPart.downloadId = downloadId

      if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
        serverPath = "/api/items/${libraryItem.id}/cover"
        destinationFilename = "cover.jpg"

        destinationFile = File("$tempFolderPath/$destinationFilename")
        finalDestinationFile = File("$itemFolderPath/$destinationFilename")

        if (finalDestinationFile.exists()) {
          Log.d(tag, "Podcast cover already exists - not downloading cover again")
        } else {
          downloadItemPart = DownloadItemPart.make(destinationFilename,destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)

          dlRequest = downloadItemPart.getDownloadRequest()
          downloadId = downloadManager.enqueue(dlRequest)
          downloadItemPart.downloadId = downloadId
        }
      }

      downloadQueue.add(downloadItem)
      startWatchingDownloads(downloadItem)
      DeviceManager.dbManager.saveDownloadItem(downloadItem)
    }
  }

  private fun startWatchingDownloads(downloadItem: DownloadItem) {
    GlobalScope.launch(Dispatchers.IO) {
      while (downloadItem.downloadItemParts.find { !it.moved && !it.failed } != null) { // While some item is not completed
        val numPartsBefore = downloadItem.downloadItemParts.size
        checkDownloads(downloadItem)

        // Keep database updated as item parts finish downloading
        if (downloadItem.downloadItemParts.size > 0 && downloadItem.downloadItemParts.size != numPartsBefore) {
          Log.d(tag, "Save download item on num parts changed from $numPartsBefore to ${downloadItem.downloadItemParts.size}")
          DeviceManager.dbManager.saveDownloadItem(downloadItem)
        }

        notifyListeners("onItemDownloadUpdate", JSObject(jacksonMapper.writeValueAsString(downloadItem)))
        delay(500)
      }

      // Remove download notifications
      downloadItem.downloadItemParts.forEach { downloadItemPart ->
        downloadItemPart.downloadId?.let {
          downloadManager.remove(it)
        }
      }

      val downloadItemScanResult = folderScanner.scanDownloadItem(downloadItem)
      DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
      downloadQueue.remove(downloadItem)

      Log.d(tag, "Item download complete ${downloadItem.itemTitle} | local library item id: ${downloadItemScanResult?.localLibraryItem?.id} | Items remaining in Queue ${downloadQueue.size}")

      val jsobj = JSObject()
      jsobj.put("libraryItemId", downloadItem.id)
      jsobj.put("localFolderId", downloadItem.localFolder.id)

      downloadItemScanResult?.localLibraryItem?.let { localLibraryItem ->
        jsobj.put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
      downloadItemScanResult?.localMediaProgress?.let { localMediaProgress ->
        jsobj.put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
      }
      notifyListeners("onItemDownloadComplete", jsobj)
    }
  }

  private fun checkDownloads(downloadItem: DownloadItem) {
    val itemParts = downloadItem.downloadItemParts.map { it }
    for (downloadItemPart in itemParts) {
      if (downloadItemPart.downloadId != null) {
        val dlid = downloadItemPart.downloadId!!
        val query = DownloadManager.Query().setFilterById(dlid)
        downloadManager.query(query).use {
          if (it.moveToFirst()) {
            val bytesColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesDownloadedColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)

            val totalBytes = if (bytesColumnIndex >= 0) it.getInt(bytesColumnIndex) else 0
            val downloadStatus = if (statusColumnIndex >= 0) it.getInt(statusColumnIndex) else 0
            val bytesDownloadedSoFar = if (bytesDownloadedColumnIndex >= 0) it.getInt(bytesDownloadedColumnIndex) else 0
            Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} bytes $totalBytes | bytes dled $bytesDownloadedSoFar | downloadStatus $downloadStatus")

            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
              // Once file download is complete move the file to the final destination
              if (!downloadItemPart.completed) {
                Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Done")
                downloadItemPart.completed = true

                val file = DocumentFileCompat.fromUri(mainActivity, downloadItemPart.destinationUri)
                Log.d(tag, "DOWNLOAD: Attempt move for file at destination ${downloadItemPart.destinationUri} | ${file?.getBasePath(mainActivity)}")

                val fcb = object : FileCallback() {
                  override fun onPrepare() {
                    Log.d(tag, "DOWNLOAD: PREPARING MOVE FILE")
                  }
                  override fun onFailed(errorCode:ErrorCode) {
                    Log.e(tag, "DOWNLOAD: FAILED TO MOVE FILE $errorCode")
                    downloadItemPart.failed = true
                    file?.delete()
                  }
                  override fun onCompleted(result:Any) {
                    Log.d(tag, "DOWNLOAD: FILE MOVE COMPLETED")
                    val resultDocFile = result as DocumentFile
                    Log.d(tag, "DOWNLOAD: COMPLETED FILE INFO ${resultDocFile.getAbsolutePath(mainActivity)}")
                    downloadItemPart.moved = true
                  }
                }

                Log.d(tag, "DOWNLOAD: Move file to final destination path: ${downloadItemPart.finalDestinationPath}")
                val localFolderFile = DocumentFileCompat.fromUri(mainActivity,Uri.parse(downloadItemPart.localFolderUrl))
                val mimetype = if (downloadItemPart.audioTrack != null) MimeType.AUDIO else MimeType.IMAGE
                val fileDescription = FileDescription(downloadItemPart.filename, downloadItemPart.itemTitle, mimetype)
                file?.moveFileTo(mainActivity,localFolderFile!!,fileDescription,fcb)
              } else {
                // Why is kotlin requiring an else here..
              }
            } else if (downloadStatus == DownloadManager.STATUS_FAILED) {
              Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Failed")
              downloadItem.downloadItemParts.remove(downloadItemPart)
            } else {
              //update progress
              val percentProgress = if (totalBytes > 0) ((bytesDownloadedSoFar * 100L) / totalBytes) else 0
              Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Progress = $percentProgress%")
              downloadItemPart.progress = percentProgress
            }
          } else {
            Log.d(tag, "Download ${downloadItemPart.filename} not found in dlmanager")
            downloadItem.downloadItemParts.remove(downloadItemPart)
          }
        }
      }
    }
  }
}
