package com.audiobookshelf.app.managers

import android.content.Context
import android.media.MediaPlayer
import android.os.*
import android.util.Log
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.SLEEP_TIMER_WAKE_UP_EXPIRATION
import com.audiobookshelf.app.plugins.AbsLogger
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

const val SLEEP_TIMER_CHIME_SOUND_VOLUME = 0.7f
const val SLEEP_TIMER_RESUME_PREFS_NAME = "sleep_timer_resume"
const val SLEEP_TIMER_RESUME_EXPIRATION = 24 * 60 * 60 * 1000L // 24 hours
const val SLEEP_TIMER_RESUME_MAX_DRIFT = 5 * 60 * 1000L // 5 minutes

/** State saved when a sleep timer finishes so the user can be offered a rewind later. */
data class SleepTimerResumeInfo(
        val endedAt: Long,
        val position: Long,
        val isAuto: Boolean,
        val libraryItemId: String,
        val episodeId: String
)

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
  private var autoTimerDisabled: Boolean = false // Disable until out of auto timer period
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

  /** SharedPreferences used to keep the resume state across app restarts. */
  private fun resumePrefs() =
          playerNotificationService.getContext()
                  .getSharedPreferences(SLEEP_TIMER_RESUME_PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * Saves the position where the sleep timer stopped playback.
   * @param position Long - the current playback position in milliseconds.
   */
  private fun saveResumeInfo(position: Long) {
    val mediaIdentity = getCurrentMediaIdentity()
    if (mediaIdentity.first.isEmpty()) return

    Log.d(tag, "Saving sleep timer resume state at $position for ${mediaIdentity.first}")
    resumePrefs()
            .edit()
            .putLong("endedAt", System.currentTimeMillis())
            .putLong("position", position)
            .putBoolean("isAuto", isAutoSleepTimer)
            .putString("libraryItemId", mediaIdentity.first)
            .putString("episodeId", mediaIdentity.second)
            .apply()
  }

  /**
   * Identifies the media that is currently open. The playback session id is not used because a
   * new session is created every time playback starts.
   * @return Pair<String, String> - library item id and episode id (empty when unknown).
   */
  private fun getCurrentMediaIdentity(): Pair<String, String> {
    val session = playerNotificationService.currentPlaybackSession ?: return "" to ""
    val libraryItemId = session.libraryItemId ?: session.localLibraryItem?.id ?: ""
    val episodeId = session.episodeId ?: session.localEpisodeId ?: ""
    return libraryItemId to episodeId
  }

  /**
   * Gets the resume state from the last finished sleep timer, if it is still relevant.
   * @return SleepTimerResumeInfo? - null when no resume state exists or it has expired.
   */
  fun getResumeInfo(): SleepTimerResumeInfo? {
    val prefs = resumePrefs()
    val endedAt = prefs.getLong("endedAt", 0L)
    if (endedAt <= 0L) return null

    if (System.currentTimeMillis() - endedAt > SLEEP_TIMER_RESUME_EXPIRATION) {
      Log.d(tag, "Sleep timer resume state expired")
      clearResumeInfo()
      return null
    }

    return SleepTimerResumeInfo(
            endedAt,
            prefs.getLong("position", 0L),
            prefs.getBoolean("isAuto", false),
            prefs.getString("libraryItemId", "") ?: "",
            prefs.getString("episodeId", "") ?: ""
    )
  }

  /** Clears the resume state from the last finished sleep timer. */
  fun clearResumeInfo() {
    resumePrefs().edit().clear().apply()
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
   * @param time Long - the time to set the sleep timer for. When 0L, use end of chapter/track time.
   * @return Boolean - true if the sleep timer was set successfully, false otherwise.
   */
  private fun setSleepTimer(time: Long): Boolean {
    Log.d(tag, "Setting Sleep Timer for $time")
    sleepTimerTask?.cancel()
    sleepTimerRunning = true
    sleepTimerFinishedAt = 0L
    sleepTimerElapsed = 0L
    setVolume(1f)

    if (time == 0L) {
      // Get the current chapter time and set the sleep timer to the end of the chapter
      val chapterEndTime = this.getChapterEndTime()

      if (chapterEndTime == null) {
        Log.e(tag, "Setting sleep timer to end of chapter/track but there is no current session")
        return false
      }

      val currentTime = getCurrentTime()
      if (currentTime > chapterEndTime) {
        Log.d(tag, "Invalid sleep timer - time is already past chapter time $chapterEndTime")
        return false
      }

      sleepTimerEndTime = chapterEndTime

      if (sleepTimerEndTime > getDuration()) {
        sleepTimerEndTime = getDuration()
      }
    } else {
      sleepTimerEndTime = 0L
    }

    // Set sleep timer length. Will be 0L if using chapter end time
    sleepTimerLength = time

    // Register shake sensor
    playerNotificationService.registerSensor()

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

                  if (sleepTimeSecondsRemaining == 30 && sleepTimerElapsed > 1 && DeviceManager.deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime == true) {
                    playChimeSound()
                  }

                  if (sleepTimeSecondsRemaining <= 0) {
                    Log.d(tag, "Sleep Timer Pausing Player on Chapter")
                    pause()

                    val endPosition = getCurrentTime()
                    playerNotificationService.clientEventEmitter?.onSleepTimerEnded(
                            endPosition
                    )
                    saveResumeInfo(endPosition)
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
    // A new timer replaces any pending rewind from an earlier timer
    clearResumeInfo()
    if (isChapterTime) {
      Log.d(tag, "Setting manual sleep timer for end of chapter")
      return setSleepTimer(0L)
    } else {
      Log.d(tag, "Setting manual sleep timer for $time")
      return setSleepTimer(time)
    }
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
      Log.i(tag, "Disabling auto sleep timer for this time period")
      autoTimerDisabled = true
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

  /** Plays chime sound */
  private fun playChimeSound() {
    AbsLogger.info(tag, "playChimeSound: Playing sleep timer chime sound")
    val ctx = playerNotificationService.getContext()
    val mediaPlayer = MediaPlayer.create(ctx, R.raw.bell)
    mediaPlayer.setVolume(SLEEP_TIMER_CHIME_SOUND_VOLUME, SLEEP_TIMER_CHIME_SOUND_VOLUME)
    mediaPlayer.start()
    mediaPlayer.setOnCompletionListener {
      mediaPlayer.release()
    }
  }

  /**
   * Gets the chapter end time for use in End of Chapter timers. If less than 10 seconds remain in
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
    // If less than 10 seconds remain in the chapter, set the timer to the next chapter or track
    // This handles the auto-rewind from not playing media for a little bit to select the next
    // chapter
    return if (timeLeftInChapter < 10000L) {
      Log.i(tag, "Getting chapter sleep timer time and current chapter has less than 10s remaining")
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

  /**
   * Rewind auto sleep timer if setting enabled. To ensure the first rewind of the time period does
   * not take place, make sure to set `isAutoSleepTimer` after calling this function.
   * @return Boolean - true if a rewind was performed.
   */
  private fun tryRewindAutoSleepTimer(): Boolean {
    val deviceSettings = DeviceManager.deviceData.deviceSettings ?: return false
    if (!isAutoSleepTimer || !deviceSettings.autoSleepTimerAutoRewind) return false

    Log.i(
            tag,
            "Auto sleep timer auto rewind seeking back ${deviceSettings.autoSleepTimerAutoRewindTime}ms"
    )
    playerNotificationService.seekBackward(deviceSettings.autoSleepTimerAutoRewindTime)
    return true
  }

  /** Checks if the sleep timer should be reset. */
  private fun checkShouldResetSleepTimer() {
    if (sleepTimerRunning) {
      // Reset the sleep timer if it has been running for at least 3 seconds or it is an end of
      // chapter/track timer
      if (sleepTimerLength == 0L || sleepTimerElapsed > 3000L) {
        Log.d(tag, "Resetting running sleep timer")
        vibrateFeedback()
        setSleepTimer(sleepTimerLength)
        play()
      }
    } else {

      if (sleepTimerFinishedAt <= 0L) return

      val finishedAtDistance = System.currentTimeMillis() - sleepTimerFinishedAt
      if (finishedAtDistance > SLEEP_TIMER_WAKE_UP_EXPIRATION) // 2 minutes
      {
        Log.d(tag, "Sleep timer finished over 2 mins ago, clearing it")
        sleepTimerFinishedAt = 0L
        return
      }

      // If timer was cleared by going negative on time, clear the sleep timer length so pressing
      // play allows playback to continue without the sleep timer continuously setting for 1 second.
      if (sleepTimerLength == 1000L) {
        Log.d(tag, "Sleep timer cleared by manually subtracting time, clearing sleep timer")
        sleepTimerFinishedAt = 0L
        return
      }

      // Automatically rewind in the book if settings are enabled. Clearing the pending resume
      // state here makes sure the resume rewind below does not rewind a second time.
      if (tryRewindAutoSleepTimer()) clearResumeInfo()

      // Set sleep timer
      Log.d(tag, "Resetting stopped sleep timer")
      vibrateFeedback()
      setSleepTimer(sleepTimerLength)
      play()
    }
  }

  /**
   * Handles the shake event to reset the sleep timer. Shaking to reset only works during the 2
   * minute grace period after the timer ends or while media is playing.
   */
  fun handleShake() {
    if ((sleepTimerRunning && getIsPlaying()) || sleepTimerFinishedAt > 0L) {
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

  /**
   * Checks whether the auto sleep timer should be set, and set up auto sleep timer if so.
   * @param skipRewind Boolean - true when playback was already rewound by the resume rewind.
   */
  fun checkAutoSleepTimer(skipRewind: Boolean = false) {
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

      val isDuringAutoTime =
              currentCalendar.after(startCalendar) && currentCalendar.before(endCalendar)

      // Determine whether to set the auto sleep timer or not
      if (autoTimerDisabled) {
        if (!isDuringAutoTime) {
          // Check if sleep timer was disabled during the previous period and enable again
          Log.i(tag, "Leaving disabled auto sleep time period, enabling for next time period")
          autoTimerDisabled = false
        } else {
          // Auto time is disabled, do not set sleep timer
          Log.i(tag, "Auto sleep timer is disabled for this time period")
        }
      } else {
        if (isDuringAutoTime) {
          // Start an auto sleep timer
          val currentHour = currentCalendar.get(Calendar.HOUR_OF_DAY)
          val currentMin = currentCalendar.get(Calendar.MINUTE)
          Log.i(tag, "Starting auto sleep timer at $currentHour:$currentMin")

          // Automatically rewind in the book if settings is enabled, unless the resume rewind
          // already moved playback
          if (!skipRewind && tryRewindAutoSleepTimer()) clearResumeInfo()

          // Set `isAutoSleepTimer` to true to indicate that the timer was set automatically
          // and to not cause the timer to rewind
          isAutoSleepTimer = true
          setSleepTimer(deviceSettings.sleepTimerLength)
        } else {
          Log.d(tag, "Not in auto sleep time period")
        }
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
    } else {}
    sleepTimerSessionId = playbackSessionId

    // Offer the user a way back to where they fell asleep
    val didResumeRewind = tryResumeRewind()

    // Do not rewind twice when the auto sleep timer starts in the same play event
    checkAutoSleepTimer(skipRewind = didResumeRewind)
  }

  /**
   * Rewinds playback when it is resumed after a sleep timer finished and the setting is set to
   * rewind automatically. When the setting is set to ask, the client shows a prompt and rewinds
   * there instead.
   * @return Boolean - true if a rewind was performed.
   */
  private fun tryResumeRewind(): Boolean {
    val deviceSettings = DeviceManager.deviceData.deviceSettings ?: return false
    if (deviceSettings.sleepTimerResumeRewind != true) return false
    if (deviceSettings.sleepTimerResumeAction != "AUTO") return false

    val info = getResumeInfo() ?: return false
    if (info.libraryItemId.isEmpty()) return false

    val currentIdentity = getCurrentMediaIdentity()
    if (currentIdentity.first.isNotEmpty() && currentIdentity.first != info.libraryItemId) {
      Log.d(tag, "Sleep timer resume state belongs to another library item, clearing it")
      clearResumeInfo()
      return false
    }
    if (info.episodeId.isNotEmpty() && currentIdentity.second.isNotEmpty() && info.episodeId != currentIdentity.second) return false

    val currentTime = getCurrentTime()
    // If playback has moved on since the timer finished then the position is no longer relevant
    if (Math.abs(currentTime - info.position) > SLEEP_TIMER_RESUME_MAX_DRIFT) {
      Log.d(tag, "Sleep timer resume state is stale, clearing it")
      clearResumeInfo()
      return false
    }

    val rewindTime = deviceSettings.sleepTimerResumeRewindTime
    if (rewindTime <= 0) return false

    // Rewind from where the timer stopped, not from the pause seek-back that may just have run,
    // and do not rewind into the previous chapter/track
    val chapterStart = playerNotificationService.getCurrentBookChapter()?.startMs ?: 0L
    val target = Math.max(chapterStart, Math.max(0L, info.position - rewindTime))
    if (target >= currentTime) return false

    Log.i(tag, "Sleep timer resume rewind from $currentTime to $target")
    clearResumeInfo()
    // This rewind replaces the shake/play reset rewind for this timer
    sleepTimerFinishedAt = 0L
    playerNotificationService.seekPlayer(target)
    playerNotificationService.clientEventEmitter?.onSleepTimerRewind(currentTime, target)
    return true
  }

  /**
   * Sends the current sleep timer state to the client.
   * Called when app resumes from background to sync UI state.
   */
  fun sendCurrentSleepTimerState() {
    if (sleepTimerRunning) {
      val timeRemaining = getSleepTimerTimeRemainingSeconds(getPlaybackSpeed())
      playerNotificationService.clientEventEmitter?.onSleepTimerSet(timeRemaining, isAutoSleepTimer)
    } else {
      // No timer running - send 0 to clear any stale UI state
      playerNotificationService.clientEventEmitter?.onSleepTimerSet(0, false)
    }
  }
}
