package com.audiobookshelf.app.data

import android.support.v4.media.MediaDescriptionCompat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

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

data class DeviceSettings(
  var disableAutoRewind:Boolean,
  var enableAltView:Boolean,
  var jumpBackwardsTime:Int,
  var jumpForwardTime:Int,
  var disableShakeToResetSleepTimer:Boolean
) {
  companion object {
    // Static method to get default device settings
    fun default():DeviceSettings {
      return DeviceSettings(
        disableAutoRewind = false,
        enableAltView = false,
        jumpBackwardsTime = 10,
        jumpForwardTime = 10,
        disableShakeToResetSleepTimer = false
      )
    }
  }

  @get:JsonIgnore
  val jumpBackwardsTimeMs get() = (jumpBackwardsTime ?: default().jumpBackwardsTime) * 1000L
  @get:JsonIgnore
  val jumpForwardTimeMs get() = (jumpForwardTime ?: default().jumpBackwardsTime) * 1000L
}

data class DeviceData(
  var serverConnectionConfigs:MutableList<ServerConnectionConfig>,
  var lastServerConnectionConfigId:String?,
  var currentLocalPlaybackSession:PlaybackSession?, // Stored to open up where left off for local media
  var deviceSettings:DeviceSettings?
) {
  @JsonIgnore
  fun getLastServerConnectionConfig():ServerConnectionConfig? {
    return lastServerConnectionConfigId?.let { lsccid ->
      return serverConnectionConfigs.find { it.id == lsccid }
    }
  }
}

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
  open fun getMediaDescription(progress:MediaProgressWrapper?): MediaDescriptionCompat { return MediaDescriptionCompat.Builder().build() }
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
