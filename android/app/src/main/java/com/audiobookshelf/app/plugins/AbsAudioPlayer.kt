package com.audiobookshelf.app.plugins

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.CastManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

@CapacitorPlugin(name = "AbsAudioPlayer")
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler:ApiHandler

  lateinit var playerNotificationService: PlayerNotificationService
  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    var foregroundServiceReady : () -> Unit = {
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.clientEventEmitter = (object : PlayerNotificationService.ClientEventEmitter {
        override fun onPlaybackSession(playbackSession: PlaybackSession) {
          notifyListeners("onPlaybackSession", JSObject(jacksonObjectMapper().writeValueAsString(playbackSession)))
        }

        override fun onPlaybackClosed() {
          emit("onPlaybackClosed", true)
        }

        override fun onPlayingUpdate(isPlaying: Boolean) {
          emit("onPlayingUpdate", isPlaying)
        }

        override fun onMetadata(metadata: JSObject) {
          notifyListeners("onMetadata", metadata)
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
          notifyListeners("onLocalMediaProgressUpdate", JSObject(jacksonObjectMapper().writeValueAsString(localMediaProgress)))
        }
      })
    }
    mainActivity.pluginCallback = foregroundServiceReady
  }

  fun emit(evtName: String, value: Any) {
    var ret:JSObject = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
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
        Handler(Looper.getMainLooper()).post() {
          Log.d(tag, "Preparing Local Media item ${jacksonObjectMapper().writeValueAsString(it)}")
          var playbackSession = it.getPlaybackSession(episodeId)
          playerNotificationService.preparePlayer(playbackSession, playWhenReady)
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      apiHandler.playLibraryItem(libraryItemId, episodeId, false) {

        Handler(Looper.getMainLooper()).post() {
          Log.d(tag, "Preparing Player TEST ${jacksonObjectMapper().writeValueAsString(it)}")
          playerNotificationService.preparePlayer(it, playWhenReady)
        }

        call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(it)))
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      var currentTime = playerNotificationService.getCurrentTime()
      var bufferedTime = playerNotificationService.getBufferedTime()
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
  fun seekPlayer(call: PluginCall) {
    var time:Long = call.getString("timeMs", "0")!!.toLong()
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.seekPlayer(time)
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    var amount:Long = call.getString("amount", "0")!!.toLong()
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.seekForward(amount)
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    var amount:Long = call.getString("amount", "0")!!.toLong()
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.seekBackward(amount)
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    var playbackSpeed:Float = call.getFloat("speed", 1.0f)!!

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.setPlaybackSpeed(playbackSpeed)
      call.resolve()
    }
  }

  @PluginMethod
  fun terminateStream(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.terminateStream()
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
    Log.d(tag, "CAST REQUEST SESSION PLUGIN")
    call.resolve()
    playerNotificationService.castManager.requestSession(mainActivity, object : CastManager.RequestSessionCallback() {
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
}
