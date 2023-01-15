package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class MediaProgress(
  var id:String,
  var libraryItemId:String,
  var episodeId:String?,
  var duration:Double, // seconds
  progress:Double, // 0 to 1
  currentTime:Double,
  isFinished:Boolean,
  var lastUpdate:Long,
  var startedAt:Long,
  var finishedAt:Long?
) : MediaProgressWrapper(isFinished, currentTime, progress) {

  @get:JsonIgnore
  override val mediaItemId get() = if (episodeId.isNullOrEmpty()) libraryItemId else "$libraryItemId-$episodeId"
}
