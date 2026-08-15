package com.audiobookshelf.app.data

internal fun audioTrack(
        index: Int = 0,
        startOffset: Double = 0.0,
        duration: Double = 10.0,
        localFileId: String? = "track-$index"
) =
        AudioTrack(
                index = index,
                startOffset = startOffset,
                duration = duration,
                title = "Track $index",
                contentUrl = "file:///track-$index.mp3",
                mimeType = "audio/mpeg",
                metadata = null,
                isLocal = true,
                localFileId = localFileId,
                serverIndex = index
        )

internal fun bookMetadata(series: List<SeriesType>? = null) =
        BookMetadata(
                title = "Book",
                subtitle = null,
                authors = mutableListOf(),
                narrators = mutableListOf(),
                genres = mutableListOf(),
                publishedYear = null,
                publishedDate = null,
                publisher = null,
                description = null,
                isbn = null,
                asin = null,
                language = null,
                explicit = false,
                authorName = null,
                authorNameLF = null,
                narratorName = null,
                seriesName = null,
                series = series
        )

internal fun book(tracks: MutableList<AudioTrack>? = mutableListOf(), series: List<SeriesType>? = null) =
        Book(
                metadata = bookMetadata(series),
                coverPath = null,
                tags = emptyList(),
                audioFiles = null,
                chapters = null,
                tracks = tracks,
                ebookFile = null,
                size = null,
                duration = null,
                numTracks = tracks?.size
        )

internal fun libraryItem(media: MediaType = book(), mediaType: String = "book") =
        LibraryItem(
                id = "library-item",
                ino = "ino",
                libraryId = "library",
                folderId = "folder",
                path = "/book",
                relPath = "book",
                mtimeMs = 0,
                ctimeMs = 0,
                birthtimeMs = 0,
                addedAt = 0,
                updatedAt = 0,
                lastScan = null,
                scanVersion = null,
                isMissing = false,
                isInvalid = false,
                mediaType = mediaType,
                media = media,
                libraryFiles = null,
                userMediaProgress = null,
                collapsedSeries = null,
                localLibraryItemId = null,
                recentEpisode = null
        )

internal fun playbackSession(
        tracks: MutableList<AudioTrack> = mutableListOf(),
        chapters: List<BookChapter> = emptyList(),
        currentTime: Double = 0.0,
        episodeId: String? = null,
        localEpisodeId: String? = null
) =
        PlaybackSession(
                id = "session",
                userId = "user",
                libraryItemId = "library-item",
                episodeId = episodeId,
                mediaType = if (episodeId == null) "book" else "podcast",
                mediaMetadata = MediaTypeMetadata("Title", false),
                deviceInfo = DeviceInfo("device", "maker", "model", 35, "test"),
                chapters = chapters,
                displayTitle = "Title",
                displayAuthor = "Author",
                coverPath = null,
                duration = tracks.sumOf { it.duration },
                playMethod = 0,
                startedAt = 1,
                updatedAt = 2,
                timeListening = 0,
                audioTracks = tracks,
                currentTime = currentTime,
                libraryItem = null,
                localLibraryItem = null,
                localEpisodeId = localEpisodeId,
                serverConnectionConfigId = "server",
                serverAddress = "https://example.invalid",
                mediaPlayer = "local"
        )

internal fun localLibraryItem(
        id: String = "local-item",
        media: MediaType = book(),
        mediaType: String = "book",
        libraryItemId: String? = "library-item",
        serverUserId: String? = "user",
        coverContentUrl: String? = null
) =
        LocalLibraryItem(
                id = id,
                folderId = "folder",
                basePath = "/local",
                absolutePath = "/local/item",
                contentUrl = "",
                isInvalid = false,
                mediaType = mediaType,
                media = media,
                localFiles = mutableListOf(),
                coverContentUrl = coverContentUrl,
                coverAbsolutePath = null,
                isLocal = true,
                serverConnectionConfigId = "server",
                serverAddress = "https://example.invalid",
                serverUserId = serverUserId,
                libraryItemId = libraryItemId
        )

internal fun localProgress(
        progress: Double = 0.0,
        libraryItemId: String? = "library-item",
        episodeId: String? = null
) =
        LocalMediaProgress(
                id = "progress",
                localLibraryItemId = "local-item",
                localEpisodeId = null,
                duration = 100.0,
                progress = progress,
                currentTime = 0.0,
                isFinished = false,
                ebookLocation = null,
                ebookProgress = null,
                lastUpdate = 0,
                startedAt = 0,
                finishedAt = null,
                serverConnectionConfigId = "server",
                serverAddress = "https://example.invalid",
                serverUserId = "user",
                libraryItemId = libraryItemId,
                episodeId = episodeId
        )
