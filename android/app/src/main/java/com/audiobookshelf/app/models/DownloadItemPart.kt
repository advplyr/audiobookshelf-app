package com.audiobookshelf.app.models

import android.app.DownloadManager
import android.net.Uri
import android.util.Log
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.EBookFile
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.File

data class DownloadItemPart(
  val id: String,
  val downloadItemId: String,
  val filename: String,
  val fileSize: Long,
  val finalDestinationPath:String,
  // Path of the (temporary) file the system DownloadManager / OkHttp writes to before the
  // SAF move. Stored as a String so it survives PaperDB serialization (Uri does not).
  val destinationPath: String,
  val serverPath: String,
  val localFolderName: String,
  val localFolderUrl: String,
  val localFolderId: String,
  val ebookFile: EBookFile?,
  val audioTrack: AudioTrack?,
  val episode: PodcastEpisode?,
  var completed:Boolean,
  var moved:Boolean,
  var isMoving:Boolean,
  var failed:Boolean,
  val finalDestinationSubfolder: String,
  var downloadId: Long?,
  var progress: Long,
  var bytesDownloaded: Long
) {
  companion object {
    fun make(downloadItemId:String, filename:String, fileSize: Long, destinationFile: File, finalDestinationFile: File, subfolder:String, serverPath:String, localFolder: LocalFolder, ebookFile: EBookFile?, audioTrack: AudioTrack?, episode: PodcastEpisode?) :DownloadItemPart {
      Log.d("DownloadItemPart", "Audio File Destination: ${destinationFile.absolutePath} | Final Destination: ${finalDestinationFile.absolutePath} | Server Path $serverPath")
      return DownloadItemPart(
        id = DeviceManager.getBase64Id(finalDestinationFile.absolutePath),
        downloadItemId,
        filename = filename,
        fileSize = fileSize,
        finalDestinationPath = finalDestinationFile.absolutePath,
        destinationPath = destinationFile.absolutePath,
        serverPath = serverPath,
        localFolderName = localFolder.name,
        localFolderUrl = localFolder.contentUrl,
        localFolderId = localFolder.id,
        ebookFile = ebookFile,
        audioTrack = audioTrack,
        episode = episode,
        completed = false,
        moved = false,
        isMoving = false,
        failed = false,
        finalDestinationSubfolder = subfolder,
        downloadId = null,
        progress = 0,
        bytesDownloaded = 0
      )
    }
  }

  @get:JsonIgnore
  val isInternalStorage get() = localFolderId.startsWith("internal-")

  // Re-derive on every access so we always pick up the current server token (if it rotated
  // while the app was suspended) and so we don't depend on Uri being deserialized.
  @get:JsonIgnore
  val serverUrl: String get() {
    var url = "${DeviceManager.serverAddress}${serverPath}?token=${DeviceManager.token}"
    if (serverPath.endsWith("/cover")) url += "&raw=1"
    return url
  }

  @get:JsonIgnore
  val uri: Uri get() = Uri.parse(serverUrl)

  @get:JsonIgnore
  val destinationUri: Uri get() = Uri.fromFile(File(destinationPath))

  @get:JsonIgnore
  val finalDestinationUri: Uri get() = Uri.fromFile(File(finalDestinationPath))

  @JsonIgnore
  fun getDownloadRequest(): DownloadManager.Request {
    val dlRequest = DownloadManager.Request(uri)
    dlRequest.setTitle(filename)
    dlRequest.setDescription("Downloading to $localFolderName with filename $filename")
    dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
    dlRequest.setDestinationUri(destinationUri)
    return dlRequest
  }
}
