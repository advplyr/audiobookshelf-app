package com.audiobookshelf.app.plugins

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.player.CastManager
import com.audiobookshelf.app.player.PLAYER_EXO
import com.audiobookshelf.app.player.PLAYER_MEDIA3
import com.audiobookshelf.app.player.PlayerListener
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.SleepTimerNotificationCenter
import com.audiobookshelf.app.player.SleepTimerUiNotifier
import com.audiobookshelf.app.player.core.NetworkMonitor
import com.audiobookshelf.app.player.media3.PlaybackController
import com.audiobookshelf.app.player.toWidgetSnapshot
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.json.JSONObject

@CapacitorPlugin(name = "AbsAudioPlayer")
@OptIn(UnstableApi::class) // Uses Media3 Player APIs exposed via PlaybackController
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler:ApiHandler
  private val mainHandler = Handler(Looper.getMainLooper())
  var castManager:CastManager? = null

  lateinit var playerNotificationService: PlayerNotificationService
  private var playbackController: PlaybackController? = null
  private var networkStateListener: NetworkMonitor.Listener? = null

  private var isCastAvailable:Boolean = false
  private var activePlaybackSession: PlaybackSession? = null
  private var lastKnownMediaPlayer: String? = null
  private var lastPauseTimestampMs: Long = 0L

  private val appEventEmitter = object : PlayerNotificationService.ClientEventEmitter {
    override fun onPlaybackSession(playbackSession: PlaybackSession) {
      notifyListeners("onPlaybackSession", JSObject(jacksonMapper.writeValueAsString(playbackSession)))
    }

    override fun onPlaybackClosed() {
      emit("onPlaybackClosed", true)
    }

    override fun onPlayingUpdate(isPlaying: Boolean) {
      emit("onPlayingUpdate", isPlaying)
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
  }

  private val sleepTimerNotifier = object : SleepTimerUiNotifier {
    override fun onSleepTimerSet(secondsRemaining: Int, isAuto: Boolean) {
      appEventEmitter.onSleepTimerSet(secondsRemaining, isAuto)
    }

    override fun onSleepTimerEnded(currentPosition: Long) {
      appEventEmitter.onSleepTimerEnded(currentPosition)
    }
  }
  private val playbackControllerListener = object : PlaybackController.Listener {
    override fun onPlaybackSession(session: PlaybackSession) {
      lastKnownMediaPlayer?.let { session.mediaPlayer = it }
      activePlaybackSession = session
      DeviceManager.setLastPlaybackSession(session)
      // ExoPlayer v2 service needs session reference for direct access; Media3 uses session commands
      if (!BuildConfig.USE_MEDIA3) {
        playerNotificationService.currentPlaybackSession = session
      }

      appEventEmitter.onPlaybackSession(session)
      notifyWidgetStateFromController()
    }

    override fun onPlayingUpdate(isPlaying: Boolean) {
      appEventEmitter.onPlayingUpdate(isPlaying)
      if (BuildConfig.USE_MEDIA3) {
        if (isPlaying) {
          activePlaybackSession?.let { maybeAutoRewindOnResume(it) }
          lastPauseTimestampMs = -1L
        } else {
          lastPauseTimestampMs = System.currentTimeMillis()
        }
      }
      notifyWidgetStateFromController()
    }

    override fun onMetadata(metadata: PlaybackMetadata) {
      appEventEmitter.onMetadata(metadata)
    }

    override fun onPlaybackSpeedChanged(speed: Float) {
      appEventEmitter.onPlaybackSpeedChanged(speed)
    }

    override fun onPlaybackClosed() {
      lastKnownMediaPlayer = null
      appEventEmitter.onPlaybackClosed()
      notifyWidgetStateFromController(isClosed = true)
    }

    override fun onMediaPlayerChanged(mediaPlayer: String) {
      lastKnownMediaPlayer = mediaPlayer
      activePlaybackSession?.let { session ->
        session.mediaPlayer = mediaPlayer
        appEventEmitter.onPlaybackSession(session)
      }
      appEventEmitter.onMediaPlayerChanged(mediaPlayer)
    }

    override fun onPlaybackFailed(errorMessage: String) {
      appEventEmitter.onPlaybackFailed(errorMessage)
    }

    override fun onPlaybackEnded() {
      notifyWidgetStateFromController(isClosed = true)
      if (!BuildConfig.USE_MEDIA3) {
        playerNotificationService.handlePlaybackEnded()
      }
      activePlaybackSession = null
      appEventEmitter.onPlaybackClosed()
    }

    override fun onSeekCompleted(positionMs: Long, mediaItemIndex: Int) {
    }
  }

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    try {
      initCastManager()
    } catch(e:Exception) {
      Log.e(tag, "initCastManager exception ${e.printStackTrace()}")
    }

    if (BuildConfig.USE_MEDIA3) {
      Log.d(tag, "USE_MEDIA3 is true. Initializing components directly in load().")

      MediaEventManager.clientEventEmitter = appEventEmitter

      if (playbackController == null) {
        playbackController = PlaybackController(mainActivity.applicationContext)
      }
      NetworkMonitor.initialize(mainActivity.applicationContext)
      if (networkStateListener == null) {
        val listener = NetworkMonitor.Listener { state ->
          appEventEmitter.onNetworkMeteredChanged(state.isUnmetered)
        }
        networkStateListener = listener
        NetworkMonitor.addListener(listener)
      }

      playbackController?.listener = playbackControllerListener
      playbackController?.connect()

      SleepTimerNotificationCenter.register(sleepTimerNotifier)

      Log.d(tag, "Media3 components initialized and PlaybackController connected.")

    } else {
      Log.d(tag, "USE_MEDIA3 is false. Using ExoPlayer foregroundServiceReady callback.")

      val foregroundServiceReady : () -> Unit = {
        playerNotificationService = mainActivity.foregroundService
        playerNotificationService.clientEventEmitter = appEventEmitter
        MediaEventManager.clientEventEmitter = appEventEmitter

        playerNotificationService.setExternalPlaybackState(null)
        SleepTimerNotificationCenter.unregister()
      }
      mainActivity.pluginCallback = foregroundServiceReady
    }
  }


  fun emit(evtName: String, value: Any) {
    val ret = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
  }

  private fun initCastManager() {
    val googleApi = GoogleApiAvailability.getInstance()
    val statusCode = googleApi.isGooglePlayServicesAvailable(mainActivity)

    if (statusCode != ConnectionResult.SUCCESS) {
      when (statusCode) {
        ConnectionResult.SERVICE_MISSING -> Log.w(tag, "initCastManager: Google Api Missing")
        ConnectionResult.SERVICE_DISABLED -> Log.w(tag, "initCastManager: Google Api Disabled")
        ConnectionResult.SERVICE_INVALID -> Log.w(tag, "initCastManager: Google Api Invalid")
        ConnectionResult.SERVICE_UPDATING -> Log.w(tag, "initCastManager: Google Api Updating")
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> Log.w(
          tag,
          "initCastManager: Google Api Update Required"
        )
      }
      return
    }

    val connListener = object: CastManager.ChromecastListener() {
      override fun onReceiverAvailableUpdate(available: Boolean) {
        Log.d(tag, "ChromecastListener: CAST Receiver Update Available $available")
        isCastAvailable = available
        emit("onCastAvailableUpdate", available)
      }

      override fun onSessionRejoin(jsonSession: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onSessionRejoin")
      }

      override fun onMediaLoaded(jsonMedia: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onMediaLoaded")
      }

      override fun onMediaUpdate(jsonMedia: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onMediaUpdate")
      }

      override fun onSessionUpdate(jsonSession: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onSessionUpdate")
      }

      override fun onSessionEnd(jsonSession: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onSessionEnd")
      }

      override fun onMessageReceived(p0: CastDevice, p1: String, p2: String) {
        Log.d(tag, "ChromecastListener: CAST onMessageReceived")
      }
    }

    castManager = CastManager(mainActivity)
    castManager?.startRouteScan(connListener)
  }
  @SuppressLint("HardwareIds")
  private fun buildDeviceInfo(): DeviceInfo {
    /* EXAMPLE
 manufacturer: Google
 model: Pixel 6
 brand: google
 sdkVersion: 32
 appVersion: 0.9.46-beta
*/
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    return DeviceInfo(
      deviceId,
      Build.MANUFACTURER,
      Build.MODEL,
      Build.VERSION.SDK_INT,
      BuildConfig.VERSION_NAME
    )
  }

  private fun notifyWidgetStateFromController(isClosed: Boolean = false) {
    if (!BuildConfig.USE_MEDIA3) return
    val updater = DeviceManager.widgetUpdater ?: return
    val session = activePlaybackSession ?: return
    val controller = playbackController
    val absolutePosition = if (controller != null) {
      currentAbsolutePositionMs(controller, session)
    } else {
      session.currentTimeMs
    }
    val snapshot = session.toWidgetSnapshot(
      context = mainActivity,
      isPlaying = controller?.isPlaying() ?: false,
      isClosed = isClosed,
      positionOverrideMs = absolutePosition
    )
    updater.onPlayerChanged(snapshot)
    if (isClosed) {
      updater.onPlayerClosed()
    }
  }

  private fun maybeAutoRewindOnResume(session: PlaybackSession) {
    if (lastPauseTimestampMs <= 0L) return
    if (DeviceManager.deviceData.deviceSettings?.disableAutoRewind == true) return
    val pauseDuration = System.currentTimeMillis() - lastPauseTimestampMs
    val seekBackMs = calcPauseSeekBackTime(pauseDuration)
    if (seekBackMs <= 0L) return
    val controller = playbackController ?: return
    val currentAbsoluteMs = currentAbsolutePositionMs(controller, session)
    val chapterStartMs = session.getChapterForTime(currentAbsoluteMs)?.startMs ?: 0L
    var safeSeekMs = seekBackMs
    val potentialPosition = currentAbsoluteMs - seekBackMs
    if (potentialPosition < chapterStartMs) {
      safeSeekMs = (currentAbsoluteMs - chapterStartMs).coerceAtLeast(0L)
    }
    if (safeSeekMs > 0L) {
      controller.seekBy(-safeSeekMs)
    }
  }

  private fun currentAbsolutePositionMs(
    controller: PlaybackController,
    session: PlaybackSession
  ): Long {
    val trackIndex = controller.currentMediaItemIndex()
    val offsetMs = session.getTrackStartOffsetMs(trackIndex)
    return controller.currentPosition() + offsetMs
  }

  private fun calcPauseSeekBackTime(durationSincePauseMs: Long): Long {
    return when {
      durationSincePauseMs < 10000L -> 0L
      durationSincePauseMs < 60000L -> 3000L
      durationSincePauseMs < 300000L -> 10000L
      durationSincePauseMs < 1800000L -> 20000L
      else -> 29500L
    }
  }

  private fun buildPlayItemRequestPayload(): PlayItemRequestPayload {
    val mediaPlayerId = if (BuildConfig.USE_MEDIA3) PLAYER_MEDIA3 else PLAYER_EXO
    return PlayItemRequestPayload(
      mediaPlayer = mediaPlayerId,
      forceDirectPlay = true,
      forceTranscode = false,
      deviceInfo = buildDeviceInfo()
    )
  }

  private fun ensureUiPlaybackEventSource() {
    playbackController?.markNextUiPlaybackEvent()
    playbackController?.forceNextPlayingStateDispatch()
  }

  @PluginMethod
  fun prepareLibraryItem(call: PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    val episodeId = call.getString("episodeId", "").toString()
    val playWhenReady = call.getBoolean("playWhenReady") == true
    val playbackRate = call.getFloat("playbackRate",1f) ?: 1f
    val startTimeOverride = call.getDouble("startTime")

    AbsLogger.info("AbsAudioPlayer", "prepareLibraryItem: lid=$libraryItemId, startTimeOverride=$startTimeOverride, playbackRate=$playbackRate")

    if (libraryItemId.isEmpty()) {
      Log.e(tag, "Invalid call to play library item no library item id")
      return call.resolve(JSObject("{\"error\":\"Invalid request\"}"))
    }

    val stopCurrentPlayback: (() -> Unit) -> Unit = { completion ->
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.closePlayback { /* fire-and-forget */ }
        completion()
      } else {
        playerNotificationService.mediaProgressSyncer.stop {
          completion()
        }
      }
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

        mainHandler.post {
          Log.d(tag, "prepareLibraryItem: Preparing Local Media item ${jacksonMapper.writeValueAsString(it)}")
          val playbackSession = it.getPlaybackSession(episode, buildDeviceInfo())
          if (startTimeOverride != null) {
            Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
            playbackSession.currentTime = startTimeOverride
          }
          Log.d(tag, "prepareLibraryItem: USE_MEDIA3=${BuildConfig.USE_MEDIA3}")
          val startPlayback = {
            if (BuildConfig.USE_MEDIA3) {
              Log.d(tag, "prepareLibraryItem: Routing to Media3 playback controller")
              playbackController?.preparePlayback(playbackSession, playWhenReady, playbackRate)
            } else {
              playerNotificationService.mediaProgressSyncer.reset()
              playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
            }
          }
          stopCurrentPlayback {
            mainHandler.post { startPlayback() }
          }
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      val playItemRequestPayload = buildPlayItemRequestPayload()
      mainHandler.post {
        if (BuildConfig.USE_MEDIA3) {
          // For Media3, ensure we flush a final sync for the current session before requesting a new one.
          playbackController?.forceSyncProgress {
            mainHandler.post {
              stopCurrentPlayback {
                apiHandler.playLibraryItem(
                  libraryItemId,
                  episodeId,
                  playItemRequestPayload
                ) { playbackSession ->
                  if (playbackSession == null) {
                    call.resolve(JSObject("{\"error\":\"Server play request failed\"}"))
                  } else {
                    if (startTimeOverride != null) {
                      Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
                      playbackSession.currentTime = startTimeOverride
                    }
                    mainHandler.post {
                      Log.d(
                        tag,
                        "Preparing Player playback session ${
                          jacksonMapper.writeValueAsString(playbackSession)
                        }"
                      )
                      PlayerListener.lazyIsPlaying = false
                      playbackController?.preparePlayback(
                        playbackSession,
                        playWhenReady,
                        playbackRate
                      )
                    }
                    call.resolve(JSObject(jacksonMapper.writeValueAsString(playbackSession)))
                  }
                }
              }
            }
          }
        } else {
          stopCurrentPlayback {
            apiHandler.playLibraryItem(
              libraryItemId,
              episodeId,
              playItemRequestPayload
            ) { playbackSession ->
              if (playbackSession == null) {
                call.resolve(JSObject("{\"error\":\"Server play request failed\"}"))
              } else {
                if (startTimeOverride != null) {
                  Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
                  playbackSession.currentTime = startTimeOverride
                }
                mainHandler.post {
                  Log.d(
                    tag,
                    "Preparing Player playback session ${
                      jacksonMapper.writeValueAsString(playbackSession)
                    }"
                  )
                  PlayerListener.lazyIsPlaying = false
                  playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
                }
                call.resolve(JSObject(jacksonMapper.writeValueAsString(playbackSession)))
              }
            }
          }
        }
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    mainHandler.post {
      val currentTime = if (BuildConfig.USE_MEDIA3) {
        playbackController?.currentPosition()?.div(1000.0) ?: 0.0
      } else {
        playerNotificationService.getCurrentTimeSeconds()
      }
      val bufferedTime = if (BuildConfig.USE_MEDIA3) {
        playbackController?.bufferedPosition()?.div(1000.0) ?: currentTime
      } else {
        playerNotificationService.getBufferedTimeSeconds()
      }
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun pausePlayer(call: PluginCall) {
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        ensureUiPlaybackEventSource()
        playbackController?.pause()
      } else {
        playerNotificationService.pause()
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun playPlayer(call: PluginCall) {
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
      ensureUiPlaybackEventSource()
        playbackController?.play()
      } else {
        playerNotificationService.play()
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun playPause(call: PluginCall) {
    mainHandler.post {
      val playing = if (BuildConfig.USE_MEDIA3) {
        ensureUiPlaybackEventSource()
        playbackController?.playPause() ?: false
      } else {
        playerNotificationService.playPause()
      }
      call.resolve(JSObject("{\"playing\":$playing}"))
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    val time:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Log.d(tag, "seek action to $time")
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.seekTo(time * 1000L)
      } else {
        playerNotificationService.seekPlayer(time * 1000L) // convert to ms
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.seekBy(amount * 1000L)
      } else {
        playerNotificationService.seekForward(amount * 1000L) // convert to ms
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.seekBy(-amount * 1000L)
      } else {
        playerNotificationService.seekBackward(amount * 1000L) // convert to ms
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    val playbackSpeed:Float = call.getFloat("value", 1.0f) ?: 1.0f

    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.setPlaybackSpeed(playbackSpeed)
      } else {
        playerNotificationService.setPlaybackSpeed(playbackSpeed)
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun closePlayback(call: PluginCall) {
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.closePlayback { success ->
          if (!success) {
            Log.w(tag, "closePlayback command returned failure")
          }
        }
      } else {
        playerNotificationService.closePlayback()
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun setSleepTimer(call: PluginCall) {
    val time:Long = call.getString("time", "360000")!!.toLong()
    val isChapterTime:Boolean = call.getBoolean("isChapterTime", false) == true

    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        val controller = playbackController
        if (controller == null) {
          val ret = JSObject()
          ret.put("success", false)
          call.resolve(ret)
          return@post
        }
        val playbackSessionId = activePlaybackSession?.id
          ?: DeviceManager.getLastPlaybackSession()?.id
        controller.setSleepTimer(time, isChapterTime, playbackSessionId) { success ->
          val ret = JSObject()
          ret.put("success", success)
          call.resolve(ret)
        }
        return@post
      }

      val playbackSession = playerNotificationService.mediaProgressSyncer.currentPlaybackSession
        ?: playerNotificationService.currentPlaybackSession
      val success = playerNotificationService.setManualSleepTimer(
        playbackSession?.id ?: "",
        time,
        isChapterTime
      )
      val ret = JSObject()
      ret.put("success", success)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        val controller = playbackController
        if (controller == null) {
          val ret = JSObject()
          ret.put("value", 0L)
          call.resolve(ret)
          return@post
        }
        controller.getSleepTimerTime { value ->
          val ret = JSObject()
          ret.put("value", value)
          call.resolve(ret)
        }
        return@post
      }

      val time = playerNotificationService.getSleepTimerTime()
      val ret = JSObject()
      ret.put("value", time)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.increaseSleepTimer(time)
      } else {
        playerNotificationService.increaseSleepTimer(time)
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.decreaseSleepTimer(time)
      } else {
        playerNotificationService.decreaseSleepTimer(time)
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    mainHandler.post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.cancelSleepTimer()
      } else {
        playerNotificationService.cancelSleepTimer()
      }
      call.resolve()
    }
  }

  override fun handleOnDestroy() {
    super.handleOnDestroy()
    if (BuildConfig.USE_MEDIA3) {
      try {
        playbackController?.stopAndDisconnect()
      } catch (_: Exception) {
      }
      SleepTimerNotificationCenter.unregister()
      networkStateListener?.let { NetworkMonitor.removeListener(it) }
      networkStateListener = null
    }
  }

  @PluginMethod
  fun requestSession(call: PluginCall) {
    // Need to make sure the player service has been started
    Log.d(tag, "CAST REQUEST SESSION PLUGIN")
    call.resolve()
    if (castManager == null) {
      Log.e(tag, "Cast Manager not initialized")
      return
    }
    val exoService = if (BuildConfig.USE_MEDIA3) null else playerNotificationService
    castManager?.requestSession(exoService, object : CastManager.RequestSessionCallback() {
      override fun onError(errorCode: Int) {
        Log.e(tag, "CAST REQUEST SESSION CALLBACK ERROR $errorCode")
      }

      override fun onCancel() {
        Log.d(tag, "CAST REQUEST SESSION ON CANCEL")
      }

      override fun onJoin(jsonSession: JSONObject?) {
        Log.d(tag, "CAST REQUEST SESSION ON JOIN")
      }
    })
  }

  @PluginMethod
  fun getIsCastAvailable(call: PluginCall) {
    val jsobj = JSObject()
    jsobj.put("value", isCastAvailable)
    call.resolve(jsobj)
  }
}
