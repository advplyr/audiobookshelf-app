package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.io.File

enum class LockOrientationSetting {
  NONE,
  PORTRAIT,
  LANDSCAPE
}

enum class HapticFeedbackSetting {
  OFF,
  LIGHT,
  MEDIUM,
  HEAVY
}

enum class ShakeSensitivitySetting {
  VERY_LOW,
  LOW,
  MEDIUM,
  HIGH,
  VERY_HIGH
}

enum class DownloadUsingCellularSetting {
  ASK,
  ALWAYS,
  NEVER
}

enum class StreamingUsingCellularSetting {
  ASK,
  ALWAYS,
  NEVER
}

enum class AndroidAutoBrowseSeriesSequenceOrderSetting {
  ASC,
  DESC
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServerConnectionConfig(
        var id: String,
        var index: Int,
        var name: String,
        var address: String,
        // version added after 0.9.81-beta
        var version: String?,
        var userId: String,
        var username: String,
        var token: String,
        var customHeaders: Map<String, String>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFile(
        var id: String,
        var filename: String?,
        var contentUrl: String,
        var basePath: String,
        var absolutePath: String,
        var mimeType: String?,
        var size: Long
) {
  @JsonIgnore
  fun exists(ctx: Context): Boolean {
    if (contentUrl.startsWith("content:")) {
      return try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(contentUrl), "r")?.use { true } ?: false
      } catch (e: Exception) {
        Log.w("LocalFile", "Cannot access SAF file $contentUrl", e)
        false
      }
    }
    return File(absolutePath).exists()
  }

  @JsonIgnore
  fun isAudioFile(): Boolean {
    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (normalizedMimeType == "application/octet-stream") return true
    if (normalizedMimeType == "video/mp4") return true
    return normalizedMimeType?.startsWith("audio/") == true
  }
  @JsonIgnore
  fun isEBookFile(): Boolean {
    return getEBookFormat() != null
  }
  @JsonIgnore
  fun getEBookFormat(): String? {
    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (normalizedMimeType == "application/epub+zip") return "epub"
    if (normalizedMimeType == "application/pdf") return "pdf"
    if (normalizedMimeType == "application/x-mobipocket-ebook") return "mobi"
    if (normalizedMimeType == "application/vnd.comicbook+zip") return "cbz"
    if (normalizedMimeType == "application/vnd.comicbook-rar") return "cbr"
    if (normalizedMimeType == "application/vnd.amazon.mobi8-ebook") return "azw3"
    return null
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFolder(
        var id: String,
        var name: String,
        var contentUrl: String,
        var basePath: String,
        var absolutePath: String,
        var storageType: String,
        var mediaType: String
)

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(JsonSubTypes.Type(LibraryItem::class), JsonSubTypes.Type(LocalLibraryItem::class))
open class LibraryItemWrapper(var id: String) {
  @JsonIgnore
  open fun getMediaDescription(
          progress: MediaProgressWrapper?,
          ctx: Context
  ): MediaDescriptionCompat {
    return MediaDescriptionCompat.Builder().build()
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceInfo(
        var deviceId: String,
        var manufacturer: String,
        var model: String,
        var sdkVersion: Int,
        var clientVersion: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayItemRequestPayload(
        var mediaPlayer: String,
        var forceDirectPlay: Boolean,
        var forceTranscode: Boolean,
        var deviceInfo: DeviceInfo
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceSettings(
        var disableAutoRewind: Boolean,
        var enableAltView: Boolean,
        var allowSeekingOnMediaControls: Boolean,
        var jumpBackwardsTime: Int,
        var jumpForwardTime: Int,
        var enableMp3IndexSeeking: Boolean,
        var disableShakeToResetSleepTimer: Boolean,
        var shakeSensitivity: ShakeSensitivitySetting,
        var lockOrientation: LockOrientationSetting,
        var hapticFeedback: HapticFeedbackSetting,
        var autoSleepTimer: Boolean,
        var autoSleepTimerStartTime: String,
        var autoSleepTimerEndTime: String,
        var autoSleepTimerAutoRewind: Boolean,
        var autoSleepTimerAutoRewindTime: Long, // Time in milliseconds
        var sleepTimerLength: Long, // Time in milliseconds
        var disableSleepTimerFadeOut: Boolean,
        var disableSleepTimerResetFeedback: Boolean,
        var enableSleepTimerAlmostDoneChime: Boolean,
        var languageCode: String,
        var downloadUsingCellular: DownloadUsingCellularSetting,
        var streamingUsingCellular: StreamingUsingCellularSetting,
        var androidAutoBrowseLimitForGrouping: Int,
        var androidAutoBrowseSeriesSequenceOrder: AndroidAutoBrowseSeriesSequenceOrderSetting
) {
  companion object {
    // Static method to get default device settings
    fun default(): DeviceSettings {
      return DeviceSettings(
              disableAutoRewind = false,
              enableAltView = true,
              allowSeekingOnMediaControls = false,
              jumpBackwardsTime = 10,
              jumpForwardTime = 10,
              enableMp3IndexSeeking = false,
              disableShakeToResetSleepTimer = false,
              shakeSensitivity = ShakeSensitivitySetting.MEDIUM,
              lockOrientation = LockOrientationSetting.NONE,
              hapticFeedback = HapticFeedbackSetting.LIGHT,
              autoSleepTimer = false,
              autoSleepTimerStartTime = "22:00",
              autoSleepTimerEndTime = "06:00",
              sleepTimerLength = 900000L, // 15 minutes
              autoSleepTimerAutoRewind = false,
              autoSleepTimerAutoRewindTime = 300000L, // 5 minutes
              disableSleepTimerFadeOut = false,
              disableSleepTimerResetFeedback = false,
              enableSleepTimerAlmostDoneChime = false,
              languageCode = "en-us",
              downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS,
              streamingUsingCellular = StreamingUsingCellularSetting.ALWAYS,
              androidAutoBrowseLimitForGrouping = 100,
              androidAutoBrowseSeriesSequenceOrder = AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
      )
    }
  }

  @get:JsonIgnore
  val jumpBackwardsTimeMs
    get() = jumpBackwardsTime * 1000L
  @get:JsonIgnore
  val jumpForwardTimeMs
    get() = jumpForwardTime * 1000L
  // A malformed or separator-less string (never persisted by this app, but not otherwise validated
  // on the way in) must not crash the hour/minute lookup, and must not yield a value outside the
  // clock either. `"0600".split(":")` is `["0600"]`, which parses cleanly to the *integer* 600 -
  // so the range check below is doing real work, not defending against a parse failure. An
  // out-of-clock hour is worse than a crash here: SleepTimerManager compares the current hour
  // against this to decide whether it is inside the auto-timer window, and 600 never matches, so
  // the auto sleep timer silently stops working with no error anywhere.
  private fun timePart(time: String, index: Int, max: Int, fallback: Int): Int =
          time.split(":").getOrNull(index)?.toIntOrNull()?.takeIf { it in 0..max } ?: fallback

  @get:JsonIgnore
  val autoSleepTimerStartHour
    get() = timePart(autoSleepTimerStartTime, 0, 23, 22)
  @get:JsonIgnore
  val autoSleepTimerStartMinute
    get() = timePart(autoSleepTimerStartTime, 1, 59, 0)
  @get:JsonIgnore
  val autoSleepTimerEndHour
    get() = timePart(autoSleepTimerEndTime, 0, 23, 6)
  @get:JsonIgnore
  val autoSleepTimerEndMinute
    get() = timePart(autoSleepTimerEndTime, 1, 59, 0)

  @JsonIgnore
  fun getShakeThresholdGravity(): Float { // Used in ShakeDetector
    return if (shakeSensitivity == ShakeSensitivitySetting.VERY_HIGH) 1.1f
    else if (shakeSensitivity == ShakeSensitivitySetting.HIGH) 1.3f
    else if (shakeSensitivity == ShakeSensitivitySetting.MEDIUM) 1.5f
    else if (shakeSensitivity == ShakeSensitivitySetting.LOW) 2f
    else if (shakeSensitivity == ShakeSensitivitySetting.VERY_LOW) 2.7f
    else {
      Log.e("DeviceSetting", "Invalid ShakeSensitivitySetting $shakeSensitivity")
      1.6f
    }
  }
}

data class DeviceData(
        var serverConnectionConfigs: MutableList<ServerConnectionConfig>,
        var lastServerConnectionConfigId: String?,
        var deviceSettings: DeviceSettings?,
        var lastPlaybackSession: PlaybackSession?
) {
  @JsonIgnore
  fun getLastServerConnectionConfig(): ServerConnectionConfig? {
    return lastServerConnectionConfigId?.let { lsccid ->
      return serverConnectionConfigs.find { it.id == lsccid }
    }
  }
}
