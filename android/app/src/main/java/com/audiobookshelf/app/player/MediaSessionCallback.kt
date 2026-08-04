package com.audiobookshelf.app.player

import android.annotation.SuppressLint
import android.content.Intent
import android.os.*
import android.util.Log
import android.view.KeyEvent
import java.util.*
import kotlin.concurrent.schedule

class MediaSessionCallback(var playerNotificationService:PlayerNotificationService) {
  var tag = "MediaSessionCallback"

  private var mediaButtonClickCount: Int = 0
  private var mediaButtonClickTimeout: Long = 1000  //ms

  fun handleMediaButtonIntent(intent: Intent): Boolean {
    return handleCallMediaButton(intent)
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
}
