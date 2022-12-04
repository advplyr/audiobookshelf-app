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
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.PodcastEpisode
import java.util.*
import kotlin.concurrent.schedule

class MediaSessionCallback(var playerNotificationService:PlayerNotificationService) : MediaSessionCompat.Callback() {
  var tag = "MediaSessionCallback"

  private var mediaButtonClickCount: Int = 0
  var mediaButtonClickTimeout: Long = 1000  //ms

  override fun onPrepare() {
    Log.d(tag, "ON PREPARE MEDIA SESSION COMPAT")
    playerNotificationService.mediaManager.getFirstItem()?.let { li ->
      playerNotificationService.mediaManager.play(li, null, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
          Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          Handler(Looper.getMainLooper()).post() {
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
          Handler(Looper.getMainLooper()).post() {
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

      if (libraryItemWrapper == null) {
        Log.e(tag, "onPlayFromMediaId: Media item not found $mediaId")
      }
    }

    libraryItemWrapper?.let { li ->
      playerNotificationService.mediaManager.play(li, podcastEpisode, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
         Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          Handler(Looper.getMainLooper()).post() {
            playerNotificationService.preparePlayer(it, true, playbackRate)
          }
        }
      }
    }
  }

  override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
    return handleCallMediaButton(mediaButtonEvent)
  }

  fun handleCallMediaButton(intent: Intent): Boolean {
    Log.w(tag, "handleCallMediaButton $intent | ${intent.action}")

    if(Intent.ACTION_MEDIA_BUTTON == intent.action) {
      val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
      Log.d(tag, "handleCallMediaButton keyEvent = $keyEvent | action ${keyEvent?.action}")

      if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
        Log.d(tag, "handleCallMediaButton: key action_down for ${keyEvent.keyCode}")
        when (keyEvent.keyCode) {
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            Log.d(tag, "handleCallMediaButton: Media Play/Pause")
            if (playerNotificationService.mPlayer.isPlaying) {
              playerNotificationService.pause()
            } else {
              playerNotificationService.play()
            }
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
              playerNotificationService.sleepTimerManager.checkShouldExtendSleepTimer()
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
          KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            playerNotificationService.jumpForward()
          }
          KeyEvent.KEYCODE_MEDIA_REWIND -> {
            playerNotificationService.jumpBackward()
          }
          KeyEvent.KEYCODE_MEDIA_STOP -> {
            playerNotificationService.closePlayback()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            Log.d(tag, "handleCallMediaButton: Media Play/Pause")
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

  override fun onCustomAction(action: String?, extras: Bundle?) {
    super.onCustomAction(action, extras)

    when (action) {
      CUSTOM_ACTION_JUMP_FORWARD -> onFastForward()
      CUSTOM_ACTION_JUMP_BACKWARD -> onRewind()
      CUSTOM_ACTION_SKIP_FORWARD -> onSkipToNext()
      CUSTOM_ACTION_SKIP_BACKWARD -> onSkipToPrevious()
    }
  }
}
