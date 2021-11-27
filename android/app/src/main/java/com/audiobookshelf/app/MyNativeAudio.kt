package com.audiobookshelf.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

@CapacitorPlugin(name = "MyNativeAudio")
class MyNativeAudio : Plugin() {
  private val tag = "MyNativeAudio"

  lateinit var mainActivity:MainActivity
  lateinit var playerNotificationService: PlayerNotificationService

  override fun load() {
    mainActivity = (activity as MainActivity)

    var foregroundServiceReady : () -> Unit = {
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.setCustomObjectListener(object: PlayerNotificationService.MyCustomObjectListener {
        override fun onPlayingUpdate(isPlaying:Boolean) {
          emit("onPlayingUpdate", isPlaying)
        }
        override fun onMetadata(metadata:JSObject) {
          notifyListeners("onMetadata", metadata)
        }
        override fun onPrepare(audiobookId:String, playWhenReady:Boolean) {
          var jsobj = JSObject()
          jsobj.put("audiobookId", audiobookId)
          jsobj.put("playWhenReady", playWhenReady)
          notifyListeners("onPrepareMedia", jsobj)
        }
        override fun onSleepTimerEnded(currentPosition:Long) {
          emit("onSleepTimerEnded", currentPosition)
        }
        override fun onSleepTimerSet(sleepTimerEndTime:Long) {
          emit("onSleepTimerSet", sleepTimerEndTime)
        }
      })
    }
    mainActivity.pluginCallback = foregroundServiceReady

  }

  fun emit(evtName: String, value:Any) {
    var ret:JSObject = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
  }

  @PluginMethod
  fun initPlayer(call: PluginCall) {
    if (!PlayerNotificationService.isStarted) {
      Log.w(tag, "Starting foreground service --")
      Intent(mainActivity, PlayerNotificationService::class.java).also { intent ->
        ContextCompat.startForegroundService(mainActivity, intent)
      }
    } else {
      Log.w(tag, "Service already started --")
    }
    var jsobj = JSObject()

    var audiobookStreamData:AudiobookStreamData = AudiobookStreamData(call.data)
    if (audiobookStreamData.playlistUrl == "" && audiobookStreamData.contentUrl == "") {
      Log.e(tag, "Invalid URL for init audio player")

      jsobj.put("success", false)
      return call.resolve(jsobj)
    }

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.initPlayer(audiobookStreamData)
      jsobj.put("success", true)
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      var currentTime = playerNotificationService.getCurrentTime()
      val ret = JSObject()
      ret.put("value", currentTime)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun getStreamSyncData(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      var isPlaying = playerNotificationService.getPlayStatus()
      var lastPauseTime = playerNotificationService.getTheLastPauseTime()
      Log.d(tag, "Get Last Pause Time $lastPauseTime")
      var currentTime = playerNotificationService.getCurrentTime()
      //if (!isPlaying) currentTime -= playerNotificationService.calcPauseSeekBackTime()
      var id = playerNotificationService.getCurrentAudiobookId()
      Log.d(tag, "Get Current id $id")
      var duration = playerNotificationService.getDuration()
      Log.d(tag, "Get duration $duration")
      val ret = JSObject()
      ret.put("lastPauseTime", lastPauseTime)
      ret.put("currentTime", currentTime)
      ret.put("isPlaying", isPlaying)
      ret.put("id", id)
      ret.put("duration", duration)
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
        var success:Boolean = playerNotificationService.setSleepTimer(time, isChapterTime)
        val ret = JSObject()
        ret.put("success", success)
        call.resolve(ret)
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    var time = playerNotificationService.getSleepTimerTime()
    val ret = JSObject()
    ret.put("value", time)
    call.resolve(ret)
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    var time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.increaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    var time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.decreaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    playerNotificationService.cancelSleepTimer()
    call.resolve()
  }

  @PluginMethod
  fun requestSession(call:PluginCall) {
    Log.d(tag, "CAST REQUEST SESSION PLUGIN")

      playerNotificationService.requestSession(mainActivity, object : PlayerNotificationService.RequestSessionCallback() {
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
