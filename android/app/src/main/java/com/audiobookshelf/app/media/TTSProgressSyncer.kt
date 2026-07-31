package com.audiobookshelf.app.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import java.util.*
import kotlin.concurrent.schedule

/**
 * Syncs the read aloud (TTS) ebook progress while the native engine speaks,
 * so progress reaches the local db and the server without the WebView reader
 * being open. Same 15s cadence as MediaProgressSyncer and the same
 * ebookLocation/ebookProgress format the reader saves - reading and listening
 * stay interchangeable. See docs/native-tts-player-design.md (A.6)
 */
class TTSProgressSyncer(
        val playerNotificationService: PlayerNotificationService,
        private val apiHandler: ApiHandler
) {
  private val tag = "TTSProgressSyncer"
  private val SYNC_INTERVAL = 15000L
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private var syncTimerTask: TimerTask? = null
  var syncTimerRunning = false
    private set

  // Set on every spoken paragraph, cleared after a successful local save
  private var dirty = false
  private var lastServerSyncTime: Long = 0

  fun start() {
    if (syncTimerRunning) return
    syncTimerRunning = true
    lastServerSyncTime = 0
    syncTimerTask =
            Timer("TTSProgressSyncTimer", false).schedule(SYNC_INTERVAL, SYNC_INTERVAL) {
              Handler(Looper.getMainLooper()).post {
                // Only sync with server on unmetered connection every 15s OR if
                // last server sync is >= 60s (MediaProgressSyncer behavior)
                val shouldSyncServer =
                        PlayerNotificationService.isUnmeteredNetwork ||
                                System.currentTimeMillis() - lastServerSyncTime >=
                                        METERED_CONNECTION_SYNC_INTERVAL
                sync(shouldSyncServer)
              }
            }
  }

  /** Called for every paragraph the engine reaches; position is read from the engine at sync time */
  fun paragraphReached() {
    dirty = true
  }

  /** Stop the timer and sync the final position (pause, stop, end of book, service destroy) */
  fun stop() {
    syncTimerTask?.cancel()
    syncTimerTask = null
    syncTimerRunning = false
    sync(true)
  }

  fun reset() {
    syncTimerTask?.cancel()
    syncTimerTask = null
    syncTimerRunning = false
    dirty = false
    lastServerSyncTime = 0
  }

  private fun sync(shouldSyncServer: Boolean) {
    if (!dirty) return
    val engine = playerNotificationService.ttsEngine ?: return
    val book = engine.book ?: return

    // Paragraphs without a location fall back to the chapter start so the
    // reader can still return close to the spoken position
    val location =
            engine.currentLocation
                    ?: book.chapters.getOrNull(engine.chapterIndex)?.startLocation
    val progress = engine.progress
    val lastUpdate = System.currentTimeMillis()

    if (book.libraryItemId.startsWith("local")) {
      dirty = false
      val localMediaProgress = saveLocalProgress(book.libraryItemId, location, progress, lastUpdate) ?: return

      // Local item linked to a server library item - also patch the server
      // when connected to that server (same mapping as the reader). A skipped
      // or failed patch catches up via syncLocalMediaProgressForUser.
      val serverLibraryItemId = localMediaProgress.libraryItemId
      if (shouldSyncServer &&
                      !serverLibraryItemId.isNullOrEmpty() &&
                      localMediaProgress.serverConnectionConfigId == DeviceManager.serverConnectionConfigId &&
                      DeviceManager.checkConnectivity(playerNotificationService)
      ) {
        sendServerProgress(serverLibraryItemId, location, progress, lastUpdate)
      }
    } else {
      // Streamed server item - progress lives on the server only, so keep
      // dirty and retry on the next tick until the patch can be sent
      if (!shouldSyncServer ||
                      DeviceManager.serverAddress.isEmpty() ||
                      DeviceManager.serverAddress != book.serverAddress ||
                      !DeviceManager.checkConnectivity(playerNotificationService)
      ) {
        return
      }
      dirty = false
      sendServerProgress(book.libraryItemId, location, progress, lastUpdate)
    }
  }

  private fun saveLocalProgress(
          localLibraryItemId: String,
          location: String?,
          progress: Double,
          lastUpdate: Long
  ): LocalMediaProgress? {
    var localMediaProgress = DeviceManager.dbManager.getLocalMediaProgress(localLibraryItemId)

    if (localMediaProgress == null) {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
      if (localLibraryItem == null) {
        Log.e(tag, "saveLocalProgress: Local library item $localLibraryItemId not found")
        return null
      }
      localMediaProgress =
              LocalMediaProgress(
                      id = localLibraryItemId,
                      localLibraryItemId = localLibraryItemId,
                      localEpisodeId = null,
                      duration = (localLibraryItem.media as? Book)?.duration ?: 0.0,
                      progress = 0.0,
                      currentTime = 0.0,
                      isFinished = false,
                      ebookLocation = location,
                      ebookProgress = progress,
                      lastUpdate = lastUpdate,
                      startedAt = 0L,
                      finishedAt = null,
                      serverConnectionConfigId = localLibraryItem.serverConnectionConfigId,
                      serverAddress = localLibraryItem.serverAddress,
                      serverUserId = localLibraryItem.serverUserId,
                      libraryItemId = localLibraryItem.libraryItemId,
                      episodeId = null
              )
    } else {
      localMediaProgress.updateEbookProgress(location ?: localMediaProgress.ebookLocation ?: "", progress)
    }

    DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)
    playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(localMediaProgress)
    Log.d(tag, "saveLocalProgress: Saved local ebook progress $progress (location: $location) for $localLibraryItemId")
    return localMediaProgress
  }

  private fun sendServerProgress(
          libraryItemId: String,
          location: String?,
          progress: Double,
          lastUpdate: Long
  ) {
    apiHandler.updateEbookProgress(libraryItemId, location, progress, lastUpdate) { success, errorMsg ->
      if (success) {
        lastServerSyncTime = System.currentTimeMillis()
        AbsLogger.info(tag, "sync: Synced TTS ebook progress $progress for item \"$libraryItemId\"")
      } else {
        // Keep dirty so the next tick retries with the latest position
        dirty = true
        AbsLogger.error(tag, "sync: Failed to sync TTS ebook progress for item \"$libraryItemId\" ($errorMsg)")
      }
    }
  }
}
