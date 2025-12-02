package com.audiobookshelf.app.player

import android.util.Log
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PlayerState
import com.audiobookshelf.app.device.DeviceManager
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

//const val PAUSE_LEN_BEFORE_RECHECK = 30000 // 30 seconds

class PlayerListener(var playerNotificationService:PlayerNotificationService) : Player.Listener, PlayerEvents {
  var tag = "PlayerListener"

  companion object {
    var lastPauseTime: Long = 0   //ms
    var lazyIsPlaying: Boolean = false
  }

  // PlayerEvents implementation (neutral)
  override fun onPlayerError(message: String, errorCode: Int?) {
    val errorMessage = message.ifBlank { "Unknown Error" }
    Log.e(tag, "onPlayerError $errorMessage")
    // Metrics: count playback errors for this session
    playerNotificationService.metricsRecordError()
    playerNotificationService.handlePlayerPlaybackError(errorMessage) // If was direct playing session, fallback to transcode
  }

  override fun onPositionDiscontinuity(isSeek: Boolean) {
    if (isSeek) {
      val player = playerNotificationService.playerWrapper
  Log.d(tag, "onPositionDiscontinuity SEEK from index=${player.getCurrentMediaItemIndex()} pos=${player.getCurrentPositionLive()} buffered=${player.getBufferedPosition()} state=${player.getPlaybackState()}")
      // If playing set seeking flag
      playerNotificationService.mediaProgressSyncer.seek()
      lastPauseTime = 0 // When seeking while paused reset the auto-rewind
    } else {
      Log.d(tag, "onPositionDiscontinuity NON-SEEK state=${playerNotificationService.playerWrapper.getPlaybackState()}")
    }
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    val player = playerNotificationService.playerWrapper
  Log.d(tag, "onIsPlayingChanged -> $isPlaying playbackState=${player.getPlaybackState()} pos=${player.getCurrentPositionLive()}")

    // Goal of these 2 if statements and the lazyIsPlaying is to ignore this event when it is triggered by a seek
    //  When a seek occurs the player is paused and buffering, then plays again right afterwards.
    if (!isPlaying && player.getPlaybackState() == Player.STATE_BUFFERING) {
      Log.d(tag, "onIsPlayingChanged: ignoring pause while buffering")
      return
    }
    if (lazyIsPlaying == isPlaying) {
      Log.d(tag, "onIsPlayingChanged: state already $isPlaying; ignoring duplicate event")
      return
    }

    lazyIsPlaying = isPlaying

    // Update widget
    playerNotificationService.getCurrentPlaybackSessionCopy()?.let { session ->
      val snapshot = session.toWidgetSnapshot(
        playerNotificationService,
        isPlaying,
        PlayerNotificationService.isClosed
      )
      DeviceManager.widgetUpdater?.onPlayerChanged(snapshot)
    }

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
          Log.d(tag, "AutoRewind: seeking back $seekBackTime ms after pause length=${System.currentTimeMillis() - lastPauseTime}")
          playerNotificationService.seekBackward(seekBackTime)
        }
      }
    } else {
      Log.d(tag, "Paused: setting lastPauseTime now (prev=$lastPauseTime)")
      lastPauseTime = System.currentTimeMillis()
    }

    // Start/stop progress sync interval
    if (isPlaying) {
      val playbackSession: PlaybackSession? = playerNotificationService.mediaProgressSyncer.currentPlaybackSession ?: playerNotificationService.currentPlaybackSession
      playbackSession?.let {
        // Handles auto-starting sleep timer and resetting sleep timer
        playerNotificationService.sleepTimerManager.handleMediaPlayEvent(it.id)

        player.setVolume(1F) // Volume on sleep timer might have decreased this

  Log.d(tag, "MediaProgressSyncer: play session=${it.id} pos=${player.getCurrentPositionLive()}")
        playerNotificationService.mediaProgressSyncer.play(it)
      }
    } else {
      // Always safe: snapshot accessor avoids wrong-thread crashes by returning cached position if off main.
      playerNotificationService.mediaProgressSyncer.pause {
        val snapshotPos = player.getCurrentPosition()
        Log.d(tag, "MediaProgressSyncer: paused at pos=$snapshotPos (snapshot)")
      }
    }

    playerNotificationService.clientEventEmitter?.onPlayingUpdate(isPlaying)
  }

  override fun onPlaybackStateChanged(state: Int) {
    when (state) {
  Player.STATE_READY -> Log.d(tag, "State READY duration=${playerNotificationService.playerWrapper.getDuration()} pos=${playerNotificationService.playerWrapper.getCurrentPositionLive()}")
  Player.STATE_BUFFERING -> Log.d(tag, "State BUFFERING pos=${playerNotificationService.playerWrapper.getCurrentPositionLive()}")
      Player.STATE_ENDED -> Log.d(tag, "State ENDED")
      Player.STATE_IDLE -> Log.d(tag, "State IDLE")
    }
    if (state == Player.STATE_READY) {
      // Metrics: record first READY latency once per session
      playerNotificationService.metricsRecordFirstReadyIfUnset()

      if (lastPauseTime == 0L) {
        lastPauseTime = -1
      }
      playerNotificationService.sendClientMetadata(PlayerState.READY)
    }
    if (state == Player.STATE_BUFFERING) {
      // Metrics: increment buffer count
      playerNotificationService.metricsRecordBuffer()
      playerNotificationService.sendClientMetadata(PlayerState.BUFFERING)
    }
    if (state == Player.STATE_ENDED) {
      playerNotificationService.sendClientMetadata(PlayerState.ENDED)

      // Metrics: log simple summary on end
      playerNotificationService.metricsLogSummary()
      playerNotificationService.handlePlaybackEnded()
    }
    if (state == Player.STATE_IDLE) {
      playerNotificationService.sendClientMetadata(PlayerState.IDLE)
    }
    // Media3 notification is now handled by Media3PlaybackService
  }

  // ExoPlayer v2 Player.Listener implementation retained for CastPlayer compatibility
  override fun onPlayerError(error: PlaybackException) {
    onPlayerError(error.message ?: "Unknown Error", error.errorCode)
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int
  ) {
    onPositionDiscontinuity(reason == Player.DISCONTINUITY_REASON_SEEK)
  }

  override fun onEvents(player: Player, events: Player.Events) {
    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
      onPlaybackStateChanged(player.playbackState)
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
