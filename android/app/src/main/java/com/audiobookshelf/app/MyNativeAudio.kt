package com.audiobookshelf.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import com.capacitorjs.plugins.app.AppPlugin
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

@CapacitorPlugin(name = "MyNativeAudio")
class MyNativeAudio : Plugin() {
  private val tag = "MyNativeAudio"

  lateinit var mainActivity:MainActivity
  lateinit var apiHandler:ApiHandler
  lateinit var playerNotificationService: PlayerNotificationService

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    var foregroundServiceReady : () -> Unit = {
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.setBridge(bridge)

      playerNotificationService.setCustomObjectListener(object : PlayerNotificationService.MyCustomObjectListener {
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
    var libraryItemId = call.getString("libraryItemId", "").toString()
    var mediaEntityId = call.getString("mediaEntityId", "").toString()
    var playWhenReady = call.getBoolean("playWhenReady") == true

    apiHandler.playLibraryItem(libraryItemId) {

      Handler(Looper.getMainLooper()).post() {
        Log.d(tag, "Preparing Player TEST ${jacksonObjectMapper().writeValueAsString(it)}")
        playerNotificationService.preparePlayer(it, playWhenReady)
      }

      call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(it)))
    }
  }

  @PluginMethod
  fun playLocalLibraryItem(call:PluginCall) {
    var localMediaItemId = call.getString("localMediaItemId", "").toString()
    var playWhenReady = call.getBoolean("playWhenReady") == true
    Log.d(tag, "playLocalLibraryItem $playWhenReady")

    DeviceManager.dbManager.loadLocalMediaItem(localMediaItemId)?.let {
      Handler(Looper.getMainLooper()).post() {
        Log.d(tag, "Preparing Local Media item ${jacksonObjectMapper().writeValueAsString(it)}")
        var playbackSession = it.getPlaybackSession()
        playerNotificationService.preparePlayer(playbackSession, playWhenReady)
      }
      return call.resolve(JSObject())
    }
    var errObj = JSObject()
    errObj.put("error", "Item Not Found")
    call.resolve(errObj)
  }

  @PluginMethod
  fun getLibraryItems(call: PluginCall) {
    var libraryId = call.getString("libraryId", "").toString()
    apiHandler.getLibraryItems(libraryId)  {
      val mapper = jacksonObjectMapper()
      var jsobj = JSObject()
      var libarray = JSArray()
      it.map {
        libarray.put(JSObject(mapper.writeValueAsString(it)))
      }
      jsobj.put("value", libarray)
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun initPlayer(call: PluginCall) {
    if (!PlayerNotificationService.isStarted) {
      Log.w(tag, "Starting foreground service --")
      Intent(mainActivity, PlayerNotificationService::class.java).also { intent ->
        ContextCompat.startForegroundService(mainActivity, intent)
      }
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
      var bufferedTime = playerNotificationService.getBufferedTime()
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)
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
