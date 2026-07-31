package com.audiobookshelf.app.managers

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.plugins.AbsLog
import com.audiobookshelf.app.plugins.AbsLogger
import io.paperdb.Paper
import java.util.concurrent.ConcurrentHashMap

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
      val localLibraryItem: LocalLibraryItem? = Paper.book("localLibraryItems").read(it)
      if (localLibraryItem != null &&
                      (mediaType.isNullOrEmpty() || mediaType == localLibraryItem.mediaType)
      ) {
        localLibraryItems.add(localLibraryItem)
      }
    }
    return localLibraryItems
  }

  fun getLocalLibraryItemsInFolder(folderId: String): List<LocalLibraryItem> {
    val localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter { it.folderId == folderId }
  }

  fun getLocalLibraryItemByLId(libraryItemId: String): LocalLibraryItem? {
    return getLocalLibraryItems().find { it.libraryItemId == libraryItemId }
  }

  fun getLocalLibraryItem(localLibraryItemId: String): LocalLibraryItem? {
    return Paper.book("localLibraryItems").read(localLibraryItemId)
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
    val downloadItems: MutableList<DownloadItem> = mutableListOf()
    Paper.book("downloadItems").allKeys.forEach { downloadItemId ->
      Paper.book("downloadItems").read<DownloadItem>(downloadItemId)?.let { downloadItems.add(it) }
    }
    return downloadItems
  }

  fun saveLocalMediaProgress(mediaProgress: LocalMediaProgress) {
    Paper.book("localMediaProgress").write(mediaProgress.id, mediaProgress)
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

  // ---------------------------------------------------------------------------------------------
  // Playback history
  //
  // An append-only event log per book. New events go only to live chunks, so playback never waits
  // on the migration of a book's old "mediaItemHistory" blob; reads merge the blob (pre-migration)
  // or the legacy chunks (post-migration) with the live chunks.
  // ---------------------------------------------------------------------------------------------

  private fun historyBook(bookId: String) = Paper.book("history_$bookId")

  private val legacyHistoryBookName = "mediaItemHistory"

  // Kept out of history_<bookId> so the record of "the blob is safe to drop" does not share the
  // fate of the data it was copied into.
  private val historyMigratedBookName = "mediaItemHistoryMigrated"

  private val historyMetadataKey = "metadata"
  private val historyLegacyChunkPrefix = "legacy_chunk_"
  private val historyLiveChunkPrefix = "live_chunk_"
  private val historyChunkSize = 200
  private val historyMigrationMaxAttempts = 3
  private val historyBlobRetentionDays = 7L
  private val historyBlobRetentionMs = historyBlobRetentionDays * 24 * 3600 * 1000
  private val historyLocks = ConcurrentHashMap<String, Any>()
  private val historyMigrationLocks = ConcurrentHashMap<String, Any>()

  // PlaybackSession.mediaItemId is "<libraryItemId>" or "<libraryItemId>-<episodeId>", so a missing
  // library item id yields "" or "null-<episodeId>" (Kotlin renders a null template arg as "null").
  private fun isValidHistoryId(bookId: String): Boolean {
    return bookId.isNotEmpty() && !bookId.startsWith("null-") && !bookId.startsWith("-")
  }

  private fun historyLock(bookId: String) = historyLocks.getOrPut(bookId) { Any() }

  private fun historyMigrationLock(bookId: String) = historyMigrationLocks.getOrPut(bookId) { Any() }

  private fun historyChunkKey(prefix: String, index: Int) =
    prefix + index.toString().padStart(7, '0')

  private fun historyChunkIndices(book: io.paperdb.Book, prefix: String): List<Int> {
    return book.allKeys
      .filter { it.startsWith(prefix) }
      .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
      .sorted()
  }

  /**
   * Copy a book's legacy single-blob history into legacy chunks. Live chunks are never touched, and
   * the append lock is held only for the short metadata write, so a long migration does not block
   * seek/save history writes.
   */
  fun ensureHistoryMigrated(bookId: String) {
    if (!isValidHistoryId(bookId)) return

    synchronized(historyMigrationLock(bookId)) {
      val record = readHistoryMigrationRecord(bookId)
      if (record.isMigrated) {
        return
      }
      // Nothing clears MIGRATING on process death, so finding it here means the last attempt died
      // part-way. Never gate on it: that would strand a book that once crashed mid-migration.
      if (record.state == HistoryMigrationState.MIGRATING) {
        Log.w(tag, "ensureHistoryMigrated: $bookId was left mid-migration by attempt ${record.attempts} - retrying")
      }
      if (record.attempts >= historyMigrationMaxAttempts) {
        // Safe to give up: reads still merge the blob, so this book stays slow but complete.
        Log.e(tag, "ensureHistoryMigrated: giving up on $bookId after ${record.attempts} failed attempts")
        return
      }

      record.state = HistoryMigrationState.MIGRATING
      record.attempts += 1
      writeHistoryMigrationRecord(bookId, record)

      // Paper throws PaperDbException on a corrupt file; treat an unreadable blob as nothing to
      // import so it is not retried forever.
      val legacy =
        try {
          Paper.book(legacyHistoryBookName).read<MediaItemHistory>(bookId)
        } catch (e: Exception) {
          Log.e(tag, "ensureHistoryMigrated: unreadable legacy history for $bookId - skipping", e)
          markHistoryMigrated(bookId, record)
          return
        }
      if (legacy == null) {
        markHistoryMigrated(bookId, record)
        return
      }

      val book = historyBook(bookId)
      // Drop chunks a failed attempt left behind so a retry cannot duplicate events.
      historyChunkIndices(book, historyLegacyChunkPrefix).forEach {
        book.delete(historyChunkKey(historyLegacyChunkPrefix, it))
      }
      synchronized(historyLock(bookId)) {
        if (book.read<MediaItemHistory>(historyMetadataKey) == null) {
          book.write(historyMetadataKey, legacy.copyWithoutEvents())
        }
      }
      legacy.events.chunked(historyChunkSize).forEachIndexed { index, chunk ->
        book.write(historyChunkKey(historyLegacyChunkPrefix, index), ArrayList(chunk))
      }

      val importedCount =
        historyChunkIndices(book, historyLegacyChunkPrefix).sumOf {
          readHistoryChunk(book, historyLegacyChunkPrefix, it).size
        }
      if (importedCount == legacy.events.size) {
        // The blob is left for cleanMigratedHistoryBlobs to sweep after the retention window; reads
        // ignore it from here, so it costs only disk.
        markHistoryMigrated(bookId, record)
        Log.i(tag, "ensureHistoryMigrated: migrated $bookId ($importedCount events)")
      } else {
        // Leaves the record MIGRATING; the next call retries from a clean slate.
        Log.w(
          tag,
          "ensureHistoryMigrated: count mismatch for $bookId (imported=$importedCount, expected=${legacy.events.size}) - will retry"
        )
      }
    }
  }

  private fun readLegacyMediaItemHistory(id: String): MediaItemHistory? {
    return try {
      Paper.book(legacyHistoryBookName).read(id)
    } catch (e: Exception) {
      Log.e(tag, "readLegacyMediaItemHistory: failed to read legacy history for $id", e)
      null
    }
  }

  private fun readHistoryMigrationRecord(bookId: String): MediaItemHistoryMigrationRecord {
    return Paper.book(historyMigratedBookName).read(bookId) ?: MediaItemHistoryMigrationRecord()
  }

  private fun writeHistoryMigrationRecord(bookId: String, record: MediaItemHistoryMigrationRecord) {
    Paper.book(historyMigratedBookName).write(bookId, record)
  }

  private fun markHistoryMigrated(bookId: String, record: MediaItemHistoryMigrationRecord) {
    record.state = HistoryMigrationState.MIGRATED
    record.attempts = 0 // consecutive failures; a success must not leave the book near the cap
    record.migratedAt = System.currentTimeMillis()
    writeHistoryMigrationRecord(bookId, record)
  }

  private fun readHistoryChunk(
    book: io.paperdb.Book,
    prefix: String,
    index: Int
  ): List<MediaItemEvent> {
    return book.read<ArrayList<MediaItemEvent>>(historyChunkKey(prefix, index)) ?: emptyList()
  }

  /**
   * Append one live event to a book's history, rewriting only the current live chunk. Never reads or
   * migrates the legacy blob.
   *
   * The target chunk is derived from the chunk keys rather than tracked separately, so one write per
   * event leaves no cross-key invariant for a crash to break.
   */
  fun appendMediaItemEvent(mediaItemHistory: MediaItemHistory, event: MediaItemEvent) {
    val bookId = mediaItemHistory.id
    if (!isValidHistoryId(bookId)) return // No real library item id -> no meaningful history

    synchronized(historyLock(bookId)) {
      val book = historyBook(bookId)
      if (book.read<MediaItemHistory>(historyMetadataKey) == null) {
        book.write(historyMetadataKey, mediaItemHistory.copyWithoutEvents())
      }

      val lastIndex = historyChunkIndices(book, historyLiveChunkPrefix).lastOrNull() ?: 0
      val current = ArrayList(readHistoryChunk(book, historyLiveChunkPrefix, lastIndex))
      val isFull = current.size >= historyChunkSize
      val targetIndex = if (isFull) lastIndex + 1 else lastIndex
      val target = if (isFull) arrayListOf(event) else current.apply { add(event) }
      book.write(historyChunkKey(historyLiveChunkPrefix, targetIndex), target)
    }
  }

  private fun readLiveHistoryEvents(book: io.paperdb.Book): List<MediaItemEvent> {
    return historyChunkIndices(book, historyLiveChunkPrefix)
      .flatMap { readHistoryChunk(book, historyLiveChunkPrefix, it) }
  }

  private fun readMigratedLegacyHistoryEvents(book: io.paperdb.Book): List<MediaItemEvent> {
    return historyChunkIndices(book, historyLegacyChunkPrefix)
      .flatMap { readHistoryChunk(book, historyLegacyChunkPrefix, it) }
  }

  /**
   * Reads only the stored metadata key, never the legacy blob, so this stays cheap on the event
   * path no matter how much history a book has.
   *
   * Returns null until something has written that key - a first playback event, the migration, or a
   * history page read. Sync events are the only caller and carry no identity of their own, so for a
   * book none of those have touched there is genuinely nothing to attach one to.
   */
  fun getMediaItemHistoryMetadata(id: String): MediaItemHistory? {
    if (!isValidHistoryId(id)) return null

    synchronized(historyLock(id)) {
      return historyBook(id).read(historyMetadataKey)
    }
  }

  fun getMediaItemHistory(id: String): MediaItemHistory? {
    if (!isValidHistoryId(id)) return null

    synchronized(historyLock(id)) {
      val book = historyBook(id)
      // Only MIGRATED means the chunks are the verified copy. A stale MIGRATING leaves partial
      // legacy chunks alongside an intact blob, so reading both would double-count.
      val isMigrated = readHistoryMigrationRecord(id).isMigrated
      val storedMetadata = book.read<MediaItemHistory>(historyMetadataKey)
      val legacy = if (isMigrated) null else readLegacyMediaItemHistory(id)
      val metadata = storedMetadata ?: legacy?.copyWithoutEvents()
      if (storedMetadata == null && metadata != null) {
        book.write(historyMetadataKey, metadata)
      }

      val legacyEvents =
        if (isMigrated) {
          readMigratedLegacyHistoryEvents(book)
        } else {
          legacy?.events ?: emptyList()
        }
      val liveEvents = readLiveHistoryEvents(book)

      if (metadata == null && legacyEvents.isEmpty() && liveEvents.isEmpty()) {
        return null
      }

      val history =
        metadata
          ?: MediaItemHistory(
            id,
            "Unset",
            id,
            null,
            false,
            null,
            null,
            null,
            System.currentTimeMillis(),
            mutableListOf()
          )
      history.events = (legacyEvents + liveEvents).sortedBy { it.timestamp }.toMutableList()
      return history
    }
  }

  /**
   * Delete legacy history blobs whose migration has been settled for longer than the retention
   * window. Migrated blobs are never read, so this only reclaims disk - the window exists purely to
   * leave a manual recovery path if a migration turns out to be wrong.
   *
   * Does disk I/O per blob; call off the main thread.
   */
  fun cleanMigratedHistoryBlobs() {
    val cutoff = System.currentTimeMillis() - historyBlobRetentionMs
    var blobsRemoved = 0
    Paper.book(legacyHistoryBookName).allKeys.forEach { bookId ->
      val record = readHistoryMigrationRecord(bookId)
      if (record.isMigrated && record.migratedAt < cutoff) {
        Paper.book(legacyHistoryBookName).delete(bookId)
        blobsRemoved++
      }
    }
    if (blobsRemoved > 0) {
      AbsLogger.info(
        "DbManager",
        "cleanMigratedHistoryBlobs: Removed $blobsRemoved legacy history blobs migrated over $historyBlobRetentionDays days ago"
      )
    }
  }
}
