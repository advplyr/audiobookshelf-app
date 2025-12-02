package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import com.audiobookshelf.app.media.MediaManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
  private var isAutoDataLoading = false

  fun requestChildren(
    parentId: String,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    Log.d(TAG, "requestChildren(parentId=$parentId, params=$params)")
    val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    if (!needsInitialLoad(parentId)) {
      fulfillRequest(parentId, params, future)
      return future
    }

    pendingRequests.add(PendingRequest(future, parentId, params))
    if (!isAutoDataLoading) {
      loadAutoData()
    }
    return future
  }

  private fun needsInitialLoad(parentId: String): Boolean {
    return parentId == Media3BrowseTree.ROOT_ID && !mediaManager.isAutoDataLoaded
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
    Log.d(TAG, "loadAutoData() start")
    isAutoDataLoading = true
    mediaManager.loadAndroidAutoItems {
      // Warm in-progress cache so Continue Listening has data (matches ExoPlayer flow).
      mediaManager.initializeInProgressItems {
        Log.d(TAG, "loadAutoData() completed, fulfilling ${pendingRequests.size} pending requests")
        isAutoDataLoading = false
        val requestsToFulfill = pendingRequests.toList()
        pendingRequests.clear()
        scope.launch {
          requestsToFulfill.forEach { request ->
            try {
              val children = browseTree.getChildren(request.parentId)
              request.future.set(
                LibraryResult.ofItemList(
                  ImmutableList.copyOf(children),
                  request.params
                )
              )
            } catch (t: Throwable) {
              request.future.setException(t)
            }
          }
        }
      }
    }
  }

  companion object {
    private val TAG = Media3AutoLibraryCoordinator::class.java.simpleName
  }
}
