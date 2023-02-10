package com.audiobookshelf.app.data

import android.content.Context
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

enum class LockOrientationSetting {
  NONE, PORTRAIT, LANDSCAPE
}

enum class HapticFeedbackSetting {
  OFF, LIGHT, MEDIUM, HEAVY
}

enum class ShakeSensitivitySetting {
  VERY_LOW, LOW, MEDIUM, HIGH, VERY_HIGH
}

data class ServerConnectionConfig(
  var id:String,
  var index:Int,
  var name:String,
  var address:String,
  var userId:String,
  var username:String,
  var token:String,
  var customHeaders:Map<String, String>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFile(
  var id:String,
  var filename:String?,
  var contentUrl:String,
  var basePath:String,
  var absolutePath:String,
  var simplePath:String,
  var mimeType:String?,
  var size:Long
) {
  @JsonIgnore
  fun isAudioFile():Boolean {
    if (mimeType == "application/octet-stream") return true
    if (mimeType == "video/mp4") return true
    return mimeType?.startsWith("audio") == true
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalFolder(
  var id:String,
  var name:String,
  var contentUrl:String,
  var basePath:String,
  var absolutePath:String,
  var simplePath:String,
  var storageType:String,
  var mediaType:String
)

@JsonTypeInfo(use= JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
  JsonSubTypes.Type(LibraryItem::class),
  JsonSubTypes.Type(LocalLibraryItem::class)
)
open class LibraryItemWrapper(var id:String) {
  @JsonIgnore
  open fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context?): MediaDescriptionCompat { return MediaDescriptionCompat.Builder().build() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceInfo(
  var manufacturer:String,
  var model:String,
  var brand:String,
  var sdkVersion:Int,
  var clientVersion: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayItemRequestPayload(
  var mediaPlayer:String,
  var forceDirectPlay:Boolean,
  var forceTranscode:Boolean,
  var deviceInfo:DeviceInfo
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceSettings(
  var disableAutoRewind:Boolean,
  var enableAltView:Boolean,
  var jumpBackwardsTime:Int,
  var jumpForwardTime:Int,
  var disableShakeToResetSleepTimer:Boolean,
  var shakeSensitivity: ShakeSensitivitySetting,
  var lockOrientation: LockOrientationSetting,
  var hapticFeedback: HapticFeedbackSetting,
  var autoSleepTimer: Boolean,
  var autoSleepTimerStartTime: String,
  var autoSleepTimerEndTime: String,
  var sleepTimerLength: Long, // Time in milliseconds
  var disableSleepTimerFadeOut: Boolean,
  var disableSleepTimerResetFeedback: Boolean,
  var showAdvancedSettings: Boolean,
  var localPathFormat: String
) {
  companion object {
    // Static method to get default device settings
    fun default():DeviceSettings {
      return DeviceSettings(
        disableAutoRewind = false,
        enableAltView = true,
        jumpBackwardsTime = 10,
        jumpForwardTime = 10,
        disableShakeToResetSleepTimer = false,
        shakeSensitivity = ShakeSensitivitySetting.MEDIUM,
        lockOrientation = LockOrientationSetting.NONE,
        hapticFeedback = HapticFeedbackSetting.LIGHT,
        autoSleepTimer = false,
        autoSleepTimerStartTime = "22:00",
        autoSleepTimerEndTime = "06:00",
        sleepTimerLength = 900000L, // 15 minutes
        disableSleepTimerFadeOut = false,
        disableSleepTimerResetFeedback = false,
        showAdvancedSettings = false,
        localPathFormat = "\$bookAuthor/\$bookTitle"
      )
    }
  }

  @get:JsonIgnore
  val jumpBackwardsTimeMs get() = jumpBackwardsTime * 1000L
  @get:JsonIgnore
  val jumpForwardTimeMs get() = jumpForwardTime * 1000L
  @get:JsonIgnore
  val autoSleepTimerStartHour get() = autoSleepTimerStartTime.split(":")[0].toInt()
  @get:JsonIgnore
  val autoSleepTimerStartMinute get() = autoSleepTimerStartTime.split(":")[1].toInt()
  @get:JsonIgnore
  val autoSleepTimerEndHour get() = autoSleepTimerEndTime.split(":")[0].toInt()
  @get:JsonIgnore
  val autoSleepTimerEndMinute get() = autoSleepTimerEndTime.split(":")[1].toInt()

  @JsonIgnore
  fun getShakeThresholdGravity() : Float { // Used in ShakeDetector
    return if (shakeSensitivity == ShakeSensitivitySetting.VERY_HIGH) 1.2f
    else if (shakeSensitivity == ShakeSensitivitySetting.HIGH) 1.4f
    else if (shakeSensitivity == ShakeSensitivitySetting.MEDIUM) 1.6f
    else if (shakeSensitivity == ShakeSensitivitySetting.LOW) 2f
    else if (shakeSensitivity == ShakeSensitivitySetting.VERY_LOW) 2.7f
    else {
      Log.e("DeviceSetting", "Invalid ShakeSensitivitySetting $shakeSensitivity")
      1.6f
    }
  }
}

data class DeviceData(
  var serverConnectionConfigs:MutableList<ServerConnectionConfig>,
  var lastServerConnectionConfigId:String?,
  var currentLocalPlaybackSession: PlaybackSession?, // Stored to open up where left off for local media. TODO: Old
  var deviceSettings: DeviceSettings?
) {
  @JsonIgnore
  fun getLastServerConnectionConfig(): ServerConnectionConfig? {
    return lastServerConnectionConfigId?.let { lsccid ->
      return serverConnectionConfigs.find { it.id == lsccid }
    }
  }
}

