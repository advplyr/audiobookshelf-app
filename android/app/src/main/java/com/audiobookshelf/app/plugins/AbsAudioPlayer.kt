package com.audiobookshelf.app.plugins

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.CastManager
import com.audiobookshelf.app.player.ExoV2PlayerBackend
import com.audiobookshelf.app.player.Media3PlayerBackend
import com.audiobookshelf.app.player.PlayerBackend
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.SleepTimerUiNotifier
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
@OptIn(UnstableApi::class) // Media3PlayerBackend uses Media3 APIs via PlaybackController
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler:ApiHandler
  private val mainHandler = Handler(Looper.getMainLooper())
  var castManager:CastManager? = null

  /** Player backend chosen once at load time; the only place BuildConfig.USE_MEDIA3 is consulted. */
  private lateinit var playerBackend: PlayerBackend

  private var isCastAvailable:Boolean = false

  private val appEventEmitter = object : PlayerNotificationService.ClientEventEmitter {
    override fun onPlaybackSession(playbackSession: PlaybackSession) {
      // retainUntilConsumed: on app open the controller attaches to a service-started session
      // (e.g. Android Auto) before the webview has registered listeners; without retention the
      // event is dropped and the UI never learns about the active session.
      notifyListeners("onPlaybackSession", JSObject(jacksonMapper.writeValueAsString(playbackSession)), true)
    }

    override fun onPlaybackClosed() {
      emit("onPlaybackClosed", true)
    }

    override fun onPlayingUpdate(isPlaying: Boolean) {
      // Retained for the same reason as onPlaybackSession: attaching to a service-started
      // session emits the playing state before the webview registers listeners, and an
      // unretained event leaves the play button out of sync with actual playback.
      val ret = JSObject()
      ret.put("value", isPlaying)
      notifyListeners("onPlayingUpdate", ret, true)
    }

    override fun onMetadata(metadata: PlaybackMetadata) {
      if (!isInForeground) return
      notifyListeners("onMetadata", JSObject(jacksonMapper.writeValueAsString(metadata)))
    }

    override fun onSleepTimerEnded(currentPosition: Long) {
      emit("onSleepTimerEnded", currentPosition)
    }

    override fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer:Boolean) {
      if (!isInForeground) return
      val ret = JSObject()
      ret.put("value", sleepTimeRemaining)
      ret.put("isAuto", isAutoSleepTimer)
      notifyListeners("onSleepTimerSet", ret)
    }

    override fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress) {
      if (!isInForeground) return
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

  // Track foreground state to avoid flooding WebView with events while backgrounded
  private var isInForeground: Boolean = true

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    try {
      initCastManager()
    } catch(e:Exception) {
      Log.e(tag, "initCastManager exception ${e.printStackTrace()}")
    }

    playerBackend = if (BuildConfig.USE_MEDIA3) {
      Log.d(tag, "load: Using Media3 player backend")
      Media3PlayerBackend(mainActivity.applicationContext, appEventEmitter, sleepTimerNotifier)
    } else {
      Log.d(tag, "load: Using ExoPlayer v2 player backend")
      ExoV2PlayerBackend(mainActivity, appEventEmitter)
    }
    playerBackend.initialize()
  }

  fun emit(evtName: String, value: Any) {
    val ret = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
  }

  override fun handleOnPause() {
    super.handleOnPause()
    isInForeground = false
  }

  override fun handleOnResume() {
    super.handleOnResume()
    isInForeground = true

    // Send current state to UI after resume to sync up
    if (::playerBackend.isInitialized) {
      playerBackend.onAppResume()
    }
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
        if (!it.hasTracks(mainActivity, episode)) {
          return call.resolve(JSObject("{\"error\":\"No audio files found on device. Download book again to fix.\"}"))
        }

        mainHandler.post {
          Log.d(tag, "prepareLibraryItem: Preparing Local Media item ${jacksonMapper.writeValueAsString(it)}")
          val playbackSession = it.getPlaybackSession(episode, playerBackend.getDeviceInfo())
          if (startTimeOverride != null) {
            Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
            playbackSession.currentTime = startTimeOverride
          }
          // Stop/close the current session (including its final progress sync) before starting the new one
          playerBackend.stopPlayback {
            mainHandler.post {
              playerBackend.preparePlayback(playbackSession, playWhenReady, playbackRate)
            }
          }
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      val playItemRequestPayload = playerBackend.getPlayItemRequestPayload(false)
      mainHandler.post {
        // Stop/close the current session (including its final progress sync) before requesting the new one
        playerBackend.stopPlayback {
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
                playerBackend.preparePlayback(playbackSession, playWhenReady, playbackRate)
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
    mainHandler.post {
      val currentTime = playerBackend.currentTimeSeconds()
      val bufferedTime = playerBackend.bufferedTimeSeconds()
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun pausePlayer(call: PluginCall) {
    mainHandler.post {
      playerBackend.pause()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPlayer(call: PluginCall) {
    mainHandler.post {
      playerBackend.play()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPause(call: PluginCall) {
    mainHandler.post {
      val playing = playerBackend.playPause()
      call.resolve(JSObject("{\"playing\":$playing}"))
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    val time:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Log.d(tag, "seek action to $time")
    mainHandler.post {
      playerBackend.seekTo(time * 1000L)
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0
    mainHandler.post {
      playerBackend.seekForward(amount * 1000L)
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    mainHandler.post {
      playerBackend.seekBackward(amount * 1000L)
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    val playbackSpeed:Float = call.getFloat("value", 1.0f) ?: 1.0f

    mainHandler.post {
      playerBackend.setPlaybackSpeed(playbackSpeed)
      call.resolve()
    }
  }

  @PluginMethod
  fun closePlayback(call: PluginCall) {
    mainHandler.post {
      playerBackend.closePlayback()
      call.resolve()
    }
  }

  @PluginMethod
  fun setSleepTimer(call: PluginCall) {
    val time:Long = call.getString("time", "360000")!!.toLong()
    val isChapterTime:Boolean = call.getBoolean("isChapterTime", false) == true

    mainHandler.post {
      playerBackend.setSleepTimer(time, isChapterTime) { success ->
        val ret = JSObject()
        ret.put("success", success)
        call.resolve(ret)
      }
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    mainHandler.post {
      playerBackend.getSleepTimerTime { value ->
        val ret = JSObject()
        ret.put("value", value)
        call.resolve(ret)
      }
    }
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    mainHandler.post {
      playerBackend.increaseSleepTimer(time)
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    mainHandler.post {
      playerBackend.decreaseSleepTimer(time)
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    mainHandler.post {
      playerBackend.cancelSleepTimer()
      call.resolve()
    }
  }

  override fun handleOnDestroy() {
    super.handleOnDestroy()
    if (::playerBackend.isInitialized) {
      playerBackend.onDestroy()
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
    castManager?.requestSession(playerBackend.castSessionService(), object : CastManager.RequestSessionCallback() {
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
