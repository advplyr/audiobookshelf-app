package com.audiobookshelf.app.player.media3

/** Reasons a progress sync is requested. */
object SyncReason {
  const val SAVE = "save"
  const val PAUSE = "pause"
  const val STOP = "stop"
  const val CLOSE = "close"
  const val FINISHED = "finished"
  const val SWITCH = "switch"

  val CRITICAL = setOf(PAUSE, STOP, CLOSE, FINISHED)

  /** Reasons that still report a playback event when the sync itself returned no result. */
  val REPORT_WITHOUT_RESULT = setOf(PAUSE, STOP, FINISHED)
}
