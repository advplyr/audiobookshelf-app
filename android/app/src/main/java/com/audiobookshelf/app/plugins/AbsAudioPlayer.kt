package com.audiobookshelf.app.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.MainActivity
import android.provider.Settings
import androidx.collection.emptyLongSet
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaItemHistory
import com.audiobookshelf.app.data.PlaybackMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.player.CastManager
import com.audiobookshelf.app.player.PlaybackController
import com.audiobookshelf.app.player.PlaybackTelemetryHost
import com.audiobookshelf.app.player.PlayerListener
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.media.PlaybackEventSource
import com.audiobookshelf.app.player.PLAYER_EXO
import com.audiobookshelf.app.player.PLAYER_MEDIA3
import com.audiobookshelf.app.player.SleepTimerNotificationCenter
import com.audiobookshelf.app.player.SleepTimerUiNotifier
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
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
  private var media3ProgressSyncer: MediaProgressSyncer? = null
  private var pendingUiPlaybackEvent: Boolean = false

  private var isCastAvailable:Boolean = false
  private var activePlaybackSession: PlaybackSession? = null
  private var lastKnownMediaPlayer: String? = null

  private val playbackStateBridge = object : PlayerNotificationService.PlaybackStateBridge {
    override fun currentPositionMs(): Long {
      return playbackController?.currentPosition() ?: 0L
    }

    override fun bufferedPositionMs(): Long {
      return playbackController?.bufferedPosition() ?: currentPositionMs()
    }

    override fun durationMs(): Long {
      return playbackController?.duration() ?: (activePlaybackSession?.totalDurationMs ?: 0L)
    }

    override fun isPlaying(): Boolean {
      return playbackController?.isPlaying() ?: false
    }

    override fun playbackState(): Int {
      return playbackController?.playbackState() ?: Player.STATE_IDLE
    }

    override fun currentMediaItemIndex(): Int {
      return playbackController?.currentMediaItemIndex()
        ?: activePlaybackSession?.getCurrentTrackIndex()
        ?: 0
    }
  }

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

  private val media3TelemetryHost = object : PlaybackTelemetryHost {
    override val appContext: Context
      get() = mainActivity.applicationContext

    override val isUnmeteredNetwork: Boolean
      get() = PlayerNotificationService.isUnmeteredNetwork

    override fun isPlayerActive(): Boolean {
      return playbackController?.isPlaying() ?: false
    }

    override fun getCurrentTimeSeconds(): Double {
      return playbackController?.currentPosition()?.div(1000.0) ?: 0.0
    }

    override fun alertSyncSuccess() {
      appEventEmitter.onProgressSyncSuccess()
    }

    override fun alertSyncFailing() {
      appEventEmitter.onProgressSyncFailing()
    }

    override fun notifyLocalProgressUpdate(localMediaProgress: LocalMediaProgress) {
      appEventEmitter.onLocalMediaProgressUpdate(localMediaProgress)
    }

    override fun checkAutoSleepTimer() {
      playbackController?.checkAutoSleepTimer()
    }
  }

  private val playbackControllerListener = object : PlaybackController.Listener {
    override fun onPlaybackSession(session: PlaybackSession) {
      lastKnownMediaPlayer?.let { session.mediaPlayer = it }
      activePlaybackSession = session
      DeviceManager.setLastPlaybackSession(session)
      if(BuildConfig.USE_MEDIA3) { }
        else{
        playerNotificationService.currentPlaybackSession = session
      }

      appEventEmitter.onPlaybackSession(session)
    }

    override fun onPlayingUpdate(isPlaying: Boolean) {
      appEventEmitter.onPlayingUpdate(isPlaying)
      if (BuildConfig.USE_MEDIA3) {
        val session = activePlaybackSession
        val syncer = media3ProgressSyncer
        if (isPlaying && session != null && syncer != null) {
          mainHandler.post { syncer.play(session) }
        }
        if (!isPlaying && syncer != null) {
          mainHandler.post { syncer.pause { } }
        }
      }
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
      if (BuildConfig.USE_MEDIA3) {
        val syncer = media3ProgressSyncer
        if (syncer != null) {
          mainHandler.post {
            syncer.finished {
              playerNotificationService.handlePlaybackEnded()
            }
          }
        }
      }
      activePlaybackSession = null
      appEventEmitter.onPlaybackClosed()
    }

    override fun onSeekCompleted(positionMs: Long, mediaItemIndex: Int) {
      if (BuildConfig.USE_MEDIA3) {
        media3ProgressSyncer?.let { syncer ->
          mainHandler.post { syncer.seek() }
        }
      }
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

    // --- REFACTORED INITIALIZATION LOGIC ---

    if (BuildConfig.USE_MEDIA3) {
      // For Media3 builds, the old PlayerNotificationService is disabled in the manifest.
      // We must initialize playback components directly instead of waiting for a service
      // that will never start.

      Log.d(tag, "USE_MEDIA3 is true. Initializing components directly in load().")

      // Set the event emitter for MediaEventManager, which is used by Media3PlaybackService.
      // This ensures events from the service are sent to the UI.
      MediaEventManager.clientEventEmitter = appEventEmitter

      // Initialize the playback controller and progress syncer.
      if (playbackController == null) {
        playbackController = PlaybackController(mainActivity.applicationContext)
      }
      if (media3ProgressSyncer == null) {
        media3ProgressSyncer = MediaProgressSyncer(media3TelemetryHost, apiHandler)
      }
      if (pendingUiPlaybackEvent) {
        ensureUiPlaybackEventSource()
      }

      // Connect the controller to its listener and to the Media3PlaybackService.
      playbackController?.listener = playbackControllerListener
      playbackController?.connect()

      // The Media3 service handles its own notifications, but we still need to manage the sleep timer.
      SleepTimerNotificationCenter.register(sleepTimerNotifier)

      Log.d(tag, "Media3 components initialized and PlaybackController connected.")

    } else {
      // This is the original logic for non-Media3 builds. It remains unchanged.
      // It relies on MainActivity starting the foreground service and then calling our callback.

      Log.d(tag, "USE_MEDIA3 is false. Using legacy foregroundServiceReady callback.")

      val foregroundServiceReady : () -> Unit = {
        playerNotificationService = mainActivity.foregroundService
        playerNotificationService.clientEventEmitter = appEventEmitter
        MediaEventManager.clientEventEmitter = appEventEmitter // Set for consistency

        // For legacy builds, the service handles everything.
        playerNotificationService.setExternalPlaybackState(null)
        playerNotificationService.setExternalSleepTimerManager(null)
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
        if (statusCode == ConnectionResult.SERVICE_MISSING) {
          Log.w(tag, "initCastManager: Google Api Missing")
        } else if (statusCode == ConnectionResult.SERVICE_DISABLED) {
          Log.w(tag, "initCastManager: Google Api Disabled")
        } else if (statusCode == ConnectionResult.SERVICE_INVALID) {
          Log.w(tag, "initCastManager: Google Api Invalid")
        } else if (statusCode == ConnectionResult.SERVICE_UPDATING) {
          Log.w(tag, "initCastManager: Google Api Updating")
        } else if (statusCode == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED) {
          Log.w(tag, "initCastManager: Google Api Update Required")
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
      android.os.Build.MANUFACTURER,
      android.os.Build.MODEL,
      android.os.Build.VERSION.SDK_INT,
      BuildConfig.VERSION_NAME
    )
  }

  private fun updateSyncerCurrentTime(seconds: Double) {
    media3ProgressSyncer?.updatePlaybackTimeFromUi(seconds)
  }

  private fun buildPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    val mediaPlayerId = if (BuildConfig.USE_MEDIA3) PLAYER_MEDIA3 else PLAYER_EXO
    return PlayItemRequestPayload(
      mediaPlayer = mediaPlayerId,
      forceDirectPlay = !forceTranscode,
      forceTranscode = forceTranscode,
      deviceInfo = buildDeviceInfo()
    )
  }

  private fun ensureUiPlaybackEventSource() {
    if (media3ProgressSyncer != null) {
      media3ProgressSyncer?.markNextPlaybackEventSource(PlaybackEventSource.UI)
      pendingUiPlaybackEvent = false
    } else {
      pendingUiPlaybackEvent = true
    }
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
          Log.d(tag, "prepareLibraryItem: Preparing Local Media item ${jacksonMapper.writeValueAsString(it)}")
          val playbackSession = it.getPlaybackSession(episode, buildDeviceInfo())
          if (startTimeOverride != null) {
            Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
            playbackSession.currentTime = startTimeOverride
          }
          Log.d(tag, "prepareLibraryItem: USE_MEDIA3=${BuildConfig.USE_MEDIA3}")
          if (BuildConfig.USE_MEDIA3) {
            Log.d(tag, "prepareLibraryItem: Routing to Media3 playback controller")
            val syncer = media3ProgressSyncer
            if (syncer?.listeningTimerRunning == true) {
              syncer.stop {
                Log.d(tag, "Media3 progress syncer was already syncing - stopped")
                PlayerListener.lazyIsPlaying = false
                Handler(Looper.getMainLooper()).post {
                  playbackController?.preparePlayback(playbackSession, playWhenReady, playbackRate)
                }
              }
            } else{
              syncer?.reset()
              playbackController?.preparePlayback(playbackSession, playWhenReady, playbackRate)
            }
          }
          else {
            if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) {
              playerNotificationService.mediaProgressSyncer.stop {
                Log.d(tag, "Media progress syncer was already syncing - stopped")
                PlayerListener.lazyIsPlaying = false

                Handler(Looper.getMainLooper()).post {
                  playerNotificationService.preparePlayer(
                    playbackSession,
                    playWhenReady,
                    playbackRate
                  )
                }
              }
            } else {
              playerNotificationService.mediaProgressSyncer.reset()
              playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
            }
          }
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      val playItemRequestPayload = buildPlayItemRequestPayload(false)
      Handler(Looper.getMainLooper()).post {
        val stopCurrentPlayback: (() -> Unit) -> Unit = { completion ->
          if (BuildConfig.USE_MEDIA3) {
            val syncer = media3ProgressSyncer
            if (syncer != null) {
              syncer.stop {
                completion()
              }
            } else {
              completion()
            }
          } else {
            playerNotificationService.mediaProgressSyncer.stop {
              completion()
            }
          }
        }

        stopCurrentPlayback {
          apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) { playbackSession ->
            if (playbackSession == null) {
              call.resolve(JSObject("{\"error\":\"Server play request failed\"}"))
            } else {
              if (startTimeOverride != null) {
                Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
                playbackSession.currentTime = startTimeOverride
              }
              Handler(Looper.getMainLooper()).post {
                Log.d(tag, "Preparing Player playback session ${jacksonMapper.writeValueAsString(playbackSession)}")
                PlayerListener.lazyIsPlaying = false
                if (BuildConfig.USE_MEDIA3) {
                  playbackController?.preparePlayback(playbackSession, playWhenReady, playbackRate)
                } else {
                  playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
                }
              }
              call.resolve(JSObject(jacksonMapper.writeValueAsString(playbackSession)))
            }
          }
        }
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
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
    Handler(Looper.getMainLooper()).post {
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
    Handler(Looper.getMainLooper()).post {
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
    Handler(Looper.getMainLooper()).post {
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
    Handler(Looper.getMainLooper()).post {
      if (BuildConfig.USE_MEDIA3) {
        updateSyncerCurrentTime(time.toDouble())
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
    Handler(Looper.getMainLooper()).post {
      if (BuildConfig.USE_MEDIA3) {
        val currentTime = media3ProgressSyncer?.currentPlaybackSession?.currentTime ?: 0.0
        updateSyncerCurrentTime(currentTime + amount)
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
    Handler(Looper.getMainLooper()).post {
      if (BuildConfig.USE_MEDIA3) {
        val currentTime = media3ProgressSyncer?.currentPlaybackSession?.currentTime ?: 0.0
        updateSyncerCurrentTime((currentTime - amount).coerceAtLeast(0.0))
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

    Handler(Looper.getMainLooper()).post {
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
      if (BuildConfig.USE_MEDIA3) {
        val controller = playbackController
        if (controller == null) {
          val ret = JSObject()
          ret.put("success", false)
          call.resolve(ret)
          return@post
        }
        val playbackSessionId = media3ProgressSyncer?.currentPlaybackSession?.id
          ?: activePlaybackSession?.id
          ?: playerNotificationService.mediaProgressSyncer.currentPlaybackSession?.id
          ?: playerNotificationService.currentPlaybackSession?.id
        controller.setSleepTimer(time, isChapterTime, playbackSessionId) { success ->
          val ret = JSObject()
          ret.put("success", success)
          call.resolve(ret)
        }
        return@post
      }

      val playbackSession = playerNotificationService.mediaProgressSyncer.currentPlaybackSession
        ?: playerNotificationService.currentPlaybackSession
      val success = playerNotificationService.sleepTimerManager.setManualSleepTimer(
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
    Handler(Looper.getMainLooper()).post {
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

      val time = playerNotificationService.sleepTimerManager.getSleepTimerTime()
      val ret = JSObject()
      ret.put("value", time)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.increaseSleepTimer(time)
      } else {
        playerNotificationService.sleepTimerManager.increaseSleepTime(time)
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.decreaseSleepTimer(time)
      } else {
        playerNotificationService.sleepTimerManager.decreaseSleepTime(time)
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      if (BuildConfig.USE_MEDIA3) {
        playbackController?.cancelSleepTimer()
      } else {
        playerNotificationService.sleepTimerManager.cancelSleepTimer()
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
    castManager?.requestSession(playerNotificationService, object : CastManager.RequestSessionCallback() {
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
