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
      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
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
}
