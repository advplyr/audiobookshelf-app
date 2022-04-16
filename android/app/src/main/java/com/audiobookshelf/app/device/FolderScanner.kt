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
import com.audiobookshelf.app.plugins.AbsDownloader
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.JSObject

class FolderScanner(var ctx: Context) {
  private val tag = "FolderScanner"
  var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())


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

     var df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(localFolder.contentUrl))

     if (df == null) {
       Log.e(tag, "Folder Doc File Invalid $localFolder.contentUrl")
       return null
     }

     var mediaItemsUpdated = 0
     var mediaItemsAdded = 0
     var mediaItemsRemoved = 0
     var mediaItemsUpToDate = 0

      // Search for files in media item folder
     var foldersFound = df.search(false, DocumentFileType.FOLDER)

      // Match folders found with local library items already saved in db
     var existingLocalLibraryItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(localFolder.id)

     // Remove existing items no longer there
    existingLocalLibraryItems = existingLocalLibraryItems.filter { lli ->
      Log.d(tag, "scanForMediaItems Checking Existing LLI ${lli.id}")
       var fileFound = foldersFound.find { f -> lli.id == getLocalLibraryItemId(f.id) }
       if (fileFound == null) {
         Log.d(tag, "Existing local library item is no longer in file system ${lli.media.metadata.title}")
         DeviceManager.dbManager.removeLocalLibraryItem(lli.id)
         mediaItemsRemoved++
       }
       fileFound != null
     }

     foldersFound.forEach { itemFolder ->
       Log.d(tag, "Iterating over Folder Found ${itemFolder.name} | ${itemFolder.getSimplePath(ctx)} | URI: ${itemFolder.uri}")
       var existingItem = existingLocalLibraryItems.find { emi -> emi.id == getLocalLibraryItemId(itemFolder.id) }

       var result = scanLibraryItemFolder(itemFolder, localFolder, existingItem, forceAudioProbe)

       if (result == ItemScanResult.REMOVED) mediaItemsRemoved++
       else if (result == ItemScanResult.UPDATED) mediaItemsUpdated++
       else if (result == ItemScanResult.ADDED) mediaItemsAdded++
       else mediaItemsUpToDate++
     }

     Log.d(tag, "Folder $${localFolder.name} scan Results: $mediaItemsAdded Added | $mediaItemsUpdated Updated | $mediaItemsRemoved Removed | $mediaItemsUpToDate Up-to-date")

     return if (mediaItemsAdded > 0 || mediaItemsUpdated > 0 || mediaItemsRemoved > 0) {
       var folderLibraryItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(localFolder.id) // Get all local media items
       FolderScanResult(mediaItemsAdded, mediaItemsUpdated, mediaItemsRemoved, mediaItemsUpToDate, localFolder, folderLibraryItems)
     } else {
       Log.d(tag, "No Media Items to save")
       FolderScanResult(mediaItemsAdded, mediaItemsUpdated, mediaItemsRemoved, mediaItemsUpToDate, localFolder, mutableListOf())
     }
   }

  fun scanLibraryItemFolder(itemFolder:DocumentFile, localFolder:LocalFolder, existingItem:LocalLibraryItem?, forceAudioProbe:Boolean):ItemScanResult {
    var itemFolderName = itemFolder.name ?: ""
    var itemId = getLocalLibraryItemId(itemFolder.id)

    var existingLocalFiles = existingItem?.localFiles ?: mutableListOf()
    var existingAudioTracks = existingItem?.media?.getAudioTracks() ?: mutableListOf()
    var isNewOrUpdated = existingItem == null

    var audioTracks = mutableListOf<AudioTrack>()
    var localFiles = mutableListOf<LocalFile>()
    var index = 1
    var startOffset = 0.0
    var coverContentUrl:String? = null
    var coverAbsolutePath:String? = null

    var filesInFolder = itemFolder.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))
    var isPodcast = localFolder.mediaType == "podcast"

    var existingLocalFilesRemoved = existingLocalFiles.filter { elf ->
      filesInFolder.find { fif -> DeviceManager.getBase64Id(fif.id) == elf.id } == null // File was not found in media item folder
    }
    if (existingLocalFilesRemoved.isNotEmpty()) {
      Log.d(tag, "${existingLocalFilesRemoved.size} Local files were removed from local media item ${existingItem?.media?.metadata?.title}")
      isNewOrUpdated = true
    }

    filesInFolder.forEach { file ->
      var mimeType = file?.mimeType ?: ""
      var filename = file?.name ?: ""
      var isAudio = mimeType.startsWith("audio")
      Log.d(tag, "Found $mimeType file $filename in folder $itemFolderName")

      var localFileId = DeviceManager.getBase64Id(file.id)

      var localFile = LocalFile(localFileId,filename,file.uri.toString(),file.getBasePath(ctx), file.getAbsolutePath(ctx),file.getSimplePath(ctx),mimeType,file.length())
      localFiles.add(localFile)

      Log.d(tag, "File attributes Id:${localFileId}|ContentUrl:${localFile.contentUrl}|isDownloadsDocument:${file.isDownloadsDocument}")

      if (isAudio) {
        var audioTrackToAdd:AudioTrack? = null

        var existingAudioTrack = existingAudioTracks.find { eat -> eat.localFileId == localFileId }
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
          var audioProbeResult = probeAudioFile(localFile.absolutePath)

          if (existingAudioTrack != null) {
            // Update audio probe data on existing audio track
            existingAudioTrack.audioProbeResult = audioProbeResult
            audioTrackToAdd = existingAudioTrack
          } else {
            // Create new audio track
            var track = AudioTrack(index, startOffset, audioProbeResult.duration, filename, localFile.contentUrl, mimeType, null, true, localFileId, audioProbeResult, null)
            audioTrackToAdd = track
          }

          startOffset += audioProbeResult.duration
          isNewOrUpdated = true
        } else {
          audioTrackToAdd = existingAudioTrack
        }

        startOffset += audioTrackToAdd.duration
        index++
        audioTracks.add(audioTrackToAdd)
      } else {
        var existingLocalFile = existingLocalFiles.find { elf -> elf.id == localFileId }

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

    if (existingItem != null && audioTracks.isEmpty()) {
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
    } else if (audioTracks.isNotEmpty()) {
      Log.d(tag, "Found local media item named $itemFolderName with ${audioTracks.size} tracks and ${localFiles.size} local files")
      var localMediaItem = LocalMediaItem(itemId, itemFolderName, localFolder.mediaType, localFolder.id, itemFolder.uri.toString(), itemFolder.getSimplePath(ctx), itemFolder.getBasePath(ctx), itemFolder.getAbsolutePath(ctx),audioTracks,localFiles,coverContentUrl,coverAbsolutePath)
      var localLibraryItem = localMediaItem.getLocalLibraryItem()
      DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)
      return ItemScanResult.ADDED
    } else {
      return ItemScanResult.UPTODATE
    }
  }

  // Scan item after download and create local library item
  fun scanDownloadItem(downloadItem: AbsDownloader.DownloadItem):LocalLibraryItem? {
    var folderDf = DocumentFileCompat.fromUri(ctx, Uri.parse(downloadItem.localFolder.contentUrl))
    var foldersFound =  folderDf?.search(false, DocumentFileType.FOLDER) ?: mutableListOf()

    var itemFolderId = ""
    var itemFolderUrl = ""
    var itemFolderBasePath = ""
    var itemFolderAbsolutePath = ""
    foldersFound.forEach {
      if (it.name == downloadItem.itemTitle) {
        itemFolderId = it.id
        itemFolderUrl = it.uri.toString()
        itemFolderBasePath = it.getBasePath(ctx)
        itemFolderAbsolutePath = it.getAbsolutePath(ctx)
      }
    }

    if (itemFolderUrl == "") {
      Log.d(tag, "scanDownloadItem failed to find media folder")
      return null
    }
    var df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(itemFolderUrl))

    if (df == null) {
      Log.e(tag, "Folder Doc File Invalid ${downloadItem.itemFolderPath}")
      return null
    }

    var localLibraryItemId = getLocalLibraryItemId(itemFolderId)
    Log.d(tag, "scanDownloadItem starting for ${downloadItem.itemFolderPath} | ${df.uri} | Item Folder Id:$itemFolderId | LLI Id:$localLibraryItemId")

    // Search for files in media item folder
    var filesFound = df.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))
    Log.d(tag, "scanDownloadItem ${filesFound.size} files found in ${downloadItem.itemFolderPath}")

    var localLibraryItem:LocalLibraryItem? = null
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

      var audioTracks:MutableList<AudioTrack> = mutableListOf()

      filesFound.forEach { docFile ->
        var itemPart = downloadItem.downloadItemParts.find { itemPart ->
          itemPart.filename == docFile.name
        }
        if (itemPart == null) {
          if (downloadItem.mediaType == "book") { // for books every download item should be a file found
            Log.e(tag, "scanDownloadItem: Item part not found for doc file ${docFile.name} | ${docFile.getAbsolutePath(ctx)} | ${docFile.uri}")
          }
        } else if (itemPart.audioTrack != null) { // Is audio track
          var audioTrackFromServer = itemPart.audioTrack
          Log.d(tag, "scanDownloadItem: Audio Track from Server index = ${audioTrackFromServer?.index}")

          var localFileId = DeviceManager.getBase64Id(docFile.id)
          var localFile = LocalFile(localFileId,docFile.name,docFile.uri.toString(),docFile.getBasePath(ctx),docFile.getAbsolutePath(ctx),docFile.getSimplePath(ctx),docFile.mimeType,docFile.length())
          localLibraryItem.localFiles.add(localFile)

          // TODO: Make asynchronous
          var audioProbeResult = probeAudioFile(localFile.absolutePath)

          // Create new audio track
          var track = AudioTrack(audioTrackFromServer?.index ?: -1, audioTrackFromServer?.startOffset ?: 0.0, audioProbeResult.duration, localFile.filename ?: "", localFile.contentUrl, localFile.mimeType ?: "", null, true, localFileId, audioProbeResult, audioTrackFromServer?.index ?: -1)
          audioTracks.add(track)

          Log.d(tag, "scanDownloadItem: Created Audio Track with index ${track.index} from local file ${localFile.absolutePath}")

          // Add podcast episodes to library
          itemPart.episode?.let { podcastEpisode ->
            var podcast = localLibraryItem.media as Podcast
            podcast.addEpisode(track, podcastEpisode)
            Log.d(tag, "scanDownloadItem: Added episode to podcast ${podcastEpisode.title} ${track.title} | Track index: ${podcastEpisode.audioTrack?.index}")
          }
        } else { // Cover image
          var localFileId = DeviceManager.getBase64Id(docFile.id)
          var localFile = LocalFile(localFileId,docFile.name,docFile.uri.toString(),docFile.getBasePath(ctx),docFile.getAbsolutePath(ctx),docFile.getSimplePath(ctx),docFile.mimeType,docFile.length())

          localLibraryItem.coverAbsolutePath = localFile.absolutePath
          localLibraryItem.coverContentUrl = localFile.contentUrl
          localLibraryItem.localFiles.add(localFile)
        }
      }

    if (audioTracks.isEmpty()) {
      Log.d(tag, "scanDownloadItem did not find any audio tracks in folder for ${downloadItem.itemFolderPath}")
      return null
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

    DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)

    return localLibraryItem
  }

  fun scanLocalLibraryItem(localLibraryItem:LocalLibraryItem, forceAudioProbe:Boolean):LocalLibraryItemScanResult? {
    var df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(localLibraryItem.contentUrl))

    if (df == null) {
      Log.e(tag, "Item Folder Doc File Invalid ${localLibraryItem.absolutePath}")
      return null
    }
    Log.d(tag, "scanLocalLibraryItem starting for ${localLibraryItem.absolutePath} | ${df.uri}")

    var wasUpdated = false

    // Search for files in media item folder
    var filesFound = df.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))
    Log.d(tag, "scanLocalLibraryItem ${filesFound.size} files found in ${localLibraryItem.absolutePath}")

    filesFound.forEach {
      try {
        Log.d(tag, "Checking file found ${it.name} | ${it.id}")
      }catch(e:Exception) {
        Log.d(tag, "Check file found exception", e)
      }
    }

    var existingAudioTracks = localLibraryItem.media.getAudioTracks()

    // Remove any files no longer found in library item folder
    var existingLocalFileIds = localLibraryItem.localFiles.map { it.id }
    existingLocalFileIds.forEach { localFileId ->
      Log.d(tag, "Checking local file id is there $localFileId")
      if (filesFound.find { DeviceManager.getBase64Id(it.id) == localFileId } == null) {
        Log.d(tag, "scanLocalLibraryItem file $localFileId was removed from ${localLibraryItem.absolutePath}")
        localLibraryItem.localFiles.removeIf { it.id == localFileId }

        if (existingAudioTracks.find { it.localFileId == localFileId } != null) {
          Log.d(tag, "scanLocalLibraryItem audio track file ${localFileId} was removed from ${localLibraryItem.absolutePath}")
          localLibraryItem.media.removeAudioTrack(localFileId)
        }
        wasUpdated = true
      }
    }

    filesFound.forEach { docFile ->
      var localFileId = DeviceManager.getBase64Id(docFile.id)
      var existingLocalFile = localLibraryItem.localFiles.find { it.id == localFileId }

      if (existingLocalFile == null || (existingLocalFile.isAudioFile() && forceAudioProbe)) {

        var localFile = existingLocalFile ?: LocalFile(localFileId,docFile.name,docFile.uri.toString(),docFile.getBasePath(ctx), docFile.getAbsolutePath(ctx),docFile.getSimplePath(ctx),docFile.mimeType,docFile.length())
        if (existingLocalFile == null) {
          localLibraryItem.localFiles.add(localFile)
          Log.d(tag, "scanLocalLibraryItem new file found ${localFile.filename}")
        }

        if (localFile.isAudioFile()) {
          // TODO: Make asynchronous
          var audioProbeResult = probeAudioFile(localFile.absolutePath)

          var existingTrack = existingAudioTracks.find { audioTrack ->
            audioTrack.localFileId == localFile.id
          }

          if (existingTrack == null) {
            // Create new audio track
              var lastTrack = existingAudioTracks.lastOrNull()
            var startOffset = (lastTrack?.startOffset ?: 0.0) + (lastTrack?.duration ?: 0.0)
            var track = AudioTrack(existingAudioTracks.size, startOffset, audioProbeResult.duration, localFile.filename ?: "", localFile.contentUrl, localFile.mimeType ?: "", null, true, localFileId, audioProbeResult, null)
            localLibraryItem.media.addAudioTrack(track)
            wasUpdated = true
          } else {
            existingTrack.audioProbeResult = audioProbeResult
            // TODO: Update data found from probe
            wasUpdated = true
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

  fun probeAudioFile(absolutePath:String):AudioProbeResult {
    var session = FFprobeKit.execute("-i \"${absolutePath}\" -print_format json -show_format -show_streams -select_streams a -show_chapters -loglevel quiet")
    Log.d(tag, "FFprobe output ${JSObject(session.output)}")

    val audioProbeResult = jacksonMapper.readValue<AudioProbeResult>(session.output)
    Log.d(tag, "Probe Result DATA ${audioProbeResult.duration} | ${audioProbeResult.size} | ${audioProbeResult.title} | ${audioProbeResult.artist}")
    return audioProbeResult
  }
}
