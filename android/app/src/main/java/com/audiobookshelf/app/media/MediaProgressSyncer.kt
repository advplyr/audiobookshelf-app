package com.audiobookshelf.app.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.core.PlaybackTelemetryHost
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

data class MediaProgressSyncData(
        var timeListened: Long, // seconds
        var duration: Double, // seconds
        var currentTime: Double // seconds
)

data class SyncResult(
        var serverSyncAttempted: Boolean,
        var serverSyncSuccess: Boolean?,
        var serverSyncMessage: String?
)

class MediaProgressSyncer(
  private val telemetryHost: PlaybackTelemetryHost,
  private val apiHandler: ApiHandler
) {
  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private val mainHandler = Handler(Looper.getMainLooper())

  private fun postCallback(cb: (SyncResult?) -> Unit, result: SyncResult?) {
    mainHandler.post { cb(result) }
  }

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false
  private var pendingManualPlaybackTime: Double? = null
  private var pendingManualPlaybackTimeExpiresAt: Long = 0

  private var lastSyncTime: Long = 0
  private var failedSyncs: Int = 0

  var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  var currentLocalMediaProgress: LocalMediaProgress? = null

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration
    get() = currentPlaybackSession?.duration ?: 0.0
  private var nextPlaybackEventSource = PlaybackEventSource.SYSTEM

  fun markNextPlaybackEventSource(source: PlaybackEventSource) {
    nextPlaybackEventSource = source
  }

  fun start(playbackSession: PlaybackSession) {
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (playbackSession.id != currentSessionId) {
        Log.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        lastSyncTime = 0L
        Log.d(tag, "start: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
    }

    listeningTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()
    Log.d(
            tag,
            "start: init last sync time $lastSyncTime with playback session id=${currentPlaybackSession?.id}"
    )

    listeningTimerTask =
            Timer("ListeningTimer", false).schedule(15000L, 15000L) {
        Handler(Looper.getMainLooper()).post {
            if (telemetryHost.isPlayerActive()) {
                  // Set auto sleep timer if enabled and within start/end time
              telemetryHost.checkAutoSleepTimer()

                  // Only sync with server on unmetered connection every 15s OR sync with server if
                  // last sync time is >= 60s
                  val shouldSyncServer =
                telemetryHost.isUnmeteredNetwork ||
                                  System.currentTimeMillis() - lastSyncTime >=
                                          METERED_CONNECTION_SYNC_INTERVAL

                  val currentTime = telemetryHost.getCurrentTimeSeconds()
                  if (currentTime > 0) {
                    sync(shouldSyncServer, currentTime) { syncResult ->
                      Log.d(tag, "Sync complete")

                      currentPlaybackSession?.let { playbackSession ->
                        MediaEventManager.saveEvent(playbackSession, syncResult)
                      }
                    }
                  }
                }
              }
            }
  }

  fun play(playbackSession: PlaybackSession) {
    Log.d(tag, "play ${playbackSession.displayTitle}")
    val source = nextPlaybackEventSource
    nextPlaybackEventSource = PlaybackEventSource.SYSTEM
    applyRefreshedTimeToSession(playbackSession)
    MediaEventManager.playEvent(playbackSession, source)
    if (source == PlaybackEventSource.UI) {
      consumeManualPlaybackTime()
    }

    start(playbackSession)
  }

  fun stop(shouldSync: Boolean? = true, cb: () -> Unit) {
    if (!listeningTimerRunning) {
      reset()
      return cb()
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "stop: Stopping listening for $currentDisplayTitle")

    val currentTime = if (shouldSync == true) telemetryHost.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      sync(true, currentTime) { syncResult ->
        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.stopEvent(playbackSession, syncResult)
        }

        reset()
        cb()
      }
    } else {
      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.stopEvent(playbackSession, null)
      }

      reset()
      cb()
    }
  }

  fun pause(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "pause: Pausing progress syncer for $currentDisplayTitle")
    Log.d(tag, "pause: Last sync time $lastSyncTime")

    val currentTime = telemetryHost.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      sync(true, currentTime) { syncResult ->
        lastSyncTime = 0L
        Log.d(tag, "pause: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0

        currentPlaybackSession?.let { playbackSession ->
          val source = nextPlaybackEventSource
          nextPlaybackEventSource = PlaybackEventSource.SYSTEM
          applyRefreshedTimeToSession(playbackSession)
          MediaEventManager.pauseEvent(playbackSession, syncResult, source)
        }

        cb()
      }
    } else {
      lastSyncTime = 0L
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime (current time < 0)")
      failedSyncs = 0

      currentPlaybackSession?.let { playbackSession ->
        val source = nextPlaybackEventSource
        nextPlaybackEventSource = PlaybackEventSource.SYSTEM
        applyRefreshedTimeToSession(playbackSession)
        MediaEventManager.pauseEvent(playbackSession, null, source)
      }

      cb()
    }
  }

  fun finished(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "finished: Stopping listening for $currentDisplayTitle")

    sync(true, currentPlaybackSession?.duration ?: 0.0) { syncResult ->
      reset()

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.finishedEvent(playbackSession, syncResult)
      }

      cb()
    }
  }

  fun seek() {
    resolveCurrentPlaybackTime()
    Log.d(tag, "seek: $currentDisplayTitle, currentTime=${currentPlaybackSession?.currentTime}")

    if (currentPlaybackSession == null) {
      Log.e(tag, "seek: Playback session not set")
      return
    }

    MediaEventManager.seekEvent(currentPlaybackSession!!, null)
  }

  // Currently unused
  fun syncFromServerProgress(mediaProgress: MediaProgress) {
    currentPlaybackSession?.let {
      it.updatedAt = mediaProgress.lastUpdate
      it.currentTime = mediaProgress.currentTime

      MediaEventManager.syncEvent(
              mediaProgress,
              "Received from server get media progress request while playback session open"
      )
      saveLocalProgress(it)
    }
  }

  fun sync(shouldSyncServer: Boolean, currentTime: Double, cb: (SyncResult?) -> Unit) {
    if (lastSyncTime <= 0) {
      Log.e(tag, "Last sync time is not set $lastSyncTime")
      postCallback(cb, null)
      return
    }

    val diffSinceLastSync = System.currentTimeMillis() - lastSyncTime
    if (diffSinceLastSync < 1000L) {
      postCallback(cb, null)
      return
    }
    val listeningTimeToAdd = diffSinceLastSync / 1000L

    val syncData = MediaProgressSyncData(listeningTimeToAdd, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(syncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(
              tag,
              "Current Playback Session invalid progress ${currentPlaybackSession?.progress} | Current Time: ${currentPlaybackSession?.currentTime} | Duration: ${currentPlaybackSession?.getTotalDuration()}"
      )
      postCallback(cb, null)
      return
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(telemetryHost.appContext)

    // Save playback session to db (server linked sessions only)
    //   Sessions are removed once successfully synced with the server
    currentPlaybackSession?.let { DeviceManager.dbManager.savePlaybackSession(it) }

    if (currentIsLocal) {
      // Save local progress sync
      currentPlaybackSession?.let {
        saveLocalProgress(it)
        lastSyncTime = System.currentTimeMillis()

        Log.d(
                tag,
                "Sync local device current serverConnectionConfigId=${DeviceManager.serverConnectionConfig?.id}"
        )
        AbsLogger.info("MediaProgressSyncer", "sync: Saved local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")

        // Local library item is linked to a server library item
        // Send sync to server also if connected to this server and local item belongs to this
        // server
        val isConnectedToSameServer = it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId
        if (hasNetworkConnection &&
                        shouldSyncServer &&
                        !it.libraryItemId.isNullOrEmpty() &&
                        isConnectedToSameServer
        ) {
          apiHandler.sendLocalProgressSync(it) { syncSuccess, errorMsg ->
            if (syncSuccess) {
              failedSyncs = 0
              telemetryHost.alertSyncSuccess()
              DeviceManager.dbManager.removePlaybackSession(it.id) // Remove session from db
              AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")
            } else {
              failedSyncs++
              if (failedSyncs == 2) {
                telemetryHost.alertSyncFailing() // Show alert in client
                failedSyncs = 0
              }
              AbsLogger.error("MediaProgressSyncer", "sync: Local progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (${DeviceManager.serverConnectionConfigName})")
            }

            postCallback(cb, SyncResult(true, syncSuccess, errorMsg))
          }
        } else {
          AbsLogger.info("MediaProgressSyncer", "sync: Not sending local progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (hasNetworkConnection: $hasNetworkConnection) (isConnectedToSameServer: $isConnectedToSameServer)")
          postCallback(cb, SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      AbsLogger.info("MediaProgressSyncer", "sync: Sending progress sync to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")

      apiHandler.sendProgressSync(currentSessionId, syncData) { syncSuccess, errorMsg ->
        if (syncSuccess) {
          AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")

          failedSyncs = 0
          telemetryHost.alertSyncSuccess()
          lastSyncTime = System.currentTimeMillis()
          DeviceManager.dbManager.removePlaybackSession(currentSessionId) // Remove session from db
        } else {
          failedSyncs++
          if (failedSyncs == 2) {
            telemetryHost.alertSyncFailing() // Show alert in client
            failedSyncs = 0
          }
          AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName})")
        }
        postCallback(cb, SyncResult(true, syncSuccess, errorMsg))
      }
    } else {
      AbsLogger.info("MediaProgressSyncer", "sync: Not sending progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName}) (hasNetworkConnection: $hasNetworkConnection)")
      postCallback(cb, SyncResult(false, null, null))
    }
  }

  private fun saveLocalProgress(playbackSession: PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      val mediaProgress =
              DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      if (mediaProgress == null) {
        currentLocalMediaProgress = playbackSession.getNewLocalMediaProgress()
      } else {
        currentLocalMediaProgress = mediaProgress
        currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    currentLocalMediaProgress?.let {
      if (it.progress.isNaN()) {
        Log.e(tag, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        telemetryHost.notifyLocalProgressUpdate(it)
        Log.d(
                tag,
                "Saved Local Progress Current Time: ID ${it.id} | ${it.currentTime} | Duration ${it.duration} | Progress ${it.progressPercent}%"
        )
      }
    }
  }

  fun reset() {
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    Log.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
    pendingManualPlaybackTime = null
    pendingManualPlaybackTimeExpiresAt = 0
  }

  fun updatePlaybackTimeFromUi(seconds: Double) {
    val duration = currentPlaybackSession?.getTotalDuration() ?: 0.0
    val clampedSeconds = when {
      duration > 0 -> seconds.coerceIn(0.0, duration)
      else -> seconds.coerceAtLeast(0.0)
    }
    pendingManualPlaybackTime = clampedSeconds
    currentPlaybackSession?.currentTime = clampedSeconds
    pendingManualPlaybackTimeExpiresAt = System.currentTimeMillis() + 5000L
  }

  private fun consumeManualPlaybackTime() {
    pendingManualPlaybackTime = null
    pendingManualPlaybackTimeExpiresAt = 0
  }

  private fun resolveCurrentPlaybackTime(): Double {
    val manualTime = pendingManualPlaybackTime
    if (manualTime != null) {
      if (System.currentTimeMillis() <= pendingManualPlaybackTimeExpiresAt) {
        currentPlaybackSession?.currentTime = manualTime
        return manualTime
      }
      pendingManualPlaybackTime = null
      pendingManualPlaybackTimeExpiresAt = 0
    }
    val current = telemetryHost.getCurrentTimeSeconds()
    if (currentPlaybackSession != null && current >= 0) {
      currentPlaybackSession?.currentTime = current
    }
    return current
  }

  private fun applyRefreshedTimeToSession(eventSession: PlaybackSession?) {
    if (eventSession == null) return
    val updatedTime = resolveCurrentPlaybackTime()
    if (updatedTime >= 0) {
      eventSession.currentTime = updatedTime
    }
  }
}
