package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.*
import androidx.media3.common.MediaMetadata
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.media3.coverUriToArtworkData

/** HLS MIME type used by DefaultMediaSourceFactory to create HlsMediaSource */
private const val MIME_TYPE_HLS = "application/x-mpegURL"

/**
 * Adapter functions that produce PlayerMediaItem DTOs from a PlaybackSession.
 * Keeping conversion logic in the player package avoids leaking framework types into the data model.
 */
fun PlaybackSession.toPlayerMediaItems(ctx: Context): List<PlayerMediaItem> {
  val mediaItems: MutableList<PlayerMediaItem> = mutableListOf()

  // Session-constant across the loop: resolve once instead of per track.
  val coverUri = this.getCoverUri(ctx)

  for (audioTrack in this.audioTracks) {
    val mediaUri = this.getContentUri(audioTrack)
    val queueItem = this.getQueueItem(audioTrack) // Queue item used in exo player CastManager
    // Use HLS MIME type for transcoded sessions so DefaultMediaSourceFactory
    // creates an HlsMediaSource instead of a ProgressiveMediaSource
    val mimeType = if (this.isHLS) MIME_TYPE_HLS else audioTrack.mimeType
    val displayTitle = this.displayTitle ?: audioTrack.title

    val playerMediaItem = PlayerMediaItem(
      mediaId = "${this.id}_${audioTrack.stableId}",
      uri = mediaUri,
      mimeType = mimeType,
      tag = queueItem,
      title = displayTitle,
      artworkUri = coverUri,
      startPositionMs = audioTrack.startOffsetMs
    )
    mediaItems.add(playerMediaItem)
  }
  return mediaItems
}

private fun PlaybackSession.castServerUriForTrack(audioTrack: AudioTrack): Uri? {
  val serverAddr = this.serverAddress ?: return null
  val uriString = if (checkIsServerVersionGte("2.22.0")) {
    if (isDirectPlay) {
      "$serverAddr/public/session/$id/track/${audioTrack.index}"
    } else {
      "$serverAddr${audioTrack.contentUrl}"
    }
  } else {
    "$serverAddr${audioTrack.contentUrl}?token=${DeviceManager.token}"
  }
  return uriString.toUri()
}

/** Cover artwork bytes for a local item, or null for server items / unreadable covers. */
private fun PlaybackSession.localCoverArtworkData(ctx: Context): ByteArray? {
  if (!isLocal) return null
  val coverUri = getCoverUri(ctx)
  if (coverUri.scheme != "content") return null
  return coverUriToArtworkData(coverUri, ctx, size = 512, quality = 85)
}

fun PlaybackSession.toMedia3MediaItems(
  ctx: Context,
  preferServerUrisForCast: Boolean = false
): List<MediaItem> {
  // Decoded once per session, then reused for every track below.
  val localArtworkData = localCoverArtworkData(ctx)

  // Session-constant across the loop: resolve once instead of per track. getCurrentTrackIndex
  // scans audioTracks, so hoisting it keeps the build linear rather than O(tracks^2) on books
  // with many files. The Media3 flavor drives its own Cast queue transport, so we build
  // MediaItems directly here and skip the exov2-only MediaQueueItem construction that
  // toPlayerMediaItems does per track.
  val currentTrackIndex = getCurrentTrackIndex()
  val bookTitle = displayTitle ?: ""
  val author = displayAuthor ?: ""
  val coverUri = getCoverUri(ctx)
  val useServerUri = preferServerUrisForCast && isLocal && !serverAddress.isNullOrBlank()

  val mediaItems = mutableListOf<MediaItem>()
  for ((index, audioTrack) in audioTracks.withIndex()) {
    val mediaUri =
      if (useServerUri) castServerUriForTrack(audioTrack) else getContentUri(audioTrack)
    val safeUri = mediaUri ?: continue
    // Use HLS MIME type for transcoded sessions so DefaultMediaSourceFactory
    // creates an HlsMediaSource instead of a ProgressiveMediaSource.
    val mimeType = if (isHLS) MIME_TYPE_HLS else audioTrack.mimeType

    // Resolve the resume track's chapter at the resume position, not the file start, so the
    // notification opens on the correct chapter instead of snapping to it on the first tick.
    val chapterPosMs =
      if (index == currentTrackIndex) currentTimeMs else getTrackStartOffsetMs(index)
    val chapterTitle = getChapterForTime(chapterPosMs)?.title
    val metadataBuilder = MediaMetadata.Builder()
      .setTitle(bookTitle)
      .setArtist(chapterTitle ?: author)
      .setAlbumTitle(bookTitle)
      .setAlbumArtist(author)
      .setArtworkUri(coverUri)
      .setTrackNumber(index + 1)
      .setTotalTrackCount(audioTracks.size)
      .setDurationMs(audioTrack.durationMs)
      .setIsPlayable(true)
      .setIsBrowsable(false)
      .setMediaType(
        if (isPodcastEpisode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
        else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
      )

    if (localArtworkData != null) {
      metadataBuilder.setArtworkData(localArtworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    }

    mediaItems.add(
      MediaItem.Builder()
        .setUri(safeUri.toString())
        .setMediaId("${id}_${audioTrack.stableId}")
        .setMimeType(mimeType)
        .setMediaMetadata(metadataBuilder.build())
        .build()
    )
  }
  return mediaItems
}
