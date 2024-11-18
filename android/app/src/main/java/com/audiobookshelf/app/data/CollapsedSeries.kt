package com.audiobookshelf.app.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class CollapsedSeries(
  id:String,
  var libraryId:String?,
  var name:String,
  //var nameIgnorePrefix:String,
  var sequence:String?,
  var libraryItemIds:MutableList<String>
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = name
  @get:JsonIgnore
  val numBooks get() = libraryItemIds.size

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    val extras = Bundle()

    val mediaId = "__LIBRARY__${libraryId}__SERIE__${id}"
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      //.setIconUri(getCoverUri())
      .setSubtitle("${numBooks} books")
      .setExtras(extras)
      .build()
  }
}
