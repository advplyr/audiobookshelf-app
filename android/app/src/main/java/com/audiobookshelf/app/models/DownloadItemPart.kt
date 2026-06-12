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
  val serverPath: String,
  // Stored at creation time so serverUrl survives a Kryo round-trip (android.net.Uri does not).
  // @JsonIgnore so the token is not included in Capacitor events sent to the JS layer.
  @JsonIgnore val serverAddress: String,
  @JsonIgnore val serverToken: String,
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
  @JsonIgnore val uri: Uri,
  @JsonIgnore val destinationUri: Uri,
  val finalDestinationSubfolder: String,
  var downloadId: Long?,
  var progress: Long,
  var bytesDownloaded: Long
) {
  companion object {
    fun make(downloadItemId:String, filename:String, fileSize: Long, destinationFile: File, finalDestinationFile: File, subfolder:String, serverPath:String, localFolder: LocalFolder, ebookFile: EBookFile?, audioTrack: AudioTrack?, episode: PodcastEpisode?) :DownloadItemPart {
      val destinationUri = Uri.fromFile(destinationFile)

      val address = DeviceManager.serverAddress
      val token = DeviceManager.token
      var downloadUrl = "${address}${serverPath}?token=${token}"
      if (serverPath.endsWith("/cover")) {
        downloadUrl += "&raw=1" // Download raw cover image
      }

      val downloadUri = Uri.parse(downloadUrl)
      Log.d("DownloadItemPart", "Audio File Destination Uri: $destinationUri | Server Path $serverPath")
      return DownloadItemPart(
        id = DeviceManager.getBase64Id(finalDestinationFile.absolutePath),
        downloadItemId,
        filename = filename,
        fileSize = fileSize,
        finalDestinationPath = finalDestinationFile.absolutePath,
        serverPath = serverPath,
        serverAddress = address,
        serverToken = token,
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
        uri = downloadUri,
        destinationUri = destinationUri,
        finalDestinationSubfolder = subfolder,
        downloadId = null,
        progress = 0,
        bytesDownloaded = 0
      )
    }
  }

  // Derived from finalDestinationPath rather than stored as a Uri field because android.net.Uri
  // does not survive a Kryo round-trip (Paper DB), and finalDestinationPath is the authoritative
  // persisted String.
  @get:JsonIgnore
  val finalDestinationUri get() = Uri.fromFile(File(finalDestinationPath))

  @get:JsonIgnore
  val isInternalStorage get() = localFolderId.startsWith("internal-")

  // Uses serverAddress/serverToken stored at creation time rather than the uri field because
  // android.net.Uri does not survive a Kryo round-trip (Paper DB), so uri is null on restore.
  @get:JsonIgnore
  val serverUrl get(): String {
    var url = "${serverAddress}${serverPath}?token=${serverToken}"
    if (serverPath.endsWith("/cover")) url += "&raw=1"
    return url
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
