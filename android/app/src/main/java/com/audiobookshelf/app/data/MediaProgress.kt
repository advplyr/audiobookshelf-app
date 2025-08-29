package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.squareup.moshi.JsonClass

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonClass(generateAdapter = true)
class MediaProgress(
  var id:String,
  var libraryItemId:String,
  var episodeId:String?,
  var duration:Double, // seconds
  progress:Double, // 0 to 1
  currentTime:Double,
  isFinished:Boolean,
  var ebookLocation:String?, // cfi tag
  var ebookProgress:Double?, // 0 to 1
  var lastUpdate:Long,
  var startedAt:Long,
  var finishedAt:Long?
) : MediaProgressWrapper(isFinished, currentTime, progress) {

  @get:JsonIgnore
  override val mediaItemId get() = if (episodeId.isNullOrEmpty()) libraryItemId else "$libraryItemId-$episodeId"
}
