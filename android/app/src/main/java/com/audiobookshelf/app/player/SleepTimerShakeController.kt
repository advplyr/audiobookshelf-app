package com.audiobookshelf.app.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

/**
 * Lightweight helper that encapsulates shake sensor registration used by the sleep timer.
 * Handles delayed unregistration to match legacy PlayerNotificationService behaviour.
 */
class SleepTimerShakeController(
  private val context: Context,
  private val expirationMs: Long,
  private val onShake: () -> Unit
) {
  private val sensorManager: SensorManager? =
    context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val shakeDetector = ShakeDetector().apply {
    setOnShakeListener(object : ShakeDetector.OnShakeListener {
      override fun onShake(count: Int) {
        onShake()
      }
    })
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private var isRegistered = false
  private var unregisterTask: TimerTask? = null

  fun register() {
    if (isRegistered) return
    unregisterTask?.cancel()
    val manager = sensorManager ?: return
    val sensor = accelerometer ?: return
    val success = manager.registerListener(shakeDetector, sensor, SensorManager.SENSOR_DELAY_UI)
    if (success) {
      isRegistered = true
    }
  }

  fun scheduleUnregister() {
    if (!isRegistered) return
    unregisterTask?.cancel()
    unregisterTask = Timer("SleepTimerShake", false).schedule(expirationMs) {
      mainHandler.post {
        sensorManager?.unregisterListener(shakeDetector)
        isRegistered = false
      }
    }
  }

  fun destroy() {
    unregisterTask?.cancel()
    unregisterTask = null
    sensorManager?.unregisterListener(shakeDetector)
    isRegistered = false
  }
}
