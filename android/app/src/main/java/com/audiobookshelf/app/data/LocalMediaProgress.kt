package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalMediaProgress(
  var id:String,
  var localLibraryItemId:String,
  var episodeId:String?,
  var duration:Double,
  var progress:Double, // 0 to 1
  var currentTime:Double,
  var isFinished:Boolean,
  var lastUpdate:Long,
  var startedAt:Long,
  var finishedAt:Long?,
  // For local lib items from server to support server sync
  var serverConnectionConfigId:String?,
  var serverAddress:String?,
  var serverUserId:String?,
  var libraryItemId:String?
)
