package com.audiobookshelf.app.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Encapsulates shake sensor registration used by the sleep timer.
 * Handles delayed unregistration using coroutines for lifecycle-aware background tasks.
 *
 * @param context The application context to access system services.
 * @param unregisterDelayMs The delay in milliseconds before the sensor is automatically unregistered.
 * @param scope The CoroutineScope to launch the delayed unregister task in. This should be
 *              tied to a lifecycle (e.g., a ViewModel's viewModelScope) to prevent leaks.
 * @param onShake The callback function to execute when a shake is detected.
 */
class SleepTimerShakeController(
  private val context: Context,
  private val unregisterDelayMs: Long,
  private val scope: CoroutineScope,
  private val onShake: () -> Unit
) {
  private val sensorManager =
    context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  private val isShakeDetectionAvailable = sensorManager != null && accelerometer != null

  private val shakeDetector = ShakeDetector().apply {
    setOnShakeListener(object : ShakeDetector.OnShakeListener {
      override fun onShake(count: Int) {
        onShake()
      }
    })
  }



  private var unregisterJob: Job? = null
  private var isRegistered = false

  /**
   * Registers the shake detector listener. If a delayed unregister task is pending,
   * it will be cancelled.
   */
  fun register() {
    // Do nothing if the sensor is already registered or not available on the device
    if (isRegistered || !isShakeDetectionAvailable) return

    // Cancel any pending unregister job
    unregisterJob?.cancel()
    unregisterJob = null

    val success = sensorManager!!.registerListener(
      shakeDetector,
      accelerometer,
      SensorManager.SENSOR_DELAY_UI
    )

    if (success) {
      isRegistered = true
    }
  }

  /**
   * Schedules a delayed task to unregister the shake detector listener after the
   * specified [unregisterDelayMs]. If a task is already scheduled, it is replaced.
   */
  fun scheduleUnregister() {
    if (!isRegistered) return

    // Cancel any previously scheduled job and launch a new one
    unregisterJob?.cancel()
    unregisterJob = scope.launch {
      delay(unregisterDelayMs)
      // This will execute on the main thread if the scope's context is Dispatchers.Main
      unregisterListener()
    }
  }

  /**
   * Immediately unregisters the sensor listener and cancels any pending unregister task.
   * This should be called when the feature is no longer needed or the host is being destroyed.
   */
  fun release() {
    unregisterJob?.cancel()
    unregisterJob = null
    unregisterListener()
  }

  /**
   * Unregisters the listener from the SensorManager if it is currently registered.
   */
  private fun unregisterListener() {
    if (isRegistered) {
      sensorManager?.unregisterListener(shakeDetector)
      isRegistered = false
    }
  }
}
