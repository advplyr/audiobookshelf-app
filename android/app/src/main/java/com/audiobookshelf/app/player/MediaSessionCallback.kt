package com.audiobookshelf.app.player

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.device.DeviceManager
import java.util.Timer
import kotlin.concurrent.schedule

class MediaSessionCallback(var playerNotificationService:PlayerNotificationService) : MediaSessionCompat.Callback() {
  var tag = "MediaSessionCallback"
  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  private var mediaButtonClickCount: Int = 0
  private var mediaButtonClickTimeout: Long = 1000  //ms

  private var clickTimer: Timer = Timer()
  private var clickTimerId: Long = System.currentTimeMillis()
  private var clickCount: Int = 0
  private var clickPressed: Boolean = false
  private var clickTimerScheduled: Boolean = false

  override fun onPrepare() {
    Log.d(tag, "ON PREPARE MEDIA SESSION COMPAT")
    playerNotificationService.mediaManager.getFirstItem()?.let { li ->
      playerNotificationService.mediaManager.play(li, null, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
          Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          Handler(Looper.getMainLooper()).post {
            playerNotificationService.preparePlayer(it,true, playbackRate)
          }
        }
      }
    }
  }

  override fun onPlay() {
    Log.d(tag, "ON PLAY MEDIA SESSION COMPAT")
    playerNotificationService.play()
  }

  override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM SEARCH $query")
    super.onPrepareFromSearch(query, extras)
  }

  override fun onPlayFromSearch(query: String?, extras: Bundle?) {
    Log.d(tag, "ON PLAY FROM SEARCH $query")
    playerNotificationService.mediaManager.getFromSearch(query)?.let { li ->
      playerNotificationService.mediaManager.play(li, null, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
           Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          Handler(Looper.getMainLooper()).post {
            playerNotificationService.preparePlayer(it, true, playbackRate)
          }
        }
      }
    }
  }

  override fun onPause() {
    Log.d(tag, "ON PAUSE MEDIA SESSION COMPAT")
    playerNotificationService.pause()
  }

  override fun onStop() {
    playerNotificationService.pause()
  }

  override fun onSkipToPrevious() {
    playerNotificationService.skipToPrevious()
  }

  override fun onSkipToNext() {
    playerNotificationService.skipToNext()
  }

  override fun onFastForward() {
    playerNotificationService.jumpForward()
  }

  override fun onRewind() {
    playerNotificationService.jumpBackward()
  }

  override fun onSeekTo(pos: Long) {
    val currentTrackStartOffset = playerNotificationService.getCurrentTrackStartOffsetMs()
    playerNotificationService.seekPlayer(currentTrackStartOffset + pos)
  }

  private fun onChangeSpeed() {
    // cycle to next speed, only contains preset android app options, as each increment needs it's own icon
    // Rounding values in the event a non preset value (.5, 1, 1.2, 1.5, 2, 3) is selected in the phone app
    val mediaManager = playerNotificationService.mediaManager
    val newSpeed = when (mediaManager.getSavedPlaybackRate()) {
      in 0.5f..0.7f -> 1.0f
      in 0.8f..1.0f -> 1.2f
      in 1.1f..1.2f -> 1.5f
      in 1.3f..1.5f -> 2.0f
      in 1.6f..2.0f -> 3.0f
      in 2.1f..3.0f -> 0.5f
      // anything set above 3 (can happen in the android app) will be reset to 1
      else -> 1.0f
    }
    mediaManager.setSavedPlaybackRate(newSpeed)
    playerNotificationService.setPlaybackSpeed(newSpeed)
    playerNotificationService.clientEventEmitter?.onPlaybackSpeedChanged(newSpeed)
  }

  override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
    Log.d(tag, "ON PLAY FROM MEDIA ID $mediaId")
    val libraryItemWrapper: LibraryItemWrapper?
    var podcastEpisode: PodcastEpisode? = null

    if (mediaId.isNullOrEmpty()) {
      libraryItemWrapper = playerNotificationService.mediaManager.getFirstItem()
    } else {
      val libraryItemWithEpisode = playerNotificationService.mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)
      if (libraryItemWithEpisode != null) {
        libraryItemWrapper = libraryItemWithEpisode.libraryItemWrapper
        podcastEpisode = libraryItemWithEpisode.episode
      } else {
        libraryItemWrapper = playerNotificationService.mediaManager.getById(mediaId)
        if (libraryItemWrapper == null) {
          Log.e(tag, "onPlayFromMediaId: Media item not found $mediaId")
        }
      }
    }

    libraryItemWrapper?.let { li ->
      playerNotificationService.mediaManager.play(li, podcastEpisode, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
         Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          Handler(Looper.getMainLooper()).post {
            playerNotificationService.preparePlayer(it, true, playbackRate)
          }
        }
      }
    }
  }

  override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
    return handleCallMediaButton(mediaButtonEvent)
  }

  private fun handleCallMediaButton(intent: Intent): Boolean {
    Log.w(tag, "handleCallMediaButton $intent | ${intent.action}")

    if(Intent.ACTION_MEDIA_BUTTON == intent.action) {
      val keyEvent = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
      }
      if(deviceSettings.enableExtendedHeadsetControls) {
        Log.d(tag, "extended headset control: enabled")
        return debounceKeyEvent(keyEvent)
      } else {
        Log.d(tag, "extended headset control: disabled")
      }

      Log.d(tag, "handleCallMediaButton keyEvent = $keyEvent | action ${keyEvent?.action}")

      // Widget button intent is only sending the action down event
      if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
        Log.d(tag, "handleCallMediaButton: key action_down for ${keyEvent.keyCode}")
        when (keyEvent.keyCode) {
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            Log.d(tag, "handleCallMediaButton: Media Play/Pause")

            // TODO: Play/pause event sent from widget when app is closed. Currently the service gets destroyed before anything can happen
//            if (playerNotificationService.currentPlaybackSession == null && DeviceManager.deviceData.lastPlaybackSession != null) {
//              Log.i(tag, "No playback session but had one in the db")
//
//              val connectionConfig = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == DeviceManager.deviceData.lastPlaybackSession?.serverConnectionConfigId }
//              connectionConfig?.let {
//                Log.i(tag, "Setting playback session from db $it")
//                DeviceManager.serverConnectionConfig = it
//
//                playerNotificationService.currentPlaybackSession = DeviceManager.deviceData.lastPlaybackSession
//                playerNotificationService.startNewPlaybackSession()
//                return true
//              }
//            }

            if (playerNotificationService.mPlayer.isPlaying) {
              if (0 == mediaButtonClickCount) playerNotificationService.pause()
              handleMediaButtonClickCount()
            } else {
              if (0 == mediaButtonClickCount) {
                playerNotificationService.play()
              }
              handleMediaButtonClickCount()
            }
          }
          KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            Log.d(tag, "handleCallMediaButton: Media Fast Forward")
            playerNotificationService.jumpForward()
          }
          KeyEvent.KEYCODE_MEDIA_REWIND -> {
            Log.d(tag, "handleCallMediaButton: Media Rewind")
            playerNotificationService.jumpBackward()
          }
        }
      }

      if (keyEvent?.action == KeyEvent.ACTION_UP) {
        Log.d(tag, "handleCallMediaButton: key action_up for ${keyEvent.keyCode}")
        when (keyEvent.keyCode) {
          KeyEvent.KEYCODE_HEADSETHOOK -> {
            Log.d(tag, "handleCallMediaButton: Headset Hook")
            if (0 == mediaButtonClickCount) {
              if (playerNotificationService.mPlayer.isPlaying)
                playerNotificationService.pause()
              else
                playerNotificationService.play()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY -> {
            Log.d(tag, "handleCallMediaButton: Media Play")
            if (0 == mediaButtonClickCount) {
              playerNotificationService.play()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            Log.d(tag, "handleCallMediaButton: Media Pause")
            if (0 == mediaButtonClickCount) playerNotificationService.pause()
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_NEXT -> {
            playerNotificationService.jumpForward()
          }
          KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            playerNotificationService.jumpBackward()
          }
          KeyEvent.KEYCODE_MEDIA_STOP -> {
            playerNotificationService.closePlayback()
          }
          else -> {
            Log.d(tag, "KeyCode:${keyEvent.keyCode}")
            return false
          }
        }
      }
    }
    return true
  }

  private fun handleMediaButtonClickCount() {
    mediaButtonClickCount++
    if (1 == mediaButtonClickCount) {
      Timer().schedule(mediaButtonClickTimeout) {
        mediaBtnHandler.sendEmptyMessage(mediaButtonClickCount)
        mediaButtonClickCount = 0
      }
    }
  }



  private fun debounceKeyEvent(keyEvent: KeyEvent?): Boolean {
    // how does this work:
    // - every keyDown and keyUp triggers a scheduled handler
    // - another keyDown or keyUp cancels the scheduled handler and re-triggers it with new values
    // - the handler takes clickCount:int and clickPressed:bool (if held down)
    // - keyCodes increase the number of clicks (PlayPause+=1, Next+=2, Prev+=3)
    // - depending on the number of clicks, the playerNotificationService handles the configured action
    // problems:
    // - the logs show pretty accurate click / hold detection, but it does not really translate well in the player
    // - since the trigger is scheduled, it does run in a different thread
    // - this leads to strange behaviour - probably easy to fix, but I'm no kotlin native (Coroutines)
    // - probably after some actions the thread of the player is no longer accessible...
    if (keyEvent?.action == KeyEvent.ACTION_UP) {
      clickPressed = false
      // Log.d(tag, "=== KeyEvent.ACTION_UP")

    } else if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
      // Log.d(tag, "=== KeyEvent.ACTION_DOWN")

      if(clickPressed) {
        return false
      }
      clickPressed = true

      when (keyEvent.keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
          clickCount++
          Log.d(tag, "=== handleCallMediaButton: Headset Hook/Play/ Pause, clickCount=$clickCount")
        }

        KeyEvent.KEYCODE_MEDIA_NEXT -> {
          clickCount += 2
          Log.d(tag, "=== handleCallMediaButton: Media Next, clickCount=$clickCount")
        }

        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
          clickCount += 3
          Log.d(tag, "=== handleCallMediaButton: Media Previous, clickCount=$clickCount")
        }

        KeyEvent.KEYCODE_MEDIA_STOP -> {
          Log.d(tag, "=== handleCallMediaButton: Media Stop, clickCount=$clickCount")
          playerNotificationService.closePlayback()
          clickTimer.cancel()
          return true
        } else -> {
          Log.d(tag, "=== KeyCode:${keyEvent.keyCode}, clickCount=$clickCount")
          return false
        }
      }
    }

    if(clickTimerScheduled) {
      Log.d(tag, "=== clickTimer cancelled ($clickTimerId): clicks=$clickCount, hold=$clickPressed =========")
      clickTimer.cancel()
      clickTimer = Timer()
    }

    clickTimer.schedule(650) {
      Log.d(tag, "=== clickTimer executed ($clickTimerId): clicks=$clickCount, hold=$clickPressed =========")
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.handleClicks(clickCount, clickPressed)
    }
      clickCount = 0
      clickTimerScheduled = false
    }
    clickTimerScheduled = true
    Log.d(tag, "=== clickTimer scheduled ($clickTimerId): clicks=$clickCount, hold=$clickPressed =========")
    return true
  }

  @Suppress("DEPRECATION")
  private val mediaBtnHandler : Handler = @SuppressLint("HandlerLeak")
  object : Handler(){
    override fun handleMessage(msg: Message) {
      super.handleMessage(msg)
      if (2 == msg.what) {
        playerNotificationService.jumpBackward()
        playerNotificationService.play()
      }
      else if (msg.what >= 3) {
        playerNotificationService.jumpForward()
        playerNotificationService.play()
      }
    }
  }

  override fun onCustomAction(action: String?, extras: Bundle?) {
    super.onCustomAction(action, extras)

    when (action) {
      CUSTOM_ACTION_JUMP_FORWARD -> onFastForward()
      CUSTOM_ACTION_JUMP_BACKWARD -> onRewind()
      CUSTOM_ACTION_SKIP_FORWARD -> onSkipToNext()
      CUSTOM_ACTION_SKIP_BACKWARD -> onSkipToPrevious()
      CUSTOM_ACTION_CHANGE_SPEED -> onChangeSpeed()
    }
  }
}
