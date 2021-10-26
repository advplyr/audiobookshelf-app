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

    var audiobook:Audiobook = Audiobook(call.data)
    if (audiobook.playlistUrl == "" && audiobook.contentUrl == "") {
      Log.e(tag, "Invalid URL for init audio player")

      jsobj.put("success", false)
      return call.resolve(jsobj)
    }

    Handler(Looper.getMainLooper()).post() {
      playerNotificationService.initPlayer(audiobook)
      jsobj.put("success", true)
      call.resolve(jsobj)
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
  fun getStreamSyncData(call: PluginCall) {
    Handler(Looper.getMainLooper()).post() {
      var isPlaying = playerNotificationService.getPlayStatus()
      var lastPauseTime = playerNotificationService.getTheLastPauseTime()
      Log.d(tag, "Get Last Pause Time $lastPauseTime")
      var currentTime = playerNotificationService.getCurrentTime()
      Log.d(tag, "Get Current Time $currentTime")
      //if (!isPlaying) currentTime -= playerNotificationService.calcPauseSeekBackTime()
      var id = playerNotificationService.getCurrentAudiobookId()
      Log.d(tag, "Get Current id $id")
      val ret = JSObject()
      ret.put("lastPauseTime", lastPauseTime)
      ret.put("currentTime", currentTime)
      ret.put("isPlaying", isPlaying)
      ret.put("id", id)
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
  fun setAudiobooks(call: PluginCall) {
    var audiobooks = call.getArray("audiobooks", JSArray())
    if (audiobooks == null) {
      Log.w(tag, "setAudiobooks IS NULL")
      call.resolve()
      return
    }

    var audiobookObjs = mutableListOf<Audiobook>()

    var len = audiobooks.length()
    (0 until len).forEach { _it ->
      var jsonobj = audiobooks.get(_it) as JSONObject

      var _names = Array(jsonobj.names().length()) {
        jsonobj.names().getString(it)
      }
      var jsobj = JSObject(jsonobj, _names)

      if (jsobj.has("duration")) {
        var dur = jsobj.getDouble("duration")
        var duration = Math.floor(dur * 1000L).toLong()
        jsobj.put("duration", duration)
      }

      var audiobook = Audiobook(jsobj)
      audiobookObjs.add(audiobook)
    }
    Log.d(tag, "Setting Audiobooks ${audiobookObjs.size}")
    playerNotificationService.setAudiobooks(audiobookObjs)
  }
}
