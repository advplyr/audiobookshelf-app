package com.audiobookshelf.app.player

import android.util.Log
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PlayerState
import com.audiobookshelf.app.device.DeviceManager
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

const val PAUSE_LEN_BEFORE_RECHECK = 30000 // 30 seconds

class PlayerListener(var playerNotificationService:PlayerNotificationService) : Player.Listener {
  var tag = "PlayerListener"

  companion object {
    var lastPauseTime: Long = 0   //ms
    var lazyIsPlaying: Boolean = false
  }

  override fun onPlayerError(error: PlaybackException) {
    val errorMessage = error.message ?: "Unknown Error"
    Log.e(tag, "onPlayerError $errorMessage")
    playerNotificationService.handlePlayerPlaybackError(errorMessage) // If was direct playing session, fallback to transcode
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int
  ) {
    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
      // If playing set seeking flag
      Log.d(tag, "onPositionDiscontinuity: oldPosition=${oldPosition.positionMs}/${oldPosition.mediaItemIndex}, newPosition=${newPosition.positionMs}/${newPosition.mediaItemIndex}, isPlaying=${playerNotificationService.currentPlayer.isPlaying} reason=SEEK")
      playerNotificationService.mediaProgressSyncer.seek()
      lastPauseTime = 0 // When seeking while paused reset the auto-rewind
    } else {
      Log.d(tag, "onPositionDiscontinuity: oldPosition=${oldPosition.positionMs}/${oldPosition.mediaItemIndex}, newPosition=${newPosition.positionMs}/${newPosition.mediaItemIndex}, isPlaying=${playerNotificationService.currentPlayer.isPlaying}, reason=$reason")
    }
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    Log.d(tag, "onIsPlayingChanged to $isPlaying | ${playerNotificationService.getMediaPlayer()} | playbackState=${playerNotificationService.currentPlayer.playbackState}")

    val player = playerNotificationService.currentPlayer

    // Goal of these 2 if statements and the lazyIsPlaying is to ignore this event when it is triggered by a seek
    //  When a seek occurs the player is paused and buffering, then plays again right afterwards.
    if (!isPlaying && player.playbackState == Player.STATE_BUFFERING) {
      Log.d(tag, "onIsPlayingChanged: Pause event when buffering is ignored")
      return
    }
    if (lazyIsPlaying == isPlaying) {
      Log.d(tag, "onIsPlayingChanged: Lazy is playing $lazyIsPlaying is already set to this so ignoring")
      return
    }

    lazyIsPlaying = isPlaying

    if (isPlaying) {
      Log.d(tag, "SeekBackTime: Player is playing")
      if (lastPauseTime > 0 && DeviceManager.deviceData.deviceSettings?.disableAutoRewind != true) {
        Log.d(tag, "SeekBackTime: playing started now set seek back time $lastPauseTime")
        var seekBackTime = calcPauseSeekBackTime()
        if (seekBackTime > 0) {
          // Current chapter is used so that seek back does not go back to the previous chapter
          val currentChapter = playerNotificationService.getCurrentBookChapter()
          val minSeekBackTime = currentChapter?.startMs ?: 0

          val currentTime = playerNotificationService.getCurrentTime()
          val newTime = currentTime - seekBackTime
          if (newTime < minSeekBackTime) {
            seekBackTime = currentTime - minSeekBackTime
          }
          Log.d(tag, "SeekBackTime $seekBackTime")
        }

        // Check if playback session still exists or sync media progress if updated
        val pauseLength: Long = System.currentTimeMillis() - lastPauseTime
        if (pauseLength > PAUSE_LEN_BEFORE_RECHECK) {
          val shouldCarryOn = playerNotificationService.checkCurrentSessionProgress(seekBackTime)
          if (!shouldCarryOn) return
        }

        if (seekBackTime > 0L) {
          playerNotificationService.seekBackward(seekBackTime)
        }
      }
    } else {
      Log.d(tag, "SeekBackTime: Player not playing set last pause time | playbackState=${player.playbackState}")
      lastPauseTime = System.currentTimeMillis()
    }

    // Start/stop progress sync interval
    if (isPlaying) {
      val playbackSession: PlaybackSession? = playerNotificationService.mediaProgressSyncer.currentPlaybackSession ?: playerNotificationService.currentPlaybackSession
      playbackSession?.let {
        // Handles auto-starting sleep timer and resetting sleep timer
        playerNotificationService.sleepTimerManager.handleMediaPlayEvent(it.id)

        player.volume = 1F // Volume on sleep timer might have decreased this

        playerNotificationService.mediaProgressSyncer.play(it)
      }
    } else {
      playerNotificationService.mediaProgressSyncer.pause {
        Log.d(tag, "Media Progress Syncer paused and synced")
      }
    }

    playerNotificationService.clientEventEmitter?.onPlayingUpdate(isPlaying)

    DeviceManager.widgetUpdater?.onPlayerChanged(playerNotificationService)
  }

  override fun onEvents(player: Player, events: Player.Events) {
    Log.d(tag, "onEvents ${playerNotificationService.getMediaPlayer()} | ${events.size()}")

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
          lastPauseTime = -1
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

        playerNotificationService.handlePlaybackEnded()
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
  }

  private fun calcPauseSeekBackTime() : Long {
    if (lastPauseTime <= 0) return 0
    val time: Long = System.currentTimeMillis() - lastPauseTime
    val seekback: Long
    if (time < 10000) seekback = 0 // 10s or less = no seekback
    else if (time < 60000) seekback = 3000 // 10s to 1m = jump back 3s
    else if (time < 300000) seekback = 10000 // 1m to 5m = jump back 10s
    else if (time < 1800000) seekback = 20000 // 5m to 30m = jump back 20s
    else seekback = 29500 // 30m and up = jump back 30s
    return seekback
  }
}
