package com.audiobookshelf.app.player

import android.content.Context
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.player.PlayerMediaItem
import android.net.Uri

/**
 * Adapter functions that produce PlayerMediaItem DTOs from a PlaybackSession.
 * Keeping conversion logic in the player package avoids leaking framework types into the data model.
 */
fun PlaybackSession.toPlayerMediaItems(ctx: Context): List<PlayerMediaItem> {
  val mediaItems: MutableList<PlayerMediaItem> = mutableListOf()

  for (audioTrack in this.audioTracks) {
    val mediaUri = this.getContentUri(audioTrack)
    val mimeType = audioTrack.mimeType

    val queueItem = this.getQueueItem(audioTrack) // Queue item used in exo player CastManager
    val playerMediaItem = PlayerMediaItem(
            mediaId = "${this.id}_${audioTrack.stableId}",
            uri = mediaUri,
            mimeType = mimeType,
            tag = queueItem,
            title = audioTrack.title,
            artworkUri = this.getCoverUri(ctx),
                startPositionMs = audioTrack.startOffsetMs
    )
    mediaItems.add(playerMediaItem)
  }
  return mediaItems
}

