package com.audiobookshelf.app.player

/**
 * Bridges sleep timer state changes back to the JS layer (or any listeners) without coupling the
 * underlying manager to a specific service implementation.
 */
interface SleepTimerUiNotifier {
  fun onSleepTimerSet(secondsRemaining: Int, isAuto: Boolean)
  fun onSleepTimerEnded(currentPosition: Long)
}
