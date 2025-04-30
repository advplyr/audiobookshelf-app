package com.audiobookshelf.app.device

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.*
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import java.io.File

class FolderScanner(var ctx: Context) {
  private val tag = "FolderScanner"

  data class DownloadItemScanResult(
          val localLibraryItem: LocalLibraryItem,
          var localMediaProgress: LocalMediaProgress?
  )

  private fun getLocalLibraryItemId(mediaItemId: String): String {
    return "local_" + DeviceManager.getBase64Id(mediaItemId)
  }

  /** Create LocalFile from downloadItemPart */
  private fun createLocalFile(downloadItemPart: DownloadItemPart): LocalFile? {
    if (downloadItemPart.isInternalStorage) {
      val file = File(downloadItemPart.finalDestinationPath)
      val localFileId = DeviceManager.getBase64Id(file.name)
      val localFile =
              LocalFile(
                      localFileId,
                      file.name,
                      file.extension,
                      Uri.fromFile(file).toString(),
                      file.getBasePath(ctx),
                      file.absolutePath,
                      file.mimeType,
                      file.length()
              )
      return localFile
    } else {
      // Convert the file path to a Uri using SAF
      val docFile = DocumentFileCompat.fromFullPath(ctx, downloadItemPart.finalDestinationPath)
      if (docFile == null) {
        Log.e(tag, "Doc File Invalid ${downloadItemPart.finalDestinationPath}")
        return null
      }
      val localFileId = DeviceManager.getBase64Id(docFile.id)
      val localFile =
              LocalFile(
                      localFileId,
                      docFile.name,
                      docFile.extension,
                      docFile.uri.toString(),
                      docFile.getBasePath(ctx),
                      docFile.getAbsolutePath(ctx),
                      docFile.mimeType,
                      docFile.length()
              )
      return localFile
    }
  }

  // Scan over all files from the downloaded item and create the full LocalLibraryItem
  private fun scanDownloadItemParts(
          localLibraryItem: LocalLibraryItem,
          downloadItem: DownloadItem,
          cb: (DownloadItemScanResult?) -> Unit
  ) {
    val audioTracks: MutableList<AudioTrack> = mutableListOf()
    var foundEBookFile = false
    var localEpisodeId: String? = null

    downloadItem.downloadItemParts.forEach { downloadItemPart ->
      Log.d(
              tag,
              "Scan internal storage item with finalDestinationUri=${downloadItemPart.finalDestinationUri}"
      )

      val localFile = createLocalFile(downloadItemPart)
      if (localFile == null) {
        Log.e(
                tag,
                "scanInternalDownloadItem: Null localFile for path ${downloadItemPart.finalDestinationPath}"
        )
      } else if (downloadItemPart.audioTrack != null) {
        val audioTrackFromServer = downloadItemPart.audioTrack
        Log.d(
                tag,
                "scanInternalDownloadItem: Audio Track from Server index = ${audioTrackFromServer.index}"
        )

        Log.d(tag, "Scan internal file localFileId=$localFile.id")
        localLibraryItem.localFiles.add(localFile)

        val trackFileMetadata =
                FileMetadata(
                        localFile.filename ?: "",
                        localFile.extension ?: "",
                        localFile.absolutePath,
                        localFile.basePath,
                        localFile.size
                )
        // Create new audio track
        val track =
                AudioTrack(
                        audioTrackFromServer.index,
                        audioTrackFromServer.startOffset,
                        audioTrackFromServer.duration,
                        localFile.filename ?: "",
                        localFile.contentUrl,
                        localFile.mimeType ?: "",
                        trackFileMetadata,
                        true,
                        localFile.id,
                        audioTrackFromServer.index
                )
        audioTracks.add(track)

        Log.d(
                tag,
                "scanInternalDownloadItem: Created Audio Track with index ${track.index} from local file ${localFile.absolutePath}"
        )

        // Add podcast episodes to library
        downloadItemPart.episode?.let { podcastEpisode ->
          val podcast = localLibraryItem.media as Podcast
          val newEpisode = podcast.addEpisode(track, podcastEpisode)
          localEpisodeId = newEpisode.id
          Log.d(
                  tag,
                  "scanInternalDownloadItem: Added episode to podcast ${podcastEpisode.title} ${track.title} | Track index: ${podcastEpisode.audioTrack?.index}"
          )
        }
      } else if (downloadItemPart.ebookFile != null) {
        foundEBookFile = true
        Log.d(tag, "scanInternalDownloadItem: Ebook file found with mimetype=${localFile.mimeType}")
        localLibraryItem.localFiles.add(localFile)

        val ebookFile =
                EBookFile(
                        downloadItemPart.ebookFile.ino,
                        downloadItemPart.ebookFile.metadata,
                        downloadItemPart.ebookFile.ebookFormat,
                        true,
                        localFile.id,
                        localFile.contentUrl
                )
        (localLibraryItem.media as Book).ebookFile = ebookFile
        Log.d(tag, "scanInternalDownloadItem: Ebook file added to lli ${localFile.contentUrl}")
      } else {

        localLibraryItem.coverAbsolutePath = localFile.absolutePath
        localLibraryItem.coverContentUrl = localFile.contentUrl
        localLibraryItem.localFiles.add(localFile)
      }
    }

    if (audioTracks.isEmpty() && !foundEBookFile) {
      Log.d(
              tag,
              "scanDownloadItem did not find any audio tracks or ebook file in folder for ${downloadItem.itemFolderPath}"
      )
      return cb(null)
    }

    // For books sort audio tracks then set
    if (downloadItem.mediaType == "book") {
      audioTracks.sortBy { it.index }

      var indexCheck = 1
      var startOffset = 0.0
      audioTracks.forEach { audioTrack ->
        if (audioTrack.index != indexCheck || audioTrack.startOffset != startOffset) {
          audioTrack.index = indexCheck
          audioTrack.startOffset = startOffset
        }
        indexCheck++
        startOffset += audioTrack.duration
      }

      localLibraryItem.media.setAudioTracks(audioTracks)
    }

    val downloadItemScanResult = DownloadItemScanResult(localLibraryItem, null)

    // If library item had media progress then make local media progress and save
    downloadItem.userMediaProgress?.let { mediaProgress ->
      val localMediaProgressId =
              if (downloadItem.episodeId.isNullOrEmpty()) localLibraryItem.id
              else "${localLibraryItem.id}-$localEpisodeId"
      val newLocalMediaProgress =
              LocalMediaProgress(
                      id = localMediaProgressId,
                      localLibraryItemId = localLibraryItem.id,
                      localEpisodeId = localEpisodeId,
                      duration = mediaProgress.duration,
                      progress = mediaProgress.progress,
                      currentTime = mediaProgress.currentTime,
                      isFinished = mediaProgress.isFinished,
                      ebookLocation = mediaProgress.ebookLocation,
                      ebookProgress = mediaProgress.ebookProgress,
                      lastUpdate = mediaProgress.lastUpdate,
                      startedAt = mediaProgress.startedAt,
                      finishedAt = mediaProgress.finishedAt,
                      serverConnectionConfigId = downloadItem.serverConnectionConfigId,
                      serverAddress = downloadItem.serverAddress,
                      serverUserId = downloadItem.serverUserId,
                      libraryItemId = downloadItem.libraryItemId,
                      episodeId = downloadItem.episodeId
              )
      Log.d(
              tag,
              "scanLibraryItemFolder: Saving local media progress ${newLocalMediaProgress.id} at progress ${newLocalMediaProgress.progress}"
      )
      DeviceManager.dbManager.saveLocalMediaProgress(newLocalMediaProgress)

      downloadItemScanResult.localMediaProgress = newLocalMediaProgress
    }

    DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)

    cb(downloadItemScanResult)
  }

  private fun scanInternalDownloadItem(
          downloadItem: DownloadItem,
          cb: (DownloadItemScanResult?) -> Unit
  ) {
    val localLibraryItemId = "local_${downloadItem.libraryItemId}"

    var localLibraryItem: LocalLibraryItem?
    localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)

    // Create the local library item if it does not already exist. The local library item will
    // already exist if downloading new podast episodes
    if (localLibraryItem == null) {
      Log.d(
              tag,
              "[InternalFolderScanner] Create local library item for ${downloadItem.media.metadata.title}"
      )
      localLibraryItem =
              LocalLibraryItem(
                      localLibraryItemId,
                      downloadItem.localFolder.id,
                      downloadItem.itemFolderPath,
                      downloadItem.itemFolderPath,
                      "",
                      false,
                      downloadItem.mediaType,
                      downloadItem.media.getLocalCopy(),
                      mutableListOf(),
                      null,
                      null,
                      true,
                      downloadItem.serverConnectionConfigId,
                      downloadItem.serverAddress,
                      downloadItem.serverUserId,
                      downloadItem.libraryItemId
              )
    }
    scanDownloadItemParts(localLibraryItem, downloadItem, cb)
  }

  // Find a folder by path in the DocumentFile tree
  private fun findFolderByPath(root: DocumentFile, subPath: String): DocumentFile? {
    var current = root
    subPath.split("/").forEach { name -> current = current.findFile(name) ?: return null }
    return current
  }

  // Scan item after download and create local library item
  fun scanDownloadItem(downloadItem: DownloadItem, cb: (DownloadItemScanResult?) -> Unit) {
    // If downloading to internal storage handle separately
    if (downloadItem.isInternalStorage) {
      scanInternalDownloadItem(downloadItem, cb)
      return
    }

    Log.d(tag, "starting my custom scanner")
    // Get the root by stripping off the localfolder content url from the itemfolderPath
    val rootPathUri = Uri.parse(downloadItem.localFolder.contentUrl)
    Log.d(tag, "root path URI after concat: ${rootPathUri}")
    val root = DocumentFileCompat.fromUri(ctx, rootPathUri)
    Log.d(tag, "Root Doc File ${root?.uri} | ${root?.name} | ${root?.length()}")
    if (root == null) {
      Log.e(tag, "Root Doc File Invalid ${rootPathUri}")
      return cb(null)
    }

    Log.d(tag, "item folder path: ${downloadItem.itemSubfolder}")
    val baseFolder = findFolderByPath(root, downloadItem.itemSubfolder)
    if (baseFolder == null) {
      Log.e(tag, "Base folder not found ${downloadItem.itemSubfolder}")
      return cb(null)
    }
    Log.d(tag, "baseFolder: ${baseFolder.uri} | ${baseFolder.name} | ${baseFolder.length()}")

    // Build references to files in this library item folder
    // e.g. absolute path is "storage/emulated/0/Audiobooks/Orson Scott Card/Enders Game"
    //        and itemSubfolder is "Orson Scott Card/Enders Game"
    val itemFolderId = baseFolder.id
    val itemFolderUrl = baseFolder.uri.toString()
    val itemFolderBasePath = baseFolder.getBasePath(ctx)
    val itemFolderAbsolutePath = baseFolder.getAbsolutePath(ctx)

    val df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(itemFolderUrl))

    if (df == null) {
      Log.e(tag, "Folder Doc File Invalid ${downloadItem.itemFolderPath}")
      return cb(null)
    }

    val localLibraryItemId = getLocalLibraryItemId(itemFolderId)
    Log.d(
            tag,
            "scanDownloadItem starting for ${downloadItem.itemFolderPath} | ${baseFolder.uri} | Item Folder Id:$itemFolderId | LLI Id:$localLibraryItemId"
    )

    // Search for files in media item folder
    // m4b files showing as mimeType application/octet-stream on Android 10 and earlier see #154
    // val filesFound = baseFolder.listFiles()

    var localLibraryItem: LocalLibraryItem?

    // Create the local library item if it does not already exist. The local library item will
    // already exist if downloading new podast episodes
    localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
    if (localLibraryItem == null) {
      Log.d(
              tag,
              "[FolderScanner] Create local library item for ${downloadItem.media.metadata.title}"
      )
      localLibraryItem =
              LocalLibraryItem(
                      localLibraryItemId,
                      downloadItem.localFolder.id,
                      itemFolderBasePath,
                      itemFolderAbsolutePath,
                      itemFolderUrl,
                      false,
                      downloadItem.mediaType,
                      downloadItem.media.getLocalCopy(),
                      mutableListOf(),
                      null,
                      null,
                      true,
                      downloadItem.serverConnectionConfigId,
                      downloadItem.serverAddress,
                      downloadItem.serverUserId,
                      downloadItem.libraryItemId
              )
    }

    scanDownloadItemParts(localLibraryItem, downloadItem, cb)
  }
}
