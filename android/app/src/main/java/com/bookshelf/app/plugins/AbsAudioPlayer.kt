package com.bookshelf.app.plugins

import android.os.Handler
import android.os.Looper
import android.util.Log
<<<<<<< HEAD:android/app/src/main/java/com/bookshelf/app/plugins/AbsAudioPlayer.kt
import androidx.core.content.ContextCompat
import com.bookshelf.app.MainActivity
import com.bookshelf.app.data.*
import com.bookshelf.app.device.DeviceManager
import com.bookshelf.app.player.CastManager
import com.bookshelf.app.player.PlayerNotificationService
import com.bookshelf.app.server.ApiHandler
=======
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.CastManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.server.ApiHandler
>>>>>>> 837df329e2c5362480009fe3173cb0f58e0ed884:android/app/src/main/java/com/audiobookshelf/app/plugins/AbsAudioPlayer.kt
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.json.JSONObject

@CapacitorPlugin(name = "AbsAudioPlayer")
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"
  var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler:ApiHandler
  var castManager:CastManager? = null

  lateinit var playerNotificationService: PlayerNotificationService

  private var isCastAvailable:Boolean = false

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    initCastManager()

    val foregroundServiceReady : () -> Unit = {
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

        override fun onProgressSyncFailing() {
          emit("onProgressSyncFailing", "")
        }

        override fun onProgressSyncSuccess() {
          emit("onProgressSyncSuccess", "")
        }

        override fun onNetworkMeteredChanged(isUnmetered:Boolean) {
          emit("onNetworkMeteredChanged", isUnmetered)
        }
      })
    }
    mainActivity.pluginCallback = foregroundServiceReady
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

  @PluginMethod
  fun prepareLibraryItem(call: PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    val episodeId = call.getString("episodeId", "").toString()
    val playWhenReady = call.getBoolean("playWhenReady") == true
    val playbackRate = call.getFloat("playbackRate",1f) ?: 1f

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

        Handler(Looper.getMainLooper()).post {
          Log.d(tag, "prepareLibraryItem: Preparing Local Media item ${jacksonMapper.writeValueAsString(it)}")
          val playbackSession = it.getPlaybackSession(episode)

          if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) { // If progress syncing then first stop before preparing next
            playerNotificationService.mediaProgressSyncer.stop {
              Log.d(tag, "Media progress syncer was already syncing - stopped")
              Handler(Looper.getMainLooper()).post { // TODO: This was needed again which is probably a design a flaw
                playerNotificationService.preparePlayer(
                  playbackSession,
                  playWhenReady,
                  playbackRate
                )
              }
            }
          } else {
            playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
          }
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      val playItemRequestPayload = playerNotificationService.getPlayItemRequestPayload(false)

      apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
        if (it == null) {
          call.resolve(JSObject("{\"error\":\"Server play request failed\"}"))
        } else {

          Handler(Looper.getMainLooper()).post {
            Log.d(tag, "Preparing Player playback session ${jacksonMapper.writeValueAsString(it)}")

            if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) { // If progress syncing then first stop before preparing next
              playerNotificationService.mediaProgressSyncer.stop {
                Log.d(tag, "Media progress syncer was already syncing - stopped")
                Handler(Looper.getMainLooper()).post { // TODO: This was needed again which is probably a design a flaw
                  playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
                }
              }
            } else {
              playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
            }
          }

          call.resolve(JSObject(jacksonMapper.writeValueAsString(it)))
        }
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val currentTime = playerNotificationService.getCurrentTimeSeconds()
      val bufferedTime = playerNotificationService.getBufferedTimeSeconds()
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun pausePlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.pause()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.play()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPause(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val playing = playerNotificationService.playPause()
      call.resolve(JSObject("{\"playing\":$playing}"))
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    val time:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekPlayer(time * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekForward(amount * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekBackward(amount * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    val playbackSpeed:Float = call.getFloat("value", 1.0f) ?: 1.0f

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.setPlaybackSpeed(playbackSpeed)
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
        val success:Boolean = playerNotificationService.sleepTimerManager.setSleepTimer(time, isChapterTime)
        val ret = JSObject()
        ret.put("success", success)
        call.resolve(ret)
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    val time = playerNotificationService.sleepTimerManager.getSleepTimerTime()
    val ret = JSObject()
    ret.put("value", time)
    call.resolve(ret)
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.increaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
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
