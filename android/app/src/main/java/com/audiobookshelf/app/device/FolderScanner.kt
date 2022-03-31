package com.audiobookshelf.app.device

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.*
import com.arthenica.ffmpegkit.FFprobeKit
import com.audiobookshelf.app.data.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class FolderScanner(var ctx: Context) {
  private val tag = "FolderScanner"

   fun scanForMediaItems(folderUrl: String, mediaType:String):FolderScanResult? {
     var df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(folderUrl))

     if (df == null) {
       Log.e(tag, "Folder Doc File Invalid $folderUrl")
       return null
     }

     var foldersFound = df.search(false, DocumentFileType.FOLDER)

     var mediaItems = mutableListOf<LocalMediaItem>()

     foldersFound.forEach {
       Log.d(tag, "Iterating over Folder Found ${it.name} | ${it.getSimplePath(ctx)} | URI: ${it.uri}")
       var folderName = it.name ?: ""

       var audioTracks = mutableListOf<AudioTrack>()
       var localFiles = mutableListOf<LocalFile>()
       var index = 1
       var startOffset = 0.0
       var coverPath:String? = null

       var filesInFolder = it.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))
       filesInFolder.forEach { it2 ->
         var mimeType = it2?.mimeType ?: ""
         var filename = it2?.name ?: ""
         var isAudio = mimeType.startsWith("audio")
         Log.d(tag, "Found $mimeType file $filename in folder $folderName")

         var localFile = LocalFile(it2.id,it2.name,it2.uri.toString(),it2.getAbsolutePath(ctx),it2.getSimplePath(ctx),it2.mimeType,it2.length())
         localFiles.add(localFile)

         Log.d(tag, "File attributes Id:${it2.id}|ContentUrl:${localFile.contentUrl}|isDownloadsDocument:${it2.isDownloadsDocument}")

         if (isAudio) {
           Log.d(tag, "Scanning Audio File Path ${localFile.absolutePath}")

           // TODO: Make asynchronous
           var session = FFprobeKit.execute("-i \"${localFile.absolutePath}\" -print_format json -show_format -show_streams -select_streams a -show_chapters -loglevel quiet")
           var sessionData = session.output
           Log.d(tag, "AFTER FFPROBE STRING $sessionData")

           val mapper = jacksonObjectMapper()
           val audioProbeResult = mapper.readValue<AudioProbeResult>(sessionData)
           Log.d(tag, "Probe Result DATA ${audioProbeResult.duration} | ${audioProbeResult.size} | ${audioProbeResult.title} | ${audioProbeResult.artist}")

           var track = AudioTrack(index, startOffset, audioProbeResult.duration, filename, localFile.contentUrl, mimeType, true, audioProbeResult)
           audioTracks.add(track)
           startOffset += audioProbeResult.duration
         } else {
           // First image file use as cover path
           if (coverPath == null) {
             coverPath = localFile.absolutePath
           }
         }
       }
       if (audioTracks.size > 0) {
        Log.d(tag, "Found local media item named $folderName with ${audioTracks.size} tracks")
         var localMediaItem = LocalMediaItem(folderName, it.uri.toString(), it.getSimplePath(ctx), it.getAbsolutePath(ctx),audioTracks,localFiles,coverPath)
         mediaItems.add(localMediaItem)
       }
     }

     return if (mediaItems.size > 0) {
       Log.d(tag, "Found ${mediaItems.size} Media Items")
       FolderScanResult(df.name, df.getAbsolutePath(ctx), mediaType, df.uri.toString(), mediaItems)
     } else {
       Log.d(tag, "No Media Items Found")
       null
     }
   }
}
