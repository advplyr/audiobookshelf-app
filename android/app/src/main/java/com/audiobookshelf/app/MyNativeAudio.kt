package com.example.myapp

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.Audiobook
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.PlayerNotificationService
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

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


    var audiobook:Audiobook = Audiobook(call.data)
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.initPlayer(audiobook)
      call.resolve()
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      var currentTime = playerNotificationService.getCurrentTime()
      Log.d(tag, "Get Current Time $currentTime")
      val ret = JSObject()
      ret.put("value", currentTime)
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
}
