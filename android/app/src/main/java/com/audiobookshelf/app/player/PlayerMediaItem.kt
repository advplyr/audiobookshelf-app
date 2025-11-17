package com.audiobookshelf.app.player

import android.net.Uri

/**
 * Neutral DTO representing a media item for the player.
 * Keeps the public player API independent from ExoPlayer / Media3 types.
 */
data class PlayerMediaItem(
  val mediaId: String,
  val uri: Uri,
  val mimeType: String? = null,
  val tag: Any? = null,
  val title: String? = null,
  val artworkUri: Uri? = null,
  val startPositionMs: Long = 0L
)
