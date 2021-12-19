package com.audiobookshelf.app

import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

const val SLEEP_EXTENSION_TIME = 900000L // 15m

class SleepTimerManager constructor(playerNotificationService:PlayerNotificationService) {
  private val tag = "SleepTimerManager"
  private val playerNotificationService:PlayerNotificationService = playerNotificationService

  private var sleepTimerTask:TimerTask? = null
  private var sleepTimerRunning:Boolean = false
  private var sleepTimerEndTime:Long = 0L
  private var sleepTimerExtensionTime:Long = 0L
  private var sleepTimerFinishedAt:Long = 0L

  private fun getCurrentTime():Long {
    return playerNotificationService.getCurrentTime()
  }

  private fun getDuration():Long {
    return playerNotificationService.getDuration()
  }

  private fun getIsPlaying():Boolean {
    return playerNotificationService.currentPlayer.isPlaying
  }

  private fun setVolume(volume:Float) {
    playerNotificationService.currentPlayer.volume = volume
  }

  private fun pause() {
    playerNotificationService.currentPlayer.pause()
  }

  private fun play() {
    playerNotificationService.currentPlayer.play()
  }

  private fun getSleepTimerTimeRemainingSeconds():Int {
    if (sleepTimerEndTime <= 0) return 0
    var sleepTimeRemaining = sleepTimerEndTime - getCurrentTime()
    return ((sleepTimeRemaining / 1000).toDouble()).roundToInt()
  }

  fun getIsSleepTimerRunning():Boolean {
    return sleepTimerRunning
  }

  fun setSleepTimer(time: Long, isChapterTime: Boolean) : Boolean {
    Log.d(tag, "Setting Sleep Timer for $time is chapter time $isChapterTime")
    sleepTimerTask?.cancel()
    sleepTimerRunning = false
    sleepTimerFinishedAt = 0L

    // Register shake sensor
    playerNotificationService.registerSensor()

    var currentTime = getCurrentTime()
    if (isChapterTime) {
      if (currentTime > time) {
        Log.d(tag, "Invalid sleep timer - current time is already passed chapter time $time")
        return false
      }
      sleepTimerEndTime = time
      sleepTimerExtensionTime = SLEEP_EXTENSION_TIME
    } else {
      sleepTimerEndTime = currentTime + time
      sleepTimerExtensionTime = time
    }

    if (sleepTimerEndTime > getDuration()) {
      sleepTimerEndTime = getDuration()
    }

    playerNotificationService.listener?.onSleepTimerSet(sleepTimerEndTime)

    sleepTimerRunning = true
    sleepTimerTask = Timer("SleepTimer", false).schedule(0L, 1000L) {
      Handler(Looper.getMainLooper()).post() {
        if (getIsPlaying()) {
          var sleepTimeSecondsRemaining = getSleepTimerTimeRemainingSeconds()
          Log.d(tag, "Sleep TIMER time remaining $sleepTimeSecondsRemaining s")

          if (sleepTimeSecondsRemaining <= 0) {
            Log.d(tag, "Sleep Timer Pausing Player on Chapter")
            pause()

            playerNotificationService.listener?.onSleepTimerEnded(getCurrentTime())
            clearSleepTimer()
            sleepTimerFinishedAt = System.currentTimeMillis()
          } else if (sleepTimeSecondsRemaining <= 30) {
            // Start fading out audio
            var volume = sleepTimeSecondsRemaining / 30F
            Log.d(tag, "SLEEP VOLUME FADE $volume | ${sleepTimeSecondsRemaining}s remaining")
            setVolume(volume)
          }
        }
      }
    }
    return true
  }

  fun clearSleepTimer() {
    sleepTimerTask?.cancel()
    sleepTimerTask = null
    sleepTimerEndTime = 0
    sleepTimerRunning = false
    playerNotificationService.unregisterSensor()
  }

  fun getSleepTimerTime():Long? {
    return sleepTimerEndTime
  }

  fun cancelSleepTimer() {
    Log.d(tag, "Canceling Sleep Timer")
    clearSleepTimer()
    playerNotificationService.listener?.onSleepTimerSet(0)
  }

  private fun extendSleepTime() {
    if (!sleepTimerRunning) return
    setVolume(1F)
    sleepTimerEndTime += sleepTimerExtensionTime
    if (sleepTimerEndTime > getDuration()) sleepTimerEndTime = getDuration()
    playerNotificationService.listener?.onSleepTimerSet(sleepTimerEndTime)
  }

  fun checkShouldExtendSleepTimer() {
    if (!sleepTimerRunning) {
      if (sleepTimerFinishedAt <= 0L) return

      var finishedAtDistance = System.currentTimeMillis() - sleepTimerFinishedAt
      if (finishedAtDistance > SLEEP_TIMER_WAKE_UP_EXPIRATION) // 2 minutes
      {
        Log.d(tag, "Sleep timer finished over 2 mins ago, clearing it")
        sleepTimerFinishedAt = 0L
        return
      }

      var newSleepTime = if (sleepTimerExtensionTime >= 0) sleepTimerExtensionTime else SLEEP_EXTENSION_TIME
      setSleepTimer(newSleepTime, false)
      play()
      return
    }
    // Only extend if within 30 seconds of finishing
    var sleepTimeRemaining = getSleepTimerTimeRemainingSeconds()
    if (sleepTimeRemaining <= 30) extendSleepTime()
  }

  fun handleShake() {
    Log.d(tag, "HANDLE SHAKE HERE")
    if (sleepTimerRunning || sleepTimerFinishedAt > 0L) checkShouldExtendSleepTimer()
  }

  fun increaseSleepTime(time: Long) {
    Log.d(tag, "Increase Sleep time $time")
    if (!sleepTimerRunning) return
    var newSleepEndTime = sleepTimerEndTime + time
    sleepTimerEndTime = if (newSleepEndTime >= getDuration()) {
      getDuration()
    } else {
      newSleepEndTime
    }
    setVolume(1F)
    playerNotificationService.listener?.onSleepTimerSet(sleepTimerEndTime)
  }

  fun decreaseSleepTime(time: Long) {
    Log.d(tag, "Decrease Sleep time $time")
    if (!sleepTimerRunning) return
    var newSleepEndTime = sleepTimerEndTime - time
    sleepTimerEndTime = if (newSleepEndTime <= 1000) {
      // End sleep timer in 1 second
      getCurrentTime() + 1000
    } else {
      newSleepEndTime
    }
    setVolume(1F)
    playerNotificationService.listener?.onSleepTimerSet(sleepTimerEndTime)
  }
}
