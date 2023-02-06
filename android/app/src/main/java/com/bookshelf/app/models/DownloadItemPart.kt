package com.bookshelf.app.models

import android.app.DownloadManager
import android.net.Uri
import android.util.Log
import com.bookshelf.app.data.AudioTrack
import com.bookshelf.app.data.LocalFolder
import com.bookshelf.app.data.PodcastEpisode
import com.bookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.File

data class DownloadItemPart(
  val id: String,
  val downloadItemId: String,
  val filename: String,
  val fileSize: Long,
  val finalDestinationPath:String,
  val serverPath: String,
  val localFolderName: String,
  val localFolderUrl: String,
  val localFolderId: String,
  val audioTrack: AudioTrack?,
  val episode: PodcastEpisode?,
  var completed:Boolean,
  var moved:Boolean,
  var isMoving:Boolean,
  var failed:Boolean,
  @JsonIgnore val uri: Uri,
  @JsonIgnore val destinationUri: Uri,
  @JsonIgnore val finalDestinationUri: Uri,
  val finalDestinationSubfolder: String,
  var downloadId: Long?,
  var progress: Long,
  var bytesDownloaded: Long
) {
  companion object {
    fun make(downloadItemId:String, filename:String, fileSize: Long, destinationFile: File, finalDestinationFile: File, subfolder:String, serverPath:String, localFolder: LocalFolder, audioTrack: AudioTrack?, episode: PodcastEpisode?) :DownloadItemPart {
      val destinationUri = Uri.fromFile(destinationFile)
      val finalDestinationUri = Uri.fromFile(finalDestinationFile)

      var downloadUrl = "${DeviceManager.serverAddress}${serverPath}?token=${DeviceManager.token}"
      if (serverPath.endsWith("/cover")) downloadUrl += "&format=jpeg" // For cover images force to jpeg
      val downloadUri = Uri.parse(downloadUrl)
      Log.d("DownloadItemPart", "Audio File Destination Uri: $destinationUri | Final Destination Uri: $finalDestinationUri | Download URI $downloadUri")
      return DownloadItemPart(
        id = DeviceManager.getBase64Id(finalDestinationFile.absolutePath),
        downloadItemId,
        filename = filename,
        fileSize = fileSize,
        finalDestinationPath = finalDestinationFile.absolutePath,
        serverPath = serverPath,
        localFolderName = localFolder.name,
        localFolderUrl = localFolder.contentUrl,
        localFolderId = localFolder.id,
        audioTrack = audioTrack,
        episode = episode,
        completed = false,
        moved = false,
        isMoving = false,
        failed = false,
        uri = downloadUri,
        destinationUri = destinationUri,
        finalDestinationUri = finalDestinationUri,
        finalDestinationSubfolder = subfolder,
        downloadId = null,
        progress = 0,
        bytesDownloaded = 0
      )
    }
  }

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
