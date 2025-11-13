package com.audiobookshelf.app.player

import android.content.Context
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.player.PlayerMediaItem
import android.net.Uri
import com.google.android.exoplayer2.MediaItem

/**
 * Adapter functions that produce ExoPlayer types from a PlaybackSession.
 * Keeping these in the player package avoids leaking Exo types into the data layer.
 */
fun PlaybackSession.toPlayerMediaItems(ctx: Context): List<PlayerMediaItem> {
  val mediaItems: MutableList<PlayerMediaItem> = mutableListOf()

  for (audioTrack in this.audioTracks) {
    val mediaUri = this.getContentUri(audioTrack)
    val mimeType = audioTrack.mimeType

    val queueItem = this.getQueueItem(audioTrack) // Queue item used in exo player CastManager
    val playerMediaItem = PlayerMediaItem(
            uri = mediaUri,
            mimeType = mimeType,
            tag = queueItem,
            title = audioTrack.title,
            artworkUri = this.getCoverUri(ctx),
            startPositionMs = 0L
    )
    mediaItems.add(playerMediaItem)
  }
  return mediaItems
}

/**
 * Convert a PlayerMediaItem to an Exo MediaItem.
 */
fun PlayerMediaItem.toExoMediaItem(): MediaItem {
  val builder = MediaItem.Builder().setUri(this.uri)
  this.tag?.let { builder.setTag(it) }
  this.mimeType?.let { builder.setMimeType(it) }
  return builder.build()
}

fun List<PlayerMediaItem>.toExoMediaItems(): List<MediaItem> = this.map { it.toExoMediaItem() }
