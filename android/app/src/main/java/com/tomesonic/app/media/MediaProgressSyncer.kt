package com.tomesonic.app.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tomesonic.app.data.LocalMediaProgress
import com.tomesonic.app.data.MediaProgress
import com.tomesonic.app.data.PlaybackSession
import com.tomesonic.app.data.PlayerState
import com.tomesonic.app.data.PlaybackMetadata
import com.tomesonic.app.device.DeviceManager
import com.tomesonic.app.player.PlayerNotificationService
import com.tomesonic.app.player.CastPlayerManager
import com.tomesonic.app.plugins.AbsLogger
import com.tomesonic.app.server.ApiHandler
import java.util.*
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
        val playerNotificationService: PlayerNotificationService,
        private val apiHandler: ApiHandler
) {
  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false

  private var lastSyncTime: Long = 0
  private var failedSyncs: Int = 0

  var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  var currentLocalMediaProgress: LocalMediaProgress? = null
  var isInitialSessionEstablishment: Boolean = true // Track if this is the first time establishing a session
  private var lastPauseTime: Long = 0 // Track when playback was paused to determine if this is a resume vs new session

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration
    get() = currentPlaybackSession?.duration ?: 0.0

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
        isInitialSessionEstablishment = true // New session, reset establishment flag
        lastPauseTime = 0 // Reset pause tracking
      } else {
        // Check if this is a resume after a short pause vs initial session establishment
        val currentTime = System.currentTimeMillis()
        val timeSincePause = if (lastPauseTime > 0) currentTime - lastPauseTime else Long.MAX_VALUE
        isInitialSessionEstablishment = timeSincePause > 30000 // Only consider initial if paused > 30 seconds
        Log.d(tag, "start: Continuing session, time since pause: ${timeSincePause}ms, isInitial: $isInitialSessionEstablishment")
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
      isInitialSessionEstablishment = true // New session
      lastPauseTime = 0 // Reset pause tracking
    } else {
      // Same session - check if this is after a significant pause
      val currentTime = System.currentTimeMillis()
      val timeSincePause = if (lastPauseTime > 0) currentTime - lastPauseTime else Long.MAX_VALUE
      isInitialSessionEstablishment = timeSincePause > 30000 // Only consider initial if paused > 30 seconds
      Log.d(tag, "start: Same session, time since pause: ${timeSincePause}ms, isInitial: $isInitialSessionEstablishment")
    }

    listeningTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()

    // Immediately persist local progress when a playback session starts.
    // This ensures sessions that begin in a native-only context (Android Auto)
    // are saved locally even if the webview is not available yet.
    try {
      currentPlaybackSession?.let { saveLocalProgress(it) }
    } catch (e: Exception) {
      Log.e(tag, "start: Failed to save initial local progress: ${e.message}")
    }
    Log.d(
            tag,
            "start: init last sync time $lastSyncTime with playback session id=${currentPlaybackSession?.id}"
    )

    listeningTimerTask =
            Timer("ListeningTimer", false).schedule(15000L, 15000L) {
              Handler(Looper.getMainLooper()).post() {
                if (playerNotificationService.currentPlayer.isPlaying) {
                  // Set auto sleep timer if enabled and within start/end time
                  playerNotificationService.sleepTimerManager.checkAutoSleepTimer()

                  // Update Android Auto queue position for track-based books only
                  // For chapter-based books, only update when Android Auto navigates
                  val currentSession = playerNotificationService.currentPlaybackSession
                  if (currentSession != null && currentSession.audioTracks.size > 1) {
                    playerNotificationService.updateQueuePositionForChapters()
                  }

                  // Only sync with server on unmetered connection every 15s OR sync with server if
                  // last sync time is >= 60s
                  val shouldSyncServer =
                          playerNotificationService.networkConnectivityManager.isUnmeteredNetwork ||
                                  System.currentTimeMillis() - lastSyncTime >=
                                          METERED_CONNECTION_SYNC_INTERVAL

                  val currentTime = playerNotificationService.getCurrentTimeSeconds()
                  if (currentTime > 0) {
                    sync(shouldSyncServer, currentTime) { syncResult ->
                      Log.d(tag, "Sync complete")

                      currentPlaybackSession?.let { playbackSession ->
                        MediaEventManager.saveEvent(playbackSession, syncResult)
                      }
                    }

                    // Send real-time progress updates to frontend during cast playbook
                    val mediaPlayerType = playerNotificationService.getMediaPlayer()
                    if (mediaPlayerType == CastPlayerManager.PLAYER_CAST) {
                      // For cast players, send continuous progress updates since cast position
                      // events might not fire as frequently as local player events
                      val totalBookDurationSeconds = playerNotificationService.getDuration() / 1000.0 // Total book duration in seconds

                      // For cast players, always use absolute book progress for Cast receiver UI
                      // The Cast receiver should show overall book progress, not chapter progress
                      val absoluteBookTimeSeconds = currentTime // This is already absolute time

                      playerNotificationService.clientEventEmitter?.onMetadata(
                        PlaybackMetadata(totalBookDurationSeconds, absoluteBookTimeSeconds, PlayerState.READY)
                      )
                      Log.d(tag, "Sent cast progress update: absolute=${absoluteBookTimeSeconds}s/${totalBookDurationSeconds}s (book progress)")
                    }
                  }
                }
              }
            }
  }

  fun play(playbackSession: PlaybackSession) {
    Log.d(tag, "play ${playbackSession.displayTitle}")
    MediaEventManager.playEvent(playbackSession)

    start(playbackSession)
    // Try to force an immediate sync after playback starts so remote progress
    // is updated promptly (if network/server available).
    try {
      forceSyncNow(true) {
        // After first sync, this is no longer initial establishment
        isInitialSessionEstablishment = false
      }
    } catch (e: Exception) {
      Log.e(tag, "play: forceSyncNow failed: ${e.message}")
    }
  }


  /**
   * Force an immediate sync attempt. This temporarily adjusts internal timings so
   * sync() will run even if lastSyncTime was just set.
   * shouldSyncServer controls whether a server sync will be attempted (sync() still
   * guards based on connectivity and server config).
   */
  fun forceSyncNow(shouldSyncServer: Boolean, cb: (SyncResult?) -> Unit) {
    if (currentPlaybackSession == null) {
      return cb(null)
    }

    // Make sure lastSyncTime is sufficiently in the past so sync() will proceed.
    lastSyncTime = System.currentTimeMillis() - 2000L

    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime <= 0) {
      return cb(null)
    }

    sync(shouldSyncServer, currentTime, cb)
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

    val currentTime =
            if (shouldSync == true) playerNotificationService.getCurrentTimeSeconds() else 0.0
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
    lastPauseTime = System.currentTimeMillis() // Track pause time for resume detection
    Log.d(tag, "pause: Pausing progress syncer for $currentDisplayTitle at ${lastPauseTime}")
    Log.d(tag, "pause: Last sync time $lastSyncTime")

    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      sync(true, currentTime) { syncResult ->
        lastSyncTime = 0L
        Log.d(tag, "pause: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0

        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.pauseEvent(playbackSession, syncResult)
        }

        cb()
      }
    } else {
      lastSyncTime = 0L
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime (current time < 0)")
      failedSyncs = 0

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.pauseEvent(playbackSession, null)
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
    val newCurrentTime = playerNotificationService.getCurrentTimeSeconds()
    val oldCurrentTime = currentPlaybackSession?.currentTime
    currentPlaybackSession?.currentTime = newCurrentTime
    Log.d(tag, "seek: $currentDisplayTitle, currentTime updated from ${oldCurrentTime}s to ${newCurrentTime}s (${newCurrentTime*1000}ms)")

    if (currentPlaybackSession == null) {
      Log.e(tag, "seek: Playback session not set")
      return
    }

    MediaEventManager.seekEvent(currentPlaybackSession!!, null)
  }

  /**
   * Updates position for Cast player progress synchronization
   * Called periodically by Cast player position updates
   */
  fun onPositionUpdate() {
    if (listeningTimerRunning) {
      val newCurrentTime = playerNotificationService.getCurrentTimeSeconds()
      val oldCurrentTime = currentPlaybackSession?.currentTime
      currentPlaybackSession?.currentTime = newCurrentTime

      // Only sync to UI/callbacks more frequently for Cast to keep UI responsive
      val isCasting = playerNotificationService.currentPlayer == playerNotificationService.castPlayer
      if (isCasting) {
        // For Cast player, trigger UI updates more frequently
        currentPlaybackSession?.let { playbackSession ->
          val playbackMetadata = PlaybackMetadata(
            playbackSession.duration,
            newCurrentTime,
            PlayerState.READY
          )
          playerNotificationService.clientEventEmitter?.onMetadata(playbackMetadata)
        }
        Log.v(tag, "onPositionUpdate [CAST]: Updated currentTime from ${oldCurrentTime}s to ${newCurrentTime}s")
      }
    }
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

      // Also update the device's last playback session for resume functionality
      DeviceManager.setLastPlaybackSession(it)
    }
  }

  fun sync(shouldSyncServer: Boolean, currentTime: Double, cb: (SyncResult?) -> Unit) {
    if (lastSyncTime <= 0) {
      Log.e(tag, "Last sync time is not set $lastSyncTime")
      return cb(null)
    }

    val diffSinceLastSync = System.currentTimeMillis() - lastSyncTime
    if (diffSinceLastSync < 1000L) {
      return cb(null)
    }
    val listeningTimeToAdd = diffSinceLastSync / 1000L

    val syncData = MediaProgressSyncData(listeningTimeToAdd, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(syncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(
              tag,
              "Current Playback Session invalid progress ${currentPlaybackSession?.progress} | Current Time: ${currentPlaybackSession?.currentTime} | Duration: ${currentPlaybackSession?.getTotalDuration()}"
      )
      return cb(null)
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(playerNotificationService)

    // Save playback session to db (server linked sessions only)
    //   Sessions are removed once successfully synced with the server
    currentPlaybackSession?.let {
      DeviceManager.dbManager.savePlaybackSession(it)

      // Also update the device's last playback session for resume functionality
      DeviceManager.setLastPlaybackSession(it)
    }

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
          // Before pushing local progress to server, query server progress and only update if
          // local progress is more recent/further. This prevents overwriting newer remote progress
          // from other devices.
          try {
            apiHandler.getMediaProgress(it.libraryItemId ?: "", it.episodeId, DeviceManager.getServerConnectionConfig(it.serverConnectionConfigId)) { serverProgress ->
              if (serverProgress == null) {
                // No server progress available or fetch failed; attempt to send local progress
                apiHandler.sendLocalProgressSync(it) { syncSuccess, errorMsg ->
                  if (syncSuccess) {
                    failedSyncs = 0
                    playerNotificationService.alertSyncSuccess()
                    DeviceManager.dbManager.removePlaybackSession(it.id) // Remove session from db
                    AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")
                  } else {
                    failedSyncs++
                    if (failedSyncs == 2) {
                      playerNotificationService.alertSyncFailing() // Show alert in client
                      failedSyncs = 0
                    }
                    AbsLogger.error("MediaProgressSyncer", "sync: Local progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (${DeviceManager.serverConnectionConfigName})")
                  }

                  cb(SyncResult(true, syncSuccess, errorMsg))
                }
              } else {
                // Use improved comparison logic that considers both timestamps and progress
                val shouldSendLocalProgress = shouldSendLocalProgressToServer(it, serverProgress)

                if (shouldSendLocalProgress) {
                  apiHandler.sendLocalProgressSync(it) { syncSuccess, errorMsg ->
                    if (syncSuccess) {
                      failedSyncs = 0
                      playerNotificationService.alertSyncSuccess()
                      DeviceManager.dbManager.removePlaybackSession(it.id) // Remove session from db
                      AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")
                    } else {
                      failedSyncs++
                      if (failedSyncs == 2) {
                        playerNotificationService.alertSyncFailing() // Show alert in client
                        failedSyncs = 0
                      }
                      AbsLogger.error("MediaProgressSyncer", "sync: Local progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (${DeviceManager.serverConnectionConfigName})")
                    }
                    cb(SyncResult(true, syncSuccess, errorMsg))
                  }
                } else {
                  AbsLogger.info("MediaProgressSyncer", "sync: Server progress is more recent or equal; not sending local progress (title: \"$currentDisplayTitle\") (serverLastUpdate: ${serverProgress.lastUpdate}) (localUpdatedAt: ${it.updatedAt})")
                  cb(SyncResult(false, null, null))
                }
              }
            }
          } catch (e: Exception) {
            AbsLogger.error("MediaProgressSyncer", "sync: Failed to compare server progress before send: ${e.message}")
            // Fallback to attempt send
            apiHandler.sendLocalProgressSync(it) { syncSuccess, errorMsg ->
              if (syncSuccess) {
                failedSyncs = 0
                playerNotificationService.alertSyncSuccess()
                DeviceManager.dbManager.removePlaybackSession(it.id) // Remove session from db
                AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")
              } else {
                failedSyncs++
                if (failedSyncs == 2) {
                  playerNotificationService.alertSyncFailing() // Show alert in client
                  failedSyncs = 0
                }
                AbsLogger.error("MediaProgressSyncer", "sync: Local progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (${DeviceManager.serverConnectionConfigName})")
              }
              cb(SyncResult(true, syncSuccess, errorMsg))
            }
          }
        } else {
          AbsLogger.info("MediaProgressSyncer", "sync: Not sending local progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (hasNetworkConnection: $hasNetworkConnection) (isConnectedToSameServer: $isConnectedToSameServer)")
          cb(SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      AbsLogger.info("MediaProgressSyncer", "sync: Sending progress sync to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")

      apiHandler.sendProgressSync(currentSessionId, syncData) { syncSuccess, errorMsg ->
        if (syncSuccess) {
          AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")

          failedSyncs = 0
          playerNotificationService.alertSyncSuccess()
          lastSyncTime = System.currentTimeMillis()
          DeviceManager.dbManager.removePlaybackSession(currentSessionId) // Remove session from db
        } else {
          failedSyncs++
          if (failedSyncs == 2) {
            playerNotificationService.alertSyncFailing() // Show alert in client
            failedSyncs = 0
          }
          AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName})")
        }
        cb(SyncResult(true, syncSuccess, errorMsg))
      }
    } else {
      AbsLogger.info("MediaProgressSyncer", "sync: Not sending progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName}) (hasNetworkConnection: $hasNetworkConnection)")
      cb(SyncResult(false, null, null))
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
        playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
        Log.d(
                tag,
                "Saved Local Progress Current Time: ID ${it.id} | ${it.currentTime} | Duration ${it.duration} | Progress ${it.progressPercent}%"
        )
      }
    }
  }

  /**
   * Determines if server progress should override local progress
   * Takes into account timestamp differences, progress differences, and session state
   */
  fun shouldUseServerProgress(serverProgress: MediaProgress, localProgress: PlaybackSession): Boolean {
    val timeDifference = serverProgress.lastUpdate - localProgress.updatedAt
    val progressDifference = serverProgress.progress - localProgress.progress
    val timeDifferenceSeconds = timeDifference / 1000.0

    Log.d(tag, "shouldUseServerProgress: Server time=${serverProgress.lastUpdate}, Local time=${localProgress.updatedAt}")
    Log.d(tag, "shouldUseServerProgress: Server progress=${serverProgress.progress}, Local progress=${localProgress.progress}")
    Log.d(tag, "shouldUseServerProgress: Time diff=${timeDifferenceSeconds}s, Progress diff=${progressDifference}")
    Log.d(tag, "shouldUseServerProgress: Initial session=${isInitialSessionEstablishment}")

    // If this is not an initial session establishment, be very conservative
    if (!isInitialSessionEstablishment) {
      Log.d(tag, "shouldUseServerProgress: Not initial session - preserving local progress")
      return false
    }

    // For initial sessions, only use server progress if:
    // 1. Server timestamp is significantly newer (more than 30 seconds)
    // 2. AND either server progress is significantly ahead OR timestamps are very different
    val serverIsSignificantlyNewer = timeDifferenceSeconds > 30
    val serverProgressIsAhead = progressDifference > 0.001 // More than 0.1% ahead
    val timestampVeryDifferent = Math.abs(timeDifferenceSeconds) > 60 // More than 1 minute difference

    val shouldUse = serverIsSignificantlyNewer && (serverProgressIsAhead || timestampVeryDifferent)

    Log.d(tag, "shouldUseServerProgress: ServerNewer=$serverIsSignificantlyNewer, ProgressAhead=$serverProgressIsAhead, TimestampDiff=$timestampVeryDifferent")
    Log.d(tag, "shouldUseServerProgress: Result=$shouldUse")

    return shouldUse
  }

  /**
   * Determines if local progress should be sent to server, considering both timestamps and absolute progress
   */
  private fun shouldSendLocalProgressToServer(localSession: PlaybackSession, serverProgress: MediaProgress): Boolean {
    val timeDifference = localSession.updatedAt - serverProgress.lastUpdate
    val progressDifference = localSession.progress - serverProgress.progress
    val timeDifferenceSeconds = timeDifference / 1000.0
    val currentTimeDifference = localSession.currentTime - serverProgress.currentTime

    Log.d(tag, "shouldSendLocalProgressToServer: Local time=${localSession.updatedAt}, Server time=${serverProgress.lastUpdate}")
    Log.d(tag, "shouldSendLocalProgressToServer: Local progress=${localSession.progress}, Server progress=${serverProgress.progress}")
    Log.d(tag, "shouldSendLocalProgressToServer: Local currentTime=${localSession.currentTime}, Server currentTime=${serverProgress.currentTime}")
    Log.d(tag, "shouldSendLocalProgressToServer: Time diff=${timeDifferenceSeconds}s, Progress diff=${progressDifference}")

    // Send local progress if:
    // 1. Local timestamp is newer AND (local progress is ahead OR local is significantly more recent)
    // 2. OR local progress is significantly ahead regardless of timestamp (to handle clock sync issues)
    val localIsNewer = timeDifferenceSeconds > 0
    val localProgressIsAhead = progressDifference > 0.001 || currentTimeDifference > 30 // 30 seconds ahead in absolute time
    val localIsSignificantlyNewer = timeDifferenceSeconds > 30 // More than 30 seconds newer
    val localIsSignificantlyAhead = progressDifference > 0.01 // More than 1% ahead in progress

    val shouldSend = (localIsNewer && (localProgressIsAhead || localIsSignificantlyNewer)) || localIsSignificantlyAhead

    Log.d(tag, "shouldSendLocalProgressToServer: LocalNewer=$localIsNewer, ProgressAhead=$localProgressIsAhead")
    Log.d(tag, "shouldSendLocalProgressToServer: SignificantlyNewer=$localIsSignificantlyNewer, SignificantlyAhead=$localIsSignificantlyAhead")
    Log.d(tag, "shouldSendLocalProgressToServer: Result=$shouldSend")

    return shouldSend
  }

  fun reset() {
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    lastPauseTime = 0L
    Log.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
    isInitialSessionEstablishment = true
  }
}
