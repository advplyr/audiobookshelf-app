package com.audiobookshelf.app.player

/**
 * Centralizes sleep timer UI notifications so the playback service does not own the notifier state.
 */
object SleepTimerNotificationCenter {
  @Volatile
  private var notifier: SleepTimerUiNotifier? = null

  fun register(notifier: SleepTimerUiNotifier?) {
    this.notifier = notifier
  }

  fun unregister() {
    notifier = null
  }

  fun notifySet(secondsRemaining: Int, isAuto: Boolean) {
    notifier?.onSleepTimerSet(secondsRemaining, isAuto)
  }

  fun notifyEnded(currentPosition: Long) {
    notifier?.onSleepTimerEnded(currentPosition)
  }
}
