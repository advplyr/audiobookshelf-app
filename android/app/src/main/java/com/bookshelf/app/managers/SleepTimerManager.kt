package com.bookshelf.app.managers

import android.content.Context
import android.os.*
import android.util.Log
import com.bookshelf.app.device.DeviceManager
import com.bookshelf.app.player.PlayerNotificationService
import com.bookshelf.app.player.SLEEP_TIMER_WAKE_UP_EXPIRATION
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

class SleepTimerManager constructor(private val playerNotificationService: PlayerNotificationService) {
  private val tag = "SleepTimerManager"

  private var sleepTimerTask:TimerTask? = null
  private var sleepTimerRunning:Boolean = false
  private var sleepTimerEndTime:Long = 0L
  private var sleepTimerLength:Long = 0L
  private var sleepTimerElapsed:Long = 0L
  private var sleepTimerFinishedAt:Long = 0L
  private var isAutoSleepTimer:Boolean = false // When timer was auto-set

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
    if (sleepTimerEndTime == 0L && sleepTimerLength > 0) { // For regular timer
      return ((sleepTimerLength - sleepTimerElapsed) / 1000).toDouble().roundToInt()
    }
    // For chapter end timer
    if (sleepTimerEndTime <= 0) return 0
    return (((sleepTimerEndTime - getCurrentTime()) / 1000).toDouble()).roundToInt()
  }

  private fun setSleepTimer(time: Long, isChapterTime: Boolean) : Boolean {
    Log.d(tag, "Setting Sleep Timer for $time is chapter time $isChapterTime")
    sleepTimerTask?.cancel()
    sleepTimerRunning = true
    sleepTimerFinishedAt = 0L
    sleepTimerElapsed = 0L

    // Register shake sensor
    playerNotificationService.registerSensor()

    val currentTime = getCurrentTime()
    if (isChapterTime) {
      if (currentTime > time) {
        Log.d(tag, "Invalid sleep timer - current time is already passed chapter time $time")
        return false
      }
      sleepTimerEndTime = time
      sleepTimerLength = 0

      if (sleepTimerEndTime > getDuration()) {
        sleepTimerEndTime = getDuration()
      }
    } else {
      sleepTimerLength = time
      sleepTimerEndTime = 0L

      if (sleepTimerLength + getCurrentTime() > getDuration()) {
        sleepTimerLength = getDuration() - getCurrentTime()
      }
    }

    playerNotificationService.clientEventEmitter?.onSleepTimerSet(getSleepTimerTimeRemainingSeconds(), isAutoSleepTimer)

    sleepTimerTask = Timer("SleepTimer", false).schedule(0L, 1000L) {
      Handler(Looper.getMainLooper()).post {
        if (getIsPlaying()) {
          sleepTimerElapsed += 1000L

          val sleepTimeSecondsRemaining = getSleepTimerTimeRemainingSeconds()
          Log.d(tag, "Timer Elapsed $sleepTimerElapsed | Sleep TIMER time remaining $sleepTimeSecondsRemaining s")

          if (sleepTimeSecondsRemaining > 0) {
            playerNotificationService.clientEventEmitter?.onSleepTimerSet(sleepTimeSecondsRemaining, isAutoSleepTimer)
          }

          if (sleepTimeSecondsRemaining <= 0) {
            Log.d(tag, "Sleep Timer Pausing Player on Chapter")
            pause()

            playerNotificationService.clientEventEmitter?.onSleepTimerEnded(getCurrentTime())
            clearSleepTimer()
            sleepTimerFinishedAt = System.currentTimeMillis()
          } else if (sleepTimeSecondsRemaining <= 60) {
            if (DeviceManager.deviceData.deviceSettings?.disableSleepTimerFadeOut == true) {
              // Set volume to 1 in case setting was enabled while fading
              setVolume(1f)
            } else {
              // Start fading out audio down to 10% volume
              val percentToReduce = 1 - (sleepTimeSecondsRemaining / 60F)
              val volume =  1f - (percentToReduce * 0.9f)
              Log.d(tag, "SLEEP VOLUME FADE $volume | ${sleepTimeSecondsRemaining}s remaining")
              setVolume(volume)
            }
          }
        }
      }
    }
    return true
  }

  fun setManualSleepTimer(time: Long, isChapterTime:Boolean):Boolean {
    isAutoSleepTimer = false
    return setSleepTimer(time, isChapterTime)
  }

  private fun clearSleepTimer() {
    sleepTimerTask?.cancel()
    sleepTimerTask = null
    sleepTimerEndTime = 0
    sleepTimerRunning = false
    isAutoSleepTimer = false
    playerNotificationService.unregisterSensor()
  }

  fun getSleepTimerTime():Long {
    return sleepTimerEndTime
  }

  fun cancelSleepTimer() {
    Log.d(tag, "Canceling Sleep Timer")

    if (isAutoSleepTimer) {
      Log.i(tag, "Disabling auto sleep timer")
      DeviceManager.deviceData.deviceSettings?.autoSleepTimer = false
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    }

    clearSleepTimer()
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(0, false)
  }

  // Vibrate when resetting sleep timer
  private fun vibrateFeedback() {
    if (DeviceManager.deviceData.deviceSettings?.disableSleepTimerResetFeedback == true) return

    val context = playerNotificationService.getContext()
    val vibrator:Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val vibratorManager =
        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
      vibrator = vibratorManager.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    vibrator.let {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val vibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 150, 150, 150),-1)
        it.vibrate(vibrationEffect)
      } else {
        @Suppress("DEPRECATION")
        it.vibrate(10)
      }
    }
  }

  // Get the chapter end time for use in End of Chapter timers
  //   if less than 2s remain in chapter then use the next chapter
  private fun getChapterEndTime():Long? {
    val currentChapterEndTimeMs = playerNotificationService.getEndTimeOfChapterOrTrack()
    if (currentChapterEndTimeMs == null) {
      Log.e(tag, "Getting chapter sleep timer end of chapter/track but there is no current session")
      return null
    }

    val timeLeftInChapter = currentChapterEndTimeMs - getCurrentTime()
    return if (timeLeftInChapter < 2000L) {
      Log.i(tag, "Getting chapter sleep timer time and current chapter has less than 2s remaining")
      val nextChapterEndTimeMs = playerNotificationService.getEndTimeOfNextChapterOrTrack()
      if (nextChapterEndTimeMs == null || currentChapterEndTimeMs == nextChapterEndTimeMs) {
        Log.e(tag, "Invalid next chapter time. No current session or equal to current chapter. $nextChapterEndTimeMs")
        null
      } else {
        nextChapterEndTimeMs
      }
    } else {
      currentChapterEndTimeMs
    }
  }

  private fun resetChapterTimer() {
    this.getChapterEndTime()?.let { chapterEndTime ->
      Log.d(tag, "Resetting stopped sleep timer to end of chapter $chapterEndTime")
      vibrateFeedback()
      setSleepTimer(chapterEndTime, true)
      play()
    }
  }

  private fun checkShouldResetSleepTimer() {
    if (!sleepTimerRunning) {
      if (sleepTimerFinishedAt <= 0L) return

      val finishedAtDistance = System.currentTimeMillis() - sleepTimerFinishedAt
      if (finishedAtDistance > SLEEP_TIMER_WAKE_UP_EXPIRATION) // 2 minutes
      {
        Log.d(tag, "Sleep timer finished over 2 mins ago, clearing it")
        sleepTimerFinishedAt = 0L
        return
      }

      // Set sleep timer
      //   When sleepTimerLength is 0 then use end of chapter/track time
      if (sleepTimerLength == 0L) {
        Log.d(tag, "Resetting stopped chapter sleep timer")
        resetChapterTimer()
      } else {
        Log.d(tag, "Resetting stopped sleep timer to length $sleepTimerLength")
        vibrateFeedback()
        setSleepTimer(sleepTimerLength, false)
        play()
      }
      return
    }

    // Does not apply to chapter sleep timers and timer must be running for at least 3 seconds
    if (sleepTimerLength > 0L && sleepTimerElapsed > 3000L) {
      Log.d(tag, "Resetting running sleep timer to length $sleepTimerLength")
      vibrateFeedback()
      setSleepTimer(sleepTimerLength, false)
    } else if (sleepTimerLength == 0L) {
      // When navigating to previous chapters make sure this is still the end of the current chapter
      this.getChapterEndTime()?.let { chapterEndTime ->
        if (chapterEndTime != sleepTimerEndTime) {
          Log.d(tag, "Resetting chapter sleep timer to end of chapter $chapterEndTime from $sleepTimerEndTime")
          vibrateFeedback()
          setSleepTimer(chapterEndTime, true)
          play()
        }
      }
    }
  }

  fun handleShake() {
    if (sleepTimerRunning || sleepTimerFinishedAt > 0L) {
      if (DeviceManager.deviceData.deviceSettings?.disableShakeToResetSleepTimer == true) {
        Log.d(tag, "Shake to reset sleep timer is disabled")
        return
      }
      checkShouldResetSleepTimer()
    }
  }

  fun increaseSleepTime(time: Long) {
    Log.d(tag, "Increase Sleep time $time")
    if (!sleepTimerRunning) return

    if (sleepTimerEndTime == 0L) {
      sleepTimerLength += time
      if (sleepTimerLength + getCurrentTime() > getDuration()) sleepTimerLength = getDuration() - getCurrentTime()
    } else {
      val newSleepEndTime = sleepTimerEndTime + time
      sleepTimerEndTime = if (newSleepEndTime >= getDuration()) {
        getDuration()
      } else {
        newSleepEndTime
      }
    }

    setVolume(1F)
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(getSleepTimerTimeRemainingSeconds(), isAutoSleepTimer)
  }

  fun decreaseSleepTime(time: Long) {
    Log.d(tag, "Decrease Sleep time $time")
    if (!sleepTimerRunning) return


    if (sleepTimerEndTime == 0L) {
      sleepTimerLength -= time
      if (sleepTimerLength <= 0) sleepTimerLength = 1000L
    } else {
      val newSleepEndTime = sleepTimerEndTime - time
      sleepTimerEndTime = if (newSleepEndTime <= 1000) {
        // End sleep timer in 1 second
        getCurrentTime() + 1000
      } else {
        newSleepEndTime
      }
    }

    setVolume(1F)
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(getSleepTimerTimeRemainingSeconds(), isAutoSleepTimer)
  }

  fun checkAutoSleepTimer() {
    if (sleepTimerRunning) { // Sleep timer already running
      return
    }
    DeviceManager.deviceData.deviceSettings?.let { deviceSettings ->
      if (!deviceSettings.autoSleepTimer) return // Check auto sleep timer is enabled

      val startCalendar = Calendar.getInstance()
      startCalendar.set(Calendar.HOUR_OF_DAY, deviceSettings.autoSleepTimerStartHour)
      startCalendar.set(Calendar.MINUTE, deviceSettings.autoSleepTimerStartMinute)
      val endCalendar = Calendar.getInstance()
      endCalendar.set(Calendar.HOUR_OF_DAY, deviceSettings.autoSleepTimerEndHour)
      endCalendar.set(Calendar.MINUTE, deviceSettings.autoSleepTimerEndMinute)

      // In cases where end time is earlier then start time then we add a day to end time
      //   e.g. start time 22:00 and end time 06:00. End time will be treated as 6am the next day.
      //   e.g. start time 08:00 and end time 22:00. Start and end time will be the same day.
      if (endCalendar.before(startCalendar)) {
        endCalendar.add(Calendar.DAY_OF_MONTH, 1)
      }

      val currentCalendar = Calendar.getInstance()
      val currentHour = SimpleDateFormat("HH:mm", Locale.getDefault()).format(currentCalendar.time)
      if (currentCalendar.after(startCalendar) && currentCalendar.before(endCalendar)) {
        Log.i(tag, "Current hour $currentHour is between ${deviceSettings.autoSleepTimerStartTime} and ${deviceSettings.autoSleepTimerEndTime} - starting sleep timer")

        // Set sleep timer
        //   When sleepTimerLength is 0 then use end of chapter/track time
        if (deviceSettings.sleepTimerLength == 0L) {
          val chapterEndTimeMs = this.getChapterEndTime()
          if (chapterEndTimeMs == null) {
            Log.e(tag, "Setting auto sleep timer to end of chapter/track but there is no current session")
          } else {
            isAutoSleepTimer = true
            setSleepTimer(chapterEndTimeMs, true)
          }
        } else {
          isAutoSleepTimer = true
          setSleepTimer(deviceSettings.sleepTimerLength, false)
        }
      } else {
        Log.d(tag, "Current hour $currentHour is NOT between ${deviceSettings.autoSleepTimerStartTime} and ${deviceSettings.autoSleepTimerEndTime}")
      }
    }
  }

  fun handleMediaPlayEvent() {
    checkShouldResetSleepTimer()
    checkAutoSleepTimer()
  }
}
