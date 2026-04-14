package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.media.MediaManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Encapsulates the Android Auto browse-tree async setup so Media3PlaybackService can focus on
 * session management and defer the pull-based loading concerns to this coordinator.
 *
 * A single shared [Deferred] represents the in-flight or completed load; every caller awaits the
 * same instance. On a successful load the deferred is reused (cache). On an unsuccessful load the
 * next request restarts it, so transient server/network failures self-heal.
 */
class Media3AutoLibraryCoordinator(
  private val mediaManager: MediaManager,
  private val browseTree: Media3BrowseTree,
  private val scope: CoroutineScope
) {

    private val loadMutex = Mutex()
    private var loadJob: Deferred<Boolean>? = null

  fun requestChildren(
    parentId: String,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      if (BuildConfig.DEBUG) Log.d(TAG, "requestChildren(parentId=$parentId)")
      return scope.future {
          ensureLoaded()
          val children = browseTree.getChildren(parentId)
          LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
      }
  }

    suspend fun awaitAutoDataLoaded() {
        ensureLoaded()
    }

    private suspend fun ensureLoaded(): Boolean {
        val job = loadMutex.withLock {
            val current = loadJob
            if (current!=null && (!current.isCompleted || current.succeededWith(true))) {
                current
            } else {
                scope.async { loadWithRetries() }.also { loadJob = it }
            }
        }
        return runCatching { job.await() }.getOrDefault(false)
    }

    private suspend fun loadWithRetries(): Boolean {
        repeat(MAX_LOAD_RETRIES) { attempt ->
            val loaded = runCatching {
                withTimeout(LOAD_TIMEOUT_MS) { loadOnce() }
            }.getOrElse { throwable ->
                Log.w(TAG, "loadAutoData attempt ${attempt + 1} failed: ${throwable.message}")
                false
            }
            if (loaded) return true
            if (attempt < MAX_LOAD_RETRIES - 1) delay(RETRY_BASE_DELAY_MS * (attempt + 1))
        }
        Log.w(TAG, "Auto data load failed after $MAX_LOAD_RETRIES attempts; serving available data")
        return false
    }

    private suspend fun loadOnce(): Boolean {
        awaitSuspendCallback { cb -> mediaManager.loadAndroidAutoItems { cb() } }
        browseTree.invalidateSeriesCache()
        if (mediaManager.serverLibraries.isNotEmpty()) {
            awaitCallback { cb -> mediaManager.populatePersonalizedDataForAllLibraries { cb() } }
            awaitCallback { cb -> mediaManager.initializeInProgressItems { cb() } }
    }
        return mediaManager.isAutoDataLoaded
  }

    private suspend fun awaitCallback(op: (cb: () -> Unit) -> Unit) =
        suspendCancellableCoroutine { cont ->
            op { if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun awaitSuspendCallback(op: suspend (cb: () -> Unit) -> Unit) =
        suspendCancellableCoroutine { cont ->
            val job = scope.async {
                runCatching { op { if (cont.isActive) cont.resume(Unit) } }
                    .onFailure { if (cont.isActive) cont.resumeWithException(it) }
            }
            cont.invokeOnCancellation { job.cancel() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<Boolean>.succeededWith(expected: Boolean): Boolean =
        getCompletionExceptionOrNull()==null && getCompleted()==expected

  companion object {
      private const val TAG = "M3AutoLibCoordinator"
      private const val MAX_LOAD_RETRIES = 3
      private const val RETRY_BASE_DELAY_MS = 2_000L
      private const val LOAD_TIMEOUT_MS = 30_000L
  }
}
