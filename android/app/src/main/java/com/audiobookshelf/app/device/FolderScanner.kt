package com.audiobookshelf.app.device

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.*
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

class FolderScanner(var ctx: Context) {
  private val tag = "FolderScanner"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  data class DownloadItemScanResult(val localLibraryItem:LocalLibraryItem, var localMediaProgress:LocalMediaProgress?)

  private fun getLocalLibraryItemId(mediaItemId:String):String {
    return "local_" + DeviceManager.getBase64Id(mediaItemId)
  }

  private fun scanInternalDownloadItem(downloadItem:DownloadItem, cb: (DownloadItemScanResult?) -> Unit) {
    val localLibraryItemId = "local_${downloadItem.libraryItemId}"

    var localEpisodeId:String? = null
    var localLibraryItem:LocalLibraryItem?
    if (downloadItem.mediaType == "book") {
      localLibraryItem = LocalLibraryItem(localLibraryItemId, downloadItem.localFolder.id, downloadItem.itemFolderPath, downloadItem.itemFolderPath, "", false, downloadItem.mediaType, downloadItem.media.getLocalCopy(), mutableListOf(), null, null, true, downloadItem.serverConnectionConfigId, downloadItem.serverAddress, downloadItem.serverUserId, downloadItem.libraryItemId)
    } else {
      // Lookup or create podcast local library item
      localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
      if (localLibraryItem == null) {
        Log.d(tag, "[FolderScanner] Podcast local library item not created yet for ${downloadItem.media.metadata.title}")
        localLibraryItem = LocalLibraryItem(localLibraryItemId, downloadItem.localFolder.id, downloadItem.itemFolderPath, downloadItem.itemFolderPath, "", false, downloadItem.mediaType, downloadItem.media.getLocalCopy(), mutableListOf(), null, null, true,downloadItem.serverConnectionConfigId,downloadItem.serverAddress,downloadItem.serverUserId,downloadItem.libraryItemId)
      }
    }

    val audioTracks:MutableList<AudioTrack> = mutableListOf()
    var foundEBookFile = false

    downloadItem.downloadItemParts.forEach { downloadItemPart ->
      Log.d(tag, "Scan internal storage item with finalDestinationUri=${downloadItemPart.finalDestinationUri}")

      val file = File(downloadItemPart.finalDestinationPath)
      Log.d(tag, "Scan internal storage item created file ${file.name}")

      if (file == null) {
        Log.e(tag, "scanInternalDownloadItem: Null docFile for path ${downloadItemPart.finalDestinationPath}")
      } else {
        if (downloadItemPart.audioTrack != null) {
          val audioTrackFromServer = downloadItemPart.audioTrack
          Log.d(
            tag,
            "scanInternalDownloadItem: Audio Track from Server index = ${audioTrackFromServer.index}"
          )

          val localFileId = DeviceManager.getBase64Id(file.name)
          Log.d(tag, "Scan internal file localFileId=$localFileId")
          val localFile = LocalFile(
            localFileId,
            file.name,
            downloadItemPart.finalDestinationUri.toString(),
            file.getBasePath(ctx),
            file.absolutePath,
            file.getSimplePath(ctx),
            file.mimeType,
            file.length()
          )
          localLibraryItem.localFiles.add(localFile)

          val trackFileMetadata = FileMetadata(file.name, file.extension, file.absolutePath, file.getBasePath(ctx), file.length())
          // Create new audio track
          val track = AudioTrack(
            audioTrackFromServer.index,
            audioTrackFromServer.startOffset,
            audioTrackFromServer.duration,
            localFile.filename ?: "",
            localFile.contentUrl,
            localFile.mimeType ?: "",
            trackFileMetadata,
            true,
            localFileId,
            null,
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
          Log.d(tag, "scanInternalDownloadItem: Ebook file found with mimetype=${file.mimeType}")
          val localFileId = DeviceManager.getBase64Id(file.name)
          val localFile = LocalFile(
            localFileId,
            file.name,
            Uri.fromFile(file).toString(),
            file.getBasePath(ctx),
            file.absolutePath,
            file.getSimplePath(ctx),
            file.mimeType,
            file.length()
          )
          localLibraryItem.localFiles.add(localFile)

          val ebookFile = EBookFile(
            downloadItemPart.ebookFile.ino,
            downloadItemPart.ebookFile.metadata,
            downloadItemPart.ebookFile.ebookFormat,
            true,
            localFileId,
            localFile.contentUrl
          )
          (localLibraryItem.media as Book).ebookFile = ebookFile
          Log.d(tag, "scanInternalDownloadItem: Ebook file added to lli ${localFile.contentUrl}")
        } else {
          val localFileId = DeviceManager.getBase64Id(file.name)
          val localFile = LocalFile(localFileId,file.name,Uri.fromFile(file).toString(),file.getBasePath(ctx),file.absolutePath,file.getSimplePath(ctx),file.mimeType,file.length())

          localLibraryItem.coverAbsolutePath = localFile.absolutePath
          localLibraryItem.coverContentUrl = localFile.contentUrl
          localLibraryItem.localFiles.add(localFile)
        }
      }
    }

    if (audioTracks.isEmpty() && !foundEBookFile) {
      Log.d(tag, "scanDownloadItem did not find any audio tracks or ebook file in folder for ${downloadItem.itemFolderPath}")
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

    val downloadItemScanResult = DownloadItemScanResult(localLibraryItem,null)

    // If library item had media progress then make local media progress and save
    downloadItem.userMediaProgress?.let { mediaProgress ->
      val localMediaProgressId = if (downloadItem.episodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"
      val newLocalMediaProgress = LocalMediaProgress(
        id = localMediaProgressId,
        localLibraryItemId = localLibraryItemId,
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
        episodeId = downloadItem.episodeId)
      Log.d(tag, "scanLibraryItemFolder: Saving local media progress ${newLocalMediaProgress.id} at progress ${newLocalMediaProgress.progress}")
      DeviceManager.dbManager.saveLocalMediaProgress(newLocalMediaProgress)

      downloadItemScanResult.localMediaProgress = newLocalMediaProgress
    }

    DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)

    cb(downloadItemScanResult)
  }

  // Scan item after download and create local library item
  fun scanDownloadItem(downloadItem: DownloadItem, cb: (DownloadItemScanResult?) -> Unit) {
    // If downloading to internal storage handle separately
    if (downloadItem.isInternalStorage) {
      scanInternalDownloadItem(downloadItem, cb)
      return
    }

    val folderDf = DocumentFileCompat.fromUri(ctx, Uri.parse(downloadItem.localFolder.contentUrl))
    val foldersFound =  folderDf?.search(true, DocumentFileType.FOLDER) ?: mutableListOf()

    var itemFolderId = ""
    var itemFolderUrl = ""
    var itemFolderBasePath = ""
    var itemFolderAbsolutePath = ""
    foldersFound.forEach {
      // e.g. absolute path is "storage/emulated/0/Audiobooks/Orson Scott Card/Enders Game"
      //        and itemSubfolder is "Orson Scott Card/Enders Game"
      if (it.getAbsolutePath(ctx).endsWith(downloadItem.itemSubfolder)) {
        itemFolderId = it.id
        itemFolderUrl = it.uri.toString()
        itemFolderBasePath = it.getBasePath(ctx)
        itemFolderAbsolutePath = it.getAbsolutePath(ctx)
      }
    }

    if (itemFolderUrl == "") {
      Log.d(tag, "scanDownloadItem failed to find media folder")
      return cb(null)
    }
    val df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(itemFolderUrl))

    if (df == null) {
      Log.e(tag, "Folder Doc File Invalid ${downloadItem.itemFolderPath}")
      return cb(null)
    }

    val localLibraryItemId = getLocalLibraryItemId(itemFolderId)
    Log.d(tag, "scanDownloadItem starting for ${downloadItem.itemFolderPath} | ${df.uri} | Item Folder Id:$itemFolderId | LLI Id:$localLibraryItemId")

    // Search for files in media item folder
    // m4b files showing as mimeType application/octet-stream on Android 10 and earlier see #154
    val filesFound = df.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*", "video/mp4", "application/*"))
    Log.d(tag, "scanDownloadItem ${filesFound.size} files found in ${downloadItem.itemFolderPath}")

    var localEpisodeId:String? = null
    var localLibraryItem:LocalLibraryItem?
    if (downloadItem.mediaType == "book") {
      localLibraryItem = LocalLibraryItem(localLibraryItemId, downloadItem.localFolder.id, itemFolderBasePath, itemFolderAbsolutePath, itemFolderUrl, false, downloadItem.mediaType, downloadItem.media.getLocalCopy(), mutableListOf(), null, null, true, downloadItem.serverConnectionConfigId, downloadItem.serverAddress, downloadItem.serverUserId, downloadItem.libraryItemId)
    } else {
      // Lookup or create podcast local library item
      localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
      if (localLibraryItem == null) {
        Log.d(tag, "[FolderScanner] Podcast local library item not created yet for ${downloadItem.media.metadata.title}")
        localLibraryItem = LocalLibraryItem(localLibraryItemId, downloadItem.localFolder.id, itemFolderBasePath, itemFolderAbsolutePath, itemFolderUrl, false, downloadItem.mediaType, downloadItem.media.getLocalCopy(), mutableListOf(), null, null, true,downloadItem.serverConnectionConfigId,downloadItem.serverAddress,downloadItem.serverUserId,downloadItem.libraryItemId)
      }
    }

    val audioTracks:MutableList<AudioTrack> = mutableListOf()
    var foundEBookFile = false

    filesFound.forEach { docFile ->
      val itemPart = downloadItem.downloadItemParts.find { itemPart ->
        itemPart.filename == docFile.name
      }
      if (itemPart == null) {
        if (downloadItem.mediaType == "book") { // for books every download item should be a file found
          Log.e(tag, "scanDownloadItem: Item part not found for doc file ${docFile.name} | ${docFile.getAbsolutePath(ctx)} | ${docFile.uri}")
        }
      } else if (itemPart.audioTrack != null) { // Is audio track
        val audioTrackFromServer = itemPart.audioTrack
        Log.d(tag, "scanDownloadItem: Audio Track from Server index = ${audioTrackFromServer.index}")

        val localFileId = DeviceManager.getBase64Id(docFile.id)
        val localFile = LocalFile(localFileId,docFile.name,docFile.uri.toString(),docFile.getBasePath(ctx),docFile.getAbsolutePath(ctx),docFile.getSimplePath(ctx),docFile.mimeType,docFile.length())
        localLibraryItem.localFiles.add(localFile)

        // Create new audio track
        val trackFileMetadata = FileMetadata(docFile.name ?: "", docFile.extension ?: "", docFile.getAbsolutePath(ctx), docFile.getBasePath(ctx), docFile.length())
        val track = AudioTrack(audioTrackFromServer.index, audioTrackFromServer.startOffset, audioTrackFromServer.duration, localFile.filename ?: "", localFile.contentUrl, localFile.mimeType ?: "", trackFileMetadata, true, localFileId, null, audioTrackFromServer.index)
        audioTracks.add(track)

        Log.d(tag, "scanDownloadItem: Created Audio Track with index ${track.index} from local file ${localFile.absolutePath}")

        // Add podcast episodes to library
        itemPart.episode?.let { podcastEpisode ->
          val podcast = localLibraryItem.media as Podcast
          val newEpisode = podcast.addEpisode(track, podcastEpisode)
          localEpisodeId = newEpisode.id
          Log.d(tag, "scanDownloadItem: Added episode to podcast ${podcastEpisode.title} ${track.title} | Track index: ${podcastEpisode.audioTrack?.index}")
        }
      } else if (itemPart.ebookFile != null) { // Ebook
        foundEBookFile = true
        Log.d(tag, "scanDownloadItem: Ebook file found with mimetype=${docFile.mimeType}")
        val localFileId = DeviceManager.getBase64Id(docFile.id)
        val localFile = LocalFile(localFileId,docFile.name,docFile.uri.toString(),docFile.getBasePath(ctx),docFile.getAbsolutePath(ctx),docFile.getSimplePath(ctx),docFile.mimeType,docFile.length())
        localLibraryItem.localFiles.add(localFile)

        val ebookFile = EBookFile(itemPart.ebookFile.ino, itemPart.ebookFile.metadata, itemPart.ebookFile.ebookFormat, true, localFileId, localFile.contentUrl)
        (localLibraryItem.media as Book).ebookFile = ebookFile
        Log.d(tag, "scanDownloadItem: Ebook file added to lli ${localFile.contentUrl}")
      } else { // Cover image
        val localFileId = DeviceManager.getBase64Id(docFile.id)
        val localFile = LocalFile(localFileId,docFile.name,docFile.uri.toString(),docFile.getBasePath(ctx),docFile.getAbsolutePath(ctx),docFile.getSimplePath(ctx),docFile.mimeType,docFile.length())

        localLibraryItem.coverAbsolutePath = localFile.absolutePath
        localLibraryItem.coverContentUrl = localFile.contentUrl
        localLibraryItem.localFiles.add(localFile)
      }
    }

    if (audioTracks.isEmpty() && !foundEBookFile) {
      Log.d(tag, "scanDownloadItem did not find any audio tracks or ebook file in folder for ${downloadItem.itemFolderPath}")
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

    val downloadItemScanResult = DownloadItemScanResult(localLibraryItem,null)

    // If library item had media progress then make local media progress and save
    downloadItem.userMediaProgress?.let { mediaProgress ->
      val localMediaProgressId = if (downloadItem.episodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"
      val newLocalMediaProgress = LocalMediaProgress(
        id = localMediaProgressId,
        localLibraryItemId = localLibraryItemId,
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
        episodeId = downloadItem.episodeId)
      Log.d(tag, "scanLibraryItemFolder: Saving local media progress ${newLocalMediaProgress.id} at progress ${newLocalMediaProgress.progress}")

      DeviceManager.dbManager.saveLocalMediaProgress(newLocalMediaProgress)

      downloadItemScanResult.localMediaProgress = newLocalMediaProgress
    }

    DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)

    cb(downloadItemScanResult)
  }
}
