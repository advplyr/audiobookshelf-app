package com.audiobookshelf.app.managers

import android.content.Context
import android.os.*
import android.util.Log
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.SLEEP_TIMER_WAKE_UP_EXPIRATION
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

class SleepTimerManager
constructor(private val playerNotificationService: PlayerNotificationService) {
  private val tag = "SleepTimerManager"

  private var sleepTimerTask: TimerTask? = null
  private var sleepTimerRunning: Boolean = false
  private var sleepTimerEndTime: Long = 0L
  private var sleepTimerLength: Long = 0L
  private var sleepTimerElapsed: Long = 0L
  private var sleepTimerFinishedAt: Long = 0L
  private var isAutoSleepTimer: Boolean = false // When timer was auto-set
  private var isFirstAutoSleepTimer: Boolean = true
  private var sleepTimerSessionId: String = ""

  /**
   * Gets the current time from the player notification service.
   * @return Long - the current time in milliseconds.
   */
  private fun getCurrentTime(): Long {
    return playerNotificationService.getCurrentTime()
  }

  /**
   * Gets the duration of the current playback.
   * @return Long - the duration in milliseconds.
   */
  private fun getDuration(): Long {
    return playerNotificationService.getDuration()
  }

  /**
   * Checks if the player is currently playing.
   * @return Boolean - true if the player is playing, false otherwise.
   */
  private fun getIsPlaying(): Boolean {
    return playerNotificationService.currentPlayer.isPlaying
  }

  /**
   * Gets the playback speed of the player.
   * @return Float - the playback speed.
   */
  private fun getPlaybackSpeed(): Float {
    return playerNotificationService.currentPlayer.playbackParameters.speed
  }

  /**
   * Sets the volume of the player.
   * @param volume Float - the volume level to set.
   */
  private fun setVolume(volume: Float) {
    playerNotificationService.currentPlayer.volume = volume
  }

  /** Pauses the player. */
  private fun pause() {
    playerNotificationService.currentPlayer.pause()
  }

  /** Plays the player. */
  private fun play() {
    playerNotificationService.currentPlayer.play()
  }

  /**
   * Gets the remaining time of the sleep timer in seconds.
   * @param speed Float - the playback speed of the player, default value is 1.
   * @return Int - the remaining time in seconds.
   */
  private fun getSleepTimerTimeRemainingSeconds(speed: Float = 1f): Int {
    if (sleepTimerEndTime == 0L && sleepTimerLength > 0) { // For regular timer
      return ((sleepTimerLength - sleepTimerElapsed) / 1000).toDouble().roundToInt()
    }
    // For chapter end timer
    if (sleepTimerEndTime <= 0) return 0
    return (((sleepTimerEndTime - getCurrentTime()) / 1000).toDouble() / speed).roundToInt()
  }

  /**
   * Sets the sleep timer.
   * @param time Long - the time to set the sleep timer for.
   * @param isChapterTime Boolean - true if the time is for the end of a chapter, false otherwise.
   * @return Boolean - true if the sleep timer was set successfully, false otherwise.
   */
  private fun setSleepTimer(time: Long, isChapterTime: Boolean): Boolean {
    Log.d(tag, "Setting Sleep Timer for $time is chapter time $isChapterTime")
    sleepTimerTask?.cancel()
    sleepTimerRunning = true
    sleepTimerFinishedAt = 0L
    sleepTimerElapsed = 0L
    setVolume(1f)

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
    }

    playerNotificationService.clientEventEmitter?.onSleepTimerSet(
            getSleepTimerTimeRemainingSeconds(getPlaybackSpeed()),
            isAutoSleepTimer
    )

    sleepTimerTask =
            Timer("SleepTimer", false).schedule(0L, 1000L) {
              Handler(Looper.getMainLooper()).post {
                if (getIsPlaying()) {
                  sleepTimerElapsed += 1000L

                  val sleepTimeSecondsRemaining =
                          getSleepTimerTimeRemainingSeconds(getPlaybackSpeed())
                  Log.d(
                          tag,
                          "Timer Elapsed $sleepTimerElapsed | Sleep TIMER time remaining $sleepTimeSecondsRemaining s"
                  )

                  if (sleepTimeSecondsRemaining > 0) {
                    playerNotificationService.clientEventEmitter?.onSleepTimerSet(
                            sleepTimeSecondsRemaining,
                            isAutoSleepTimer
                    )
                  }

                  if (sleepTimeSecondsRemaining <= 0) {
                    Log.d(tag, "Sleep Timer Pausing Player on Chapter")
                    pause()

                    playerNotificationService.clientEventEmitter?.onSleepTimerEnded(
                            getCurrentTime()
                    )
                    clearSleepTimer()
                    sleepTimerFinishedAt = System.currentTimeMillis()
                  } else if (sleepTimeSecondsRemaining <= 60 &&
                                  DeviceManager.deviceData
                                          .deviceSettings
                                          ?.disableSleepTimerFadeOut != true
                  ) {
                    // Start fading out audio down to 10% volume
                    val percentToReduce = 1 - (sleepTimeSecondsRemaining / 60F)
                    val volume = 1f - (percentToReduce * 0.9f)
                    Log.d(
                            tag,
                            "SLEEP VOLUME FADE $volume | ${sleepTimeSecondsRemaining}s remaining"
                    )
                    setVolume(volume)
                  } else {
                    setVolume(1f)
                  }
                }
              }
            }
    return true
  }

  /**
   * Sets a manual sleep timer.
   * @param playbackSessionId String - the playback session ID.
   * @param time Long - the time to set the sleep timer for.
   * @param isChapterTime Boolean - true if the time is for the end of a chapter, false otherwise.
   * @return Boolean - true if the sleep timer was set successfully, false otherwise.
   */
  fun setManualSleepTimer(playbackSessionId: String, time: Long, isChapterTime: Boolean): Boolean {
    sleepTimerSessionId = playbackSessionId
    isAutoSleepTimer = false
    return setSleepTimer(time, isChapterTime)
  }

  /** Clears the sleep timer. */
  private fun clearSleepTimer() {
    sleepTimerTask?.cancel()
    sleepTimerTask = null
    sleepTimerEndTime = 0
    sleepTimerRunning = false
    playerNotificationService.unregisterSensor()

    setVolume(1f)
  }

  /**
   * Gets the sleep timer end time.
   * @return Long - the sleep timer end time in milliseconds.
   */
  fun getSleepTimerTime(): Long {
    return sleepTimerEndTime
  }

  /** Cancels the sleep timer. */
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

  /** Provides vibration feedback when resetting the sleep timer. */
  private fun vibrateFeedback() {
    if (DeviceManager.deviceData.deviceSettings?.disableSleepTimerResetFeedback == true) return

    val context = playerNotificationService.getContext()
    val vibrator: Vibrator
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
        val vibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 150, 150, 150), -1)
        it.vibrate(vibrationEffect)
      } else {
        @Suppress("DEPRECATION") it.vibrate(10)
      }
    }
  }

  /**
   * Gets the chapter end time for use in End of Chapter timers. If less than 2 seconds remain in
   * the chapter, then use the next chapter.
   * @return Long? - the chapter end time in milliseconds, or null if there is no current session.
   */
  private fun getChapterEndTime(): Long? {
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
        Log.e(
                tag,
                "Invalid next chapter time. No current session or equal to current chapter. $nextChapterEndTimeMs"
        )
        null
      } else {
        nextChapterEndTimeMs
      }
    } else {
      currentChapterEndTimeMs
    }
  }

  /** Resets the chapter timer. */
  private fun resetChapterTimer() {
    this.getChapterEndTime()?.let { chapterEndTime ->
      Log.d(tag, "Resetting stopped sleep timer to end of chapter $chapterEndTime")
      vibrateFeedback()
      setSleepTimer(chapterEndTime, true)
      play()
    }
  }

  /** Checks if the sleep timer should be reset. */
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

      // Automatically Rewind in the book if settings is enabled
      if (isAutoSleepTimer) {
        DeviceManager.deviceData.deviceSettings?.let { deviceSettings ->
          if (deviceSettings.autoSleepTimerAutoRewind && !isFirstAutoSleepTimer) {
            Log.i(
                    tag,
                    "Auto sleep timer auto rewind seeking back ${deviceSettings.autoSleepTimerAutoRewindTime}ms"
            )
            playerNotificationService.seekBackward(deviceSettings.autoSleepTimerAutoRewindTime)
          }
          isFirstAutoSleepTimer = false
        }
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
          Log.d(
                  tag,
                  "Resetting chapter sleep timer to end of chapter $chapterEndTime from $sleepTimerEndTime"
          )
          vibrateFeedback()
          setSleepTimer(chapterEndTime, true)
          play()
        }
      }
    }
  }

  /** Handles the shake event to reset the sleep timer. */
  fun handleShake() {
    if (sleepTimerRunning || sleepTimerFinishedAt > 0L) {
      if (DeviceManager.deviceData.deviceSettings?.disableShakeToResetSleepTimer == true) {
        Log.d(tag, "Shake to reset sleep timer is disabled")
        return
      }
      checkShouldResetSleepTimer()
    }
  }

  /**
   * Increases the sleep timer time.
   * @param time Long - the time to increase the sleep timer by.
   */
  fun increaseSleepTime(time: Long) {
    Log.d(tag, "Increase Sleep time $time")
    if (!sleepTimerRunning) return

    // Increase the sleep timer time (if using fixed length) or end time (if using chapter end time)
    // and ensure it doesn't go over the duration of the current playback item
    if (sleepTimerEndTime == 0L) {
      // Fixed length
      sleepTimerLength += time
      sleepTimerLength = minOf(sleepTimerLength, getDuration() - getCurrentTime())
    } else {
      // Chapter end time
      sleepTimerEndTime =
              minOf(sleepTimerEndTime + (time * getPlaybackSpeed()).roundToInt(), getDuration())
    }

    setVolume(1F)
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(
            getSleepTimerTimeRemainingSeconds(getPlaybackSpeed()),
            isAutoSleepTimer
    )
  }

  /**
   * Decreases the sleep timer time.
   * @param time Long - the time to decrease the sleep timer by.
   */
  fun decreaseSleepTime(time: Long) {
    Log.d(tag, "Decrease Sleep time $time")
    if (!sleepTimerRunning) return

    // Decrease the sleep timer time (if using fixed length) or end time (if using chapter end time)
    // and ensure it doesn't go below 1 second
    if (sleepTimerEndTime == 0L) {
      // Fixed length
      sleepTimerLength = maxOf(sleepTimerLength - time, 1000L)
    } else {
      // Chapter end time
      sleepTimerEndTime =
              maxOf(
                      sleepTimerEndTime - (time * getPlaybackSpeed()).roundToInt(),
                      getCurrentTime() + 1000
              )
    }

    setVolume(1F)
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(
            getSleepTimerTimeRemainingSeconds(getPlaybackSpeed()),
            isAutoSleepTimer
    )
  }

  /** Checks if the auto sleep timer should be set. */
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

      val currentCalendar = Calendar.getInstance()

      // In cases where end time is before start time then we shift the time window forward or
      // backward based on the current time.
      //   e.g. start time 22:00 and end time 06:00.
      //          If current time is less than start time (e.g. 00:30) then start time will be the
      // previous day.
      //          If current time is greater than start time (e.g. 23:00) then end time will be the
      // next day.
      if (endCalendar.before(startCalendar)) {
        if (currentCalendar.before(startCalendar)) { // Shift start back a day
          startCalendar.add(Calendar.DAY_OF_MONTH, -1)
        } else { // Shift end forward a day
          endCalendar.add(Calendar.DAY_OF_MONTH, 1)
        }
      }

      val currentHour = SimpleDateFormat("HH:mm", Locale.getDefault()).format(currentCalendar.time)
      if (currentCalendar.after(startCalendar) && currentCalendar.before(endCalendar)) {
        Log.i(
                tag,
                "Current hour $currentHour is between ${deviceSettings.autoSleepTimerStartTime} and ${deviceSettings.autoSleepTimerEndTime} - starting sleep timer"
        )

        // Automatically Rewind in the book if settings is enabled
        if (deviceSettings.autoSleepTimerAutoRewind && !isFirstAutoSleepTimer) {
          Log.i(
                  tag,
                  "Auto sleep timer auto rewind seeking back ${deviceSettings.autoSleepTimerAutoRewindTime}ms"
          )
          playerNotificationService.seekBackward(deviceSettings.autoSleepTimerAutoRewindTime)
        }
        isFirstAutoSleepTimer = false

        // Set sleep timer
        //   When sleepTimerLength is 0 then use end of chapter/track time
        if (deviceSettings.sleepTimerLength == 0L) {
          val chapterEndTimeMs = this.getChapterEndTime()
          if (chapterEndTimeMs == null) {
            Log.e(
                    tag,
                    "Setting auto sleep timer to end of chapter/track but there is no current session"
            )
          } else {
            isAutoSleepTimer = true
            setSleepTimer(chapterEndTimeMs, true)
          }
        } else {
          isAutoSleepTimer = true
          setSleepTimer(deviceSettings.sleepTimerLength, false)
        }
      } else {
        isFirstAutoSleepTimer = true
        Log.d(
                tag,
                "Current hour $currentHour is NOT between ${deviceSettings.autoSleepTimerStartTime} and ${deviceSettings.autoSleepTimerEndTime}"
        )
      }
    }
  }

  /**
   * Handles the media play event and checks if the sleep timer should be reset or set.
   * @param playbackSessionId String - the playback session ID.
   */
  fun handleMediaPlayEvent(playbackSessionId: String) {
    // Check if the playback session has changed
    // If it hasn't changed OR the sleep timer is running then check reset the timer
    //   e.g. You set a manual sleep timer for 10 mins, then decide to change books, the sleep timer
    // will stay on and reset to 10 mins
    if (sleepTimerSessionId == playbackSessionId || sleepTimerRunning) {
      checkShouldResetSleepTimer()
    } else {
      isFirstAutoSleepTimer = true
    }
    sleepTimerSessionId = playbackSessionId

    checkAutoSleepTimer()
  }
}
