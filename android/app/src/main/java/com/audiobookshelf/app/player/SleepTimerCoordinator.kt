package com.audiobookshelf.app.player

import android.content.Context
import com.audiobookshelf.app.managers.SleepTimerHost
import com.audiobookshelf.app.managers.SleepTimerManager
import kotlinx.coroutines.CoroutineScope

const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 2 * 60 * 1000L // 2 minutes

/**
 * Bridges sleep timer to player implementations. Provides playback state,
 * control methods, and UI notification callbacks.
 */
interface SleepTimerHostAdapter {
  val context: Context
  fun currentTimeMs(): Long // Playback position (ms)
  fun durationMs(): Long // Total media duration (ms)
  fun isPlaying(): Boolean
  fun playbackSpeed(): Float
  fun setVolume(volume: Float) // 0.0-1.0 for fade-out
  fun pause()
  fun play()
  fun seekBackward(amountMs: Long)
  fun endTimeOfChapterOrTrack(): Long? // Absolute position (ms) where current chapter/track ends
  fun endTimeOfNextChapterOrTrack(): Long?
  fun notifySleepTimerSet(secondsRemaining: Int, isAuto: Boolean)
  fun notifySleepTimerEnded(currentPosition: Long)
  fun getCurrentSessionId(): String?
}

class SleepTimerCoordinator(
  private val scope: CoroutineScope,
  private val wakeUpExpirationMs: Long = SLEEP_TIMER_WAKE_UP_EXPIRATION
) : SleepTimerHost {

  private var hostAdapter: SleepTimerHostAdapter? = null
  private var shakeController: SleepTimerShakeController? = null
  private var sleepTimerManager: SleepTimerManager? = null

  // Tracks whether the sleep timer ended on a session and prevents auto-rearm when the user resumes
  // playback on the same session without explicitly setting a new timer. This avoids unwanted behavior
  // where the timer would restart automatically after the user dismisses it.
  private var sleepTimerEndObserved = false
  private var lastSleepTimerEndedSessionId: String? = null

  fun isStarted(): Boolean = hostAdapter != null

  private val adapter: SleepTimerHostAdapter
    get() = hostAdapter ?: error("SleepTimerHostAdapter must be set before using SleepTimerCoordinator")

  @Synchronized
  fun start(adapter: SleepTimerHostAdapter) {
    if (hostAdapter != null) return
    hostAdapter = adapter
    ensureShakeController()
    ensureSleepTimerManager()
  }

  @Synchronized
  fun release() {
    shakeController?.release()
    shakeController = null
    sleepTimerManager = null
    sleepTimerEndObserved = false
    lastSleepTimerEndedSessionId = null
    hostAdapter = null
  }

  fun handlePlayStarted(sessionId: String) {
    if (sleepTimerEndObserved && lastSleepTimerEndedSessionId == sessionId) {
      sleepTimerEndObserved = false
      lastSleepTimerEndedSessionId = null
      return
    }
    ensureSleepTimerManager().handleMediaPlayEvent(sessionId)
    checkAutoTimerIfNeeded()
  }

  fun checkAutoTimerIfNeeded() {
    sleepTimerManager?.checkAutoSleepTimer()
  }

  fun setManualTimer(sessionId: String, timeMs: Long, isChapterTime: Boolean): Boolean {
    return ensureSleepTimerManager().setManualSleepTimer(sessionId, timeMs, isChapterTime)
  }

  fun increaseTimer(timeMs: Long) {
    sleepTimerManager?.increaseSleepTime(timeMs)
  }

  fun decreaseTimer(timeMs: Long) {
    sleepTimerManager?.decreaseSleepTime(timeMs)
  }

  fun cancelTimer() {
    sleepTimerManager?.cancelSleepTimer()
  }

  fun getTimerTimeMs(): Long {
    return sleepTimerManager?.getSleepTimerTime() ?: 0L
  }

  fun handleShake() {
    sleepTimerManager?.handleShake()
  }

  fun sendCurrentSleepTimerState() {
    sleepTimerManager?.sendCurrentSleepTimerState()
  }

  override val context: Context
    get() = adapter.context

  override fun currentTimeMs(): Long = adapter.currentTimeMs()

  override fun durationMs(): Long = adapter.durationMs()

  override fun isPlaying(): Boolean = adapter.isPlaying()

  override fun playbackSpeed(): Float = adapter.playbackSpeed()

  override fun setVolume(volume: Float) = adapter.setVolume(volume)

  override fun pause() = adapter.pause()

  override fun play() = adapter.play()

  override fun seekBackward(amountMs: Long) = adapter.seekBackward(amountMs)

  override fun endTimeOfChapterOrTrack(): Long? = hostAdapter?.endTimeOfChapterOrTrack()

  override fun endTimeOfNextChapterOrTrack(): Long? = hostAdapter?.endTimeOfNextChapterOrTrack()

  override fun notifySleepTimerSet(secondsRemaining: Int, isAuto: Boolean) {
    sleepTimerEndObserved = false
    lastSleepTimerEndedSessionId = null
    hostAdapter?.notifySleepTimerSet(secondsRemaining, isAuto)
  }

  override fun notifySleepTimerEnded(currentPosition: Long) {
    hostAdapter?.notifySleepTimerEnded(currentPosition)
    hostAdapter?.getCurrentSessionId()?.let { sessionId ->
      if (sessionId.isNotEmpty()) {
        lastSleepTimerEndedSessionId = sessionId
        sleepTimerEndObserved = true
      }
    }
  }

  override fun registerSensor() {
    ensureShakeController()
    shakeController?.register()
  }

  override fun unregisterSensor() {
    ensureShakeController()
    shakeController?.scheduleUnregister()
  }

  private fun ensureSleepTimerManager(): SleepTimerManager {
    val manager = sleepTimerManager
    if (manager != null) {
      return manager
    }
    val newManager = SleepTimerManager(this)
    sleepTimerManager = newManager
    return newManager
  }

  private fun ensureShakeController() {
    val adapter = hostAdapter ?: return
    if (shakeController == null) {
      shakeController = SleepTimerShakeController(
        adapter.context,
        wakeUpExpirationMs,
        scope
      ) {
        handleShake()
      }
    }
  }
}
