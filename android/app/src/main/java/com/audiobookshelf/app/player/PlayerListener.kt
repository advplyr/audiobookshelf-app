package com.audiobookshelf.app.player

import android.util.Log
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PlayerState
import com.audiobookshelf.app.device.DeviceManager
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

//const val PAUSE_LEN_BEFORE_RECHECK = 30000 // 30 seconds

class PlayerListener(var playerNotificationService:PlayerNotificationService) : Player.Listener {
  var tag = "PlayerListener"

  companion object {
    var lastPauseTime: Long = 0   //ms
    var lazyIsPlaying: Boolean = false
  }

  override fun onPlayerError(error: PlaybackException) {
    val errorMessage = error.message ?: "Unknown Error"
    Log.e(tag, "onPlayerError $errorMessage")
    // Metrics: count playback errors for this session
    playerNotificationService.metricsRecordError()
    playerNotificationService.handlePlayerPlaybackError(errorMessage) // If was direct playing session, fallback to transcode
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int
  ) {
    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
      // If playing set seeking flag
      playerNotificationService.mediaProgressSyncer.seek()
      lastPauseTime = 0 // When seeking while paused reset the auto-rewind
    } else {
      // No-op for other discontinuities
    }
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    val player = playerNotificationService.playerWrapper

    // Goal of these 2 if statements and the lazyIsPlaying is to ignore this event when it is triggered by a seek
    //  When a seek occurs the player is paused and buffering, then plays again right afterwards.
    if (!isPlaying && player.getPlaybackState() == Player.STATE_BUFFERING) {
      return
    }
    if (lazyIsPlaying == isPlaying) {
      return
    }

    lazyIsPlaying = isPlaying

    // Update widget
    DeviceManager.widgetUpdater?.onPlayerChanged(playerNotificationService)

    if (isPlaying) {
      if (lastPauseTime > 0 && DeviceManager.deviceData.deviceSettings?.disableAutoRewind != true) {
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
        }

        // TODO: this needs to be reworked so that the audio doesn't start playing before it checks for updated progress
        // Check if playback session still exists or sync media progress if updated
//        val pauseLength: Long = System.currentTimeMillis() - lastPauseTime
//        if (pauseLength > PAUSE_LEN_BEFORE_RECHECK) {
//          val shouldCarryOn = playerNotificationService.checkCurrentSessionProgress(seekBackTime)
//          if (!shouldCarryOn) return
//        }

        if (seekBackTime > 0L) {
          playerNotificationService.seekBackward(seekBackTime)
        }
      }
    } else {
      lastPauseTime = System.currentTimeMillis()
    }

    // Start/stop progress sync interval
    if (isPlaying) {
      val playbackSession: PlaybackSession? = playerNotificationService.mediaProgressSyncer.currentPlaybackSession ?: playerNotificationService.currentPlaybackSession
      playbackSession?.let {
        // Handles auto-starting sleep timer and resetting sleep timer
        playerNotificationService.sleepTimerManager.handleMediaPlayEvent(it.id)

        player.setVolume(1F) // Volume on sleep timer might have decreased this

        playerNotificationService.mediaProgressSyncer.play(it)
      }
    } else {
      playerNotificationService.mediaProgressSyncer.pause { }
    }

    playerNotificationService.clientEventEmitter?.onPlayingUpdate(isPlaying)
  }

  override fun onEvents(player: Player, events: Player.Events) {
    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {

      if (playerNotificationService.playerWrapper.getPlaybackState() == Player.STATE_READY) {
        // Metrics: record first READY latency once per session
        playerNotificationService.metricsRecordFirstReadyIfUnset()

        if (lastPauseTime == 0L) {
          lastPauseTime = -1
        }
        playerNotificationService.sendClientMetadata(PlayerState.READY)
      }
      if (playerNotificationService.playerWrapper.getPlaybackState() == Player.STATE_BUFFERING) {
        // Metrics: increment buffer count
        playerNotificationService.metricsRecordBuffer()
        playerNotificationService.sendClientMetadata(PlayerState.BUFFERING)
      }
      if (playerNotificationService.playerWrapper.getPlaybackState() == Player.STATE_ENDED) {
        playerNotificationService.sendClientMetadata(PlayerState.ENDED)

        // Metrics: log simple summary on end
        playerNotificationService.metricsLogSummary()
        playerNotificationService.handlePlaybackEnded()
      }
      if (playerNotificationService.playerWrapper.getPlaybackState() == Player.STATE_IDLE) {
        playerNotificationService.sendClientMetadata(PlayerState.IDLE)
      }
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
