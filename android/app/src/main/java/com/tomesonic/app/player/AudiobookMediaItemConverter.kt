package com.tomesonic.app.player

import android.util.Log
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.tomesonic.app.utils.MimeTypeUtil
import org.json.JSONObject

/**
 * Custom MediaItemConverter for book-level Cast playback.
 * Simplified for default receiver - handles entire books instead of individual chapters.
 */
class AudiobookMediaItemConverter : MediaItemConverter {

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        // Convert from Cast MediaQueueItem back to Media3 MediaItem
        // Simplified for book-level playback

        val mediaInfo = mediaQueueItem.media ?: throw IllegalArgumentException("MediaQueueItem must have MediaInfo")

        // ContentId is the book's audio URI
        val contentId = mediaInfo.contentId
        val customData = mediaInfo.customData

        Log.d("AudiobookConverter", "Converting book-level Cast MediaInfo back to MediaItem - URI: $contentId")

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(contentId)

        // Extract book-level custom data if present
        if (customData != null) {
            // Restore book ID as mediaId
            val bookId = customData.optString("bookId", "")
            if (bookId.isNotEmpty()) {
                mediaItemBuilder.setMediaId(bookId)
                Log.d("AudiobookConverter", "Restored book ID: $bookId")
            }
        }

        // Build MediaMetadata from Cast metadata
        val castMetadata = mediaInfo.metadata
        if (castMetadata != null) {
            val metadataBuilder = MediaMetadata.Builder()

            castMetadata.getString(CastMediaMetadata.KEY_TITLE)?.let {
                metadataBuilder.setTitle(it)
            }
            castMetadata.getString(CastMediaMetadata.KEY_SUBTITLE)?.let {
                metadataBuilder.setArtist(it)
            }
            castMetadata.getString(CastMediaMetadata.KEY_ALBUM_TITLE)?.let {
                metadataBuilder.setAlbumTitle(it)
            }

            // Extract track number and total from custom data
            customData?.let { data ->
                val trackNumber = data.optInt("trackNumber", 0)
                val totalTracks = data.optInt("totalTracks", 0)
                if (trackNumber > 0) metadataBuilder.setTrackNumber(trackNumber)
                if (totalTracks > 0) metadataBuilder.setTotalTrackCount(totalTracks)
            }

            mediaItemBuilder.setMediaMetadata(metadataBuilder.build())
        }

        return mediaItemBuilder.build()
    }

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        // Convert from Media3 MediaItem to Cast MediaQueueItem
        // Simplified for book-level playback with default receiver

        val uri = mediaItem.localConfiguration?.uri?.toString()
            ?: throw IllegalArgumentException("MediaItem must have a URI")

        Log.d("AudiobookConverter", "Converting book-level MediaItem to Cast - URI: $uri")

        // Use the actual URI - default receiver will load this directly
        val mediaInfoBuilder = MediaInfo.Builder(uri)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(MimeTypeUtil.getMimeType(uri))

        // Book-level custom data for identification
        val customData = JSONObject()

        // Use full track duration for Cast absolute positioning
        mediaItem.mediaMetadata.durationMs?.let { durationMs ->
            if (durationMs > 0) {
                mediaInfoBuilder.setStreamDuration(durationMs)
                Log.d("AudiobookConverter", "Using full track duration for absolute positioning: ${durationMs}ms")
            }
        }

        // Add chapter-based identification but with book-level display preference
        val mediaId = mediaItem.mediaId ?: ""
        customData.put("mediaId", mediaId)

        // Extract chapter/book information from metadata extras
        val extras = mediaItem.mediaMetadata.extras
        if (extras != null) {
            // Book-level display info (consistent across all chapters)
            extras.getString("bookId")?.let { bookId ->
                customData.put("bookId", bookId)
            }
            extras.getString("bookTitle")?.let { title ->
                customData.put("bookTitle", title)
            }
            extras.getString("bookAuthor")?.let { author ->
                customData.put("bookAuthor", author)
            }

            // Track-specific info for absolute positioning
            val trackIndex = extras.getInt("trackIndex", -1)
            if (trackIndex >= 0) {
                customData.put("trackIndex", trackIndex)
            }
            val totalTracks = extras.getInt("totalTracks", 0)
            if (totalTracks > 0) {
                customData.put("totalTracks", totalTracks)
            }
            val totalChapters = extras.getInt("totalChapters", 0)
            if (totalChapters > 0) {
                customData.put("totalChapters", totalChapters)
            }

            // Track timing for absolute positioning
            val trackStartOffsetMs = extras.getLong("trackStartOffsetMs", 0L)
            val trackDurationMs = extras.getLong("trackDurationMs", 0L)
            if (trackStartOffsetMs >= 0L) {
                customData.put("trackStartOffsetMs", trackStartOffsetMs)
                customData.put("trackDurationMs", trackDurationMs)
                Log.d("AudiobookConverter", "Added track timing: offset=${trackStartOffsetMs}ms, duration=${trackDurationMs}ms")
            }

            val chaptersInTrack = extras.getInt("chaptersInTrack", 0)
            if (chaptersInTrack > 0) {
                customData.put("chaptersInTrack", chaptersInTrack)
            }
        }

        // Mark as full track with absolute positioning
        customData.put("isFullTrack", true) // Use full track for absolute positioning
        customData.put("displayAsBook", true) // Hint for receiver to show book info
        customData.put("useAbsolutePositioning", true) // Enable absolute seek positioning

        // Build Cast MediaMetadata for book-level display
        val media3Metadata = mediaItem.mediaMetadata
        val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK) // Use music track for book-level

        // Set book title as the main title
        media3Metadata.title?.let {
            castMetadata.putString(CastMediaMetadata.KEY_TITLE, it.toString())
        }

        // Set author as subtitle - prefer bookAuthor from extras if available
        val authorForSubtitle = customData.optString("bookAuthor").takeIf { it.isNotEmpty() }
            ?: media3Metadata.artist?.toString()

        authorForSubtitle?.let {
            castMetadata.putString(CastMediaMetadata.KEY_SUBTITLE, it)
        }

        // Set book title as album (for grouping)
        media3Metadata.albumTitle?.let {
            castMetadata.putString(CastMediaMetadata.KEY_ALBUM_TITLE, it.toString())
        }

        // Add book cover artwork for Cast receiver display
        media3Metadata.artworkUri?.let { artworkUri ->
            val image = com.google.android.gms.common.images.WebImage(
                android.net.Uri.parse(artworkUri.toString())
            )
            castMetadata.addImage(image)
            Log.d("AudiobookConverter", "Added book cover artwork to Cast metadata: $artworkUri")
        }

        val mediaInfo = mediaInfoBuilder
            .setMetadata(castMetadata)
            .setCustomData(customData)
            .build()

        // Create MediaQueueItem with the MediaInfo
        val queueItem = MediaQueueItem.Builder(mediaInfo).build()

        Log.d("AudiobookConverter", "Created book-level MediaQueueItem for '${media3Metadata.title}' with duration ${mediaInfo.streamDuration}ms")

        return queueItem
    }

}
