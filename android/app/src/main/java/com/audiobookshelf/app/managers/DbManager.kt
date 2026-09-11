package com.audiobookshelf.app.managers

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.plugins.AbsLog
import com.audiobookshelf.app.plugins.AbsLogger
import io.paperdb.Paper

class DbManager {
  val tag = "DbManager"

  companion object {
    private var isDbInitialized = false

    fun initialize(ctx: Context) {
      if (isDbInitialized) return
      Paper.init(ctx)
      isDbInitialized = true
      Log.i("DbManager", "Initialized Paper db")
    }
  }

  /**
   * Reads one entry, dropping it when it cannot be read.
   *
   * A file left half written - the app was killed mid write, the device ran out of storage - makes
   * kryo throw, and an unhandled throw here takes the app down on every start. The only way out for
   * a user would be clearing the app's storage, which takes their downloads and progress with it.
   */
  private inline fun <reified T> readEntry(bookName: String, key: String): T? {
    val book = Paper.book(bookName)

    return try {
      book.read<T>(key)
    } catch (e: Exception) {
      Log.e(tag, "readEntry: Dropping unreadable $bookName entry $key", e)
      book.delete(key)
      null
    }
  }

  /** Reads every entry of a book, dropping the ones that cannot be read. See [readEntry]. */
  private inline fun <reified T> readBook(bookName: String): List<T> {
    return Paper.book(bookName).allKeys.mapNotNull { readEntry<T>(bookName, it) }
  }

  fun getDeviceData(): DeviceData {
    return readEntry<DeviceData>("device", "data")
            ?: DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
  }
  fun saveDeviceData(deviceData: DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun getLocalLibraryItems(mediaType: String? = null): MutableList<LocalLibraryItem> {
    return readBook<LocalLibraryItem>("localLibraryItems")
            .filter { mediaType.isNullOrEmpty() || mediaType == it.mediaType }
            .toMutableList()
  }

  fun getLocalLibraryItemsInFolder(folderId: String): List<LocalLibraryItem> {
    val localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter { it.folderId == folderId }
  }

  fun getLocalLibraryItemByLId(libraryItemId: String): LocalLibraryItem? {
    return getLocalLibraryItems().find { it.libraryItemId == libraryItemId }
  }

  fun getLocalLibraryItem(localLibraryItemId: String): LocalLibraryItem? {
    return readEntry("localLibraryItems", localLibraryItemId)
  }

  fun getLocalLibraryItemWithEpisode(podcastEpisodeId: String): LibraryItemWithEpisode? {
    var podcastEpisode: PodcastEpisode? = null
    val localLibraryItem =
            getLocalLibraryItems("podcast").find { localLibraryItem ->
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

  fun removeLocalLibraryItem(localLibraryItemId: String) {
    Paper.book("localLibraryItems").delete(localLibraryItemId)
  }

  fun saveLocalLibraryItems(localLibraryItems: List<LocalLibraryItem>) {
    localLibraryItems.map { Paper.book("localLibraryItems").write(it.id, it) }
  }

  fun saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
    Paper.book("localLibraryItems").write(localLibraryItem.id, localLibraryItem)
  }

  fun saveLocalFolder(localFolder: LocalFolder) {
    Paper.book("localFolders").write(localFolder.id, localFolder)
  }

  fun getLocalFolder(folderId: String): LocalFolder? {
    return readEntry("localFolders", folderId)
  }

  fun getAllLocalFolders(): List<LocalFolder> {
    return readBook("localFolders")
  }

  fun removeLocalFolder(folderId: String) {
    val localLibraryItems = getLocalLibraryItemsInFolder(folderId)
    localLibraryItems.forEach { Paper.book("localLibraryItems").delete(it.id) }
    Paper.book("localFolders").delete(folderId)
  }

  fun saveDownloadItem(downloadItem: DownloadItem) {
    Paper.book("downloadItems").write(downloadItem.id, downloadItem)
  }

  fun removeDownloadItem(downloadItemId: String) {
    Paper.book("downloadItems").delete(downloadItemId)
  }

  fun getDownloadItems(): List<DownloadItem> {
    return readBook("downloadItems")
  }

  fun saveLocalMediaProgress(mediaProgress: LocalMediaProgress) {
    Paper.book("localMediaProgress").write(mediaProgress.id, mediaProgress)
  }
  // For books this will just be the localLibraryItemId for podcast episodes this will be
  // "{localLibraryItemId}-{episodeId}"
  fun getLocalMediaProgress(localMediaProgressId: String): LocalMediaProgress? {
    return readEntry("localMediaProgress", localMediaProgressId)
  }
  fun getAllLocalMediaProgress(): List<LocalMediaProgress> {
    return readBook("localMediaProgress")
  }
  fun removeLocalMediaProgress(localMediaProgressId: String) {
    Paper.book("localMediaProgress").delete(localMediaProgressId)
  }

  fun removeAllLocalMediaProgress() {
    Paper.book("localMediaProgress").destroy()
  }

  // Make sure all local file ids still exist
  fun cleanLocalLibraryItems(context: Context) {
    val localLibraryItems = getLocalLibraryItems()

    localLibraryItems.forEach { lli ->
      var hasUpdates = false

      // Check local files
      lli.localFiles =
              lli.localFiles.filter { localFile ->
                val exists = localFile.exists(context)
                if (!exists) {
                  Log.d(
                          tag,
                          "cleanLocalLibraryItems: Local file ${localFile.absolutePath} was removed from library item ${lli.media.metadata.title}"
                  )
                  hasUpdates = true
                }
                exists
              } as
                      MutableList<LocalFile>

      // Check audio tracks and episodes
      if (lli.isPodcast) {
        val podcast = lli.media as Podcast
        podcast.episodes =
                podcast.episodes?.filter { ep ->
                  if (lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } == null) {
                    Log.d(
                            tag,
                            "cleanLocalLibraryItems: Podcast episode ${ep.title} was removed from library item ${lli.media.metadata.title}"
                    )
                    hasUpdates = true
                  }
                  ep.audioTrack != null &&
                          lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } != null
                } as
                        MutableList<PodcastEpisode>
      } else {
        val book = lli.media as Book
        book.tracks =
                book.tracks?.filter { track ->
                  if (lli.localFiles.find { lf -> lf.id == track.localFileId } == null) {
                    Log.d(
                            tag,
                            "cleanLocalLibraryItems: Audio track ${track.title} was removed from library item ${lli.media.metadata.title}"
                    )
                    hasUpdates = true
                  }
                  lli.localFiles.find { lf -> lf.id == track.localFileId } != null
                } as
                        MutableList<AudioTrack>
      }

      // Check cover still there
      lli.coverAbsolutePath?.let {
        val coverExists =
                lli.localFiles.any { localFile ->
                  localFile.absolutePath == it && localFile.exists(context)
                }
        if (!coverExists) {
          Log.d(
                  tag,
                  "cleanLocalLibraryItems: Cover $it was removed from library item ${lli.media.metadata.title}"
          )
          lli.coverAbsolutePath = null
          lli.coverContentUrl = null
          hasUpdates = true
        }
      }

      if (lli.serverConnectionConfigId == null) {
        // Local-only item support was removed in app version 0.9.67, remove any remaining local
        // only items beginning in 0.9.80
        Log.d(tag, "cleanLocalLibraryItems: Local only item ${lli.id} - removing from ABS")
        Paper.book("localLibraryItems").delete(lli.id)
      } else if (hasUpdates) {
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
      if (!it.id.startsWith("local")) {
        // A bug on the server when syncing local media progress was replacing the media progress id
        // causing duplicate progress. Remove them.
        Log.d(
                tag,
                "cleanLocalMediaProgress: Invalid local media progress does not start with 'local' (fixed on server 2.0.24)"
        )
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI == null) {
        Log.d(
                tag,
                "cleanLocalMediaProgress: No matching local library item for local media progress ${it.id} - removing"
        )
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI.isPodcast) {
        if (it.localEpisodeId.isNullOrEmpty()) {
          Log.d(tag, "cleanLocalMediaProgress: Podcast media progress has no episode id - removing")
          Paper.book("localMediaProgress").delete(it.id)
        } else {
          val podcast = matchingLLI.media as Podcast
          val matchingLEp = podcast.episodes?.find { ep -> ep.id == it.localEpisodeId }
          if (matchingLEp == null) {
            Log.d(
                    tag,
                    "cleanLocalMediaProgress: Podcast media progress for episode ${it.localEpisodeId} not found - removing"
            )
            Paper.book("localMediaProgress").delete(it.id)
          }
        }
      }
    }
  }

  fun saveMediaItemHistory(mediaItemHistory: MediaItemHistory) {
    Paper.book("mediaItemHistory").write(mediaItemHistory.id, mediaItemHistory)
  }
  fun getMediaItemHistory(id: String): MediaItemHistory? {
    return readEntry("mediaItemHistory", id)
  }

  fun savePlaybackSession(playbackSession: PlaybackSession) {
    Paper.book("playbackSession").write(playbackSession.id, playbackSession)
  }
  fun removePlaybackSession(playbackSessionId: String) {
    Paper.book("playbackSession").delete(playbackSessionId)
  }
  fun getPlaybackSessions(): List<PlaybackSession> {
    return readBook("playbackSession")
  }

  fun saveLog(log: AbsLog) {
    Paper.book("log").write(log.id, log)
  }
  fun getAllLogs(): List<AbsLog> {
    val logs: MutableList<AbsLog> = readBook<AbsLog>("log").toMutableList()
    return logs.sortedBy { it.timestamp }
  }
  fun removeAllLogs() {
    Paper.book("log").destroy()
  }
  fun cleanLogs() {
    val numberOfHoursToKeep = 48
    val keepLogCutoff = System.currentTimeMillis() - (3600000 * numberOfHoursToKeep)
    val allLogs = getAllLogs()
    var logsRemoved = 0
    allLogs.forEach {
      if (it.timestamp < keepLogCutoff) {
        Paper.book("log").delete(it.id)
        logsRemoved++
      }
    }
    if (logsRemoved > 0) {
      AbsLogger.info(
              "DbManager",
              "cleanLogs: Removed $logsRemoved logs older than $numberOfHoursToKeep hours"
      )
    }
  }
}
