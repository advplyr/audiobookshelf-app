package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class MediaItemHistory(
        var id: String, // media id
        var mediaDisplayTitle: String,
        var libraryItemId: String,
        var episodeId: String?,
        var isLocal: Boolean,
        var serverConnectionConfigId: String?,
        var serverAddress: String?,
        var serverUserId: String?,
        var createdAt: Long,
        var events: MutableList<MediaItemEvent>,
)
