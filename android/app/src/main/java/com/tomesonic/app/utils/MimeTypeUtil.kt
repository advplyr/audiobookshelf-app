package com.tomesonic.app.utils

import androidx.media3.common.MimeTypes

/**
 * Centralized MIME type detection utility for consistent audio format handling
 * across ExoPlayer, Cast, and media conversion components.
 */
object MimeTypeUtil {

    /**
     * Detects MIME type from a file URI or filename
     * Uses Media3 MimeTypes constants for consistency
     */
    fun getMimeType(uriOrFilename: String): String {
        return when {
            uriOrFilename.contains(".mp3", ignoreCase = true) -> MimeTypes.AUDIO_MPEG
            uriOrFilename.contains(".m4a", ignoreCase = true) -> MimeTypes.AUDIO_MP4
            uriOrFilename.contains(".mp4", ignoreCase = true) -> MimeTypes.AUDIO_MP4
            uriOrFilename.contains(".m4b", ignoreCase = true) -> MimeTypes.AUDIO_MP4
            uriOrFilename.contains(".aac", ignoreCase = true) -> MimeTypes.AUDIO_AAC
            uriOrFilename.contains(".flac", ignoreCase = true) -> MimeTypes.AUDIO_FLAC
            uriOrFilename.contains(".ogg", ignoreCase = true) -> MimeTypes.AUDIO_OGG
            uriOrFilename.contains(".opus", ignoreCase = true) -> MimeTypes.AUDIO_OPUS
            uriOrFilename.contains(".wav", ignoreCase = true) -> MimeTypes.AUDIO_WAV
            uriOrFilename.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            else -> MimeTypes.AUDIO_MPEG // Default to MP3 for unknown audio types
        }
    }

    /**
     * Gets a readable format name for logging and debugging
     */
    fun getFormatName(mimeType: String): String {
        return when (mimeType) {
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MP4 -> "MP4/M4A/M4B"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OGG -> "OGG"
            MimeTypes.AUDIO_OPUS -> "OPUS"
            MimeTypes.AUDIO_WAV -> "WAV"
            MimeTypes.APPLICATION_M3U8 -> "HLS (M3U8)"
            else -> "Unknown ($mimeType)"
        }
    }
}