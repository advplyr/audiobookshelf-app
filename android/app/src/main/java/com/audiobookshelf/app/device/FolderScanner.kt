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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class FolderScanner(var ctx: Context) {
  private val tag = "FolderScanner"

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

      // Match folders found with media items already saved in db
     var existingMediaItems = DeviceManager.dbManager.getLocalMediaItemsInFolder(localFolder.id)

     // Remove existing items no longer there
     existingMediaItems = existingMediaItems.filter { lmi ->
       var fileFound = foldersFound.find { f -> lmi.id == DeviceManager.getBase64Id(f.id)  }
       if (fileFound == null) {
         Log.d(tag, "Existing media item is no longer in file system ${lmi.name}")
         DeviceManager.dbManager.removeLocalMediaItem(lmi.id)
         mediaItemsRemoved++
       }
       fileFound != null
     }

     var mediaItems = mutableListOf<LocalMediaItem>()

     foldersFound.forEach {
       Log.d(tag, "Iterating over Folder Found ${it.name} | ${it.getSimplePath(ctx)} | URI: ${it.uri}")

       var itemFolderName = it.name ?: ""
       var itemId = DeviceManager.getBase64Id(it.id)

       var existingMediaItem = existingMediaItems.find { emi -> emi.id == itemId }
       var existingLocalFiles = existingMediaItem?.localFiles ?: mutableListOf()
       var existingAudioTracks = existingMediaItem?.audioTracks ?: mutableListOf()
       var isNewOrUpdated = existingMediaItem == null

       var audioTracks = mutableListOf<AudioTrack>()
       var localFiles = mutableListOf<LocalFile>()
       var index = 1
       var startOffset = 0.0
       var coverPath:String? = null

       var filesInFolder = it.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))

       var existingLocalFilesRemoved = existingLocalFiles.filter { elf ->
         filesInFolder.find { fif -> DeviceManager.getBase64Id(fif.id) == elf.id } == null // File was not found in media item folder
       }
       if (existingLocalFilesRemoved.isNotEmpty()) {
         Log.d(tag, "${existingLocalFilesRemoved.size} Local files were removed from local media item ${existingMediaItem?.name}")
         isNewOrUpdated = true
       }

       filesInFolder.forEach { file ->
         var mimeType = file?.mimeType ?: ""
         var filename = file?.name ?: ""
         var isAudio = mimeType.startsWith("audio")
         Log.d(tag, "Found $mimeType file $filename in folder $itemFolderName")

         var localFileId = DeviceManager.getBase64Id(file.id)

         var localFile = LocalFile(localFileId,filename,file.uri.toString(),file.getAbsolutePath(ctx),file.getSimplePath(ctx),mimeType,file.length())
         localFiles.add(localFile)

         Log.d(tag, "File attributes Id:${localFileId}|ContentUrl:${localFile.contentUrl}|isDownloadsDocument:${file.isDownloadsDocument}")

         if (isAudio) {
           var audioTrackToAdd:AudioTrack? = null

           var existingAudioTrack = existingAudioTracks.find { eat -> eat.localFileId == localFileId }
           if (existingAudioTrack != null) { // Update existing audio track
             if (existingAudioTrack.index != index) {
               Log.d(tag, "Updating Audio track index from ${existingAudioTrack.index} to $index")
               existingAudioTrack.index = index
               isNewOrUpdated = true
             }
             if (existingAudioTrack.startOffset != startOffset) {
               Log.d(tag, "Updating Audio track startOffset ${existingAudioTrack.startOffset} to $startOffset")
               existingAudioTrack.startOffset = startOffset
               isNewOrUpdated = true
             }
           }

           if (existingAudioTrack == null || forceAudioProbe) {
             Log.d(tag, "Scanning Audio File Path ${localFile.absolutePath}")

             // TODO: Make asynchronous
             var session = FFprobeKit.execute("-i \"${localFile.absolutePath}\" -print_format json -show_format -show_streams -select_streams a -show_chapters -loglevel quiet")

             val audioProbeResult = jacksonObjectMapper().readValue<AudioProbeResult>(session.output)
             Log.d(tag, "Probe Result DATA ${audioProbeResult.duration} | ${audioProbeResult.size} | ${audioProbeResult.title} | ${audioProbeResult.artist}")

             if (existingAudioTrack != null) {
               // Update audio probe data on existing audio track
               existingAudioTrack.audioProbeResult = audioProbeResult
               audioTrackToAdd = existingAudioTrack
             } else {
               // Create new audio track
               var track = AudioTrack(index, startOffset, audioProbeResult.duration, filename, localFile.contentUrl, mimeType, true, localFileId, audioProbeResult)
               audioTrackToAdd = track
             }

             startOffset += audioProbeResult.duration
             index++
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
             isNewOrUpdated = true
           }
           if (existingMediaItem != null && existingMediaItem.coverPath == null) {
             // Existing media item did not have a cover - cover found on scan
             isNewOrUpdated = true
           }

           // First image file use as cover path
           if (coverPath == null) {
             coverPath = localFile.contentUrl
           }
         }
       }

       if (existingMediaItem != null && audioTracks.isEmpty()) {
         Log.d(tag, "Local media item ${existingMediaItem.name} no longer has audio tracks - removing item")
         DeviceManager.dbManager.removeLocalMediaItem(existingMediaItem.id)
         mediaItemsRemoved++
       } else if (existingMediaItem != null && !isNewOrUpdated) {
         Log.d(tag, "Local media item ${existingMediaItem.name} has no updates")
         mediaItemsUpToDate++
       } else if (audioTracks.isNotEmpty()) {
         if (existingMediaItem != null) mediaItemsUpdated++
         else mediaItemsAdded++

        Log.d(tag, "Found local media item named $itemFolderName with ${audioTracks.size} tracks and ${localFiles.size} local files")
         var localMediaItem = LocalMediaItem(itemId, itemFolderName, localFolder.mediaType, localFolder.id, it.uri.toString(), it.getSimplePath(ctx), it.getAbsolutePath(ctx),audioTracks,localFiles,coverPath)
         mediaItems.add(localMediaItem)
       }
     }

     Log.d(tag, "Folder $${localFolder.name} scan Results: $mediaItemsAdded Added | $mediaItemsUpdated Updated | $mediaItemsRemoved Removed | $mediaItemsUpToDate Up-to-date")

     return if (mediaItems.isNotEmpty()) {
       DeviceManager.dbManager.saveLocalMediaItems(mediaItems)

       var folderMediaItems = DeviceManager.dbManager.getLocalMediaItemsInFolder(localFolder.id) // Get all local media items
       FolderScanResult(mediaItemsAdded, mediaItemsUpdated, mediaItemsRemoved, mediaItemsUpToDate, localFolder, folderMediaItems)
     } else {
       Log.d(tag, "No Media Items to save")
       FolderScanResult(mediaItemsAdded, mediaItemsUpdated, mediaItemsRemoved, mediaItemsUpToDate, localFolder, mutableListOf())
     }
   }
}
