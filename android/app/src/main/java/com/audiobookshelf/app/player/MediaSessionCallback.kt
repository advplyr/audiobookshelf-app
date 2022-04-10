package com.audiobookshelf.app.player

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import com.audiobookshelf.app.data.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.concurrent.schedule

class MediaSessionCallback(var playerNotificationService:PlayerNotificationService) : MediaSessionCompat.Callback() {
  var tag = "MediaSessionCallback"

  private var mediaButtonClickCount: Int = 0
  var mediaButtonClickTimeout: Long = 1000  //ms
  var seekAmount: Long = 20000   //ms

  override fun onPrepare() {
    Log.d(tag, "ON PREPARE MEDIA SESSION COMPAT")
    playerNotificationService.mediaManager.getFirstItem()?.let { li ->
      playerNotificationService.mediaManager.play(li) {
        Log.d(tag, "About to prepare player with li ${li.title}")
        Handler(Looper.getMainLooper()).post() {
          playerNotificationService.preparePlayer(it,true)
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
      playerNotificationService.mediaManager.play(li) {
        Log.d(tag, "About to prepare player with li ${li.title}")
        Handler(Looper.getMainLooper()).post() {
          playerNotificationService.preparePlayer(it,true)
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
    playerNotificationService.seekBackward(seekAmount)
  }

  override fun onSkipToNext() {
    playerNotificationService.seekForward(seekAmount)
  }

  override fun onFastForward() {
    playerNotificationService.seekForward(seekAmount)
  }

  override fun onRewind() {
    playerNotificationService.seekForward(seekAmount)
  }

  override fun onSeekTo(pos: Long) {
    playerNotificationService.seekPlayer(pos)
  }

  override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
    Log.d(tag, "ON PLAY FROM MEDIA ID $mediaId")
    var libraryItem: LibraryItem? = null
    if (mediaId.isNullOrEmpty()) {
      libraryItem = playerNotificationService.mediaManager.getFirstItem()
    } else {
      libraryItem = playerNotificationService.mediaManager.getById(mediaId)
    }

    libraryItem?.let { li ->
      playerNotificationService.mediaManager.play(li) {
        Log.d(tag, "About to prepare player with li ${li.title}")
        Handler(Looper.getMainLooper()).post() {
          playerNotificationService.preparePlayer(it,true)
        }
      }
    }
  }

  override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
    return handleCallMediaButton(mediaButtonEvent)
  }

  fun handleCallMediaButton(intent: Intent): Boolean {
    if(Intent.ACTION_MEDIA_BUTTON == intent.getAction()) {
      var keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
      if (keyEvent?.getAction() == KeyEvent.ACTION_UP) {
        when (keyEvent?.getKeyCode()) {
          KeyEvent.KEYCODE_HEADSETHOOK -> {
            if (0 == mediaButtonClickCount) {
              if (playerNotificationService.mPlayer.isPlaying)
                playerNotificationService.pause()
              else
                playerNotificationService.play()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY -> {
            if (0 == mediaButtonClickCount) {
              playerNotificationService.play()
              playerNotificationService.sleepTimerManager.checkShouldExtendSleepTimer()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            if (0 == mediaButtonClickCount) playerNotificationService.pause()
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_NEXT -> {
            playerNotificationService.seekForward(seekAmount)
          }
          KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            playerNotificationService.seekBackward(seekAmount)
          }
          KeyEvent.KEYCODE_MEDIA_STOP -> {
            playerNotificationService.terminateStream()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            if (playerNotificationService.mPlayer.isPlaying) {
              if (0 == mediaButtonClickCount) playerNotificationService.pause()
              handleMediaButtonClickCount()
            } else {
              if (0 == mediaButtonClickCount) {
                playerNotificationService.play()
                playerNotificationService.sleepTimerManager.checkShouldExtendSleepTimer()
              }
              handleMediaButtonClickCount()
            }
          }
          else -> {
            Log.d(tag, "KeyCode:${keyEvent?.getKeyCode()}")
            return false
          }
        }
      }
    }
    return true
  }

  fun handleMediaButtonClickCount() {
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
        playerNotificationService.seekBackward(seekAmount)
        playerNotificationService.play()
      }
      else if (msg.what >= 3) {
        playerNotificationService.seekForward(seekAmount)
        playerNotificationService.play()
      }
    }
  }

}
