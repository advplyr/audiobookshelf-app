package com.tomesonic.app.player

import android.content.Context
import android.net.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tomesonic.app.data.PlaybackSession
import com.tomesonic.app.data.Podcast
import com.tomesonic.app.device.DeviceManager
import com.tomesonic.app.media.MediaManager
import com.tomesonic.app.plugins.AbsLogger
import com.tomesonic.app.server.ApiHandler

/**
 * Manages network connectivity monitoring and related functionality
 */
class NetworkConnectivityManager(
    private val context: Context,
    private val service: PlayerNotificationService
) {
    companion object {
        private const val TAG = "NetworkConnectivityManager"
    }

    var isUnmeteredNetwork = false
    var hasNetworkConnectivity = false // Not 100% reliable has internet

    private var connectivityManager: ConnectivityManager? = null
    private var forceReloadingAndroidAuto = false
    private var firstLoadDone = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // Network capabilities have changed for the network
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)

            isUnmeteredNetwork = networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED
            )
            hasNetworkConnectivity = networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) && networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )

            Log.i(
                TAG,
                "Network capabilities changed. hasNetworkConnectivity=$hasNetworkConnectivity | isUnmeteredNetwork=$isUnmeteredNetwork"
            )

            service.clientEventEmitter?.onNetworkMeteredChanged(isUnmeteredNetwork)

            if (hasNetworkConnectivity) {
                handleNetworkRestored()
            }
        }
    }

    /**
     * Initializes network connectivity monitoring
     */
    fun initialize() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        connectivityManager = context.getSystemService(ConnectivityManager::class.java) as ConnectivityManager
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)

        Log.d(TAG, "Network connectivity monitoring initialized")
    }

    /**
     * Handles network restoration - forces Android Auto reload and syncs local sessions
     */
    private fun handleNetworkRestored() {
        // Force android auto loading if libraries are empty.
        // Lack of network connectivity is most likely reason for libraries being empty
        if (service.isBrowseTreeInitialized() &&
            firstLoadDone &&
            service.mediaManager.serverLibraries.isEmpty()
        ) {
            Log.d("NetworkConnectivityManager", "AALibrary: Network restored and libraries empty - setting forceReloadingAndroidAuto to true")
            forceReloadingAndroidAuto = true
            // MIGRATION: MediaLibraryService doesn't have notifyChildrenChanged
            // service.notifyChildrenChanged("/")
        }

        // Send any queued local progress syncs when network is restored
        syncLocalSessionsOnNetworkRestore()
    }

    /**
     * Syncs local playback sessions when network is restored
     */
    private fun syncLocalSessionsOnNetworkRestore() {
        val unsyncedSessions = DeviceManager.dbManager.getPlaybackSessions()
        if (unsyncedSessions.isNotEmpty()) {
            service.apiHandler.sendSyncLocalSessions(unsyncedSessions) { success: Boolean, error: String? ->
                if (success) {
                    AbsLogger.info(TAG, "Network restored: Successfully synced ${unsyncedSessions.size} local sessions")
                    // Clear synced sessions from local storage, but keep the most recent one for offline local playback
                    val sortedSessions = unsyncedSessions.sortedByDescending { it.updatedAt }
                    val sessionsToRemove = if (sortedSessions.size > 1) sortedSessions.drop(1) else emptyList()
                    sessionsToRemove.forEach { session ->
                        DeviceManager.dbManager.removePlaybackSession(session.id)
                    }
                    if (sessionsToRemove.isNotEmpty()) {
                        AbsLogger.info(TAG, "Network restored: Kept most recent session (${sortedSessions.first().displayTitle}) for offline playback")
                    }
                } else {
                    AbsLogger.error(TAG, "Network restored: Failed to sync local sessions: $error")
                }
            }
        }
    }

    /**
     * Resumes from last session for Android Auto when it starts
     */
    fun resumeFromLastSessionForAndroidAuto() {
        try {

            // First check for local playback session saved on device
            val lastPlaybackSession = DeviceManager.deviceData.lastPlaybackSession
            if (lastPlaybackSession != null) {
                // Check if session has meaningful progress (not at the very beginning)
                val progress = lastPlaybackSession.currentTime / lastPlaybackSession.duration
                val isResumable = progress > 0.01

                if (isResumable) {

                    // If connected to server, check if server has newer progress for same media
                    if (DeviceManager.checkConnectivity(context)) {
                        // For remote streaming, always request a fresh session to avoid expired URLs
                        if (!lastPlaybackSession.isLocal) {
                            Log.d("NetworkConnectivityManager", "Remote session detected, requesting fresh session from server")

                            // Get fresh session from server, preserving position
                            val playItemRequestPayload = service.getPlayItemRequestPayload(false)
                            service.apiHandler.playLibraryItem(
                                lastPlaybackSession.libraryItemId ?: "",
                                lastPlaybackSession.episodeId,
                                playItemRequestPayload
                            ) { freshSession ->
                                if (freshSession != null) {
                                    // Check server progress vs local and use the latest position
                                    service.checkServerSessionVsLocal(lastPlaybackSession, { shouldUseServer: Boolean, serverSession: PlaybackSession? ->
                                        val positionToUse = if (shouldUseServer && serverSession != null) {
                                            serverSession.currentTime
                                        } else {
                                            lastPlaybackSession.currentTime
                                        }

                                        // Apply the latest position to fresh session
                                        freshSession.currentTime = positionToUse
                                        freshSession.timeListening = lastPlaybackSession.timeListening

                                        Log.d("NetworkConnectivityManager", "Using fresh session with position: ${freshSession.currentTime}s")

                                        // Determine if we should start playing based on Android Auto mode
                                        val shouldStartPlaying = service.isAndroidAuto
                                        val savedPlaybackSpeed = service.mediaManager.getSavedPlaybackRate()

                                        Handler(Looper.getMainLooper()).post {
                                            if (service.mediaProgressSyncer.listeningTimerRunning) {
                                                service.mediaProgressSyncer.stop {
                                                    service.preparePlayer(freshSession, shouldStartPlaying, savedPlaybackSpeed)
                                                }
                                            } else {
                                                service.mediaProgressSyncer.reset()
                                                service.preparePlayer(freshSession, shouldStartPlaying, savedPlaybackSpeed)
                                            }
                                        }
                                    })
                                } else {
                                    Log.e("NetworkConnectivityManager", "Failed to get fresh session, falling back to restoration via AbsAudioPlayer")
                                    // Fall back to normal restoration flow which already handles this
                                }
                            }
                        } else {
                            // Local session - use existing logic
                            service.checkServerSessionVsLocal(lastPlaybackSession, { shouldUseServer: Boolean, serverSession: PlaybackSession? ->
                                val sessionToUse = if (shouldUseServer && serverSession != null) {
                                    serverSession
                                } else {
                                    lastPlaybackSession
                                }

                                // Determine if we should start playing based on Android Auto mode
                                val shouldStartPlaying = service.isAndroidAuto

                                // Prepare the player with appropriate play state and saved playback speed
                                val savedPlaybackSpeed = service.mediaManager.getSavedPlaybackRate()
                                Handler(Looper.getMainLooper()).post {
                                    if (service.mediaProgressSyncer.listeningTimerRunning) {
                                        service.mediaProgressSyncer.stop {
                                            service.preparePlayer(sessionToUse, shouldStartPlaying, savedPlaybackSpeed)
                                        }
                                    } else {
                                        service.mediaProgressSyncer.reset()
                                        service.preparePlayer(sessionToUse, shouldStartPlaying, savedPlaybackSpeed)
                                    }
                                }
                            })
                        }
                    } else {
                        // No connectivity, use local session only (remote sessions can't work without connectivity)
                        if (lastPlaybackSession.isLocal) {
                            // Determine if we should start playing based on Android Auto mode
                            val shouldStartPlaying = service.isAndroidAuto

                            // Prepare the player with appropriate play state and saved playback speed
                            val savedPlaybackSpeed = service.mediaManager.getSavedPlaybackRate()
                            Handler(Looper.getMainLooper()).post {
                                if (service.mediaProgressSyncer.listeningTimerRunning) {
                                    service.mediaProgressSyncer.stop {
                                        service.preparePlayer(lastPlaybackSession, shouldStartPlaying, savedPlaybackSpeed)
                                    }
                                } else {
                                    service.mediaProgressSyncer.reset()
                                    service.preparePlayer(lastPlaybackSession, shouldStartPlaying, savedPlaybackSpeed)
                                }
                            }
                        } else {
                            Log.d("NetworkConnectivityManager", "Remote session requires connectivity, skipping restoration")
                        }
                    }
                    return
                } else {
                }
            }

            // No suitable local session found, check server for last session if connected
            if (!DeviceManager.checkConnectivity(context)) {
                return
            }

            // Use getCurrentUser to get user data which should include session information
            service.apiHandler.getCurrentUser { user ->
                if (user != null) {

                    try {
                        // Get the most recent media progress
                        if (user.mediaProgress.isNotEmpty()) {
                            val latestProgress = user.mediaProgress.maxByOrNull { it.lastUpdate }

                            if (latestProgress != null && latestProgress.currentTime > 0) {

                                // Check if this library item is downloaded locally
                                val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(latestProgress.libraryItemId)

                                if (localLibraryItem != null) {

                                    // Create a local playback session
                                    val deviceInfo = service.getDeviceInfo()
                                    val episode = if (latestProgress.episodeId != null && localLibraryItem.isPodcast) {
                                        val podcast = localLibraryItem.media as? Podcast
                                        podcast?.episodes?.find { ep -> ep.id == latestProgress.episodeId }
                                    } else null

                                    val localPlaybackSession = localLibraryItem.getPlaybackSession(episode, deviceInfo)
                                    // Override the current time with the server progress to sync position
                                    localPlaybackSession.currentTime = latestProgress.currentTime

                                    // Determine if we should start playing based on Android Auto mode
                                    val shouldStartPlaying = service.isAndroidAuto

                                    // Prepare the player with appropriate play state and saved playback speed
                                    val savedPlaybackSpeed = service.mediaManager.getSavedPlaybackRate()
                                    Handler(Looper.getMainLooper()).post {
                                        if (service.mediaProgressSyncer.listeningTimerRunning) {
                                            service.mediaProgressSyncer.stop {
                                                service.preparePlayer(localPlaybackSession, shouldStartPlaying, savedPlaybackSpeed)
                                            }
                                        } else {
                                            service.mediaProgressSyncer.reset()
                                            service.preparePlayer(localPlaybackSession, shouldStartPlaying, savedPlaybackSpeed)
                                        }
                                    }
                                    return@getCurrentUser
                                }

                                // Not downloaded locally, stream from server if possible
                                // TODO: Implement server streaming functionality
                                Log.w(TAG, "Android Auto: Server streaming not yet implemented in NetworkConnectivityManager")
                            } else {
                            }
                        } else {
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Android Auto: Error processing user data for session resume", e)
                    }
                } else {
                    Log.w(TAG, "Android Auto: Failed to get user data from server")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Android Auto: Error during session resume", e)
        }
    }

    /**
     * Sets the first load done flag
     */
    fun setFirstLoadDone(done: Boolean) {
        firstLoadDone = done
    }

    /**
     * Releases network connectivity resources
     */
    fun release() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network connectivity monitoring released")
        } catch (error: Exception) {
            Log.e(TAG, "Error unregistering network listening callback $error")
        }
    }
}
