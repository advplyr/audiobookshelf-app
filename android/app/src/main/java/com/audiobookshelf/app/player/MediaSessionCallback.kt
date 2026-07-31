package com.audiobookshelf.app.player

import android.annotation.SuppressLint
import android.content.Intent
import android.os.*
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
  private var mediaButtonClickTimeout: Long = 1000  //ms

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

  // When a read aloud (TTS) session is active the shared media session is taken
  // over by the TTS player - route transport controls to the engine instead of
  // the audio player. Mapping per docs/native-tts-player-design.md (A.3)
  private fun activeTTSEngine(): TTSPlaybackEngine? {
    return playerNotificationService.ttsEngineIfSessionActive()
  }

  override fun onPlay() {
    Log.d(tag, "ON PLAY MEDIA SESSION COMPAT")
    activeTTSEngine()?.let {
      it.play()
      return
    }
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
    activeTTSEngine()?.let {
      it.pause()
      return
    }
    playerNotificationService.pause()
  }

  override fun onStop() {
    activeTTSEngine()?.let {
      it.stop()
      return
    }
    playerNotificationService.pause()
  }

  override fun onSkipToPrevious() {
    activeTTSEngine()?.let {
      it.seekChapter(-1)
      return
    }
    playerNotificationService.skipToPrevious()
  }

  override fun onSkipToNext() {
    activeTTSEngine()?.let {
      it.seekChapter(1)
      return
    }
    playerNotificationService.skipToNext()
  }

  override fun onFastForward() {
    activeTTSEngine()?.let {
      it.seekParagraph(1)
      return
    }
    playerNotificationService.jumpForward()
  }

  override fun onRewind() {
    activeTTSEngine()?.let {
      it.seekParagraph(-1)
      return
    }
    playerNotificationService.jumpBackward()
  }

  override fun onSeekTo(pos: Long) {
    activeTTSEngine()?.let {
      it.seekToPositionMs(pos)
      return
    }
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

      Log.d(tag, "handleCallMediaButton keyEvent = $keyEvent | action ${keyEvent?.action}")

      // Read aloud (TTS) session active - buttons control the TTS engine
      activeTTSEngine()?.let { tts ->
        return handleTTSMediaButton(tts, keyEvent)
      }

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

  private fun handleTTSMediaButton(tts: TTSPlaybackEngine, keyEvent: KeyEvent?): Boolean {
    val isPlaying = tts.state == TTSPlaybackEngine.TTSState.PLAYING
    // Mirrors the audio handling: widgets only send ACTION_DOWN for play/pause,
    // headset/BT buttons arrive as ACTION_UP
    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
      when (keyEvent.keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (isPlaying) tts.pause() else tts.play()
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> tts.seekParagraph(1)
        KeyEvent.KEYCODE_MEDIA_REWIND -> tts.seekParagraph(-1)
      }
    }
    if (keyEvent?.action == KeyEvent.ACTION_UP) {
      when (keyEvent.keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK -> if (isPlaying) tts.pause() else tts.play()
        KeyEvent.KEYCODE_MEDIA_PLAY -> tts.play()
        KeyEvent.KEYCODE_MEDIA_PAUSE -> tts.pause()
        KeyEvent.KEYCODE_MEDIA_NEXT -> tts.seekChapter(1)
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> tts.seekChapter(-1)
        KeyEvent.KEYCODE_MEDIA_STOP -> tts.stop()
        else -> return false
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
      CUSTOM_ACTION_CHANGE_SPEED -> onChangeSpeed()
    }
  }
}
