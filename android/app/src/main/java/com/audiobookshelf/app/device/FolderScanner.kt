package com.audiobookshelf.app.device

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.*
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.Level
import com.audiobookshelf.app.data.AudioProbeResult
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.LocalMediaItem
import com.audiobookshelf.app.data.PlaybackSession
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue


class FolderScanner(var ctx: Context) {
  private val tag = "FolderScanner"

   fun scanForAudiobooks(folderUrl: String):MutableList<LocalMediaItem> {
     var df: DocumentFile? = DocumentFileCompat.fromUri(ctx, Uri.parse(folderUrl))

     if (df == null) {
       Log.e(tag, "Folder Doc File Invalid $folderUrl")
       return mutableListOf()
     }

     var mediaFolders = mutableListOf<LocalMediaItem>()
     var foldersFound = df.search(false, DocumentFileType.FOLDER)

     foldersFound.forEach {
       Log.d(tag, "Iterating over Folder Found ${it.name} | ${it.getSimplePath(ctx)} | URI: ${it.uri}")
       var folderName = it.name ?: ""
       var mediaFiles = mutableListOf<LocalMediaItem>()

       var audioTracks = mutableListOf<AudioTrack>()
       var index = 1

       var filesInFolder = it.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))
       filesInFolder.forEach { it2 ->
         var mimeType = it2?.mimeType ?: ""
         var filename = it2?.name ?: ""
         var isAudio = mimeType.startsWith("audio")
         Log.d(tag, "Found $mimeType file $filename in folder $folderName")

         if (isAudio) {
           var absolutePath = it2.getAbsolutePath(ctx)
           Log.d(tag, "Audio File Path $absolutePath")

           // TODO: Make asynchronous
           var session = FFprobeKit.execute("-i \"$absolutePath\" -print_format json -show_format -show_streams -select_streams a -show_chapters -loglevel quiet")
           var sessionData = session.output
           Log.d(tag, "AFTER FFPROBE STRING $sessionData")

           val mapper = jacksonObjectMapper()
           val audioProbeResult = mapper.readValue<AudioProbeResult>(sessionData)
           Log.d(tag, "Probe Result DATA ${audioProbeResult.duration} | ${audioProbeResult.size} | ${audioProbeResult.title} | ${audioProbeResult.artist}")

           var track = AudioTrack(index, 0.0, 0.0, filename, absolutePath, mimeType, true)
           audioTracks.add(track)
         } else {
           Log.d(tag, "Found non audio file $filename")
         }
//         var imageFile = StorageManager.MediaFile(it2.uri, filename, it2.getSimplePath(context), it2.length(), mimeType, isAudio)
//         mediaFiles.add(imageFile)
       }
       if (mediaFiles.size > 0) {

       }
     }

     return mediaFolders
   }
}
