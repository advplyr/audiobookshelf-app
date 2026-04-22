package com.audiobookshelf.app.managers

import android.content.Context
import android.media.MediaPlayer
import android.os.*
import android.util.Log
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import java.util.*
import kotlin.concurrent.schedule

/**
 * StayAwakeManager — dead-man's-switch sleep detection.
 *
 * Unlike the timer-based SleepTimerManager, this actively checks if the user
 * is still awake by playing periodic soft chimes and waiting for confirmation.
 *
 * Flow:
 *   1. User enables "Stay Awake" mode
 *   2. Every [checkIntervalMs] a soft chime plays
 *   3. A notification/overlay shows "Still listening?" for [responseWindowMs]
 *   4. User taps → timer resets, interval may increase (they're alert)
 *   5. Miss 1 check → volume drops to 50%, next check comes sooner
 *   6. Miss 2 checks → playback pauses, position bookmarked, rewind suggestion stored
 *
 * The chime is gentle and mixed at low volume so it doesn't break immersion.
 * Interval adapts: starts long (15min), shortens after first miss.
 */
class StayAwakeManager(
    private val playerNotificationService: PlayerNotificationService
) {
    private val tag = "StayAwakeManager"

    private var isActive = false
    private var checkTask: TimerTask? = null
    private var missedChecks = 0
    private var lastConfirmedAt = 0L
    private var waitingForResponse = false
    private var responseTimer: TimerTask? = null

    // Intervals in milliseconds
    private var checkIntervalMs = 15 * 60 * 1000L  // 15 min initial
    private val responseWindowMs = 60 * 1000L        // 1 min to respond
    private val minIntervalMs = 5 * 60 * 1000L      // 5 min minimum
    private val chimeVolume = 0.3f

    private fun getCurrentTime(): Long = playerNotificationService.getCurrentTime()
    private fun getIsPlaying(): Boolean = playerNotificationService.currentPlayer.isPlaying
    private fun setVolume(volume: Float) { playerNotificationService.currentPlayer.volume = volume }
    private fun pause() { playerNotificationService.currentPlayer.pause() }

    /**
     * Start Stay Awake mode.
     */
    fun start() {
        if (isActive) return
        Log.i(tag, "Starting Stay Awake mode, interval=${checkIntervalMs / 1000}s")
        isActive = true
        missedChecks = 0
        lastConfirmedAt = System.currentTimeMillis()
        waitingForResponse = false
        setVolume(1f)
        scheduleNextCheck()
    }

    /**
     * Stop Stay Awake mode.
     */
    fun stop() {
        Log.i(tag, "Stopping Stay Awake mode")
        isActive = false
        checkTask?.cancel()
        responseTimer?.cancel()
        checkTask = null
        responseTimer = null
        waitingForResponse = false
        missedChecks = 0
        setVolume(1f)
    }

    fun isRunning(): Boolean = isActive

    /**
     * Called when user confirms they're awake (taps the notification/button).
     */
    fun confirmAwake() {
        if (!isActive) return
        Log.d(tag, "User confirmed awake")
        waitingForResponse = false
        responseTimer?.cancel()
        missedChecks = 0
        lastConfirmedAt = System.currentTimeMillis()
        setVolume(1f)

        // User is alert — can extend interval slightly
        checkIntervalMs = minOf(checkIntervalMs + 2 * 60 * 1000L, 20 * 60 * 1000L)
        Log.d(tag, "Next check in ${checkIntervalMs / 1000}s")

        vibrateFeedback()
        scheduleNextCheck()

        playerNotificationService.clientEventEmitter?.onStayAwakeConfirmed()
    }

    private fun scheduleNextCheck() {
        checkTask?.cancel()
        if (!isActive) return

        checkTask = Timer("StayAwakeCheck", false).schedule(checkIntervalMs) {
            Handler(Looper.getMainLooper()).post {
                if (isActive && getIsPlaying()) {
                    performCheck()
                }
            }
        }
    }

    /**
     * Play a soft chime and wait for user response.
     */
    private fun performCheck() {
        Log.d(tag, "Performing awake check (missed so far: $missedChecks)")
        playChime()
        waitingForResponse = true

        // Notify UI to show "Still listening?" prompt
        playerNotificationService.clientEventEmitter?.onStayAwakeCheck(
            missedChecks,
            (responseWindowMs / 1000).toInt()
        )

        // Start response countdown
        responseTimer?.cancel()
        responseTimer = Timer("StayAwakeResponse", false).schedule(responseWindowMs) {
            Handler(Looper.getMainLooper()).post {
                if (waitingForResponse && isActive) {
                    handleMissedCheck()
                }
            }
        }
    }

    /**
     * Handle a missed check-in.
     */
    private fun handleMissedCheck() {
        missedChecks++
        waitingForResponse = false
        Log.w(tag, "Missed check #$missedChecks")

        when (missedChecks) {
            1 -> {
                // First miss: lower volume, shorten interval
                Log.i(tag, "First miss — lowering volume to 50%, shortening interval")
                setVolume(0.5f)
                checkIntervalMs = maxOf(checkIntervalMs / 2, minIntervalMs)

                playerNotificationService.clientEventEmitter?.onStayAwakeMissed(
                    missedChecks, false
                )
                scheduleNextCheck()
            }
            else -> {
                // Second miss: user is asleep — pause and bookmark
                Log.i(tag, "Second miss — user likely asleep, pausing playback")
                val sleepPosition = getCurrentTime()
                pause()
                stop()

                playerNotificationService.clientEventEmitter?.onStayAwakeMissed(
                    missedChecks, true
                )
                playerNotificationService.clientEventEmitter?.onStayAwakeSleepDetected(
                    sleepPosition
                )
            }
        }
    }

    private fun playChime() {
        try {
            val ctx = playerNotificationService.getContext()
            val mediaPlayer = MediaPlayer.create(ctx, R.raw.bell)
            mediaPlayer.setVolume(chimeVolume, chimeVolume)
            mediaPlayer.start()
            mediaPlayer.setOnCompletionListener { mediaPlayer.release() }
        } catch (e: Exception) {
            Log.e(tag, "Failed to play chime: ${e.message}")
        }
    }

    private fun vibrateFeedback() {
        try {
            val context = playerNotificationService.getContext()
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            Log.e(tag, "Vibrate failed: ${e.message}")
        }
    }
}
