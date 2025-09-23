package com.tomesonic.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
// MIGRATION: MediaBrowserServiceCompat → MediaLibraryService
// import android.support.v4.media.MediaBrowserCompat
// import android.support.v4.media.MediaDescriptionCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.util.Log
// import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.tomesonic.app.R
import com.tomesonic.app.data.*
import com.tomesonic.app.device.DeviceManager
import com.tomesonic.app.media.MediaManager
import com.tomesonic.app.media.*
import com.tomesonic.app.media.getUriToAbsIconDrawable
import com.tomesonic.app.media.getUriToDrawable
import com.tomesonic.app.plugins.AbsLogger
import kotlinx.coroutines.runBlocking

class MediaBrowserManager(
    private val service: PlayerNotificationService,
    private val mediaManager: MediaManager,
    private val networkConnectivityManager: NetworkConnectivityManager,
    private val ctx: Context
) {
    private val tag = "MediaBrowserManager"

    // Helper function to determine if a book should be browsable (has chapters)
    private fun shouldBookBeBrowsable(libraryItem: LibraryItem): Boolean {
        return libraryItem.mediaType == "book" &&
               (libraryItem.media as? Book)?.chapters?.isNotEmpty() == true
    }

    // Helper function for local library items
    private fun shouldLocalBookBeBrowsable(localLibraryItem: LocalLibraryItem): Boolean {
        return localLibraryItem.mediaType == "book" &&
               (localLibraryItem.media as? Book)?.chapters?.isNotEmpty() == true
    }

    // Helper function to format duration in seconds to readable format
    private fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    // Constants
    companion object {
        const val AUTO_MEDIA_ROOT = "/"
        const val LIBRARIES_ROOT = "__LIBRARIES__"
        const val RECENTLY_ROOT = "__RECENTLY__"
        const val DOWNLOADS_ROOT = "__DOWNLOADS__"
        const val CONTINUE_ROOT = "__CONTINUE__"
        const val LOCAL_ROOT = "__LOCAL__"

        // Android Auto package names for MediaBrowser validation
        private const val ANDROID_AUTO_PKG_NAME = "com.google.android.projection.gearhead"
        private const val ANDROID_AUTO_SIMULATOR_PKG_NAME = "com.google.android.projection.gearhead.emulator"
        private const val ANDROID_WEARABLE_PKG_NAME = "com.google.android.wearable.app"
        private const val ANDROID_GSEARCH_PKG_NAME = "com.google.android.googlequicksearchbox"
        private const val ANDROID_AUTOMOTIVE_PKG_NAME = "com.google.android.projection.gearhead.phone"

        private val VALID_MEDIA_BROWSERS = setOf(
            "com.tomesonic.app",
            "com.tomesonic.app.debug",
            ANDROID_AUTO_PKG_NAME,
            ANDROID_AUTO_SIMULATOR_PKG_NAME,
            ANDROID_WEARABLE_PKG_NAME,
            ANDROID_GSEARCH_PKG_NAME,
            ANDROID_AUTOMOTIVE_PKG_NAME
        )
    }
    private var forceReloadingAndroidAuto: Boolean = false
    private var cacheResetInProgress: Boolean = false // Prevent multiple cache resets during connection
    private var firstLoadDone: Boolean = false
    private var cachedSearch: String = ""
    // MIGRATION: Update cached search results to Media3 MediaItem
    private var cachedSearchResults: MutableList<MediaItem> = mutableListOf()
    private lateinit var browseTree: BrowseTree

    // Only allowing android auto or similar to access media browser service
    //  normal loading of audiobooks is handled in webview (not natively)
    fun isValid(packageName: String, uid: Int): Boolean {
        Log.d(tag, "AABrowser: Checking if package $packageName (uid: $uid) is valid for media browser")
        if (!VALID_MEDIA_BROWSERS.contains(packageName)) {
            Log.d(tag, "AABrowser: Package $packageName not in valid list: $VALID_MEDIA_BROWSERS")
            return false
        }
        Log.d(tag, "AABrowser: Package $packageName is valid")
        return true
    }

    fun initializeBrowseTree() {
        if (::browseTree.isInitialized) return

        val itemsInProgress = mediaManager.serverItemsInProgress
        val libraries = mediaManager.serverLibraries
        val recentsLoaded = mediaManager.hasRecentShelvesLoaded()

        browseTree = BrowseTree(ctx, itemsInProgress, libraries, recentsLoaded)
        Log.d(tag, "AABrowser: BrowseTree initialized")
    }

    fun isBrowseTreeInitialized(): Boolean {
        return ::browseTree.isInitialized
    }

    // MIGRATION: MediaBrowserServiceCompat → MediaLibraryService
    fun onGetLibraryRoot(
        clientPackageName: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.d(tag, "AALibrary: MediaBrowserManager.onGetLibraryRoot called for $clientPackageName")
        // Verify that the specified package is allowed to access your content
        return if (!isValid(clientPackageName, 0)) { // MediaLibraryService doesn't provide clientUid
            // No further calls will be made to other media browsing methods.
            Log.d(tag, "AALibrary: Client $clientPackageName not allowed to access media browser")
            Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))
        } else {
            Log.d(tag, "AALibrary: Client $clientPackageName allowed, proceeding with onGetLibraryRoot")
            AbsLogger.info(tag, "AALibrary: clientPackageName: $clientPackageName")
            PlayerNotificationService.isStarted = true

            // Reset cache if no longer connected to server or server changed
            if (mediaManager.checkResetServerItems() && !cacheResetInProgress) {
                Log.d(tag, "AALibrary: checkResetServerItems returned true and no cache reset in progress, forcing reload")
                AbsLogger.info(tag, "AALibrary: Reset Android Auto server items cache (${DeviceManager.serverConnectionConfigString})")
                forceReloadingAndroidAuto = true
                firstLoadDone = false // Reset firstLoadDone when server items are reset
                networkConnectivityManager.setFirstLoadDone(false) // Sync with NetworkConnectivityManager
                cacheResetInProgress = true // Prevent further cache resets during this connection

                // Trigger refresh to ensure service is ready
                Handler(Looper.getMainLooper()).post {
                    AbsLogger.info(tag, "onGetLibraryRoot: Triggering Android Auto refresh after cache reset")
                    // MIGRATION: MediaLibraryService uses session.notifyChildrenChanged
                    // service.notifyChildrenChanged(AUTO_MEDIA_ROOT)
                }
            } else if (cacheResetInProgress) {
                Log.d(tag, "AALibrary: Cache reset already in progress, skipping additional reset")
            }

            service.isAndroidAuto = true

            // Create root media item for MediaLibraryService
            val rootMediaItem = MediaItem.Builder()
                .setMediaId(AUTO_MEDIA_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Audiobookshelf")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()

            Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, params))
        }
    }

    // MIGRATION: MediaBrowserServiceCompat → MediaLibraryService
    fun onGetChildren(
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        AbsLogger.info(tag, "onGetChildren: parentId: $parentId (${DeviceManager.serverConnectionConfigString})")

        return try {
            // Initialize browse tree if not already done
            initializeBrowseTree()

            val children = when (parentId) {
                AUTO_MEDIA_ROOT -> {
                    // Return root categories
                    val rootItems = mutableListOf<MediaItem>()

                    // Continue reading root
                    rootItems.add(
                        MediaItem.Builder()
                            .setMediaId(CONTINUE_ROOT)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Continue Listening")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                    .setArtworkUri(getUriToDrawable(ctx, R.drawable.ic_play))
                                    .build()
                            )
                            .build()
                    )

                    // Recent root
                    rootItems.add(
                        MediaItem.Builder()
                            .setMediaId(RECENTLY_ROOT)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Recently Added")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                    .setArtworkUri(getUriToDrawable(ctx, R.drawable.ic_recent))
                                    .build()
                            )
                            .build()
                    )

                    // Local books root
                    rootItems.add(
                        MediaItem.Builder()
                            .setMediaId(LOCAL_ROOT)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Local Books")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                    .setArtworkUri(getUriToDrawable(ctx, R.drawable.ic_download))
                                    .build()
                            )
                            .build()
                    )

                    // Libraries root
                    rootItems.add(
                        MediaItem.Builder()
                            .setMediaId(LIBRARIES_ROOT)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Libraries")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                    .setArtworkUri(getUriToDrawable(ctx, R.drawable.ic_library))
                                    .build()
                            )
                            .build()
                    )

                    rootItems
                }
                LIBRARIES_ROOT -> {
                    // Return list of libraries
                    mediaManager.serverLibraries.map { library ->
                        library.getMediaItem(ctx)
                    }.toMutableList()
                }
                CONTINUE_ROOT -> {
                    // Return items in progress
                    Log.d(tag, "Getting continue items: ${mediaManager.serverItemsInProgress.size} items")
                    mediaManager.serverItemsInProgress.map { itemInProgress ->
                        val libraryItem = itemInProgress.libraryItemWrapper as LibraryItem
                        val isBrowsable = shouldBookBeBrowsable(libraryItem)

                        // Create MediaItem for item in progress
                        MediaItem.Builder()
                            .setMediaId(libraryItem.id)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(libraryItem.title ?: "Unknown Title")
                                    .setArtist(libraryItem.authorName ?: "Unknown Author")
                                    .setIsPlayable(!isBrowsable)
                                    .setIsBrowsable(isBrowsable)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                    .build()
                            )
                            .build()
                    }.toMutableList()
                }
                RECENTLY_ROOT -> {
                    // Return recent items from cached library recent shelves
                    val recentItems = mutableListOf<MediaItem>()
                    Log.d(tag, "Getting recent items from ${mediaManager.getAllCachedLibraryRecentShelves().size} libraries")
                    mediaManager.getAllCachedLibraryRecentShelves().values.forEach { shelves ->
                        shelves.forEach { shelf ->
                            when (shelf) {
                                is LibraryShelfBookEntity -> {
                                    shelf.entities?.forEach { libraryItem ->
                                        val mediaItem = MediaItem.Builder()
                                            .setMediaId(libraryItem.id)
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(libraryItem.title ?: "Unknown Title")
                                                    .setArtist(libraryItem.authorName ?: "Unknown Author")
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                                    .build()
                                            )
                                            .build()
                                        recentItems.add(mediaItem)
                                    }
                                }
                                is LibraryShelfPodcastEntity -> {
                                    shelf.entities?.forEach { libraryItem ->
                                        val mediaItem = MediaItem.Builder()
                                            .setMediaId(libraryItem.id)
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(libraryItem.title ?: "Unknown Title")
                                                    .setArtist(libraryItem.authorName ?: "Unknown Author")
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                                    .build()
                                            )
                                            .build()
                                        recentItems.add(mediaItem)
                                    }
                                }
                                // Handle other shelf types if needed
                                else -> {
                                    // Skip other types for now
                                }
                            }
                        }
                    }
                    Log.d(tag, "Found ${recentItems.size} recent items")
                    recentItems
                }
                LOCAL_ROOT -> {
                    // Return local books
                    val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
                    Log.d(tag, "Getting local books: ${localBooks.size} items")
                    localBooks.map { localLibraryItem ->
                        val isBrowsable = shouldLocalBookBeBrowsable(localLibraryItem)

                        MediaItem.Builder()
                            .setMediaId(localLibraryItem.id)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(localLibraryItem.media.metadata.title ?: "Unknown Title")
                                    .setArtist(localLibraryItem.authorName ?: "Unknown Author")
                                    .setIsPlayable(!isBrowsable)
                                    .setIsBrowsable(isBrowsable)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                    .build()
                            )
                            .build()
                    }.toMutableList()
                }
                DOWNLOADS_ROOT -> {
                    // Return downloaded items - for now return empty list
                    // TODO: Implement downloaded items browsing
                    mutableListOf()
                }
                else -> {
                    // Check if parentId is a library ID
                    val library = mediaManager.serverLibraries.find { it.id == parentId }
                    if (library != null) {
                        // Return books in this library from cached discovery
                        val libraryItems = mediaManager.getCachedLibraryDiscoveryItems(library.id)
                        Log.d(tag, "Getting library items for ${library.name}: ${libraryItems.size} items")
                        libraryItems.map { libraryItem ->
                            val isBrowsable = shouldBookBeBrowsable(libraryItem)
                            MediaItem.Builder()
                                .setMediaId(libraryItem.id)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(libraryItem.title ?: "Unknown Title")
                                        .setArtist(libraryItem.authorName ?: "Unknown Author")
                                        .setIsPlayable(!isBrowsable)
                                        .setIsBrowsable(isBrowsable)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                        .build()
                                )
                                .build()
                        }.toMutableList()
                    } else {
                        // Check if parentId is a local book ID with chapters
                        val localBook = DeviceManager.dbManager.getLocalLibraryItem(parentId)
                        if (localBook != null && shouldLocalBookBeBrowsable(localBook)) {
                            // Return chapters for this local book
                            val book = localBook.media as Book
                            Log.d(tag, "Getting chapters for local book: ${localBook.title} (${book.chapters?.size ?: 0} chapters)")
                            book.chapters?.mapIndexed { index, chapter ->
                                MediaItem.Builder()
                                    .setMediaId("${localBook.id}_chapter_$index")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(chapter.title ?: "Chapter ${index + 1}")
                                            .setArtist(localBook.authorName ?: "Unknown Author")
                                            .setAlbumTitle(localBook.title ?: "Unknown Book")
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                                            .build()
                                    )
                                    .build()
                            }?.toMutableList() ?: mutableListOf()
                        } else {
                            // Check if parentId matches any cached server library items
                            var foundServerBook: LibraryItem? = null

                            // Search through all cached library discovery items
                            for (library in mediaManager.serverLibraries) {
                                val libraryItems = mediaManager.getCachedLibraryDiscoveryItems(library.id)
                                foundServerBook = libraryItems.find { it.id == parentId }
                                if (foundServerBook != null) break
                            }

                            // Also check items in progress
                            if (foundServerBook == null) {
                                foundServerBook = mediaManager.serverItemsInProgress
                                    .map { it.libraryItemWrapper as LibraryItem }
                                    .find { it.id == parentId }
                            }

                            if (foundServerBook != null && shouldBookBeBrowsable(foundServerBook)) {
                                val book = foundServerBook.media as Book
                                Log.d(tag, "Getting chapters for server book: ${foundServerBook.media?.metadata?.title} (${book.chapters?.size ?: 0} chapters)")
                                book.chapters?.mapIndexed { index, chapter ->
                                    MediaItem.Builder()
                                        .setMediaId("${foundServerBook.id}_chapter_$index")
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(chapter.title ?: "Chapter ${index + 1}")
                                                .setArtist(foundServerBook.authorName ?: "Unknown Author")
                                                .setAlbumTitle(foundServerBook.media?.metadata?.title ?: "Unknown Book")
                                                .setIsPlayable(true)
                                                .setIsBrowsable(false)
                                                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                                                .build()
                                        )
                                        .build()
                                }?.toMutableList() ?: mutableListOf()
                            } else {
                                // Unknown parent ID
                                Log.w(tag, "Unknown parentId: $parentId")
                                mutableListOf()
                            }
                        }
                    }
                }
            }

            Log.d(tag, "Returning ${children.size} children for parentId: $parentId")
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        } catch (e: Exception) {
            Log.e(tag, "Error in onGetChildren for parentId: $parentId", e)
            Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
        }
    }

    // MIGRATION: onSearch - updated to MediaLibraryService pattern
    fun onSearch(
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        // MIGRATION-TODO: Convert search implementation to Media3 patterns
        AbsLogger.info(tag, "onSearch: query: $query")
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    fun onGetSearchResult(
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        // MIGRATION-TODO: Convert search result implementation to Media3 patterns
        AbsLogger.info(tag, "onGetSearchResult: query: $query")
        val emptyList = ImmutableList.of<MediaItem>()
        return Futures.immediateFuture(LibraryResult.ofItemList(emptyList, params))
    }

    // Helper method for Android Auto reloading
    fun forceReload() {
        forceReloadingAndroidAuto = true
        firstLoadDone = false
        cacheResetInProgress = true
    }

}
