package com.audiobookshelf.app.plugins

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.managers.DownloadItemManager
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File

@CapacitorPlugin(name = "AbsDownloader")
class AbsDownloader : Plugin() {
  private val tag = "AbsDownloader"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var downloadManager: DownloadManager
  lateinit var apiHandler: ApiHandler
  lateinit var folderScanner: FolderScanner
  lateinit var downloadItemManager: DownloadItemManager

  private val clientEventEmitter = (object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem:DownloadItem) {
      notifyListeners("onDownloadItem", JSObject(jacksonMapper.writeValueAsString(downloadItem)))
    }
    override fun onDownloadItemPartUpdate(downloadItemPart:DownloadItemPart) {
      notifyListeners("onDownloadItemPartUpdate", JSObject(jacksonMapper.writeValueAsString(downloadItemPart)))
    }
    override fun onDownloadItemComplete(jsobj:JSObject) {
      notifyListeners("onItemDownloadComplete", jsobj)
    }
  })

  override fun load() {
    mainActivity = (activity as MainActivity)
    downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    folderScanner = FolderScanner(mainActivity)
    apiHandler = ApiHandler(mainActivity)
    downloadItemManager = DownloadItemManager(downloadManager, folderScanner, mainActivity, clientEventEmitter)
    Log.d(tag, "Build SDK ${Build.VERSION.SDK_INT}")
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    val libraryItemId = call.data.getString("libraryItemId").toString()
    var episodeId = call.data.getString("episodeId").toString()
    if (episodeId == "null") episodeId = ""
    val localFolderId = call.data.getString("localFolderId").toString()
    Log.d(tag, "Download library item $libraryItemId to folder $localFolderId / episode: $episodeId")

    val downloadId = if (episodeId.isEmpty()) libraryItemId else "$libraryItemId-$episodeId"
    if (downloadItemManager.downloadItemQueue.find { it.id == downloadId } != null) {
      Log.d(tag, "Download already started for this media entity $downloadId")
      return call.resolve(JSObject("{\"error\":\"Download already started for this media entity\"}"))
    }

    apiHandler.getLibraryItemWithProgress(libraryItemId, episodeId) { libraryItem ->
      if (libraryItem == null) {
        call.resolve(JSObject("{\"error\":\"Server request failed\"}"))
      } else {
        Log.d(tag, "Got library item from server ${libraryItem.id}")

        val localFolder = DeviceManager.dbManager.getLocalFolder(localFolderId)
        if (localFolder != null) {

          if (episodeId.isNotEmpty() && libraryItem.mediaType != "podcast") {
            Log.e(tag, "Library item is not a podcast but episode was requested")
            call.resolve(JSObject("{\"error\":\"Invalid library item not a podcast\"}"))
          } else if (episodeId.isNotEmpty()) {
            val podcast = libraryItem.media as Podcast
            val episode = podcast.episodes?.find { podcastEpisode ->
              podcastEpisode.id == episodeId
            }
            if (episode == null) {
              call.resolve(JSObject("{\"error\":\"Invalid podcast episode not found\"}"))
            } else {
              startLibraryItemDownload(libraryItem, localFolder, episode)
              call.resolve()
            }
          } else {
            startLibraryItemDownload(libraryItem, localFolder, null)
            call.resolve()
          }
        } else {
          call.resolve(JSObject("{\"error\":\"Local Folder Not Found\"}"))
        }
      }
    }
  }

  // Clean folder path so it can be used in URL
  private fun cleanRelPath(relPath: String): String {
    val cleanedRelPath = relPath.replace("\\", "/").replace("%", "%25").replace("#", "%23")
    return if (cleanedRelPath.startsWith("/")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  // Item filenames could be the same if they are in sub-folders, this will make them unique
  private fun getFilenameFromRelPath(relPath: String): String {
    var cleanedRelPath = relPath.replace("\\", "_").replace("/", "_")
    cleanedRelPath = cleanStringForFileSystem(cleanedRelPath)
    return if (cleanedRelPath.startsWith("_")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  // Replace characters that cant be used in the file system
  // Reserved characters: ?:\"*|/\\<>
  private fun cleanStringForFileSystem(str:String):String {
    val reservedCharacters = listOf("?", "\"", "*", "|", "/", "\\", "<", ">")
    var newTitle = str
    newTitle = newTitle.replace(":", " -") // Special case replace : with -

    reservedCharacters.forEach {
      newTitle = newTitle.replace(it, "")
    }
    return newTitle
  }
  private fun parseTemplateString(userTemplate: String?, libraryItem: LibraryItem):String{
    val template = userTemplate ?: "\$bookAuthor/\$bookTitle"
    val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)
    val bookAuthor = cleanStringForFileSystem(libraryItem.media.metadata.getAuthorDisplayName())
    val subtitle = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getSubtitleDisplay())
    //series
    val narrator = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getNarratorDisplayName())
    val genres = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getGenresDisplayName())
    val publishedYear = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getPublishedYearDisplay())
    val publishedDate = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getPublishedDateDisplay())
    val publisher = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getPublisherDisplay())
    val isbn = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getISBNDisplay())
    val asin = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getASINDisplay())
    val language = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getLanguageDisplay())
    val explicit = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getExplicitDisplay())
    val seriesSummary = cleanStringForFileSystem((libraryItem.media.metadata as BookMetadata).getSeriesSummary())
    var output = template
    while(output.contains("\$bookTitle")) output = output.replace("\$bookTitle", bookTitle)
    while(output.contains("\$bookAuthor")) output = output.replace("\$bookAuthor", bookAuthor)
    while(output.contains("\$subtitle")) output = output.replace("\$subtitle", subtitle)
    while(output.contains("\$narrator")) output = output.replace("\$narrator", narrator)
    while(output.contains("\$genres")) output = output.replace("\$genres", genres)
    while(output.contains("\$publishedYear")) output = output.replace("\$publishedYear", publishedYear)
    while(output.contains("\$publishedDate")) output = output.replace("\$publishedDate", publishedDate)
    while(output.contains("\$publisher")) output = output.replace("\$publisher", publisher)
    while(output.contains("\$isbn")) output = output.replace("\$isbn", isbn)
    while(output.contains("\$asin")) output = output.replace("\$asin", asin)
    while(output.contains("\$language")) output = output.replace("\$language", language)
    while(output.contains("\$explicit")) output = output.replace("\$explicit", explicit)
    while(output.contains("\$seriesSummary")) output = output.replace("\$seriesSummary", seriesSummary)

    val seriesNameRegex = "\\\$seriesName\\[\\d+\\]".toRegex()
    while(seriesNameRegex.find(output) != null) {
      val subString = seriesNameRegex.find(output)!!.value
      if(subString != null){
        val digitStr = subString.replace("\$seriesName[","").replace("]","")
        val digit = digitStr.toInt()
        val replacementText = (libraryItem.media.metadata as BookMetadata).getSeriesNameDisplay(digit)
        output = output.replace(subString, replacementText)
      }
    }
    while(output.contains("\$seriesName")) output = output.replace("\$seriesName", (libraryItem.media.metadata as BookMetadata).getSeriesNameDisplay(0))

    val seriesSeqRegex = "\\\$seriesSequence\\[\\d+\\]".toRegex()
    while(seriesSeqRegex.find(output) != null) {
      val subString = seriesSeqRegex.find(output)!!.value
      if(subString != null){
        val digitStr = subString.replace("\$seriesSequence[","").replace("]","")
        val digit = digitStr.toInt()
        val replacementText = (libraryItem.media.metadata as BookMetadata).getSeriesSequenceDisplay(digit)
        output = output.replace(subString, replacementText)
      }
    }
    while(output.contains("\$seriesSequence")) output = output.replace("\$seriesSequence", (libraryItem.media.metadata as BookMetadata).getSeriesSequenceDisplay(0))

    return output
  }
  private fun startLibraryItemDownload(libraryItem: LibraryItem, localFolder: LocalFolder, episode:PodcastEpisode?) {
    val tempFolderPath = mainActivity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    Log.d(tag, "downloadCacheDirectory=$tempFolderPath")
    if (libraryItem.mediaType == "book") {
      val itemSubfolder = if(DeviceManager.deviceData.deviceSettings != null) {
        parseTemplateString(DeviceManager.deviceData.deviceSettings!!.localPathFormat, libraryItem)
      } else{
        parseTemplateString(null, libraryItem)
      }
      /*
      val testTemplate = "bookAuthor = \$bookAuthor\n" +
        "bookTitle = \$bookTitle\n" +
        "explicit = \$explicit\n" +
        "subtitle = \$subtitle\n" +
        "narrator = \$narrator\n" +
        "genres = \$genres\n" +
        "publishedYear = \$publishedYear\n" +
        "publishedDate = \$publishedDate\n" +
        "publisher = \$publisher\n" +
        "isbn = \$isbn\n" +
        "asin = \$asin\n" +
        "language = \$language\n" +
        "seriesSummary = \$seriesSummary\n" +
        "seriesName = \$seriesName\n" +
        "seriesName[0] = \$seriesName[0]\n" +
        "seriesName[100] = \$seriesName[100]\n" +
        "seriesSequence = \$seriesSequence\n" +
        "seriesSequence[0] = \$seriesSequence[0]\n" +
        "seriesSequence[-1] = \$seriesSequence[-1]\n" +
        "seriesSequence[10] = \$seriesSequence[10]\n" +
        "seriesSequence[100] = \$seriesSequence[100]\n" +
        "seriesSequence[1000] = \$seriesSequence[1000]\n" +
        "output = \$output"
      val parsedTemplate = parseTemplateString(testTemplate, libraryItem)
*/
      val tracks = libraryItem.media.getAudioTracks()
      Log.d(tag, "Starting library item download with ${tracks.size} tracks")
      val itemFolderPath = "${localFolder.absolutePath}/$itemSubfolder"

      val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)
      val downloadItem = DownloadItem(libraryItem.id, libraryItem.id, null, libraryItem.userMediaProgress,DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, itemSubfolder, libraryItem.media, mutableListOf())

      // Create download item part for each audio track
      tracks.forEach { audioTrack ->
        val fileSize = audioTrack.metadata?.size ?: 0
        val serverPath = "/s/item/${libraryItem.id}/${cleanRelPath(audioTrack.relPath)}"
        val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)
        Log.d(tag, "Audio File Server Path $serverPath | AF RelPath ${audioTrack.relPath} | LocalFolder Path ${localFolder.absolutePath} | DestName $destinationFilename")

        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename")

        if (destinationFile.exists()) {
          Log.d(tag, "TEMP Audio file already exists, removing it from ${destinationFile.absolutePath}")
          destinationFile.delete()
        }

        if (finalDestinationFile.exists()) {
          Log.d(tag, "Audio file already exists, removing it from ${finalDestinationFile.absolutePath}")
          finalDestinationFile.delete()
        }

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,audioTrack,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
          val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
          val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

          val serverPath = "/api/items/${libraryItem.id}/cover"
          val destinationFilename = "cover-${libraryItem.id}.jpg"
          val destinationFile = File("$tempFolderPath/$destinationFilename")
          val finalDestinationFile = File("$itemFolderPath/$destinationFilename")

          if (destinationFile.exists()) {
            Log.d(tag, "TEMP Audio file already exists, removing it from ${destinationFile.absolutePath}")
            destinationFile.delete()
          }

          if (finalDestinationFile.exists()) {
            Log.d(tag, "Cover already exists, removing it from ${finalDestinationFile.absolutePath}")
            finalDestinationFile.delete()
          }

          val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, coverFileSize,  destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }

        downloadItemManager.addDownloadItem(downloadItem)
      }
    } else {
      // Podcast episode download
      val podcastTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val audioTrack = episode?.audioTrack
      val fileSize = audioTrack?.metadata?.size ?: 0

      Log.d(tag, "Starting podcast episode download")
      val itemFolderPath = localFolder.absolutePath + "/" + podcastTitle
      val downloadItemId = "${libraryItem.id}-${episode?.id}"
      val downloadItem = DownloadItem(downloadItemId, libraryItem.id, episode?.id, libraryItem.userMediaProgress, DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, podcastTitle, podcastTitle, libraryItem.media, mutableListOf())

      var serverPath = "/s/item/${libraryItem.id}/${cleanRelPath(audioTrack?.relPath ?: "")}"
      var destinationFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")
      Log.d(tag, "Audio File Server Path $serverPath | AF RelPath ${audioTrack?.relPath} | LocalFolder Path ${localFolder.absolutePath} | DestName $destinationFilename")

      var destinationFile = File("$tempFolderPath/$destinationFilename")
      var finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      if (finalDestinationFile.exists()) {
        Log.d(tag, "Audio file already exists, removing it from ${finalDestinationFile.absolutePath}")
        finalDestinationFile.delete()
      }

      var downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,fileSize, destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,audioTrack,episode)
      downloadItem.downloadItemParts.add(downloadItemPart)

      if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
        val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
        val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

        serverPath = "/api/items/${libraryItem.id}/cover"
        destinationFilename = "cover.jpg"

        destinationFile = File("$tempFolderPath/$destinationFilename")
        finalDestinationFile = File("$itemFolderPath/$destinationFilename")

        if (finalDestinationFile.exists()) {
          Log.d(tag, "Podcast cover already exists - not downloading cover again")
        } else {
          downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,coverFileSize,destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }
      }

      downloadItemManager.addDownloadItem(downloadItem)
    }
  }
}
