package com.audiobookshelf.app.models

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
  var fileSize: Long,
  @JsonIgnore val destinationPath: String,
  val finalDestinationPath:String,
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
  @JsonIgnore val uri: Uri,
  @JsonIgnore val destinationUri: Uri,
  @JsonIgnore val finalDestinationUri: Uri,
  @JsonIgnore var completedDestinationUri: String?,
  val finalDestinationSubfolder: String,
  var downloadId: Long?,
  @JsonIgnore var lastUpdateTime: Long?,
  var progress: Long,
  var bytesDownloaded: Long,
  @JsonIgnore var retryCount: Int = 0,
  @JsonIgnore var authRetryCount: Int = 0,
  @JsonIgnore var waitingForSpace: Boolean = false,
  @JsonIgnore var reusedExistingFile: Boolean = false,
  /** Earliest wall-clock time (epoch ms) the next attempt may start; the UI shows it as a countdown. */
  var nextAttemptAt: Long = 0L,
  /** Validators from the server response, sent as If-Range so a changed file restarts from zero. */
  @JsonIgnore var etag: String? = null,
  @JsonIgnore var lastModified: String? = null,
  @JsonIgnore var finalizeFailureCount: Int = 0,
  @JsonIgnore var waitingForNetwork: Boolean = false,
  @JsonIgnore var waitingForWifi: Boolean = false
) {
  companion object {
    fun make(downloadItemId:String, filename:String, fileSize: Long, destinationFile: File, finalDestinationFile: File, subfolder:String, serverPath:String, localFolder: LocalFolder, ebookFile: EBookFile?, audioTrack: AudioTrack?, episode: PodcastEpisode?) :DownloadItemPart {
      val destinationUri = Uri.fromFile(destinationFile)
      val finalDestinationUri = Uri.fromFile(finalDestinationFile)
      val rawCover = if (serverPath.endsWith("/cover")) "?raw=1" else ""
      val downloadUri = Uri.parse("${DeviceManager.serverAddress}${serverPath}$rawCover")

      Log.d("DownloadItemPart", "Audio File Destination Uri: $destinationUri | Final Destination Uri: $finalDestinationUri | Server Path $serverPath")
      return DownloadItemPart(
        id = DeviceManager.getBase64Id(finalDestinationFile.absolutePath),
        downloadItemId,
        filename = filename,
        fileSize = fileSize,
        destinationPath = destinationFile.absolutePath,
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
        completedDestinationUri = null,
        finalDestinationSubfolder = subfolder,
        downloadId = null,
        lastUpdateTime = null,
        progress = 0,
        bytesDownloaded = 0,
        reusedExistingFile = false
      )
    }
  }

  @get:JsonIgnore
  val isInternalStorage get() = localFolderId.startsWith("internal-")

  /** Status shown by the downloading page. */
  val status: String
    get() =
            when {
              moved -> "completed"
              failed -> "failed"
              isMoving || completed -> "finalizing"
              waitingForSpace -> "waitingForStorage"
              waitingForWifi -> "waitingForWifi"
              waitingForNetwork -> "waitingForNetwork"
              downloadId != null -> "downloading"
              nextAttemptAt > System.currentTimeMillis() -> "retrying"
              else -> "queued"
            }

}
