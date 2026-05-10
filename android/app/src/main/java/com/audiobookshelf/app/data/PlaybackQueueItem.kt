package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/** Playback queue item matching JavaScript structure. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackQueueItem(
    val id: String, // Unique ID for Vue tracking (timestamp + random)
    val libraryItemId: String, // Can be local or server ID depending on isLocal
    val episodeId: String?, // Null for audiobooks, populated for podcast episodes
    val serverLibraryItemId: String?, // Server library item ID (may be null for local-only items)
    val serverEpisodeId: String?, // Server episode ID (may be null)
    val title: String,
    val author: String?,
    val duration: Double,
    val coverPath: String?,
    val isLocal: Boolean,
    val currentTime: Double // Saved progress position to resume from
) {
    companion object {
        private val mapper = jacksonObjectMapper()

        fun fromJson(json: String): PlaybackQueueItem? {
            return try {
                mapper.readValue<PlaybackQueueItem>(json)
            } catch (e: Exception) {
                null
            }
        }

        fun fromJsonArray(json: String): List<PlaybackQueueItem> {
            return try {
                mapper.readValue<List<PlaybackQueueItem>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun toJson(): String {
        return mapper.writeValueAsString(this)
    }

    fun getMediaItemId(): String {
        return if (episodeId.isNullOrEmpty()) {
            libraryItemId
        } else {
            "$libraryItemId-$episodeId"
        }
    }

    fun isPodcastEpisode(): Boolean {
        return !episodeId.isNullOrEmpty()
    }
}

fun List<PlaybackQueueItem>.toJsonArray(): String {
    val mapper = jacksonObjectMapper()
    return mapper.writeValueAsString(this)
}
