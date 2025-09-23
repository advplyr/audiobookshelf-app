package com.tomesonic.app.data

enum class PlayerState {
  IDLE, BUFFERING, READY, ENDED
}

data class PlaybackMetadata(
  val duration:Double,
  val currentTime:Double,
  val playerState:PlayerState
)
