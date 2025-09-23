package com.tomesonic.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlin.math.roundToInt

@JsonIgnoreProperties(ignoreUnknown = true)
class LocalMediaProgress(
  var id:String,
  var localLibraryItemId:String,
  var localEpisodeId:String?,
  var duration:Double,
  progress:Double, // 0 to 1
  currentTime:Double,
  isFinished:Boolean,
  var ebookLocation:String?, // cfi tag
  var ebookProgress:Double?, // 0 to 1
  var lastUpdate:Long,
  var startedAt:Long,
  var finishedAt:Long?,
  // For local lib items from server to support server sync
  var serverConnectionConfigId:String?,
  var serverAddress:String?,
  var serverUserId:String?,
  var libraryItemId:String?,
  var episodeId:String?
) : MediaProgressWrapper(isFinished, currentTime, progress) {
  @get:JsonIgnore
  val progressPercent get() = if (progress.isNaN()) 0 else (progress * 100).roundToInt()
  @get:JsonIgnore
  override val mediaItemId get() = if (libraryItemId != null) {
        if (episodeId.isNullOrEmpty()) libraryItemId ?: "" else "$libraryItemId-$episodeId"
    } else {
        if (localEpisodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"
    }

  @JsonIgnore
  fun isMatch(mediaProgress:MediaProgress):Boolean {
    if (episodeId != null) return libraryItemId == mediaProgress.libraryItemId && episodeId == mediaProgress.episodeId
    return libraryItemId == mediaProgress.libraryItemId
  }

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
    lastUpdate = playbackSession.updatedAt
    isFinished = playbackSession.progress >= 0.99
    finishedAt = if (isFinished) lastUpdate else null
  }

  @JsonIgnore
  fun updateEbookProgress(ebookLocation:String, ebookProgress:Double) {
    lastUpdate = System.currentTimeMillis()
    this.ebookProgress = ebookProgress
    this.ebookLocation = ebookLocation
  }

  @JsonIgnore
  fun updateFromServerMediaProgress(serverMediaProgress:MediaProgress) {
    isFinished = serverMediaProgress.isFinished
    progress = serverMediaProgress.progress
    currentTime = serverMediaProgress.currentTime
    ebookProgress = serverMediaProgress.ebookProgress
    ebookLocation = serverMediaProgress.ebookLocation
    duration = serverMediaProgress.duration
    lastUpdate = serverMediaProgress.lastUpdate
    finishedAt = serverMediaProgress.finishedAt
    startedAt = serverMediaProgress.startedAt
  }
}
