package com.bookshelf.app.player

import android.util.Log
import com.bookshelf.app.data.PlayerState
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

const val PAUSE_LEN_BEFORE_RECHECK = 30000 // 30 seconds

class PlayerListener(var playerNotificationService:PlayerNotificationService) : Player.Listener {
  var tag = "PlayerListener"

  companion object {
    var lastPauseTime: Long = 0   //ms
  }

  private var onSeekBack: Boolean = false

  override fun onPlayerError(error: PlaybackException) {
    val errorMessage = error.message ?: "Unknown Error"
    Log.e(tag, "onPlayerError $errorMessage")
    playerNotificationService.handlePlayerPlaybackError(errorMessage) // If was direct playing session, fallback to transcode
  }

  override fun onEvents(player: Player, events: Player.Events) {
    Log.d(tag, "onEvents ${player.deviceInfo} | ${playerNotificationService.getMediaPlayer()} | ${events.size()}")

    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
      Log.d(tag, "EVENT_POSITION_DISCONTINUITY")
    }

    if (events.contains(Player.EVENT_IS_LOADING_CHANGED)) {
      Log.d(tag, "EVENT_IS_LOADING_CHANGED : " + playerNotificationService.currentPlayer.isLoading)
    }

    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
      Log.d(tag, "EVENT_PLAYBACK_STATE_CHANGED MediaPlayer = ${playerNotificationService.getMediaPlayer()}")

      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_READY) {
        Log.d(tag, "STATE_READY : " + playerNotificationService.currentPlayer.duration)

        if (lastPauseTime == 0L) {
          lastPauseTime = -1;
        }
        playerNotificationService.sendClientMetadata(PlayerState.READY)
      }
      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_BUFFERING) {
        Log.d(tag, "STATE_BUFFERING : " + playerNotificationService.currentPlayer.currentPosition)
        playerNotificationService.sendClientMetadata(PlayerState.BUFFERING)
      }
      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_ENDED) {
        Log.d(tag, "STATE_ENDED")
        playerNotificationService.sendClientMetadata(PlayerState.ENDED)
      }
      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_IDLE) {
        Log.d(tag, "STATE_IDLE")
        playerNotificationService.sendClientMetadata(PlayerState.IDLE)
      }
    }

    if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
      Log.d(tag, "EVENT_MEDIA_METADATA_CHANGED ${playerNotificationService.getMediaPlayer()}")
    }
    if (events.contains(Player.EVENT_PLAYLIST_METADATA_CHANGED)) {
      Log.d(tag, "EVENT_PLAYLIST_METADATA_CHANGED ${playerNotificationService.getMediaPlayer()}")
    }
    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
      Log.d(tag, "EVENT IS PLAYING CHANGED ${playerNotificationService.getMediaPlayer()}")

      if (player.isPlaying) {
        Log.d(tag, "SeekBackTime: Player is playing")
        if (lastPauseTime > 0) {
          if (onSeekBack) onSeekBack = false
          else {
            Log.d(tag, "SeekBackTime: playing started now set seek back time $lastPauseTime")
            var backTime = calcPauseSeekBackTime()
            if (backTime > 0) {
              if (backTime >= playerNotificationService.getCurrentTime()) backTime = playerNotificationService.getCurrentTime() - 500
              Log.d(tag, "SeekBackTime $backTime")
              onSeekBack = true
              playerNotificationService.seekBackward(backTime)
            } else {
              Log.d(tag, "SeekBackTime: back time is 0")
            }
          }

          // Check if playback session still exists or sync media progress if updated
          val pauseLength: Long = System.currentTimeMillis() - lastPauseTime
          if (pauseLength > PAUSE_LEN_BEFORE_RECHECK) {
            val shouldCarryOn = playerNotificationService.checkCurrentSessionProgress()
            if (!shouldCarryOn) return
          }
        }
      } else {
        Log.d(tag, "SeekBackTime: Player not playing set last pause time")
        lastPauseTime = System.currentTimeMillis()
      }

      // Start/stop progress sync interval
      Log.d(tag, "Playing ${playerNotificationService.getCurrentBookTitle()}")
      if (player.isPlaying) {
        player.volume = 1F // Volume on sleep timer might have decreased this
        playerNotificationService.mediaProgressSyncer.start()
      } else {
        playerNotificationService.mediaProgressSyncer.stop()
      }

      playerNotificationService.clientEventEmitter?.onPlayingUpdate(player.isPlaying)
    }
  }

  private fun calcPauseSeekBackTime() : Long {
    if (lastPauseTime <= 0) return 0
    val time: Long = System.currentTimeMillis() - lastPauseTime
    val seekback: Long
    if (time < 3000) seekback = 0
    else if (time < 300000) seekback = 10000 // 3s to 5m = jump back 10s
    else if (time < 1800000) seekback = 20000 // 5m to 30m = jump back 20s
    else seekback = 29500 // 30m and up = jump back 30s
    return seekback
  }
}
