package com.tomesonic.app.plugins

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tomesonic.app.MainActivity
import com.tomesonic.app.R
import com.tomesonic.app.data.*
import com.tomesonic.app.device.DeviceManager
import com.tomesonic.app.media.MediaEventManager
import com.tomesonic.app.player.CastManager
import com.tomesonic.app.player.PlayerListener
import com.tomesonic.app.player.PlayerNotificationService
import com.tomesonic.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
// Cast-related imports for Media3
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
// MediaRouter for Cast device discovery
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.media.CastMediaOptions
import org.json.JSONObject

@CapacitorPlugin(name = "AbsAudioPlayer")
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler:ApiHandler

  // Rate limiting for socket updates to prevent overwhelming the player
  private var lastSocketUpdateTime = 0L
  private val SOCKET_UPDATE_MIN_INTERVAL = 1000L // Minimum 1 second between socket updates
  private var castContext: CastContext? = null
  private var mediaRouter: MediaRouter? = null
  private var mediaRouteSelector: MediaRouteSelector? = null

  lateinit var playerNotificationService: PlayerNotificationService

  private var isCastAvailable: Boolean = false

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    try {
      initCastManager()
    } catch(e:Exception) {
      Log.e(tag, "initCastManager exception ${e.printStackTrace()}")
    }

    val foregroundServiceReady : () -> Unit = {
      Log.d(tag, "foregroundServiceReady callback called - service is now initialized")
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.clientEventEmitter = (object : PlayerNotificationService.ClientEventEmitter {
        override fun onPlaybackSession(playbackSession: PlaybackSession) {
          notifyListeners("onPlaybackSession", JSObject(jacksonMapper.writeValueAsString(playbackSession)))
        }

        override fun onPlaybackClosed() {
          emit("onPlaybackClosed", true)
          // Ensure progress is saved and synced when playback is closed (native-only sessions)
          try {
            Handler(Looper.getMainLooper()).post {
              try {
                playerNotificationService.mediaProgressSyncer.stop(true) { /* finished */ }
              } catch (e: Exception) {
                Log.e(tag, "onPlaybackClosed: Failed to stop/sync mediaProgressSyncer: ${e.message}")
              }
            }
          } catch (e: Exception) {
            Log.e(tag, "onPlaybackClosed: Exception scheduling stop: ${e.message}")
          }
        }

        override fun onPlayingUpdate(isPlaying: Boolean) {
          emit("onPlayingUpdate", isPlaying)
          // When playback state changes, persist and try to sync immediately.
          try {
            Handler(Looper.getMainLooper()).post {
              try {
                if (isPlaying) {
                  // Force an immediate sync attempt on start (will respect network/settings)
                  playerNotificationService.mediaProgressSyncer.forceSyncNow(true) { /* result ignored */ }
                } else {
                  // On pause, ensure local progress is saved and attempt to push to server
                  playerNotificationService.mediaProgressSyncer.pause { /* result ignored */ }
                }
              } catch (e: Exception) {
                Log.e(tag, "onPlayingUpdate: Failed to trigger sync/pause: ${e.message}")
              }
            }
          } catch (e: Exception) {
            Log.e(tag, "onPlayingUpdate: Exception scheduling sync: ${e.message}")
          }
        }

        override fun onMetadata(metadata: PlaybackMetadata) {
          notifyListeners("onMetadata", JSObject(jacksonMapper.writeValueAsString(metadata)))
        }

        override fun onSleepTimerEnded(currentPosition: Long) {
          emit("onSleepTimerEnded", currentPosition)
        }

        override fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer:Boolean) {
          val ret = JSObject()
          ret.put("value", sleepTimeRemaining)
          ret.put("isAuto", isAutoSleepTimer)
          notifyListeners("onSleepTimerSet", ret)
        }

        override fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress) {
          notifyListeners("onLocalMediaProgressUpdate", JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
        }

        override fun onPlaybackFailed(errorMessage: String) {
          emit("onPlaybackFailed", errorMessage)
        }

        override fun onMediaPlayerChanged(mediaPlayer:String) {
          emit("onMediaPlayerChanged", mediaPlayer)
        }

        override fun onProgressSyncFailing() {
          emit("onProgressSyncFailing", "")
        }

        override fun onProgressSyncSuccess() {
          emit("onProgressSyncSuccess", "")
        }

        override fun onNetworkMeteredChanged(isUnmetered:Boolean) {
          emit("onNetworkMeteredChanged", isUnmetered)
        }

        override fun onMediaItemHistoryUpdated(mediaItemHistory:MediaItemHistory) {
          notifyListeners("onMediaItemHistoryUpdated", JSObject(jacksonMapper.writeValueAsString(mediaItemHistory)))
        }

        override fun onPlaybackSpeedChanged(playbackSpeed:Float) {
          emit("onPlaybackSpeedChanged", playbackSpeed)
        }
      })

      MediaEventManager.clientEventEmitter = playerNotificationService.clientEventEmitter
      // --- Sync playback state and metadata on service connection ---
      syncCurrentPlaybackState()
    }
    mainActivity.pluginCallback = foregroundServiceReady
  }

  // --- New function to sync playback state and metadata ---
  fun syncCurrentPlaybackState() {
    if (!::playerNotificationService.isInitialized) {
      Log.d(tag, "PlayerNotificationService not initialized yet, skipping sync")
      return
    }
    try {
      val playbackSession = playerNotificationService.currentPlaybackSession
      if (playbackSession != null) {
        Log.d(tag, "Syncing playback state: ${playbackSession.libraryItem?.media?.metadata?.title}")
        notifyListeners("onPlaybackSession", JSObject(jacksonMapper.writeValueAsString(playbackSession)))

        // Create and emit metadata using the same pattern as the service
        val duration = playbackSession.duration
        val isPlaying = playerNotificationService.currentPlayer.isPlaying

        // Get current time - prefer playback session time if player position seems incorrect
        // This prevents reporting stale player position during app resume
        val playerCurrentTime = playerNotificationService.getCurrentTimeSeconds()
        val sessionCurrentTime = playbackSession.currentTime

        // Use session time if player time is significantly different and player is not actively playing
        // This handles the case where player position hasn't been restored yet on app resume
        val currentTime = if (!isPlaying && Math.abs(playerCurrentTime - sessionCurrentTime) > 1.0) {
          Log.d(tag, "Using session time ($sessionCurrentTime) instead of player time ($playerCurrentTime) - likely stale position")
          sessionCurrentTime
        } else {
          playerCurrentTime
        }

        // Use READY state for both playing and paused (when player is loaded)
        // Only use IDLE for truly idle states
        val playerState = PlayerState.READY
        val metadata = PlaybackMetadata(duration, currentTime, playerState)
        notifyListeners("onMetadata", JSObject(jacksonMapper.writeValueAsString(metadata)))

        // Also emit the current playing state to update play/pause button
        emit("onPlayingUpdate", isPlaying)

        // Emit current time to update progress bar
        val ret = JSObject()
        ret.put("value", currentTime)
        ret.put("bufferedTime", playerNotificationService.getBufferedTimeSeconds())
        notifyListeners("onTimeUpdate", ret)

        Log.d(tag, "Synced state - Playing: $isPlaying, CurrentTime: $currentTime, Duration: $duration, PlayerState: READY")

        // Force a small delay then emit playing state again to ensure UI updates
        Handler(Looper.getMainLooper()).post {
          emit("onPlayingUpdate", isPlaying)
          Log.d(tag, "Re-emitted playing state: $isPlaying")
        }

      } else {
        Log.d(tag, "No active playback session - checking server for last session")
        resumeFromLastServerSession()
      }
    } catch (e: Exception) {
      Log.e(tag, "Failed to sync playback state: ${e.message}")
    }
  }

  // --- Helper method to check if player service is ready ---
  private fun isPlayerServiceReady(): Boolean {
    return ::playerNotificationService.isInitialized
  }

    // --- Helper method to determine if we should use server progress ---
  private fun shouldUseServerProgress(playbackSession: PlaybackSession, serverProgress: MediaProgress): Boolean {
    val localCurrentTime = playbackSession.currentTime
    val serverCurrentTime = serverProgress.currentTime
    val localUpdatedAt = playbackSession.updatedAt ?: 0L
    val serverUpdatedAt = serverProgress.lastUpdate

    // Check if server progress is newer (within a reasonable time window)
    val serverIsNewer = serverUpdatedAt > localUpdatedAt

    // Check if server progress is significantly farther ahead (more than 30 seconds)
    val serverIsFarther = serverCurrentTime > localCurrentTime + 30.0

    // Also check if server progress is significantly behind (more than 30 seconds)
    // In this case, we might want to use local progress if it's much further
    val serverIsMuchBehind = serverCurrentTime < localCurrentTime - 30.0

    val shouldUseServer = if (serverIsNewer) {
      // If server is newer, use it if it's not much behind local progress
      !serverIsMuchBehind
    } else {
      // If server is older, only use it if it's significantly farther ahead
      serverIsFarther
    }

    Log.d(tag, "Progress comparison - Local: ${localCurrentTime}s (updated: $localUpdatedAt), Server: ${serverCurrentTime}s (updated: $serverUpdatedAt)")
    Log.d(tag, "Decision: ${if (shouldUseServer) "USE SERVER" else "USE LOCAL"} (newer: $serverIsNewer, farther: $serverIsFarther, muchBehind: $serverIsMuchBehind)")

    return shouldUseServer
  }

  // --- Resume from last server session when no active session ---
  private fun resumeFromLastServerSession() {
    if (!::playerNotificationService.isInitialized) {
      Log.d(tag, "PlayerNotificationService not initialized yet, skipping resume")
      return
    }

    // Check if we have a valid server connection before making API calls
    if (!DeviceManager.isConnectedToServer) {
      Log.d(tag, "No valid server connection available, skipping resume from server session")
      return
    }

    try {
      Log.d(tag, "Querying server for current user to get last playback session")

      // Use getCurrentUser to get user data which should include current session
      apiHandler.getCurrentUser { user ->
        if (user != null) {
          Log.d(tag, "Got user data from server")

          try {
            // Get the most recent media progress
            if (user.mediaProgress.isNotEmpty()) {
              val latestProgress = user.mediaProgress.maxByOrNull { it.lastUpdate }

              if (latestProgress != null && latestProgress.currentTime > 0) {
                Log.d(tag, "Found recent progress: ${latestProgress.libraryItemId} at ${latestProgress.currentTime}s")

                // Check if this library item is downloaded locally
                val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(latestProgress.libraryItemId)

                if (localLibraryItem != null) {
                  Log.d(tag, "Found local download for ${localLibraryItem.title}, using local copy")

                  // Create a local playback session
                  val deviceInfo = playerNotificationService.getDeviceInfo()
                  val episode = if (latestProgress.episodeId != null && localLibraryItem.isPodcast) {
                    val podcast = localLibraryItem.media as? Podcast
                    podcast?.episodes?.find { ep -> ep.id == latestProgress.episodeId }
                  } else null

                  val localPlaybackSession = localLibraryItem.getPlaybackSession(episode, deviceInfo)

                  // Check if we should use server progress or local progress
                  val shouldUseServerProgress = shouldUseServerProgress(localPlaybackSession, latestProgress)

                  if (shouldUseServerProgress) {
                    // Override the current time with the server progress to sync position
                    localPlaybackSession.currentTime = latestProgress.currentTime
                    Log.d(tag, "Using server progress: ${latestProgress.currentTime}s (newer/farther than local)")
                  } else {
                    Log.d(tag, "Using local progress: ${localPlaybackSession.currentTime}s (server progress not newer/farther)")
                  }

                  Log.d(tag, "Resuming from local download: ${localLibraryItem.title} at ${localPlaybackSession.currentTime}s")

                  // Get current playbook speed from MediaManager (same as Android Auto implementation)
                  val currentPlaybackSpeed = playerNotificationService.mediaManager.getSavedPlaybackRate()

                  // Determine if we should start playing based on Android Auto mode
                  val shouldStartPlaying = playerNotificationService.isAndroidAuto

                  // Prepare the player with appropriate play state and saved playback speed
                  Handler(Looper.getMainLooper()).post {
                    if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) {
                      playerNotificationService.mediaProgressSyncer.stop {
                        PlayerListener.lazyIsPlaying = false
                        playerNotificationService.preparePlayer(localPlaybackSession, shouldStartPlaying, currentPlaybackSpeed)
                      }
                    } else {
                      playerNotificationService.mediaProgressSyncer.reset()
                      PlayerListener.lazyIsPlaying = false
                      playerNotificationService.preparePlayer(localPlaybackSession, shouldStartPlaying, currentPlaybackSpeed)
                    }
                  }
                  return@getCurrentUser
                }

                // No local copy found, get the library item from server
                Log.d(tag, "No local download found, using server streaming")
                apiHandler.getLibraryItem(latestProgress.libraryItemId) { libraryItem ->
                  if (libraryItem != null) {
                    Log.d(tag, "Got library item: ${libraryItem.media?.metadata?.title}")

                    // Create a playback session from the library item and progress
                    Handler(Looper.getMainLooper()).post {
                      try {
                        val episode = if (latestProgress.episodeId != null) {
                          val podcastMedia = libraryItem.media as? Podcast
                          podcastMedia?.episodes?.find { ep -> ep.id == latestProgress.episodeId }
                        } else null

                        // Use the API to get a proper playback session but don't start playback
                        val playItemRequestPayload = playerNotificationService.getPlayItemRequestPayload(false)

                        // Try to get the current playback speed from the player, default to 1.0f if not available
                        val currentPlaybackSpeed = try {
                          if (::playerNotificationService.isInitialized && playerNotificationService.currentPlayer != null) {
                            playerNotificationService.currentPlayer.playbackParameters?.speed ?: 1.0f
                          } else {
                            1.0f
                          }
                        } catch (e: Exception) {
                          Log.d(tag, "Could not get current playback speed, using default: ${e.message}")
                          1.0f
                        }

                        Log.d(tag, "Using playback speed: $currentPlaybackSpeed")

                        apiHandler.playLibraryItem(latestProgress.libraryItemId, latestProgress.episodeId, playItemRequestPayload) { playbackSession ->
                          if (playbackSession != null) {
                            // Check if we should use server progress or local progress
                            val shouldUseServerProgress = shouldUseServerProgress(playbackSession, latestProgress)

                            if (shouldUseServerProgress) {
                              // Override the current time with the saved progress
                              playbackSession.currentTime = latestProgress.currentTime
                              Log.d(tag, "Using server progress: ${latestProgress.currentTime}s (newer/farther than local)")
                            } else {
                              Log.d(tag, "Using local progress: ${playbackSession.currentTime}s (server progress not newer/farther)")
                            }

                            // Determine if we should start playing based on Android Auto mode
                            val shouldStartPlaying = playerNotificationService.isAndroidAuto
                            val playStateText = if (shouldStartPlaying) "playing" else "paused"

                            Log.d(tag, "Resuming from server session: ${libraryItem.media.metadata?.title} at ${playbackSession.currentTime}s in $playStateText state with speed ${currentPlaybackSpeed}x")

                            // Prepare the player with appropriate play state on main thread with correct playback speed
                            Handler(Looper.getMainLooper()).post {
                              if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) {
                                playerNotificationService.mediaProgressSyncer.stop {
                                  PlayerListener.lazyIsPlaying = false
                                  playerNotificationService.preparePlayer(playbackSession, shouldStartPlaying, currentPlaybackSpeed) // Use correct speed
                                }
                              } else {
                                playerNotificationService.mediaProgressSyncer.reset()
                                PlayerListener.lazyIsPlaying = false
                                playerNotificationService.preparePlayer(playbackSession, shouldStartPlaying, currentPlaybackSpeed) // Use correct speed
                              }
                            }
                          } else {
                            Log.e(tag, "Failed to create playback session from server")
                          }
                        }

                      } catch (e: Exception) {
                        Log.e(tag, "Error creating playback session from server data: ${e.message}")
                      }
                    }
                  } else {
                    Log.d(tag, "Could not get library item ${latestProgress.libraryItemId} from server")
                  }
                }
              } else {
                Log.d(tag, "No recent progress found or progress is at beginning")
              }
            } else {
              Log.d(tag, "No media progress found in user data")
            }

          } catch (e: Exception) {
            Log.e(tag, "Error processing user session data: ${e.message}")
          }
        } else {
          Log.d(tag, "No user data found from server")
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Failed to resume from last server session: ${e.message}")
    }
  }

  // --- Smart sync that waits for web view to be ready ---
  fun syncCurrentPlaybackStateWhenReady(maxRetries: Int = 10, retryIntervalMs: Long = 500) {
    var retryCount = 0

    fun attemptSync() {
      try {
        // Check if bridge and web view are ready
        val webView = bridge?.webView
        if (webView != null && bridge != null) {
          // Additional check to see if web view has loaded content
          webView.evaluateJavascript("(function() { return document.readyState === 'complete' && window.Capacitor != null; })();") { result ->
            if (result == "true") {
              Log.d(tag, "Web view is ready, syncing playback state")
              syncCurrentPlaybackState()
            } else {
              retryCount++
              if (retryCount < maxRetries) {
                Log.d(tag, "Web view not ready yet, retry $retryCount/$maxRetries")
                Handler(Looper.getMainLooper()).post {
                  attemptSync()
                }
              } else {
                Log.w(tag, "Max retries reached, falling back to immediate sync")
                syncCurrentPlaybackState()
              }
            }
          }
          return
        }

        retryCount++
        if (retryCount < maxRetries) {
          Log.d(tag, "Bridge/WebView not ready yet, retry $retryCount/$maxRetries")
          Handler(Looper.getMainLooper()).post {
            attemptSync()
          }
        } else {
          Log.w(tag, "Max retries reached, falling back to immediate sync")
          syncCurrentPlaybackState()
        }
      } catch (e: Exception) {
        Log.e(tag, "Error checking web view readiness: ${e.message}")
        // Fallback to immediate sync
        syncCurrentPlaybackState()
      }
    }

    attemptSync()
  }

  fun emit(evtName: String, value: Any) {
    val ret = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
  }

  // --- Wait for player service to be ready before preparing library item ---
  private fun prepareLibraryItemWhenReady(call: PluginCall, libraryItem: LocalLibraryItem, episode: PodcastEpisode?, startTimeOverride: Double?, playbackRate: Float, retryCount: Int = 0) {
    val maxRetries = 50 // 5 seconds max wait time
    if (::playerNotificationService.isInitialized) {
      // Service is ready, proceed immediately
      Log.d(tag, "prepareLibraryItem: Service ready after $retryCount retries, preparing Local Media item")
      val playbackSession = libraryItem.getPlaybackSession(episode, playerNotificationService.getDeviceInfo())
      if (startTimeOverride != null) {
        Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
        playbackSession.currentTime = startTimeOverride
      }

      if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) { // If progress syncing then first stop before preparing next
        playerNotificationService.mediaProgressSyncer.stop {
          Log.d(tag, "Media progress syncer was already syncing - stopped")
          PlayerListener.lazyIsPlaying = false

          Handler(Looper.getMainLooper()).post { // TODO: This was needed again which is probably a design a flaw
            playerNotificationService.preparePlayer(
              playbackSession,
              true, // playWhenReady for local items
              playbackRate
            )
          }
        }
      } else {
        playerNotificationService.mediaProgressSyncer.reset()
        playerNotificationService.preparePlayer(playbackSession, true, playbackRate) // playWhenReady for local items
      }
      call.resolve(JSObject())
    } else {
      // Service not ready yet
      if (retryCount == 0) {
        // First attempt - start the service if not already started
        Log.d(tag, "prepareLibraryItem: Service not initialized, starting service...")
        mainActivity.startMyService()
      }

      if (retryCount >= maxRetries) {
        // Timeout - service never initialized
        Log.e(tag, "prepareLibraryItem: Service initialization timeout after ${maxRetries * 100}ms")
        call.resolve(JSObject("{\"error\":\"Player service failed to initialize\"}"))
      } else {
        // Service not ready yet, wait and retry
        Log.d(tag, "prepareLibraryItem: PlayerNotificationService not ready yet, waiting... (attempt ${retryCount + 1}/$maxRetries)")
        Handler(Looper.getMainLooper()).postDelayed({
          prepareLibraryItemWhenReady(call, libraryItem, episode, startTimeOverride, playbackRate, retryCount + 1)
        }, 100) // Check again in 100ms
      }
    }
  }

  private fun initCastManager() {
    // Check Google Play Services availability
    val googleApi = GoogleApiAvailability.getInstance()
    val statusCode = googleApi.isGooglePlayServicesAvailable(mainActivity)

    if (statusCode != ConnectionResult.SUCCESS) {
        when (statusCode) {
          ConnectionResult.SERVICE_MISSING -> Log.w(tag, "initCastManager: Google Play Services Missing")
          ConnectionResult.SERVICE_DISABLED -> Log.w(tag, "initCastManager: Google Play Services Disabled")
          ConnectionResult.SERVICE_INVALID -> Log.w(tag, "initCastManager: Google Play Services Invalid")
          ConnectionResult.SERVICE_UPDATING -> Log.w(tag, "initCastManager: Google Play Services Updating")
          ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> Log.w(tag, "initCastManager: Google Play Services Update Required")
        }
        return
    }

    try {
      // Initialize Cast Context for Media3
      castContext = CastContext.getSharedInstance(mainActivity)
      Log.d(tag, "Cast Context initialized successfully")
      Log.d(tag, "Cast Context state: ${castContext?.castState}")

      // IMPORTANT: Debug the actual Cast options being used
      val castOptions = castContext?.castOptions
      val expectedCastAppId = mainActivity.getString(R.string.cast_app_id)
      Log.d(tag, "CAST_DEBUG: Cast Context receiver app ID: ${castOptions?.receiverApplicationId}")
      Log.d(tag, "CAST_DEBUG: Expected app ID: $expectedCastAppId")

      if (castOptions?.receiverApplicationId != expectedCastAppId) {
        Log.e(tag, "CAST_DEBUG: *** MISMATCH! Cast Context is using wrong app ID: ${castOptions?.receiverApplicationId}")
        Log.e(tag, "CAST_DEBUG: *** This indicates Cast framework cache issue or CastOptionsProvider not being used")
      } else {
        Log.d(tag, "CAST_DEBUG: Cast Context is using correct test app ID")
      }

      // Add cast session state debugging
      castContext?.addCastStateListener { state ->
        when (state) {
          CastState.NO_DEVICES_AVAILABLE -> Log.d(tag, "CAST_DEBUG: No devices available")
          CastState.NOT_CONNECTED -> Log.d(tag, "CAST_DEBUG: Not connected")
          CastState.CONNECTING -> Log.d(tag, "CAST_DEBUG: Connecting...")
          CastState.CONNECTED -> Log.d(tag, "CAST_DEBUG: Connected successfully")
        }
      }

      // Monitor session manager for connection errors
      val sessionManager = castContext?.sessionManager
      sessionManager?.addSessionManagerListener(object : SessionManagerListener<Session> {
        override fun onSessionStarted(session: Session, sessionId: String) {
          Log.d(tag, "CAST_DEBUG: Session started: $sessionId")
          if (session is CastSession) {
            Log.d(tag, "CAST_DEBUG: Session app ID: ${session.applicationMetadata?.applicationId}")
          }
        }

        override fun onSessionStarting(session: Session) {
          Log.d(tag, "CAST_DEBUG: Session starting")
        }

        override fun onSessionStartFailed(session: Session, error: Int) {
          Log.e(tag, "CAST_DEBUG: *** SESSION START FAILED with error code: $error")
          when (error) {
            2473 -> Log.e(tag, "CAST_DEBUG: Error 2473 - RECEIVER_APP_NOT_COMPATIBLE")
            2474 -> Log.e(tag, "CAST_DEBUG: Error 2474 - RECEIVER_UNAVAILABLE")
            else -> Log.e(tag, "CAST_DEBUG: Unknown error code: $error")
          }
        }

        override fun onSessionEnded(session: Session, error: Int) {
          Log.d(tag, "CAST_DEBUG: Session ended with error: $error")
        }

        override fun onSessionEnding(session: Session) {
          Log.d(tag, "CAST_DEBUG: Session ending")
        }

        override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
          Log.d(tag, "CAST_DEBUG: Session resumed")
        }

        override fun onSessionResuming(session: Session, sessionId: String) {
          Log.d(tag, "CAST_DEBUG: Session resuming: $sessionId")
        }

        override fun onSessionResumeFailed(session: Session, error: Int) {
          Log.e(tag, "CAST_DEBUG: Session resume failed: $error")
        }

        override fun onSessionSuspended(session: Session, reason: Int) {
          Log.d(tag, "CAST_DEBUG: Session suspended: $reason")
        }
      })      // Initialize MediaRouter for device discovery
      mediaRouter = MediaRouter.getInstance(mainActivity)

      // Use the same Cast App ID as defined in string resources
      val castAppId = mainActivity.getString(R.string.cast_app_id)
      mediaRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(castAppId))
        .build()
      Log.d(tag, "MediaRouter initialized with Cast app ID: $castAppId")

      // Log available routes for debugging
      val routes = mediaRouter?.routes
      Log.d(tag, "MediaRouter found ${routes?.size ?: 0} total routes")
      routes?.forEach { route ->
        Log.d(tag, "Route: ${route.name} - ${route.description} - supportsControlCategory: ${route.supportsControlCategory(CastMediaControlIntent.categoryForCast(castAppId))}")
      }

      // Listen for cast state changes
      castContext?.addCastStateListener { state ->
        val isAvailable = state != CastState.NO_DEVICES_AVAILABLE
        Log.d(tag, "Cast state changed: $state, available: $isAvailable")

        // Log additional cast state details
        Log.d(tag, "Cast session manager: ${castContext?.sessionManager}")
        Log.d(tag, "Current cast session: ${castContext?.sessionManager?.currentCastSession}")

        // TEMPORARY: Always keep Cast enabled for testing
        val forceEnabled = true
        if (isCastAvailable != forceEnabled) {
          isCastAvailable = forceEnabled
          Log.d(tag, "FORCE ENABLED for testing - Cast availability forced to: $forceEnabled")
          emit("onCastAvailableUpdate", forceEnabled)
        }
      }

      // Add session manager listeners for connect/disconnect events
      castContext?.sessionManager?.addSessionManagerListener(object : SessionManagerListener<Session> {
        override fun onSessionStarted(session: Session, sessionId: String) {
          Log.d(tag, "Cast session started: ${(session as? CastSession)?.castDevice?.friendlyName}")
          emit("onCastSessionConnected", mapOf("deviceName" to ((session as? CastSession)?.castDevice?.friendlyName ?: "Unknown")))
        }

        override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
          Log.d(tag, "Cast session resumed: ${(session as? CastSession)?.castDevice?.friendlyName}")
          emit("onCastSessionConnected", mapOf("deviceName" to ((session as? CastSession)?.castDevice?.friendlyName ?: "Unknown")))
        }

        override fun onSessionEnded(session: Session, error: Int) {
          Log.d(tag, "Cast session ended")
          emit("onCastSessionDisconnected", true)
        }

        override fun onSessionSuspended(session: Session, reason: Int) {
          Log.d(tag, "Cast session suspended")
          emit("onCastSessionDisconnected", true)
        }

        override fun onSessionStartFailed(session: Session, error: Int) {
          Log.e(tag, "Cast session start failed: $error")
          emit("onCastSessionFailed", mapOf("error" to error))
        }

        override fun onSessionStarting(session: Session) {
          Log.d(tag, "Cast session starting...")
        }

        override fun onSessionEnding(session: Session) {
          Log.d(tag, "Cast session ending...")
        }

        override fun onSessionResuming(session: Session, sessionId: String) {
          Log.d(tag, "Cast session resuming...")
        }

        override fun onSessionResumeFailed(session: Session, error: Int) {
          Log.e(tag, "Cast session resume failed: $error")
          emit("onCastSessionFailed", mapOf("error" to error))
        }
      })

      // Initial cast availability check
      castContext?.let { context ->
        val realAvailability = context.castState != CastState.NO_DEVICES_AVAILABLE
        // TEMPORARY: Force Cast availability for testing
        isCastAvailable = true  // Force enable for testing
        Log.d(tag, "Initial cast availability: $realAvailability (state: ${context.castState})")
        Log.d(tag, "FORCE ENABLED for testing - isCastAvailable set to: $isCastAvailable")
        emit("onCastAvailableUpdate", isCastAvailable)
      }

    } catch (e: Exception) {
      Log.e(tag, "Failed to initialize Cast Context: ${e.javaClass.simpleName}: ${e.message}", e)
      Log.e(tag, "Cast functionality will not be available")
      isCastAvailable = false
      emit("onCastAvailableUpdate", false)
    }
  }

  @PluginMethod
  fun prepareLibraryItem(call: PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    val episodeId = call.getString("episodeId", "").toString()
    val playWhenReady = call.getBoolean("playWhenReady") == true
    val playbackRate = call.getFloat("playbackRate",1f) ?: 1f
    val startTimeOverride = call.getDouble("startTime")

    Log.d(tag, "prepareLibraryItem: ===== STARTING PREPARATION =====")
    Log.d(tag, "prepareLibraryItem: Library Item ID: $libraryItemId")
    Log.d(tag, "prepareLibraryItem: Episode ID: $episodeId")
    Log.d(tag, "prepareLibraryItem: Play When Ready: $playWhenReady")
    Log.d(tag, "prepareLibraryItem: Playback Rate: $playbackRate")
    Log.d(tag, "prepareLibraryItem: Start Time Override: $startTimeOverride")

    // Check for cast session before preparation (thread-safe)
    if (::playerNotificationService.isInitialized) {
      playerNotificationService.castPlayerManager.isConnectedSafe { isCastConnected ->
        Log.d(tag, "prepareLibraryItem: Cast session connected: $isCastConnected")
        if (isCastConnected) {
          Log.d(tag, "prepareLibraryItem: Active cast session detected - will switch to cast player during preparation")
        }
      }

      // Also schedule a delayed check in case the cast session is establishing
      Handler(Looper.getMainLooper()).postDelayed({
        playerNotificationService.castPlayerManager.isConnectedSafe { isCastConnected ->
          Log.d(tag, "prepareLibraryItem: [DELAYED CHECK] Cast session connected: $isCastConnected")
          if (isCastConnected) {
            Log.d(tag, "prepareLibraryItem: [DELAYED] Cast session now available - may need to restart playback")
          }
        }
      }, 2000) // Check again after 2 seconds
    }

    AbsLogger.info("AbsAudioPlayer", "prepareLibraryItem: lid=$libraryItemId, startTimeOverride=$startTimeOverride, playbackRate=$playbackRate")

    if (libraryItemId.isEmpty()) {
      Log.e(tag, "prepareLibraryItem: Invalid call - no library item id")
      return call.resolve(JSObject("{\"error\":\"Invalid request\"}"))
    }

    if (libraryItemId.startsWith("local")) { // Play local media item
      DeviceManager.dbManager.getLocalLibraryItem(libraryItemId)?.let {
        var episode: PodcastEpisode? = null
        if (episodeId.isNotEmpty()) {
          val podcastMedia = it.media as Podcast
          episode = podcastMedia.episodes?.find { ep -> ep.id == episodeId }
          if (episode == null) {
            Log.e(tag, "prepareLibraryItem: Podcast episode not found $episodeId")
            return call.resolve(JSObject("{\"error\":\"Podcast episode not found\"}"))
          }
        }
        if (!it.hasTracks(episode)) {
          return call.resolve(JSObject("{\"error\":\"No audio files found on device. Download book again to fix.\"}"))
        }

        Handler(Looper.getMainLooper()).post {
          prepareLibraryItemWhenReady(call, it, episode, startTimeOverride, playbackRate)
        }
      }
    } else { // Play library item from server
      Handler(Looper.getMainLooper()).post {
        if (!::playerNotificationService.isInitialized) {
          Log.e(tag, "prepareLibraryItem: playerNotificationService not initialized yet for server item")
          call.resolve(JSObject("{\"error\":\"Player service not ready\"}"))
          return@post
        }
        val playItemRequestPayload = playerNotificationService.getPlayItemRequestPayload(false)
        playerNotificationService.mediaProgressSyncer.stop {
          apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
            if (it == null) {
              call.resolve(JSObject("{\"error\":\"Server play request failed\"}"))
            } else {
              if (startTimeOverride != null) {
                Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
                it.currentTime = startTimeOverride
              }

              Handler(Looper.getMainLooper()).post {
                Log.d(tag, "Preparing Player playback session ${jacksonMapper.writeValueAsString(it)}")
                PlayerListener.lazyIsPlaying = false
                playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
              }
              call.resolve(JSObject(jacksonMapper.writeValueAsString(it)))
            }
          }
        }
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val currentTime = playerNotificationService.getCurrentTimeSeconds()
      val bufferedTime = playerNotificationService.getBufferedTimeSeconds()
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)

      // Note: Chapter information is intentionally NOT included here to avoid
      // the web UI using chapter-relative durations instead of total duration.
      // The web UI handles its own chapter management and expects absolute time/duration.
      // Chapter info is available separately via getChapterProgress() if needed.

      call.resolve(ret)
    }
  }

  @PluginMethod
  fun getChapterProgress(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val chapterProgress = playerNotificationService.getChapterProgressInfo()
      if (chapterProgress != null) {
        val ret = JSObject()
        ret.put("chapterIndex", chapterProgress.chapterIndex)
        ret.put("chapterTitle", chapterProgress.chapterTitle)
        ret.put("chapterPosition", chapterProgress.chapterPosition)
        ret.put("chapterDuration", chapterProgress.chapterDuration)
        ret.put("chapterProgress", chapterProgress.chapterProgress)
        ret.put("absolutePosition", playerNotificationService.getCurrentTime())
        ret.put("totalDuration", playerNotificationService.currentPlaybackSession?.totalDurationMs ?: 0L)
        call.resolve(ret)
      } else {
        val ret = JSObject()
        ret.put("error", "No chapter progress available")
        call.resolve(ret)
      }
    }
  }

  @PluginMethod
  fun pausePlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.pause()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      Log.d(tag, "playPlayer: Called - checking player state...")

      if (!isPlayerServiceReady()) {
        Log.e(tag, "playPlayer: PlayerNotificationService not initialized yet")
        call.resolve(JSObject("{\"error\":\"Player service not ready\"}"))
        return@post
      }

      Log.d(tag, "playPlayer: PlayerNotificationService is ready")
      Log.d(tag, "playPlayer: Current playback session: ${playerNotificationService.currentPlaybackSession?.displayTitle ?: "null"}")
      Log.d(tag, "playPlayer: Current media item count: ${playerNotificationService.currentPlayer.mediaItemCount}")

      // Check if we have a valid playback session
      if (playerNotificationService.currentPlaybackSession == null) {
        Log.e(tag, "playPlayer: No playback session available")

        // Try to check if there's a last session we can resume
        val lastSession = DeviceManager.deviceData.lastPlaybackSession
        if (lastSession != null) {
          Log.w(tag, "playPlayer: Found last session '${lastSession.displayTitle}', but playback session is null")
          Log.w(tag, "playPlayer: This suggests prepareLibraryItem was not called or failed")
          // TODO: We could attempt to auto-prepare here, but we need the libraryItemId
        } else {
          Log.w(tag, "playPlayer: No last session available either")
        }

        call.resolve(JSObject("{\"error\":\"No playback session. Call prepareLibraryItem first.\"}"))
        return@post
      }

      // Check if we have media items loaded
      if (playerNotificationService.currentPlayer.mediaItemCount == 0) {
        Log.e(tag, "playPlayer: No media items loaded in player")
        Log.w(tag, "playPlayer: Session exists but no media items - this indicates preparePlayer was not called")
        Log.w(tag, "playPlayer: Session: ${playerNotificationService.currentPlaybackSession?.displayTitle}")
        Log.w(tag, "playPlayer: Session ID: ${playerNotificationService.currentPlaybackSession?.mediaItemId}")

        // DEFENSIVE FIX: Try to automatically prepare the current session
        val currentSession = playerNotificationService.currentPlaybackSession
        if (currentSession != null) {
          Log.i(tag, "playPlayer: Attempting automatic preparation of current session")
          Log.d("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Session found: '${currentSession.displayTitle}', ID: ${currentSession.mediaItemId}")
          Log.d("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Session chapters: ${currentSession.chapters.size}")
          Log.d("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Session current time: ${currentSession.currentTime}")

          try {
            // Get current playback speed from MediaManager
            val currentPlaybackSpeed = playerNotificationService.mediaManager.getSavedPlaybackRate()
            Log.d("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Using playback speed: $currentPlaybackSpeed")

            // Prepare the player with playWhenReady=true since user wants to play
            Log.d("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Calling preparePlayer() now...")
            playerNotificationService.preparePlayer(currentSession, true, currentPlaybackSpeed)

            // Check if preparation was successful
            val mediaItemCountAfterPrep = playerNotificationService.currentPlayer.mediaItemCount
            Log.d("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - After preparePlayer call, media item count: $mediaItemCountAfterPrep")

            if (mediaItemCountAfterPrep > 0) {
              Log.d("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Success! Media items loaded")
            } else {
              Log.e("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Failed! Still no media items after preparation")
            }

            // Resolve immediately - the preparation will handle starting playback
            Log.i(tag, "playPlayer: Automatic preparation initiated for session: ${currentSession.displayTitle}")
            call.resolve()
            return@post
          } catch (e: Exception) {
            Log.e("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Exception during preparation: ${e.javaClass.simpleName}: ${e.message}")
            Log.e("NUXT_SKIP_DEBUG", "playPlayer: AUTO_PREPARE - Exception stack trace:", e)
            Log.e(tag, "playPlayer: Automatic preparation failed: ${e.message}")
            call.resolve(JSObject("{\"error\":\"Failed to prepare media items: ${e.message}\"}"))
            return@post
          }
        }

        // If no current session or preparation failed, return error
        call.resolve(JSObject("{\"error\":\"No media items loaded in player. Session exists but not prepared.\"}"))
        return@post
      }

      Log.d(tag, "playPlayer: All checks passed - starting playback for session: ${playerNotificationService.currentPlaybackSession?.displayTitle}")
      Log.d(tag, "playPlayer: Media items loaded: ${playerNotificationService.currentPlayer.mediaItemCount}")
      playerNotificationService.play()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPause(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      if (!isPlayerServiceReady()) {
        Log.e(tag, "playPause: PlayerNotificationService not initialized yet")
        call.resolve(JSObject("{\"error\":\"Player service not ready\"}"))
        return@post
      }
      val playing = playerNotificationService.playPause()
      call.resolve(JSObject("{\"playing\":$playing}"))
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    val time:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Log.d(tag, "seek action to $time")
    Handler(Looper.getMainLooper()).post {
      if (!isPlayerServiceReady()) {
        Log.e(tag, "seek: PlayerNotificationService not initialized yet")
        call.resolve(JSObject("{\"error\":\"Player service not ready\"}"))
        return@post
      }
      playerNotificationService.seekPlayer(time * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0
    Log.d("NUXT_SKIP_DEBUG", "AbsAudioPlayer.seekForward called with amount: $amount seconds")
    Handler(Looper.getMainLooper()).post {
      Log.d("NUXT_SKIP_DEBUG", "AbsAudioPlayer.seekForward: Using working jumpForward method instead of broken seekForward")
      playerNotificationService.jumpForward() // Use the working method that notification buttons use
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Log.d("NUXT_SKIP_DEBUG", "AbsAudioPlayer.seekBackward called with amount: $amount seconds")
    Handler(Looper.getMainLooper()).post {
      Log.d("NUXT_SKIP_DEBUG", "AbsAudioPlayer.seekBackward: Using working jumpBackward method instead of broken seekBackward")
      playerNotificationService.jumpBackward() // Use the working method that notification buttons use
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    val playbackSpeed:Float = call.getFloat("value", 1.0f) ?: 1.0f

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.setPlaybackSpeed(playbackSpeed)
      call.resolve()
    }
  }

  @PluginMethod
  fun closePlayback(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.closePlayback()
      call.resolve()
    }
  }

  @PluginMethod
  fun setSleepTimer(call: PluginCall) {
    val time:Long = call.getString("time", "360000")!!.toLong()
    val isChapterTime:Boolean = call.getBoolean("isChapterTime", false) == true

    Handler(Looper.getMainLooper()).post {
        val playbackSession: PlaybackSession? = playerNotificationService.mediaProgressSyncer.currentPlaybackSession ?: playerNotificationService.currentPlaybackSession
        val success:Boolean = playerNotificationService.sleepTimerManager.setManualSleepTimer(playbackSession?.id ?: "", time, isChapterTime)
        val ret = JSObject()
        ret.put("success", success)
        call.resolve(ret)
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    val time = playerNotificationService.sleepTimerManager.getSleepTimerTime()
    val ret = JSObject()
    ret.put("value", time)
    call.resolve(ret)
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.increaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.decreaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.cancelSleepTimer()
    }
    call.resolve()
  }

  @PluginMethod
  fun requestSession(call: PluginCall) {
    Log.d(tag, "CAST REQUEST SESSION - triggering cast session")
    call.resolve()

    if (castContext == null) {
      Log.e(tag, "Cast Context not initialized")
      return
    }

    try {
      // Show the Cast dialog to let user select a Cast device
      activity.runOnUiThread {
        val sessionManager = castContext?.sessionManager
        if (sessionManager != null) {
          // Start a cast session - this will show the Cast device selection dialog
          val currentSession = sessionManager.currentCastSession
          if (currentSession != null && currentSession.isConnected) {
            Log.d(tag, "Already connected to cast device: ${currentSession.castDevice?.friendlyName}")
            emit("onCastSessionConnected", true)
          } else {
            // For Capacitor apps with web UI, emit event instead of showing native dialog
            Log.d(tag, "Requesting Cast session through web UI")
            emit("onCastSessionRequested", true)
          }
        } else {
          Log.e(tag, "SessionManager is null")
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Error requesting cast session", e)
    }
  }

  @PluginMethod
  fun getIsCastAvailable(call: PluginCall) {
    val jsobj = JSObject()
    jsobj.put("value", isCastAvailable)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun getCastDevices(call: PluginCall) {
    if (castContext == null || mediaRouter == null || mediaRouteSelector == null) {
      Log.e(tag, "Cast Context or MediaRouter not initialized")
      call.reject("Cast not available")
      return
    }

    try {
      // MediaRouter must be accessed on main thread
      activity.runOnUiThread {
        try {
          val deviceArray = JSArray()

          // Get available routes from MediaRouter
          val routes = mediaRouter!!.getRoutes()

          for (route in routes) {
            // Only include Cast-compatible routes
            if (route.matchesSelector(mediaRouteSelector!!)) {
              val device = JSObject()
              device.put("id", route.id)
              device.put("name", route.name)
              device.put("description", route.description ?: "")
              device.put("isConnected", route.isSelected)
              device.put("connectionState", route.connectionState)
              deviceArray.put(device)
            }
          }

          val ret = JSObject()
          ret.put("devices", deviceArray)
          call.resolve(ret)
        } catch (e: Exception) {
          Log.e(tag, "Error getting cast devices on main thread", e)
          call.reject("Error getting cast devices: ${e.message}")
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Error getting cast devices", e)
      call.reject("Error getting cast devices: ${e.message}")
    }
  }

  @PluginMethod
  fun connectToCastDevice(call: PluginCall) {
    val deviceId = call.getString("deviceId")
    if (deviceId == null) {
      call.reject("Device ID is required")
      return
    }

    if (castContext == null || mediaRouter == null) {
      Log.e(tag, "Cast Context or MediaRouter not initialized")
      call.reject("Cast not available")
      return
    }

    try {
      activity.runOnUiThread {
        // Find the route by ID
        val routes = mediaRouter!!.getRoutes()
        val targetRoute = routes.find { it.id == deviceId }

        if (targetRoute != null) {
          Log.d(tag, "Connecting to cast device: ${targetRoute.name}")
          mediaRouter!!.selectRoute(targetRoute)
          call.resolve()
        } else {
          Log.e(tag, "Cast device not found: $deviceId")
          call.reject("Cast device not found")
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Error connecting to cast device", e)
      call.reject("Error connecting to cast device: ${e.message}")
    }
  }

  @PluginMethod
  fun disconnectFromCastDevice(call: PluginCall) {
    if (castContext == null) {
      Log.e(tag, "Cast Context not initialized")
      call.reject("Cast not available")
      return
    }

    try {
      activity.runOnUiThread {
        val sessionManager = castContext?.sessionManager
        if (sessionManager != null) {
          val currentSession = sessionManager.currentCastSession
          if (currentSession != null && currentSession.isConnected) {
            Log.d(tag, "Disconnecting from cast device: ${currentSession.castDevice?.friendlyName}")
            sessionManager.endCurrentSession(true)
            call.resolve()
          } else {
            Log.w(tag, "No active cast session to disconnect")
            call.resolve()
          }
        } else {
          Log.e(tag, "SessionManager is null")
          call.reject("SessionManager not available")
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Error disconnecting from cast device", e)
      call.reject("Error disconnecting from cast device: ${e.message}")
    }
  }

  @PluginMethod
  fun getCurrentCastDevice(call: PluginCall) {
    if (castContext == null) {
      Log.e(tag, "Cast Context not initialized")
      call.resolve()
      return
    }

    try {
      val sessionManager = castContext?.sessionManager
      if (sessionManager != null) {
        val currentSession = sessionManager.currentCastSession
        if (currentSession != null && currentSession.isConnected) {
          val device = currentSession.castDevice
          if (device != null) {
            val deviceInfo = JSObject()
            deviceInfo.put("id", device.deviceId)
            deviceInfo.put("name", device.friendlyName)
            deviceInfo.put("modelName", device.modelName)
            deviceInfo.put("isConnected", true)

            val ret = JSObject()
            ret.put("device", deviceInfo)
            call.resolve(ret)
            return
          }
        }
      }

      // No connected device
      call.resolve()
    } catch (e: Exception) {
      Log.e(tag, "Error getting current cast device", e)
      call.resolve()
    }
  }

  @PluginMethod
  fun syncPlaybackState(call: PluginCall) {
    syncCurrentPlaybackState()
    call.resolve()
  }

  @PluginMethod
  fun getLastPlaybackSession(call: PluginCall) {
    val lastPlaybackSession = DeviceManager.deviceData.lastPlaybackSession
    if (lastPlaybackSession != null) {
      val jsObject = JSObject(jacksonMapper.writeValueAsString(lastPlaybackSession))
      call.resolve(jsObject)
    } else {
      call.resolve()
    }
  }

  @PluginMethod
  fun resumeLastPlaybackSession(call: PluginCall) {
    val lastPlaybackSession = DeviceManager.deviceData.lastPlaybackSession
    if (lastPlaybackSession != null) {
      // Check if session has meaningful progress (not at the very beginning)
      val progress = lastPlaybackSession.currentTime / lastPlaybackSession.duration
      if (progress > 0.01) {
        Log.d("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: Resuming session '${lastPlaybackSession.displayTitle}' at ${progress * 100}% (${lastPlaybackSession.currentTime}s/${lastPlaybackSession.duration}s)")
        Log.d("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: Session has ${lastPlaybackSession.audioTracks.size} audio tracks, ${lastPlaybackSession.chapters.size} chapters")

        // Log audio track details for debugging format issues
        lastPlaybackSession.audioTracks.forEachIndexed { index, track ->
          Log.d("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: Track $index: ${track.title} - ${track.contentUrl}")
        }

        // Ensure this runs on the main thread since ExoPlayer operations require it
        Handler(Looper.getMainLooper()).post {
          try {
            val savedPlaybackSpeed = playerNotificationService.mediaManager.getSavedPlaybackRate()
            // Determine if we should start playing based on Android Auto mode
            val shouldStartPlaying = playerNotificationService.isAndroidAuto
            Log.d("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: Calling preparePlayer with playWhenReady=$shouldStartPlaying, playbackRate=$savedPlaybackSpeed")
            playerNotificationService.preparePlayer(lastPlaybackSession, shouldStartPlaying, savedPlaybackSpeed)
            Log.d("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: preparePlayer completed successfully")
            call.resolve()
          } catch (e: Exception) {
            Log.e("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: Error in preparePlayer: ${e.javaClass.simpleName}: ${e.message}")
            Log.e("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: Full exception:", e)
            call.reject("Failed to resume session: ${e.message}", "RESUME_FAILED")
          }
        }
      } else {
        Log.w("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: Session not resumable - progress too low: ${progress * 100}%")
        call.reject("Session not resumable", "PROGRESS_INVALID")
      }
    } else {
      Log.w("NUXT_SKIP_DEBUG", "resumeLastPlaybackSession: No last session found")
      call.reject("No last session found", "NO_SESSION")
    }
  }

  @PluginMethod
  fun hasResumableSession(call: PluginCall) {
    val lastPlaybackSession = DeviceManager.deviceData.lastPlaybackSession
    val ret = JSObject()

    if (lastPlaybackSession != null) {
      val progress = lastPlaybackSession.currentTime / lastPlaybackSession.duration
      val isResumable = progress > 0.01
      ret.put("hasSession", true)
      ret.put("isResumable", isResumable)
      ret.put("progress", progress)
      ret.put("title", lastPlaybackSession.displayTitle ?: "Unknown")
    } else {
      ret.put("hasSession", false)
      ret.put("isResumable", false)
    }

    call.resolve(ret)
  }

  @PluginMethod
  fun navigateToChapter(call: PluginCall) {
    val chapterIndex: Int = call.getInt("chapterIndex", -1) ?: -1
    Log.d(tag, "navigateToChapter action to chapter $chapterIndex")
    if (chapterIndex < 0) {
      call.reject("Invalid chapter index")
      return
    }

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.navigateToChapter(chapterIndex)
      call.resolve()
    }
  }

  @PluginMethod
  fun skipToNextChapter(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekToNextChapter()
      call.resolve()
    }
  }

  @PluginMethod
  fun skipToPreviousChapter(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekToPreviousChapter()
      call.resolve()
    }
  }

  @PluginMethod
  fun getCurrentNavigationIndex(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val currentIndex = playerNotificationService.getCurrentNavigationIndex()
      val ret = JSObject()
      ret.put("index", currentIndex)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun getNavigationItemCount(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val count = playerNotificationService.getNavigationItemCount()
      val ret = JSObject()
      ret.put("count", count)
      call.resolve(ret)
    }
  }

    @PluginMethod
    fun setChapterTrack(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        Log.d(tag, "setChapterTrack: enabled=$enabled")

        Handler(Looper.getMainLooper()).post {
            if (::playerNotificationService.isInitialized) {
                playerNotificationService.setUseChapterTrack(enabled)
                call.resolve()
            } else {
                call.reject("Player service not ready")
            }
        }
    }

    @PluginMethod
    fun seekInChapter(call: PluginCall) {
        val position: Double = call.getDouble("position") ?: 0.0 // Position in seconds within current chapter
        Log.d(tag, "seekInChapter action to $position seconds within current chapter")

        Handler(Looper.getMainLooper()).post {
            if (::playerNotificationService.isInitialized) {
                val playbackSession = playerNotificationService.getCurrentPlaybackSessionCopy()
                if (playbackSession != null && playbackSession.chapters.isNotEmpty()) {
                    // Calculate chapter boundaries using raw player data
                    val currentTimeMs = playerNotificationService.getCurrentTime()
                    val currentChapter = playbackSession.getChapterForTime(currentTimeMs)

                    if (currentChapter != null) {
                        // Convert chapter-relative position to absolute position
                        val positionMs = (position * 1000).toLong()
                        val absolutePositionMs = currentChapter.startMs + positionMs
                        // Ensure we don't seek beyond the chapter end
                        val clampedPositionMs = absolutePositionMs.coerceAtMost(currentChapter.endMs - 1)

                        Log.d(tag, "seekInChapter: Chapter-relative ${positionMs}ms -> absolute ${clampedPositionMs}ms in chapter '${currentChapter.title}'")
                        playerNotificationService.seekPlayer(clampedPositionMs)
                        call.resolve()
                    } else {
                        Log.w(tag, "seekInChapter: No current chapter found, falling back to regular seek")
                        // Fallback to regular seek
                        val currentTime = playerNotificationService.getCurrentTimeSeconds()
                        playerNotificationService.seekPlayer(((currentTime + position) * 1000).toLong())
                        call.resolve()
                    }
                } else {
                    Log.d(tag, "seekInChapter: No chapters available, falling back to regular seek")
                    // Fallback to regular seek
                    val currentTime = playerNotificationService.getCurrentTimeSeconds()
                    playerNotificationService.seekPlayer(((currentTime + position) * 1000).toLong())
                    call.resolve()
                }
            } else {
                call.reject("Player service not ready")
            }
        }
    }

    @PluginMethod
    fun getChapterInfo(call: PluginCall) {
        Handler(Looper.getMainLooper()).post {
            if (::playerNotificationService.isInitialized) {
                val playbackSession = playerNotificationService.getCurrentPlaybackSessionCopy()
                if (playbackSession != null && playbackSession.chapters.isNotEmpty()) {
                    // Calculate chapter info using raw player data and session chapters
                    val currentTimeMs = playerNotificationService.getCurrentTime()
                    val currentChapter = playbackSession.getChapterForTime(currentTimeMs)

                    val ret = JSObject()
                    ret.put("hasChapters", true)

                    if (currentChapter != null) {
                        val chapterIndex = playbackSession.chapters.indexOf(currentChapter)
                        val chapterPositionMs = currentTimeMs - currentChapter.startMs
                        val chapterDurationMs = currentChapter.endMs - currentChapter.startMs
                        val totalDurationMs = playbackSession.totalDurationMs

                        ret.put("currentChapterIndex", chapterIndex)
                        ret.put("currentChapterTitle", currentChapter.title ?: "Untitled Chapter")
                        ret.put("chapterPosition", chapterPositionMs / 1000.0) // Chapter-relative position in seconds
                        ret.put("chapterDuration", chapterDurationMs / 1000.0) // Chapter duration in seconds
                        ret.put("chapterProgress", if (chapterDurationMs > 0) chapterPositionMs.toFloat() / chapterDurationMs else 0f)
                        ret.put("totalProgress", if (totalDurationMs > 0) currentTimeMs.toFloat() / totalDurationMs else 0f)
                    }

                    // Include all chapters information from session data
                    val chaptersArray = playbackSession.chapters.map { chapter ->
                        val chapterObj = JSObject()
                        chapterObj.put("title", chapter.title ?: "Untitled Chapter")
                        chapterObj.put("start", chapter.start)
                        chapterObj.put("end", chapter.end)
                        chapterObj.put("startMs", chapter.startMs)
                        chapterObj.put("endMs", chapter.endMs)
                        chapterObj
                    }
                    ret.put("chapters", jacksonMapper.writeValueAsString(chaptersArray))

                    call.resolve(ret)
                } else {
                    val ret = JSObject()
                    ret.put("hasChapters", false)
                    call.resolve(ret)
                }
            } else {
                call.reject("Player service not ready")
            }
        }
    }

    @PluginMethod
    fun userMediaProgressUpdate(call: PluginCall) {
        val data = call.data
        val libraryItemId = data.getString("libraryItemId")
        val episodeId = data.getString("episodeId")

        // Rate limiting to prevent overwhelming the player with too frequent updates
        val currentTimeMillis = System.currentTimeMillis()
        if (currentTimeMillis - lastSocketUpdateTime < SOCKET_UPDATE_MIN_INTERVAL) {
            AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Rate limiting socket update (too frequent)")
            call.resolve()
            return
        }
        lastSocketUpdateTime = currentTimeMillis

        val localLibraryItemId = playerNotificationService.currentPlaybackSession?.libraryItemId
        val localEpisodeId = playerNotificationService.currentPlaybackSession?.episodeId

        // Debug logging to understand the values
        AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Received socket update - libraryItemId=$libraryItemId, episodeId=$episodeId")
        AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Current session - localLibraryItemId=$localLibraryItemId, localEpisodeId=$localEpisodeId")
        AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Current playback session exists: ${playerNotificationService.currentPlaybackSession != null}")

        // If there's no current playback session, we can't determine if this is for the currently playing item
        if (playerNotificationService.currentPlaybackSession == null) {
            AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: No current playback session, processing update")
            // Continue with the update
        }

        // Ignore socket updates for the currently playing item
        // Check if this is the same item we're currently playing
        val isCurrentlyPlayingItem = (libraryItemId == localLibraryItemId) &&
                                    (episodeId == localEpisodeId ||
                                     (episodeId.isNullOrEmpty() && localEpisodeId.isNullOrEmpty()) ||
                                     (episodeId == "" && localEpisodeId.isNullOrEmpty()) ||
                                     (localEpisodeId == "" && episodeId.isNullOrEmpty()))

        // Also check if the player is currently playing - if so, we should be more conservative about updates
        val isPlayerCurrentlyPlaying = playerNotificationService.currentPlayer.isPlaying

        // If this is the currently selected item, we should be very conservative about updates
        // This prevents interrupting active playback with socket updates
        if (isCurrentlyPlayingItem) {
            if (isPlayerCurrentlyPlaying) {
                AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Ignoring socket progress update for actively playing item")
                call.resolve()
                return
            } else {
                AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Current item is paused, allowing conservative progress update")
                // For paused items, we might still want to update progress, but let's be careful
            }
        }

        // For non-currently-playing items, we can be more aggressive with updates
        if (!isCurrentlyPlayingItem) {
            AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Processing update for different item (not currently playing)")
        }

        val lastUpdate = data.getLong("lastUpdate")
        val currentTime = data.getDouble("currentTime")
        val duration = data.getDouble("duration")
        val isPlaying = data.getBoolean("isPlaying", false)
        val isBuffering = data.getBoolean("isBuffering", false)
        val ebookProgress = data.getDouble("ebookProgress")

        // Get local media progress
        val mediaItemId = if (episodeId != null) "$libraryItemId-$episodeId" else libraryItemId ?: ""
        val localMediaProgress = DeviceManager.dbManager.getLocalMediaProgress(mediaItemId)
        if (localMediaProgress != null) {
            // Convert timestamps to the same timezone for accurate comparison
            val serverLastUpdateMs = lastUpdate
            val localLastUpdateMs = localMediaProgress.lastUpdate

            // Convert currentTime to milliseconds for comparison
            val serverCurrentTimeMs = (currentTime * 1000).toLong()
            val localCurrentTimeMs = (localMediaProgress.currentTime * 1000).toLong()

            Log.d("AbsAudioPlayer", "userMediaProgressUpdate: Comparing server vs local progress")
            Log.d("AbsAudioPlayer", "userMediaProgressUpdate: Server - time: ${currentTime}s (${serverCurrentTimeMs}ms), lastUpdate: $serverLastUpdateMs")
            Log.d("AbsAudioPlayer", "userMediaProgressUpdate: Local  - time: ${localMediaProgress.currentTime}s (${localCurrentTimeMs}ms), lastUpdate: $localLastUpdateMs")

            // Only update if server timestamp is newer AND server progress is significantly ahead
            if (serverLastUpdateMs > localLastUpdateMs) {
                val timeDiffMs = serverCurrentTimeMs - localCurrentTimeMs
                val oneMinuteMs = 60 * 1000L // 1 minute in milliseconds

                Log.d("AbsAudioPlayer", "userMediaProgressUpdate: Server timestamp is newer. Progress difference: ${timeDiffMs}ms (${timeDiffMs/1000.0}s)")

                if (timeDiffMs > oneMinuteMs) {
                    AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Syncing progress from server for \"$libraryItemId\" | server: ${currentTime}s vs local: ${localMediaProgress.currentTime}s (diff: ${timeDiffMs/1000.0}s)")

                    // Update local media progress with server data
                    localMediaProgress.currentTime = currentTime
                    localMediaProgress.duration = duration
                    localMediaProgress.ebookProgress = ebookProgress
                    localMediaProgress.lastUpdate = serverLastUpdateMs
                    DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)
                } else {
                    AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Server progress difference (${timeDiffMs/1000.0}s) is less than 1 minute threshold, keeping local progress")
                }
            } else {
                AbsLogger.info("AbsAudioPlayer", "userMediaProgressUpdate: Local timestamp is newer or equal, keeping local progress | server lastUpdate=$serverLastUpdateMs <= local lastUpdate=$localLastUpdateMs")
            }
        }
        call.resolve()
    }
}
