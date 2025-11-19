package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import androidx.media.utils.MediaConstants as LegacyMediaConstants

@JsonIgnoreProperties(ignoreUnknown = true)
class LibraryAuthorItem(
  id:String,
  var libraryId:String,
  var name:String,
  var description:String?,
  var imagePath:String?,
  var addedAt:Long,
  var updatedAt:Long,
  var numBooks:Int?,
  var libraryItems:MutableList<LibraryItem>?,
  var series:MutableList<LibrarySeriesItem>?
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = name

  @get:JsonIgnore
  val bookCount get() = if (numBooks != null) numBooks else libraryItems!!.size

  @JsonIgnore
  fun getPortraitUri(): Uri {
    if (imagePath == null) {
      return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.md_account_outline)
    }

    return Uri.parse("${DeviceManager.serverAddress}/api/authors/$id/image?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, groupTitle: String?): MediaDescriptionCompat {
    val extras = Bundle()
    if (groupTitle !== null) {
      extras.putString(
        LegacyMediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
        groupTitle
      )
    }

    val mediaId = "__LIBRARY__${libraryId}__AUTHOR__${id}"
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      .setIconUri(getPortraitUri())
      .setSubtitle("${bookCount} books")
      .setExtras(extras)
      .build()
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, null)
  }

  /**
   * detailed implementation for creating a MediaItem, including the groupTitle.
   */
  @OptIn(UnstableApi::class)
  @JsonIgnore
  fun getMediaItem(
    progress: MediaProgressWrapper?,
    context: Context,
    groupTitle: String?
  ): MediaItem {
    val extras = Bundle()
    if (groupTitle != null) {
      extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
    }

    val mediaId = "__LIBRARY__${libraryId}__AUTHOR__${id}"
    val subtitle = "$bookCount books"

    val metadata = MediaMetadata.Builder()
      .setTitle(this.title)
      .setSubtitle(subtitle)
      .setArtworkUri(getPortraitUri())
      .setIsBrowsable(true) // An author is always a browsable folder
      .setIsPlayable(false)
      .setExtras(extras) // Apply the extras bundle
      .build()

    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(metadata)
      .build()
  }

  /**
   * The modern, public override for getMediaItem. It calls the detailed
   * implementation with a null groupTitle, perfectly mirroring the legacy pattern.
   */
  @JsonIgnore
  override fun getMediaItem(progress: MediaProgressWrapper?, context: Context): MediaItem {
    return getMediaItem(progress, context, null)
  }
}
