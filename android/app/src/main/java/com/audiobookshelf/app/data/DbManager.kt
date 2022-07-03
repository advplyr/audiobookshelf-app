package com.audiobookshelf.app.data

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.plugins.AbsDownloader
import io.paperdb.Paper
import java.io.File

class DbManager {
  val tag = "DbManager"

  companion object {
    var isDbInitialized = false

    fun initialize(ctx: Context) {
      if (isDbInitialized) return
      Paper.init(ctx)
      isDbInitialized = true
      Log.i("DbManager", "Initialized Paper db")
    }
  }

  fun getDeviceData(): DeviceData {
    return Paper.book("device").read("data") ?: DeviceData(mutableListOf(), null, null, DeviceSettings.default())
  }
  fun saveDeviceData(deviceData:DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun getLocalLibraryItems(mediaType:String? = null):MutableList<LocalLibraryItem> {
    val localLibraryItems:MutableList<LocalLibraryItem> = mutableListOf()
    Paper.book("localLibraryItems").allKeys.forEach {
      val localLibraryItem:LocalLibraryItem? = Paper.book("localLibraryItems").read(it)
      if (localLibraryItem != null && (mediaType.isNullOrEmpty() || mediaType == localLibraryItem.mediaType)) {
        localLibraryItems.add(localLibraryItem)
      }
    }
    return localLibraryItems
  }

  fun getLocalLibraryItemsInFolder(folderId:String):List<LocalLibraryItem> {
    val localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter {
      it.folderId == folderId
    }
  }

  fun getLocalLibraryItemByLLId(libraryItemId:String):LocalLibraryItem? {
    return getLocalLibraryItems().find { it.libraryItemId == libraryItemId }
  }

  fun getLocalLibraryItem(localLibraryItemId:String):LocalLibraryItem? {
    return Paper.book("localLibraryItems").read(localLibraryItemId)
  }

  fun getLocalLibraryItemWithEpisode(podcastEpisodeId:String):LibraryItemWithEpisode? {
    var podcastEpisode:PodcastEpisode? = null
    val localLibraryItem = getLocalLibraryItems("podcast").find { localLibraryItem ->
      val podcast = localLibraryItem.media as Podcast
      podcastEpisode = podcast.episodes?.find { it.id == podcastEpisodeId }
      podcastEpisode != null
    }
    return if (localLibraryItem != null) {
      LibraryItemWithEpisode(localLibraryItem, podcastEpisode!!)
    } else {
      null
    }
  }

  fun removeLocalLibraryItem(localLibraryItemId:String) {
    Paper.book("localLibraryItems").delete(localLibraryItemId)
  }

  fun saveLocalLibraryItems(localLibraryItems:List<LocalLibraryItem>) {
    localLibraryItems.map {
        Paper.book("localLibraryItems").write(it.id, it)
      }
  }

  fun saveLocalLibraryItem(localLibraryItem:LocalLibraryItem) {
    Paper.book("localLibraryItems").write(localLibraryItem.id, localLibraryItem)
  }

  fun saveLocalFolder(localFolder:LocalFolder) {
    Paper.book("localFolders").write(localFolder.id,localFolder)
  }

  fun getLocalFolder(folderId:String):LocalFolder? {
    return Paper.book("localFolders").read(folderId)
  }

  fun getAllLocalFolders():List<LocalFolder> {
    val localFolders:MutableList<LocalFolder> = mutableListOf()
    Paper.book("localFolders").allKeys.forEach { localFolderId ->
      Paper.book("localFolders").read<LocalFolder>(localFolderId)?.let {
        localFolders.add(it)
      }
    }
    return localFolders
  }

  fun removeLocalFolder(folderId:String) {
    val localLibraryItems = getLocalLibraryItemsInFolder(folderId)
    localLibraryItems.forEach {
      Paper.book("localLibraryItems").delete(it.id)
    }
    Paper.book("localFolders").delete(folderId)
  }

  fun saveDownloadItem(downloadItem: AbsDownloader.DownloadItem) {
    Paper.book("downloadItems").write(downloadItem.id, downloadItem)
  }

  fun removeDownloadItem(downloadItemId:String) {
    Paper.book("downloadItems").delete(downloadItemId)
  }

  fun getDownloadItems():List<AbsDownloader.DownloadItem> {
    val downloadItems:MutableList<AbsDownloader.DownloadItem> = mutableListOf()
    Paper.book("downloadItems").allKeys.forEach { downloadItemId ->
      Paper.book("downloadItems").read<AbsDownloader.DownloadItem>(downloadItemId)?.let {
        downloadItems.add(it)
      }
    }
    return downloadItems
  }

  fun saveLocalMediaProgress(mediaProgress:LocalMediaProgress) {
    Paper.book("localMediaProgress").write(mediaProgress.id,mediaProgress)
  }
  // For books this will just be the localLibraryItemId for podcast episodes this will be "{localLibraryItemId}-{episodeId}"
  fun getLocalMediaProgress(localMediaProgressId:String):LocalMediaProgress? {
    return Paper.book("localMediaProgress").read(localMediaProgressId)
  }
  fun getAllLocalMediaProgress():List<LocalMediaProgress> {
    val mediaProgress:MutableList<LocalMediaProgress> = mutableListOf()
    Paper.book("localMediaProgress").allKeys.forEach { localMediaProgressId ->
      Paper.book("localMediaProgress").read<LocalMediaProgress>(localMediaProgressId)?.let {
        mediaProgress.add(it)
      }
    }
    return mediaProgress
  }
  fun removeLocalMediaProgress(localMediaProgressId:String) {
    Paper.book("localMediaProgress").delete(localMediaProgressId)
  }

  fun removeAllLocalMediaProgress() {
    Paper.book("localMediaProgress").destroy()
  }

  // Make sure all local file ids still exist
  fun cleanLocalLibraryItems() {
    val localLibraryItems = getLocalLibraryItems()

    localLibraryItems.forEach { lli ->
      var hasUpates = false

      // Check local files
      lli.localFiles = lli.localFiles.filter { localFile ->

        val file = File(localFile.absolutePath)
        if (!file.exists()) {
          Log.d(tag, "cleanLocalLibraryItems: Local file ${localFile.absolutePath} was removed from library item ${lli.media.metadata.title}")
          hasUpates = true
        }
        file.exists()
      } as MutableList<LocalFile>

      // Check audio tracks and episodes
      if (lli.isPodcast) {
        val podcast = lli.media as Podcast
        podcast.episodes = podcast.episodes?.filter { ep ->
          if (lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } == null) {
            Log.d(tag, "cleanLocalLibraryItems: Podcast episode ${ep.title} was removed from library item ${lli.media.metadata.title}")
            hasUpates = true
          }
          ep.audioTrack != null && lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } != null
        } as MutableList<PodcastEpisode>
      } else {
        val book = lli.media as Book
        book.tracks = book.tracks?.filter { track ->
          if (lli.localFiles.find { lf -> lf.id == track.localFileId } == null) {
            Log.d(tag, "cleanLocalLibraryItems: Audio track ${track.title} was removed from library item ${lli.media.metadata.title}")
            hasUpates = true
          }
          lli.localFiles.find { lf -> lf.id == track.localFileId } != null
        } as MutableList<AudioTrack>
      }

      // Check cover still there
      lli.coverAbsolutePath?.let {
        val coverFile = File(it)

        if (!coverFile.exists()) {
          Log.d(tag, "cleanLocalLibraryItems: Cover $it was removed from library item ${lli.media.metadata.title}")
          lli.coverAbsolutePath = null
          lli.coverContentUrl = null
          hasUpates = true
        }
      }

      if (hasUpates) {
        Log.d(tag, "cleanLocalLibraryItems: Saving local library item ${lli.id}")
        Paper.book("localLibraryItems").write(lli.id, lli)
      }
    }
  }

  // Remove any local media progress where the local media item is not found
  fun cleanLocalMediaProgress() {
    val localMediaProgress = getAllLocalMediaProgress()
    val localLibraryItems = getLocalLibraryItems()
    localMediaProgress.forEach {
      val matchingLLI = localLibraryItems.find { lli -> lli.id == it.localLibraryItemId }
      if (matchingLLI == null) {
        Log.d(tag, "cleanLocalMediaProgress: No matching local library item for local media progress ${it.id} - removing")
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI.isPodcast) {
        if (it.localEpisodeId.isNullOrEmpty()) {
          Log.d(tag, "cleanLocalMediaProgress: Podcast media progress has no episode id - removing")
          Paper.book("localMediaProgress").delete(it.id)
        } else {
          val podcast = matchingLLI.media as Podcast
          val matchingLEp = podcast.episodes?.find { ep -> ep.id == it.localEpisodeId }
          if (matchingLEp == null) {
            Log.d(tag, "cleanLocalMediaProgress: Podcast media progress for episode ${it.localEpisodeId} not found - removing")
            Paper.book("localMediaProgress").delete(it.id)
          }
        }
      }
    }
  }

  fun saveLocalPlaybackSession(playbackSession:PlaybackSession) {
    Paper.book("localPlaybackSession").write(playbackSession.id,playbackSession)
  }
  fun getLocalPlaybackSession(playbackSessionId:String):PlaybackSession? {
    return Paper.book("localPlaybackSession").read(playbackSessionId)
  }
}
