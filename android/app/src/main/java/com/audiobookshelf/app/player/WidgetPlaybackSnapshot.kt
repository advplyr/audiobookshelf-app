package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import com.audiobookshelf.app.data.PlaybackSession

data class WidgetPlaybackSnapshot(
  val title: String?,
  val author: String?,
  val coverUri: Uri?,
  val positionMs: Long,
  val durationMs: Long,
  val isPlaying: Boolean,
  val isClosed: Boolean
)

fun PlaybackSession.toWidgetSnapshot(
  context: Context,
  isPlaying: Boolean,
  isClosed: Boolean,
  positionOverrideMs: Long? = null
): WidgetPlaybackSnapshot {
  val currentPosition = positionOverrideMs ?: currentTimeMs
  return WidgetPlaybackSnapshot(
    title = displayTitle,
    author = displayAuthor,
    coverUri = getCoverUri(context),
    positionMs = currentPosition,
    durationMs = totalDurationMs,
    isPlaying = isPlaying,
    isClosed = isClosed
  )
}
