package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import com.audiobookshelf.app.media.MediaManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * All callers await one shared [Deferred] to avoid duplicate setup. Successful loads remain
 * cached; failed loads are discarded so a transient server or network failure can recover.
 */
class Media3AutoLibraryCoordinator(
  private val mediaManager: MediaManager,
  private val browseTree: Media3BrowseTree,
  private val scope: CoroutineScope,
  /** Refreshes Auto after an initially offline root omitted server shelves. */
  private val onAutoDataLoaded: () -> Unit = {}
) {

  private val loadMutex = Mutex()
  private var loadJob: Deferred<Boolean>? = null

  fun requestChildren(
    parentId: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return scope.future {
      // Downloads and local podcast containers are served from disk, so they never wait on the
      // server; the root renders immediately and refreshes once the load completes.
      when {
        parentId == Media3BrowseTree.ROOT_ID -> scope.launch { ensureLoaded() }
        parentId == Media3BrowseTree.DOWNLOADS_ID || parentId.startsWith("local_") -> Unit
        else -> ensureLoaded()
      }
      val children = browseTree.getChildren(parentId)
      // Encode artwork only for the page crossing Binder.
      val pagedChildren =
        browseTree.withPagedArtwork(parentId, pageChildren(children, page, pageSize))
      LibraryResult.ofItemList(ImmutableList.copyOf(pagedChildren), params)
    }
  }

  private fun pageChildren(
    children: ImmutableList<MediaItem>,
    page: Int,
    pageSize: Int
  ): List<MediaItem> {
    if (page < 0 || pageSize <= 0) return children
    val start = page.toLong() * pageSize.toLong()
    if (start >= children.size) return emptyList()
    val end = (start + pageSize).coerceAtMost(children.size.toLong()).toInt()
    return children.subList(start.toInt(), end)
  }

  suspend fun awaitAutoDataLoaded() {
    ensureLoaded()
  }

  private suspend fun ensureLoaded(): Boolean {
    val job = loadMutex.withLock {
      val current = loadJob
      if (current != null && (!current.isCompleted || current.succeededWith(true))) {
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
      if (loaded) {
        runCatching { onAutoDataLoaded() }
          .onFailure { Log.w(TAG, "onAutoDataLoaded failed: ${it.message}") }
        return true
      }
      if (attempt < MAX_LOAD_RETRIES - 1) delay(RETRY_BASE_DELAY_MS * (attempt + 1))
    }
    Log.w(TAG, "Auto data load failed after $MAX_LOAD_RETRIES attempts; serving available data")
    return false
  }

  private suspend fun loadOnce(): Boolean {
    awaitSuspendCallback { cb -> mediaManager.loadAndroidAutoItems { cb() } }
    browseTree.invalidateSeriesCache()
    if (mediaManager.serverLibraries.isNotEmpty()) {
      // These populate disjoint state via independent network calls, so run them concurrently
      // rather than serializing two round-trips before browse children can be served.
      listOf(
        scope.async { awaitCallback { cb -> mediaManager.populatePersonalizedDataForAllLibraries { cb() } } },
        scope.async { awaitCallback { cb -> mediaManager.initializeInProgressItems { cb() } } }
      ).awaitAll()
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
    getCompletionExceptionOrNull() == null && getCompleted() == expected

  companion object {
    private const val TAG = "M3AutoLibCoordinator"
    private const val MAX_LOAD_RETRIES = 3
    private const val RETRY_BASE_DELAY_MS = 2_000L
    private const val LOAD_TIMEOUT_MS = 30_000L
  }
}
