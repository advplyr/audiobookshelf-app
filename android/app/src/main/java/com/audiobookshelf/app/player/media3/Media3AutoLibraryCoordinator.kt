package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.media.MediaManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Encapsulates the Android Auto browse-tree async setup so Media3PlaybackService can focus on
 * session management and defer the pull-based loading concerns to this coordinator.
 */
class Media3AutoLibraryCoordinator(
  private val mediaManager: MediaManager,
  private val browseTree: Media3BrowseTree,
  private val scope: CoroutineScope
) {

  private data class PendingRequest(
    val future: SettableFuture<LibraryResult<ImmutableList<MediaItem>>>,
    val parentId: String,
    val params: MediaLibraryService.LibraryParams?
  )

  private val pendingRequests = mutableListOf<PendingRequest>()

    @Volatile
  private var isAutoDataLoading = false

    @Volatile
    private var isFullyLoaded = false
  private var autoDataLoadedDeferred = CompletableDeferred<Unit>()
    private var loadAttemptCount = 0

  fun requestChildren(
    parentId: String,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      if (BuildConfig.DEBUG) Log.d(TAG, "requestChildren(parentId=$parentId, params=$params)")
    val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
      if (isFullyLoaded) {
      fulfillRequest(parentId, params, future)
      return future
    }

      synchronized(pendingRequests) {
          pendingRequests.add(PendingRequest(future, parentId, params))
      }
    if (!isAutoDataLoading) {
      loadAutoData()
    }
    return future
  }

  private fun fulfillRequest(
    parentId: String,
    params: MediaLibraryService.LibraryParams?,
    future: SettableFuture<LibraryResult<ImmutableList<MediaItem>>>
  ) {
    scope.launch {
      try {
        val children = browseTree.getChildren(parentId)
        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
      } catch (t: Throwable) {
        future.setException(t)
      }
    }
  }

  private fun loadAutoData() {
      if (BuildConfig.DEBUG) Log.d(TAG, "loadAutoData() start (attempt=${loadAttemptCount + 1})")
    isAutoDataLoading = true
    autoDataLoadedDeferred = CompletableDeferred()
      loadAttemptCount++
      scope.launch {
          try {
              withTimeout(LOAD_TIMEOUT_MS) {
          mediaManager.loadAndroidAutoItems {
              browseTree.invalidateSeriesCache()
              if (mediaManager.serverLibraries.isEmpty()) {
                  onLoadFinished()
              } else {
              mediaManager.populatePersonalizedDataForAllLibraries {
                  mediaManager.initializeInProgressItems {
                      onLoadFinished()
                  }
              }
              }
          }
              }
          } catch (e: Exception) {
              Log.w(TAG, "loadAutoData() timed out or failed: ${e.message}")
              onLoadFinished()
          }
      }
  }

    private fun onLoadFinished() {
        val dataLoaded = mediaManager.isAutoDataLoaded
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "loadAutoData() finished, dataLoaded=$dataLoaded, pendingRequests=${pendingRequests.size}")
        }
        isAutoDataLoading = false
        if (dataLoaded) {
            isFullyLoaded = true
        }
        if (!autoDataLoadedDeferred.isCompleted) {
            autoDataLoadedDeferred.complete(Unit)
        }

        if (dataLoaded) {
            loadAttemptCount = 0
            drainPendingRequests()
        } else if (loadAttemptCount < MAX_LOAD_RETRIES) {
            val delayMs = RETRY_BASE_DELAY_MS * loadAttemptCount
            if (BuildConfig.DEBUG) Log.d(TAG, "Auto data not loaded, retrying in ${delayMs}ms")
            scope.launch {
                delay(delayMs)
                if (!isFullyLoaded) {
                    loadAutoData()
                }
            }
        } else {
            // Exhausted retries — fulfill pending requests with whatever we have
            // (downloads are still available without server connection)
            Log.w(TAG, "Auto data load failed after $MAX_LOAD_RETRIES attempts, fulfilling with available data")
            loadAttemptCount = 0
            drainPendingRequests()
        }
    }

    private fun drainPendingRequests() {
        val requestsToFulfill = synchronized(pendingRequests) {
            val snapshot = pendingRequests.toList()
            pendingRequests.clear()
            snapshot
        }
        if (requestsToFulfill.isEmpty()) return
        scope.launch {
            requestsToFulfill.forEach { request ->
                try {
                    val children = browseTree.getChildren(request.parentId)
                    request.future.set(
                        LibraryResult.ofItemList(ImmutableList.copyOf(children), request.params)
                    )
                } catch (t: Throwable) {
                    request.future.setException(t)
        }
      }
    }
  }

  suspend fun awaitAutoDataLoaded() {
      if (isFullyLoaded) return
    if (!isAutoDataLoading) {
      loadAutoData()
    }
      if (isFullyLoaded) return
    autoDataLoadedDeferred.await()
  }

  companion object {
      private const val TAG = "M3AutoLibCoordinator"
      private const val MAX_LOAD_RETRIES = 3
      private const val RETRY_BASE_DELAY_MS = 2_000L
      private const val LOAD_TIMEOUT_MS = 30_000L
  }
}
