package com.audiobookshelf.app.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

data class MediaProgressSyncData(
  var timeListened:Long, // seconds
  var duration:Double, // seconds
  var currentTime:Double // seconds
)

class MediaProgressSyncer(playerNotificationService:PlayerNotificationService, apiHandler: ApiHandler) {
  private val tag = "MediaProgressSync"
  private val playerNotificationService:PlayerNotificationService = playerNotificationService
  private val apiHandler = apiHandler

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning:Boolean = false

  private var lastSyncTime:Long = 0

  var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  var currentLocalMediaProgress: LocalMediaProgress? = null

  val currentDisplayTitle get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal get() = currentPlaybackSession?.isLocal == true
  val currentSessionId get() = currentPlaybackSession?.id ?: ""
  val currentPlaybackDuration get() = currentPlaybackSession?.duration ?: 0.0

  fun start() {
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (playerNotificationService.getCurrentPlaybackSessionId() != currentSessionId) {
        Log.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        lastSyncTime = 0L
      } else {
        return
      }
    }
    listeningTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playerNotificationService.getCurrentPlaybackSessionCopy()

    listeningTimerTask = Timer("ListeningTimer", false).schedule(0L, 5000L) {
      Handler(Looper.getMainLooper()).post() {
        if (playerNotificationService.currentPlayer.isPlaying) {
          var currentTime = playerNotificationService.getCurrentTimeSeconds()
          sync(currentTime)
        }
      }
    }
  }

  fun stop() {
    if (!listeningTimerRunning) return
    Log.d(tag, "stop: Stopping listening for $currentDisplayTitle")

    var currentTime = playerNotificationService.getCurrentTimeSeconds()
    sync(currentTime)
    reset()
  }

  fun sync(currentTime:Double) {
    var diffSinceLastSync = System.currentTimeMillis() - lastSyncTime
    if (diffSinceLastSync < 1000L) {
      return
    }
    var listeningTimeToAdd = diffSinceLastSync / 1000L
    lastSyncTime = System.currentTimeMillis()

    var syncData = MediaProgressSyncData(listeningTimeToAdd,currentPlaybackDuration,currentTime)

    currentPlaybackSession?.syncData(syncData)
    if (currentIsLocal) {
      // Save local progress sync
      currentPlaybackSession?.let {
        DeviceManager.dbManager.saveLocalPlaybackSession(it)
        saveLocalProgress(it)

        // Send sync to server also if connected to this server and local item belongs to this server
        if (it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId) {
          apiHandler.sendLocalProgressSync(it) {
            Log.d(tag, "Local progress sync data sent to server $currentDisplayTitle for time $currentTime")
          }
        }
      }
    } else {
      apiHandler.sendProgressSync(currentSessionId, syncData) {
        Log.d(tag, "Progress sync data sent to server $currentDisplayTitle for time $currentTime")
      }
    }
  }

  private fun saveLocalProgress(playbackSession:PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      var mediaProgress = DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      if (mediaProgress == null) {
        currentLocalMediaProgress = playbackSession.getNewLocalMediaProgress()
      } else {
        currentLocalMediaProgress = mediaProgress
      }
    } else {
      currentLocalMediaProgress?.currentTime = playbackSession.currentTime
      currentLocalMediaProgress?.lastUpdate = playbackSession.updatedAt
      currentLocalMediaProgress?.progress = playbackSession.progress
    }
    currentLocalMediaProgress?.let {
      DeviceManager.dbManager.saveLocalMediaProgress(it)
      playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
      Log.d(tag, "Saved Local Progress Current Time: ${it.currentTime} | Duration ${it.duration} | Progress ${(it.progress * 100).roundToInt()}%")
    }
  }

  fun reset() {
    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
  }
}
