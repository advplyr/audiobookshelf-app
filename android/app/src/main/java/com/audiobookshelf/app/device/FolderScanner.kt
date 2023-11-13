package com.audiobookshelf.app.device

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.*
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Level
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.JSObject
import org.json.JSONException
import java.io.File

class FolderScanner(var ctx: Context) {
  private val tag = "FolderScanner"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  data class DownloadItemScanResult(val localLibraryItem:LocalLibraryItem, var localMediaProgress:LocalMediaProgress?)

  private fun getLocalLibraryItemId(mediaItemId:String):String {
    return "local_" + DeviceManager.getBase64Id(mediaItemId)
  }

  enum class ItemScanResult {
    ADDED, REMOVED, UPDATED, UPTODATE
  }

  // TODO: CLEAN this monster! Divide into bite-size methods
   fun scanForMediaItems(localFolder:LocalFolder, forceAudioProbe:Boolean):FolderScanResult? {
    FFmpegKitConfig.enableLogCallback { log ->
      if (log.level != Level.AV_LOG_STDERR) { // STDERR is filled with junk
        Log.d(tag, "FFmpeg-Kit Log: (${log.level}) ${log.message}")
      }
    }

     val df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(localFolder.contentUrl))

     if (df == null) {
       Log.e(tag, "Folder Doc File Invalid $localFolder.contentUrl")
       return null
     }

     var mediaItemsUpdated = 0
     var mediaItemsAdded = 0
     var mediaItemsRemoved = 0
     var mediaItemsUpToDate = 0

      // Search for files in media item folder
     val foldersFound = df.search(true, DocumentFileType.FOLDER)

      // Match folders found with local library items already saved in db
     var existingLocalLibraryItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(localFolder.id)

     // Remove existing items no longer there
    existingLocalLibraryItems = existingLocalLibraryItems.filter { lli ->
      Log.d(tag, "scanForMediaItems Checking Existing LLI ${lli.id}")
       val fileFound = foldersFound.find { f -> lli.id == getLocalLibraryItemId(f.id) }
       if (fileFound == null) {
         Log.d(tag, "Existing local library item is no longer in file system ${lli.media.metadata.title}")
         DeviceManager.dbManager.removeLocalLibraryItem(lli.id)
         mediaItemsRemoved++
       }
       fileFound != null
     }

     foldersFound.forEach { itemFolder ->
       Log.d(tag, "Iterating over Folder Found ${itemFolder.name} | ${itemFolder.getSimplePath(ctx)} | URI: ${itemFolder.uri}")
       val existingItem = existingLocalLibraryItems.find { emi -> emi.id == getLocalLibraryItemId(itemFolder.id) }

       val filesInFolder = itemFolder.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*", "video/mp4", "application/*"))

       // Do not scan folders that have no media items and not an existing item already
       if (existingItem != null || filesInFolder.isNotEmpty()) {
         when (scanLibraryItemFolder(itemFolder, filesInFolder, localFolder, existingItem, forceAudioProbe)) {
           ItemScanResult.REMOVED -> mediaItemsRemoved++
           ItemScanResult.UPDATED -> mediaItemsUpdated++
           ItemScanResult.ADDED -> mediaItemsAdded++
           else -> mediaItemsUpToDate++
         }
       }
     }

     Log.d(tag, "Folder $${localFolder.name} scan Results: $mediaItemsAdded Added | $mediaItemsUpdated Updated | $mediaItemsRemoved Removed | $mediaItemsUpToDate Up-to-date")

     return if (mediaItemsAdded > 0 || mediaItemsUpdated > 0 || mediaItemsRemoved > 0) {
       val folderLibraryItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(localFolder.id) // Get all local media items
       FolderScanResult(mediaItemsAdded, mediaItemsUpdated, mediaItemsRemoved, mediaItemsUpToDate, localFolder, folderLibraryItems)
     } else {
       Log.d(tag, "No Media Items to save")
       FolderScanResult(mediaItemsAdded, mediaItemsUpdated, mediaItemsRemoved, mediaItemsUpToDate, localFolder, mutableListOf())
     }
   }

  private fun scanLibraryItemFolder(itemFolder:DocumentFile, filesInFolder:List<DocumentFile>, localFolder:LocalFolder, existingItem:LocalLibraryItem?, forceAudioProbe:Boolean):ItemScanResult {
    val itemFolderName = itemFolder.name ?: ""
    val itemId = getLocalLibraryItemId(itemFolder.id)

    val existingLocalFiles = existingItem?.localFiles ?: mutableListOf()
    val existingAudioTracks = existingItem?.media?.getAudioTracks() ?: mutableListOf()
    var isNewOrUpdated = existingItem == null

    val audioTracks = mutableListOf<AudioTrack>()
    val localFiles = mutableListOf<LocalFile>()
    var index = 1
    var startOffset = 0.0
    var coverContentUrl:String? = null
    var coverAbsolutePath:String? = null
    var hasEBookFile = false
    var newEBookFile:EBookFile? = null

    val existingLocalFilesRemoved = existingLocalFiles.filter { elf ->
      filesInFolder.find { fif -> DeviceManager.getBase64Id(fif.id) == elf.id } == null // File was not found in media item folder
    }
    if (existingLocalFilesRemoved.isNotEmpty()) {
      Log.d(tag, "${existingLocalFilesRemoved.size} Local files were removed from local media item ${existingItem?.media?.metadata?.title}")
      isNewOrUpdated = true
    }

    filesInFolder.forEach { file ->
      val mimeType = file.mimeType ?: ""
      val filename = file.name ?: ""
      Log.d(tag, "Found $mimeType file $filename in folder $itemFolderName")

      val localFileId = DeviceManager.getBase64Id(file.id)

      val localFile = LocalFile(localFileId,filename,file.uri.toString(),file.getBasePath(ctx), file.getAbsolutePath(ctx),file.getSimplePath(ctx),mimeType,file.length())
      localFiles.add(localFile)

      Log.d(tag, "File attributes Id:${localFileId}|ContentUrl:${localFile.contentUrl}|isDownloadsDocument:${file.isDownloadsDocument}")

      if (localFile.isAudioFile()) {
        val audioTrackToAdd:AudioTrack?

        val existingAudioTrack = existingAudioTracks.find { eat -> eat.localFileId == localFileId }
        if (existingAudioTrack != null) { // Update existing audio track
          if (existingAudioTrack.index != index) {
            Log.d(tag, "scanLibraryItemFolder Updating Audio track index from ${existingAudioTrack.index} to $index")
            existingAudioTrack.index = index
            isNewOrUpdated = true
          }
          if (existingAudioTrack.startOffset != startOffset) {
            Log.d(tag, "scanLibraryItemFolder Updating Audio track startOffset ${existingAudioTrack.startOffset} to $startOffset")
            existingAudioTrack.startOffset = startOffset
            isNewOrUpdated = true
          }
        }

        if (existingAudioTrack == null || forceAudioProbe) {
          Log.d(tag, "scanLibraryItemFolder Scanning Audio File Path ${localFile.absolutePath} | ForceAudioProbe=${forceAudioProbe}")

          // TODO: Make asynchronous
          val audioProbeResult = probeAudioFile(localFile.absolutePath)

          if (existingAudioTrack != null) {
            // Update audio probe data on existing audio track
            existingAudioTrack.audioProbeResult = audioProbeResult
            audioTrackToAdd = existingAudioTrack
          } else {
            // Create new audio track
            val track = AudioTrack(index, startOffset, audioProbeResult?.duration ?: 0.0, filename, localFile.contentUrl, mimeType, null, true, localFileId, audioProbeResult, null)
            audioTrackToAdd = track
          }

          startOffset += audioProbeResult?.duration ?: 0.0
          isNewOrUpdated = true
        } else {
          audioTrackToAdd = existingAudioTrack
        }

        startOffset += audioTrackToAdd.duration
        index++
        audioTracks.add(audioTrackToAdd)
      } else if (localFile.isEBookFile()) {
        val existingLocalFile = existingLocalFiles.find { elf -> elf.id == localFileId }

        if (localFolder.mediaType == "book") {
          hasEBookFile = true
          if (existingLocalFile == null) {
            newEBookFile = EBookFile(localFileId, null, localFile.getEBookFormat() ?: "", true, localFileId, localFile.contentUrl)
          }
        }
      } else {
        val existingLocalFile = existingLocalFiles.find { elf -> elf.id == localFileId }

        if (existingLocalFile == null) {
          Log.d(tag, "scanLibraryItemFolder new local file found ${localFile.absolutePath}")
          isNewOrUpdated = true
        }
        if (existingItem != null && existingItem.coverContentUrl == null) {
          // Existing media item did not have a cover - cover found on scan
          Log.d(tag, "scanLibraryItemFolder setting cover ${localFile.absolutePath}")
          isNewOrUpdated = true
          existingItem.coverAbsolutePath = localFile.absolutePath
          existingItem.coverContentUrl = localFile.contentUrl
          existingItem.media.coverPath = localFile.absolutePath
        }

        // First image file use as cover path
        if (coverContentUrl == null) {
          coverContentUrl = localFile.contentUrl
          coverAbsolutePath = localFile.absolutePath
        }
      }
    }

    if (existingItem != null && audioTracks.isEmpty() && !hasEBookFile) {
      Log.d(tag, "Local library item ${existingItem.media.metadata.title} no longer has audio tracks - removing item")
      DeviceManager.dbManager.removeLocalLibraryItem(existingItem.id)
      return ItemScanResult.REMOVED
    } else if (existingItem != null && !isNewOrUpdated) {
      Log.d(tag, "Local library item ${existingItem.media.metadata.title} has no updates")
      return ItemScanResult.UPTODATE
    } else if (existingItem != null) {
      Log.d(tag, "Updating local library item ${existingItem.media.metadata.title}")
      existingItem.updateFromScan(audioTracks,localFiles)
      DeviceManager.dbManager.saveLocalLibraryItem(existingItem)
      return ItemScanResult.UPDATED
    } else if (audioTracks.isNotEmpty() || newEBookFile != null) {
      Log.d(tag, "Found local media item named $itemFolderName with ${audioTracks.size} tracks and ${localFiles.size} local files")
      val localMediaItem = LocalMediaItem(itemId, itemFolderName, localFolder.mediaType, localFolder.id, itemFolder.uri.toString(), itemFolder.getSimplePath(ctx), itemFolder.getBasePath(ctx), itemFolder.getAbsolutePath(ctx),audioTracks,newEBookFile,localFiles,coverContentUrl,coverAbsolutePath)
      val localLibraryItem = localMediaItem.getLocalLibraryItem()
      DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)
      return ItemScanResult.ADDED
    } else {
      return ItemScanResult.UPTODATE
    }
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

          // Create new audio track
          val track = AudioTrack(
            audioTrackFromServer.index,
            audioTrackFromServer.startOffset,
            audioTrackFromServer.duration,
            localFile.filename ?: "",
            localFile.contentUrl,
            localFile.mimeType ?: "",
            null,
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
        val track = AudioTrack(audioTrackFromServer.index, audioTrackFromServer.startOffset, audioTrackFromServer.duration, localFile.filename ?: "", localFile.contentUrl, localFile.mimeType ?: "", null, true, localFileId, null, audioTrackFromServer.index)
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

  fun scanLocalLibraryItem(localLibraryItem:LocalLibraryItem, forceAudioProbe:Boolean):LocalLibraryItemScanResult? {
    val df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(localLibraryItem.contentUrl))

    if (df == null) {
      Log.e(tag, "Item Folder Doc File Invalid ${localLibraryItem.absolutePath}")
      return null
    }
    Log.d(tag, "scanLocalLibraryItem starting for ${localLibraryItem.absolutePath} | ${df.uri}")

    var wasUpdated = false

    // Search for files in media item folder
    val filesFound = df.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*", "video/mp4", "application/*"))
    Log.d(tag, "scanLocalLibraryItem ${filesFound.size} files found in ${localLibraryItem.absolutePath}")

    filesFound.forEach {
      try {
        Log.d(tag, "Checking file found ${it.name} | ${it.id}")
      }catch(e:Exception) {
        Log.d(tag, "Check file found exception", e)
      }
    }

    val existingAudioTracks = localLibraryItem.media.getAudioTracks()

    // Remove any files no longer found in library item folder
    val existingLocalFileIds = localLibraryItem.localFiles.map { it.id }
    existingLocalFileIds.forEach { localFileId ->
      Log.d(tag, "Checking local file id is there $localFileId")
      if (filesFound.find { DeviceManager.getBase64Id(it.id) == localFileId } == null) {
        Log.d(tag, "scanLocalLibraryItem file $localFileId was removed from ${localLibraryItem.absolutePath}")
        localLibraryItem.localFiles.removeIf { it.id == localFileId }

        if (existingAudioTracks.find { it.localFileId == localFileId } != null) {
          Log.d(tag, "scanLocalLibraryItem audio track file $localFileId was removed from ${localLibraryItem.absolutePath}")
          localLibraryItem.media.removeAudioTrack(localFileId)
        }
        wasUpdated = true
      }
    }

    filesFound.forEach { docFile ->
      val localFileId = DeviceManager.getBase64Id(docFile.id)
      val existingLocalFile = localLibraryItem.localFiles.find { it.id == localFileId }

      if (existingLocalFile == null || (existingLocalFile.isAudioFile() && forceAudioProbe)) {

        val localFile = existingLocalFile ?: LocalFile(localFileId,docFile.name,docFile.uri.toString(),docFile.getBasePath(ctx), docFile.getAbsolutePath(ctx),docFile.getSimplePath(ctx),docFile.mimeType,docFile.length())
        if (existingLocalFile == null) {
          localLibraryItem.localFiles.add(localFile)
          Log.d(tag, "scanLocalLibraryItem new file found ${localFile.filename}")
        }

        if (localFile.isAudioFile()) {
          // TODO: Make asynchronous
          val audioProbeResult = probeAudioFile(localFile.absolutePath)

          val existingTrack = existingAudioTracks.find { audioTrack ->
            audioTrack.localFileId == localFileId
          }

          if (existingTrack == null) {
            // Create new audio track
            val lastTrack = existingAudioTracks.lastOrNull()
            val startOffset = (lastTrack?.startOffset ?: 0.0) + (lastTrack?.duration ?: 0.0)
            val track = AudioTrack(existingAudioTracks.size, startOffset, audioProbeResult?.duration ?: 0.0, localFile.filename ?: "", localFile.contentUrl, localFile.mimeType ?: "", null, true, localFileId, audioProbeResult, null)
            localLibraryItem.media.addAudioTrack(track)
            Log.d(tag, "Added New Audio Track ${track.title}")
            wasUpdated = true
          } else {
            existingTrack.audioProbeResult = audioProbeResult
            // TODO: Update data found from probe

            Log.d(tag, "Updated Audio Track Probe Data ${existingTrack.title}")

            wasUpdated = true
          }
        } else if (localFile.isEBookFile()) {
          if (localLibraryItem.mediaType == "book") {
            val existingEbookFile = (localLibraryItem.media as Book).ebookFile
            if (existingEbookFile == null || existingEbookFile.localFileId != localFileId) {
              val ebookFile = EBookFile(localFileId, null, localFile.getEBookFormat() ?: "", true, localFileId, localFile.contentUrl)
              (localLibraryItem.media as Book).ebookFile = ebookFile
              Log.d(tag, "scanLocalLibraryItem: Ebook file added to lli ${localFile.contentUrl}")
              wasUpdated = true
            }
          }
        } else { // Check if cover is empty
          if (localLibraryItem.coverContentUrl == null) {
            Log.d(tag, "scanLocalLibraryItem setting cover for ${localLibraryItem.media.metadata.title}")
            localLibraryItem.coverContentUrl = localFile.contentUrl
            localLibraryItem.coverAbsolutePath = localFile.absolutePath
            wasUpdated = true
          }
        }
      }
    }

    if (wasUpdated) {
      Log.d(tag, "Local library item was updated - saving it")
      DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)
    } else {
      Log.d(tag, "Local library item was up-to-date")
    }
    return LocalLibraryItemScanResult(wasUpdated, localLibraryItem)
  }

  private fun probeAudioFile(absolutePath:String):AudioProbeResult? {
    val session = FFprobeKit.execute("-i \"${absolutePath}\" -print_format json -show_format -show_streams -select_streams a -show_chapters -loglevel quiet")

    var probeObject:JSObject? = null
    try {
      probeObject = JSObject(session.output)
    } catch(error:JSONException) {
      Log.e(tag, "Failed to parse probe result $error")
    }

    Log.d(tag, "FFprobe output $probeObject")
    return if (probeObject == null || !probeObject.has("streams")) { // Check if output is empty
      Log.d(tag, "probeAudioFile Probe audio file $absolutePath failed or invalid")
      null
    } else {
      val audioProbeResult = jacksonMapper.readValue<AudioProbeResult>(session.output)
      Log.d(tag, "Probe Result DATA ${audioProbeResult.duration} | ${audioProbeResult.size} | ${audioProbeResult.title} | ${audioProbeResult.artist}")
      audioProbeResult
    }
  }
}
