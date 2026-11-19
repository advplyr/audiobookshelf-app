package com.audiobookshelf.app.managers

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.plugins.AbsLog
import com.audiobookshelf.app.plugins.AbsLogger
import io.paperdb.Paper
import java.util.UUID

class DbManager {
  val tag = "DbManager"

  // Library operations may acquire this lock; progress operations never acquire the library lock.
  private val localMediaProgressLock = Any()

  companion object {
    private var isDbInitialized = false

    fun initialize(ctx: Context) {
      if (isDbInitialized) return
      Paper.init(ctx)
      isDbInitialized = true
      Log.i("DbManager", "Initialized Paper db")
    }
  }

  fun getDeviceData(): DeviceData {
    return Paper.book("device").read("data")
            ?: DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
  }
  fun saveDeviceData(deviceData: DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun getLocalLibraryItems(mediaType: String? = null): MutableList<LocalLibraryItem> {
    val localLibraryItems: MutableList<LocalLibraryItem> = mutableListOf()
    Paper.book("localLibraryItems").allKeys.forEach {
      val localLibraryItem = synchronized(this) {
        val record = Paper.book("localLibraryItems").read<Any>(it)
        val base = localLibraryItemBase(record)
        // Filter before opening episode journals, especially for book-only queries.
        if (base == null || (!mediaType.isNullOrEmpty() && mediaType != base.mediaType)) null
        else mergeLocalLibraryItem(record)
      }
      if (localLibraryItem != null) localLibraryItems.add(localLibraryItem)
    }
    return localLibraryItems
  }

  data class BulkOfflineItem(
          val id: String,
          val libraryItemId: String,
          val folderId: String,
          val absolutePath: String,
          val serverConnectionConfigId: String?,
          val serverUserId: String?,
          val episodeIds: Set<String>,
          val hasOfflineBook: Boolean
  )

  // Builds a compact inventory of accessible files, including saved episode updates.
  // May normalize matching selected records from previousUserId to owner without compacting journals.
  @Synchronized
  fun getBulkOfflineItems(
          context: Context,
          ids: Set<String>,
          mediaType: String,
          owner: Pair<String, String>? = null,
          previousUserId: String? = null
  ): List<BulkOfflineItem> {
    val library = Paper.book("localLibraryItems")
    return library.allKeys.mapNotNull { key ->
      val stored = library.read<Any>(key)
      val item = when (stored) {
        is LocalPodcastRecord -> stored.item
        is LocalLibraryItem -> stored
        else -> return@mapNotNull null
      }
      val serverId = item.libraryItemId ?: return@mapNotNull null
      if (serverId !in ids || item.mediaType != mediaType) return@mapNotNull null
      if (owner != null && !previousUserId.isNullOrBlank() && previousUserId != owner.second &&
              item.serverConnectionConfigId == owner.first && item.serverUserId == previousUserId) {
        // Normalize the authenticated profile's migration alias without compacting episode journals.
        item.serverUserId = owner.second
        library.write(key, stored)
      }
      val files = item.localFiles.associateBy { it.id }
      val episodeIds = (item.media as? Podcast)?.episodes.orEmpty().mapNotNullTo(mutableSetOf()) { episode ->
        episode.serverEpisodeId?.takeIf { files[episode.audioTrack?.localFileId]?.exists(context) == true }
      }
      if (stored is LocalPodcastRecord) {
        val book = podcastEpisodeBook(stored.episodeGeneration)
        book.allKeys.forEach { episodeKey ->
          val entry = book.read<DownloadedPodcastEpisode>(episodeKey) ?: return@forEach
          val episodeId = entry.episode.serverEpisodeId ?: return@forEach
          if (entry.audioFile?.exists(context) == true) episodeIds.add(episodeId)
          else episodeIds.remove(episodeId)
        }
      }
      val book = item.media as? Book
      val bookFileIds = book?.let {
        it.getAudioTracks().map { track -> track.localFileId } + listOfNotNull(it.ebookFile).map { ebook -> ebook.localFileId }
      }.orEmpty()
      val hasOfflineBook = bookFileIds.isNotEmpty() && bookFileIds.all { files[it]?.exists(context) == true }
      BulkOfflineItem(item.id, serverId, item.folderId, item.absolutePath, item.serverConnectionConfigId,
              item.serverUserId, episodeIds, hasOfflineBook)
    }
  }

  fun getLocalLibraryItemsInFolder(folderId: String): List<LocalLibraryItem> {
    val localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter { it.folderId == folderId }
  }

  fun getLocalLibraryItemByLId(libraryItemId: String): LocalLibraryItem? {
    Paper.book("localLibraryItems").allKeys.forEach { key ->
      val item = synchronized(this) {
        val record = Paper.book("localLibraryItems").read<Any>(key)
        if (localLibraryItemBase(record)?.libraryItemId == libraryItemId) mergeLocalLibraryItem(record)
        else null
      }
      if (item != null) return item
    }
    return null
  }

  private fun localLibraryItemBase(record: Any?): LocalLibraryItem? = when (record) {
    is LocalPodcastRecord -> record.item
    is LocalLibraryItem -> record
    else -> null
  }

  data class DownloadedPodcastEpisode(
          val episode: PodcastEpisode,
          val audioFile: LocalFile?,
          val cover: LocalFile?,
          val addedAt: Long
  )

  // A generation pointer makes replacing a whole record and retiring its old deltas atomic.
  data class LocalPodcastRecord(val item: LocalLibraryItem, val episodeGeneration: String)

  private fun podcastEpisodeBook(generation: String) = Paper.book("downloadedPodcastEpisodes-$generation")

  // Reads legacy items directly, or merges a podcast base with its saved episode updates.
  @Synchronized
  fun getLocalLibraryItem(localLibraryItemId: String): LocalLibraryItem? {
    return mergeLocalLibraryItem(Paper.book("localLibraryItems").read<Any>(localLibraryItemId))
  }

  // Callers hold the library lock while reading the base and its journal.
  private fun mergeLocalLibraryItem(record: Any?): LocalLibraryItem? {
    if (record is LocalLibraryItem) return record
    if (record !is LocalPodcastRecord) return null

    val item = record.item
    val podcast = item.media as Podcast
    val episodes = podcast.episodes.orEmpty().associateByTo(linkedMapOf()) { it.id }
    val files = item.localFiles.associateByTo(linkedMapOf()) { it.id }
    val book = podcastEpisodeBook(record.episodeGeneration)
    val entries = book.allKeys.mapNotNull { book.read<DownloadedPodcastEpisode>(it) }
            .sortedWith(compareBy({ it.addedAt }, { it.episode.index }, { it.episode.id }))
    val replacedAudioFiles = mutableSetOf<String>()
    for (entry in entries) {
      val previousFileId = episodes.put(entry.episode.id, entry.episode)?.audioTrack?.localFileId
      if (previousFileId != null && previousFileId != entry.episode.audioTrack?.localFileId) {
        replacedAudioFiles.add(previousFileId)
      }
      entry.audioFile?.let { files[it.id] = it }
      entry.cover?.let {
        files[it.id] = it
        item.coverAbsolutePath = it.absolutePath
        item.coverContentUrl = it.contentUrl
      }
    }
    podcast.episodes = episodes.values.toMutableList().apply {
      forEachIndexed { index, episode -> episode.index = index + 1 }
    }
    val currentAudioFileIds = episodes.values.mapNotNullTo(mutableSetOf()) { it.audioTrack?.localFileId }
    for (id in replacedAudioFiles) {
      if (id !in currentAudioFileIds) files.remove(id)
    }
    item.localFiles = files.values.toMutableList()
    return item
  }

  // Returns only the base record for incremental download processing, excluding episode updates.
  @Synchronized
  fun getLocalPodcastDownloadBase(id: String): LocalLibraryItem? {
    return when (val record = Paper.book("localLibraryItems").read<Any>(id)) {
      is LocalPodcastRecord -> record.item
      is LocalLibraryItem -> record
      else -> null
    }
  }

  // Saves only the selected server episodes; replaying an episode replaces its existing entry.
  @Synchronized
  fun saveDownloadedPodcastEpisodes(item: LocalLibraryItem, episodeIds: Set<String>) {
    val library = Paper.book("localLibraryItems")
    val existing = library.read<Any>(item.id)
    val record = if (existing is LocalPodcastRecord) existing else {
      // Keep the existing representation as the base so old episodes require no migration.
      val base = (existing as? LocalLibraryItem) ?: item
      LocalPodcastRecord(base, UUID.randomUUID().toString()).also { library.write(item.id, it) }
    }
    val podcast = item.media as Podcast
    val files = item.localFiles.associateBy { it.id }
    val cover = item.localFiles.find { it.contentUrl == item.coverContentUrl }
    val book = podcastEpisodeBook(record.episodeGeneration)
    val addedAt = System.currentTimeMillis()
    for (episode in podcast.episodes.orEmpty()) {
      if (episode.serverEpisodeId !in episodeIds) continue
      val originalAddedAt = book.read<DownloadedPodcastEpisode>(episode.id)?.addedAt ?: addedAt
      book.write(episode.id, DownloadedPodcastEpisode(episode, files[episode.audioTrack?.localFileId], cover, originalAddedAt))
    }
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

  @Synchronized
  fun removeLocalLibraryItem(localLibraryItemId: String) {
    val library = Paper.book("localLibraryItems")
    val old = library.read<Any>(localLibraryItemId)
    library.delete(localLibraryItemId)
    if (old is LocalPodcastRecord) podcastEpisodeBook(old.episodeGeneration).destroy()
  }

  fun saveLocalLibraryItems(localLibraryItems: List<LocalLibraryItem>) {
    localLibraryItems.forEach(::saveLocalLibraryItem)
  }

  // Replaces the whole record and discards its episode journal; callers must supply the complete item.
  @Synchronized
  fun saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
    val library = Paper.book("localLibraryItems")
    val old = library.read<Any>(localLibraryItem.id)
    library.write(localLibraryItem.id, localLibraryItem)
    if (old is LocalPodcastRecord) podcastEpisodeBook(old.episodeGeneration).destroy()
  }

  fun saveLocalFolder(localFolder: LocalFolder) {
    Paper.book("localFolders").write(localFolder.id, localFolder)
  }

  fun getLocalFolder(folderId: String): LocalFolder? {
    return Paper.book("localFolders").read(folderId)
  }

  fun getAllLocalFolders(): List<LocalFolder> {
    val localFolders: MutableList<LocalFolder> = mutableListOf()
    Paper.book("localFolders").allKeys.forEach { localFolderId ->
      Paper.book("localFolders").read<LocalFolder>(localFolderId)?.let { localFolders.add(it) }
    }
    return localFolders
  }

  fun removeLocalFolder(folderId: String) {
    val localLibraryItems = getLocalLibraryItemsInFolder(folderId)
    localLibraryItems.forEach { removeLocalLibraryItem(it.id) }
    Paper.book("localFolders").delete(folderId)
  }

  fun saveDownloadItem(downloadItem: DownloadItem) {
    Paper.book("downloadItems").write(downloadItem.id, downloadItem)
  }

  fun removeDownloadItem(downloadItemId: String) {
    Paper.book("downloadItems").delete(downloadItemId)
  }

  fun getDownloadItems(): List<DownloadItem> {
    val downloadItems: MutableList<DownloadItem> = mutableListOf()
    Paper.book("downloadItems").allKeys.forEach { downloadItemId ->
      Paper.book("downloadItems").read<DownloadItem>(downloadItemId)?.let { downloadItems.add(it) }
    }
    return downloadItems
  }

  /**
   * Validate persisted download queue entries before restoring them.
   *
   * Queue entries can become incompatible or malformed, for example after an app update. If any
   * entry cannot be read or fails the checks required for queue restoration, the entire persisted
   * queue is cleared so startup can continue without restoring invalid data.
   *
   * @return true if the queue passes validation or was successfully cleared; false if an invalid
   * queue could not be cleared
   */
  fun ensureValidDownloadQueue(): Boolean {
    val downloadBook = Paper.book("downloadItems")
    val queueIsValid =
            try {
              downloadBook.allKeys
                      .map { downloadItemId ->
                        val item =
                                downloadBook.read<DownloadItem>(downloadItemId)
                                        ?: throw IllegalStateException(
                                                "Download queue entry $downloadItemId could not be read"
                                        )
                        if (item.id != downloadItemId) {
                          throw IllegalStateException(
                                  "Download queue entry $downloadItemId has a mismatched item ID"
                          )
                        }
                        item
                      }
                      .all { item ->
                        listOf<String?>(item.id, item.libraryItemId, item.localFolder.id).all {
                          !it.isNullOrBlank()
                        } &&
                                item.downloadItemParts.isNotEmpty() &&
                                item.downloadItemParts.all { part ->
                                  listOf<String?>(
                                                  part.id,
                                                  part.downloadItemId,
                                                  part.filename,
                                                  part.destinationPath,
                                                  part.finalDestinationPath,
                                                  part.serverPath,
                                                  part.localFolderId
                                          )
                                          .all { !it.isNullOrBlank() } &&
                                          part.downloadItemId == item.id
                                }
                      }
            } catch (readError: Exception) {
              AbsLogger.error(
                      tag,
                      "Could not read persisted download queue; clearing queue (${readError.message})"
              )
              false
            }

    if (queueIsValid) return true

    AbsLogger.error(tag, "Persisted download queue contains invalid data; clearing queue")
    return try {
      downloadBook.destroy()
      val remainingKeys = downloadBook.allKeys
      if (remainingKeys.isEmpty()) {
        AbsLogger.info(tag, "Cleared invalid persisted download queue")
        true
      } else {
        AbsLogger.error(
                tag,
                "Invalid persisted download queue still contains ${remainingKeys.size} entries after clearing"
        )
        false
      }
    } catch (clearError: Exception) {
      AbsLogger.error(
              tag,
              "Could not clear invalid persisted download queue (${clearError.message})"
      )
      false
    }
  }

  fun saveLocalMediaProgress(mediaProgress: LocalMediaProgress) {
    synchronized(localMediaProgressLock) {
      Paper.book("localMediaProgress").write(mediaProgress.id, mediaProgress)
    }
  }

  fun saveDownloadedProgressIfNewer(progress: LocalMediaProgress): Boolean = synchronized(localMediaProgressLock) {
    val existing = getLocalMediaProgress(progress.id)
    if (existing != null && existing.lastUpdate >= progress.lastUpdate) return@synchronized false
    saveLocalMediaProgress(progress)
    true
  }

  // For books this will just be the localLibraryItemId for podcast episodes this will be
  // "{localLibraryItemId}-{episodeId}"
  fun getLocalMediaProgress(localMediaProgressId: String): LocalMediaProgress? {
    return Paper.book("localMediaProgress").read(localMediaProgressId)
  }
  fun getAllLocalMediaProgress(): List<LocalMediaProgress> {
    val mediaProgress: MutableList<LocalMediaProgress> = mutableListOf()
    Paper.book("localMediaProgress").allKeys.forEach { localMediaProgressId ->
      Paper.book("localMediaProgress").read<LocalMediaProgress>(localMediaProgressId)?.let {
        mediaProgress.add(it)
      }
    }
    return mediaProgress
  }
  fun removeLocalMediaProgress(localMediaProgressId: String) {
    Paper.book("localMediaProgress").delete(localMediaProgressId)
  }

  fun removeAllLocalMediaProgress() {
    Paper.book("localMediaProgress").destroy()
  }

  // Make sure all local file ids still exist
  fun cleanLocalLibraryItems(context: Context) {
    // Release the library lock between items, keeping each read/update/save together.
    Paper.book("localLibraryItems").allKeys.forEach { id ->
      synchronized(this) {
        val lli = getLocalLibraryItem(id) ?: return@synchronized
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

        val fileIds = lli.localFiles.mapTo(hashSetOf()) { it.id }

        // Check audio tracks and episodes
        if (lli.isPodcast) {
          val podcast = lli.media as Podcast
          podcast.episodes =
                  podcast.episodes?.filter { ep ->
                    if (ep.audioTrack?.localFileId !in fileIds) {
                      Log.d(
                              tag,
                              "cleanLocalLibraryItems: Podcast episode ${ep.title} was removed from library item ${lli.media.metadata.title}"
                      )
                      hasUpdates = true
                    }
                    ep.audioTrack != null &&
                            ep.audioTrack?.localFileId in fileIds
                  } as
                          MutableList<PodcastEpisode>
        } else {
          val book = lli.media as Book
          book.tracks =
                  book.tracks?.filter { track ->
                    if (track.localFileId !in fileIds) {
                      Log.d(
                              tag,
                              "cleanLocalLibraryItems: Audio track ${track.title} was removed from library item ${lli.media.metadata.title}"
                      )
                      hasUpdates = true
                    }
                    track.localFileId in fileIds
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
          removeLocalLibraryItem(lli.id)
        } else if (hasUpdates) {
          Log.d(tag, "cleanLocalLibraryItems: Saving local library item ${lli.id}")
          saveLocalLibraryItem(lli)
        }
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
    return Paper.book("mediaItemHistory").read(id)
  }

  fun savePlaybackSession(playbackSession: PlaybackSession) {
    Paper.book("playbackSession").write(playbackSession.id, playbackSession)
  }
  fun removePlaybackSession(playbackSessionId: String) {
    Paper.book("playbackSession").delete(playbackSessionId)
  }
  fun getPlaybackSessions(): List<PlaybackSession> {
    val sessions: MutableList<PlaybackSession> = mutableListOf()
    Paper.book("playbackSession").allKeys.forEach { playbackSessionId ->
      Paper.book("playbackSession").read<PlaybackSession>(playbackSessionId)?.let {
        sessions.add(it)
      }
    }
    return sessions
  }

  fun saveLog(log: AbsLog) {
    Paper.book("log").write(log.id, log)
  }
  fun getAllLogs(): List<AbsLog> {
    val logs: MutableList<AbsLog> = mutableListOf()
    Paper.book("log").allKeys.forEach { logId ->
      Paper.book("log").read<AbsLog>(logId)?.let { logs.add(it) }
    }
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
