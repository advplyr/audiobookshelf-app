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
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.PodcastEpisode
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
      playerNotificationService.mediaManager.play(li, null, playerNotificationService.getPlayItemRequestPayload(false)) {
        Log.d(tag, "About to prepare player with ${it.displayTitle}")
        Handler(Looper.getMainLooper()).post() {
          playerNotificationService.preparePlayer(it,true,null)
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
        Log.d(tag, "About to prepare player with ${it.displayTitle}")
        Handler(Looper.getMainLooper()).post() {
          playerNotificationService.preparePlayer(it,true,null)
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
    var libraryItemWrapper: LibraryItemWrapper? = null
    var podcastEpisode: PodcastEpisode? = null

    if (mediaId.isNullOrEmpty()) {
      libraryItemWrapper = playerNotificationService.mediaManager.getFirstItem()
    } else if (mediaId.startsWith("ep_") || mediaId.startsWith("local_ep_")) { // Playing podcast episode
      val libraryItemWithEpisode = playerNotificationService.mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)
      libraryItemWrapper = libraryItemWithEpisode?.libraryItemWrapper
      podcastEpisode = libraryItemWithEpisode?.episode
    } else {
      libraryItemWrapper = playerNotificationService.mediaManager.getById(mediaId)
    }

    libraryItemWrapper?.let { li ->
      playerNotificationService.mediaManager.play(li, podcastEpisode, playerNotificationService.getPlayItemRequestPayload(false)) {
        Log.d(tag, "About to prepare player with ${it.displayTitle}")
        Handler(Looper.getMainLooper()).post() {
          playerNotificationService.preparePlayer(it,true,null)
        }
      }
    }
  }

  override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
    return handleCallMediaButton(mediaButtonEvent)
  }

  fun handleCallMediaButton(intent: Intent): Boolean {
    if(Intent.ACTION_MEDIA_BUTTON == intent.action) {
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
            playerNotificationService.closePlayback()
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
            Log.d(tag, "KeyCode:${keyEvent.getKeyCode()}")
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
