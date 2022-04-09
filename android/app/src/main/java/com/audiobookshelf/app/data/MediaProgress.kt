package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaProgress(
  val id:String,
  val libraryItemId:String,
  val episodeId:String,
  val duration:Double,
  val progress:Double, // 0 to 1
  val currentTime:Int,
  val isFinished:Boolean,
  val lastUpdate:Long,
  val startedAt:Long,
  val finishedAt:Long,
  val isLocal:Boolean?
)
