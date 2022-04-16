package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalMediaProgress(
  var id:String,
  var localLibraryItemId:String,
  var localEpisodeId:String?,
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
  var libraryItemId:String?,
  var episodeId:String?
) {
  @JsonIgnore
  fun updateIsFinished(finished:Boolean) {
    if (isFinished != finished) { // If finished changed then set progress
      progress = if (finished) 1.0 else 0.0
    }

    isFinished = finished
    lastUpdate = System.currentTimeMillis()
    finishedAt = if (isFinished) lastUpdate else null
  }

  @JsonIgnore
  fun updateFromPlaybackSession(playbackSession:PlaybackSession) {
    currentTime = playbackSession.currentTime
    progress = playbackSession.progress
    lastUpdate = System.currentTimeMillis()

    isFinished = playbackSession.progress >= 0.99
    finishedAt = if (isFinished) lastUpdate else null
  }
}
