package com.audiobookshelf.app.plugins

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.CastManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.cast.CastDevice
import org.json.JSONObject

@CapacitorPlugin(name = "AbsAudioPlayer")
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"
  var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler:ApiHandler
  lateinit var castManager:CastManager

  lateinit var playerNotificationService: PlayerNotificationService

  private var isCastAvailable:Boolean = false

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    initCastManager()

    var foregroundServiceReady : () -> Unit = {
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.clientEventEmitter = (object : PlayerNotificationService.ClientEventEmitter {
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

        override fun onPrepare(audiobookId: String, playWhenReady: Boolean) {
          var jsobj = JSObject()
          jsobj.put("audiobookId", audiobookId)
          jsobj.put("playWhenReady", playWhenReady)
          notifyListeners("onPrepareMedia", jsobj)
        }

        override fun onSleepTimerEnded(currentPosition: Long) {
          emit("onSleepTimerEnded", currentPosition)
        }

        override fun onSleepTimerSet(sleepTimeRemaining: Int) {
          emit("onSleepTimerSet", sleepTimeRemaining)
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
      })
    }
    mainActivity.pluginCallback = foregroundServiceReady
  }

  fun emit(evtName: String, value: Any) {
    var ret = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
  }

  fun initCastManager() {
    var connListener = object: CastManager.ChromecastListener() {
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
    castManager.startRouteScan(connListener)
  }

  @PluginMethod
  fun prepareLibraryItem(call: PluginCall) {
    // Need to make sure the player service has been started
    if (!PlayerNotificationService.isStarted) {
      Log.w(tag, "prepareLibraryItem: PlayerService not started - Starting foreground service --")
      Intent(mainActivity, PlayerNotificationService::class.java).also { intent ->
        ContextCompat.startForegroundService(mainActivity, intent)
      }
    }

    var libraryItemId = call.getString("libraryItemId", "").toString()
    var episodeId = call.getString("episodeId", "").toString()
    var playWhenReady = call.getBoolean("playWhenReady") == true

    if (libraryItemId.isEmpty()) {
      Log.e(tag, "Invalid call to play library item no library item id")
      return call.resolve()
    }

    if (libraryItemId.startsWith("local")) { // Play local media item
      DeviceManager.dbManager.getLocalLibraryItem(libraryItemId)?.let {
        var episode: PodcastEpisode? = null
        if (!episodeId.isNullOrEmpty()) {
          var podcastMedia = it.media as Podcast
          episode = podcastMedia.episodes?.find { ep -> ep.id == episodeId }
          if (episode == null) {
            Log.e(tag, "prepareLibraryItem: Podcast episode not found $episodeId")
            return call.resolve(JSObject())
          }
        }

        Handler(Looper.getMainLooper()).post() {
          Log.d(tag, "prepareLibraryItem: Preparing Local Media item ${jacksonMapper.writeValueAsString(it)}")
          var playbackSession = it.getPlaybackSession(episode)
          playerNotificationService.preparePlayer(playbackSession, playWhenReady)
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      var mediaPlayer = playerNotificationService.getMediaPlayer()

      apiHandler.playLibraryItem(libraryItemId, episodeId, false, mediaPlayer) {

        Handler(Looper.getMainLooper()).post() {
          Log.d(tag, "Preparing Player TEST ${jacksonMapper.writeValueAsString(it)}")
          playerNotificationService.preparePlayer(it, playWhenReady)
        }

        call.resolve(JSObject(jacksonMapper.writeValueAsString(it)))
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      var currentTime = playerNotificationService.getCurrentTimeSeconds()
      var bufferedTime = playerNotificationService.getBufferedTimeSeconds()
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun pausePlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.pause()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.play()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPause(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      var playing = playerNotificationService.playPause()
      call.resolve(JSObject("{\"playing\":$playing}"))
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    var time:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.seekPlayer(time * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    var amount:Int = call.getInt("value", 0) ?: 0
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.seekForward(amount * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    var amount:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.seekBackward(amount * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    var playbackSpeed:Float = call.getFloat("value", 1.0f) ?: 1.0f

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.setPlaybackSpeed(playbackSpeed)
      call.resolve()
    }
  }

  @PluginMethod
  fun closePlayback(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.closePlayback()
      call.resolve()
    }
  }

  @PluginMethod
  fun setSleepTimer(call: PluginCall) {
    var time:Long = call.getString("time", "360000")!!.toLong()
    var isChapterTime:Boolean = call.getBoolean("isChapterTime", false) == true

    Handler(Looper.getMainLooper()).post() {
        var success:Boolean = playerNotificationService.sleepTimerManager.setSleepTimer(time, isChapterTime)
        val ret = JSObject()
        ret.put("success", success)
        call.resolve(ret)
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    var time = playerNotificationService.sleepTimerManager.getSleepTimerTime()
    val ret = JSObject()
    ret.put("value", time)
    call.resolve(ret)
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    var time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.sleepTimerManager.increaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    var time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.sleepTimerManager.decreaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    playerNotificationService.sleepTimerManager.cancelSleepTimer()
    call.resolve()
  }

  @PluginMethod
  fun requestSession(call: PluginCall) {
    // Need to make sure the player service has been started
    Log.d(tag, "CAST REQUEST SESSION PLUGIN")
    call.resolve()
    castManager.requestSession(playerNotificationService, object : CastManager.RequestSessionCallback() {
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
    var jsobj = JSObject()
    jsobj.put("value", isCastAvailable)
    call.resolve(jsobj)
  }
}
