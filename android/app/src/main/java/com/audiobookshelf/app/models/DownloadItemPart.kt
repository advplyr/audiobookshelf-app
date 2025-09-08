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
        val finalDestinationPath: String,
        val serverPath: String,
        val localFolderName: String,
        val localFolderUrl: String,
        val localFolderId: String,
        val ebookFile: EBookFile?,
        val audioTrack: AudioTrack?,
        val episode: PodcastEpisode?,
        var completed: Boolean,
        var moved: Boolean,
        var isMoving: Boolean,
        var failed: Boolean,
        @JsonIgnore val uri: Uri?,
        @JsonIgnore val destinationUri: Uri?,
        @JsonIgnore val finalDestinationUri: Uri?,
        val finalDestinationSubfolder: String,
        var downloadId: Long?,
        var progress: Long,
        var bytesDownloaded: Long
) {
  companion object {
    fun make(
            downloadItemId: String,
            filename: String,
            fileSize: Long,
            destinationFile: File,
            finalDestinationFile: File,
            subfolder: String,
            serverPath: String,
            localFolder: LocalFolder,
            ebookFile: EBookFile?,
            audioTrack: AudioTrack?,
            episode: PodcastEpisode?
    ): DownloadItemPart {
      val destinationUri = Uri.fromFile(destinationFile)
      val finalDestinationUri = Uri.fromFile(finalDestinationFile)

      var downloadUrl = "${DeviceManager.serverAddress}${serverPath}?token=${DeviceManager.token}"
      if (serverPath.endsWith("/cover")) {
        downloadUrl += "&raw=1" // Download raw cover image
      }

      val downloadUri = Uri.parse(downloadUrl)
      Log.d(
              "DownloadItemPart",
              "Audio File Destination Uri: $destinationUri | Final Destination Uri: $finalDestinationUri | Download URI $downloadUri"
      )
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
              ebookFile = ebookFile,
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

  @get:JsonIgnore
  val isInternalStorage
    get() = localFolderId.startsWith("internal-")

  @get:JsonIgnore
  val serverUrl
    get() =
            uri?.toString()
                    ?: run {
                      // Reconstruct URL if uri is null (e.g., when loaded from database)
                      val serverAddress = DeviceManager.serverAddress
                      val token = DeviceManager.token

                      if (serverAddress.isBlank()) {
                        Log.e(
                                "DownloadItemPart",
                                "Cannot reconstruct URL - server address is blank"
                        )
                        return@run ""
                      }

                      if (token.isBlank()) {
                        Log.e("DownloadItemPart", "Cannot reconstruct URL - token is blank")
                        return@run ""
                      }

                      var downloadUrl = "${serverAddress}${serverPath}?token=${token}"
                      if (serverPath.endsWith("/cover")) {
                        downloadUrl += "&raw=1"
                      }
                      downloadUrl
                    }

  @JsonIgnore
  fun getDownloadRequest(): DownloadManager.Request? {
    val downloadUri = uri ?: return null
    val destUri = destinationUri ?: return null

    val dlRequest = DownloadManager.Request(downloadUri)
    dlRequest.setTitle(filename)
    dlRequest.setDescription("Downloading to $localFolderName with filename $filename")
    dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
    dlRequest.setDestinationUri(destUri)
    return dlRequest
  }
}
