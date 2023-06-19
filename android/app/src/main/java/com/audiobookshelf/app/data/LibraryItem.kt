package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class LibraryItem(
  id:String,
  var ino:String,
  var libraryId:String,
  var folderId:String,
  var path:String,
  var relPath:String,
  var mtimeMs:Long,
  var ctimeMs:Long,
  var birthtimeMs:Long,
  var addedAt:Long,
  var updatedAt:Long,
  var lastScan:Long?,
  var scanVersion:String?,
  var isMissing:Boolean,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var libraryFiles:MutableList<LibraryFile>?,
  var userMediaProgress:MediaProgress?, // Only included when requesting library item with progress (for downloads)
  var localLibraryItemId:String? // For Android Auto
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = media.metadata.title
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()

  @JsonIgnore
  fun getCoverUri(): Uri {
    if (media.coverPath == null) {
      return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
    }

    return Uri.parse("${DeviceManager.serverAddress}/api/items/$id/cover?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun checkHasTracks():Boolean {
    return if (mediaType == "podcast") {
      ((media as Podcast).numEpisodes ?: 0) > 0
    } else {
      ((media as Book).numTracks ?: 0) > 0
    }
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    val extras = Bundle()

    if (localLibraryItemId != null) {
      extras.putLong(
        MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
        MediaDescriptionCompat.STATUS_DOWNLOADED
      )
    }

    if (progress != null) {
      if (progress.isFinished) {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        )
      } else {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        )
        extras.putDouble(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.progress
        )
      }
    } else if (mediaType != "podcast") {
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
      )
    }

    val mediaId = localLibraryItemId ?: id
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      .setIconUri(getCoverUri())
      .setSubtitle(authorName)
      .setExtras(extras)
      .build()
  }
}
