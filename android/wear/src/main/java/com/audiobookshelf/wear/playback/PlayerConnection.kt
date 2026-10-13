package com.audiobookshelf.wear.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.data.BookDetail
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Connects the UI to [PlaybackService]. */
class PlayerConnection(private val context: Context) {
  private val app = context.applicationContext as WearApp
  private var future: ListenableFuture<MediaController>? = null
  private val _controller = MutableStateFlow<MediaController?>(null)
  val controller: StateFlow<MediaController?> = _controller.asStateFlow()

  fun connect() {
    if (future != null) return
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    future = MediaController.Builder(context, token).buildAsync().also { f ->
      f.addListener({
        try {
          _controller.value = f.get()
        } catch (e: Exception) {
          Log.e(TAG, "Could not connect to player", e)
        }
      }, MoreExecutors.directExecutor())
    }
  }

  fun release() {
    future?.let(MediaController::releaseFuture)
    future = null
    _controller.value = null
  }

  /**
   * Plays a book, preferring the downloaded copy. Resumes from whichever is newer: the position
   * saved on the watch or the server's progress (which includes listening done on the phone).
   */
  suspend fun play(book: BookDetail) {
    val current = NowPlaying.book.value
    if (current?.book?.mediaKey == book.mediaKey) {
      controller.value?.play()
      return
    }

    val local = app.downloads.get(book.mediaKey)
    val startTime = resumeTime(book)
    val playing = PlayingBook(local ?: book, isLocal = local != null, sessionId = UUID.randomUUID().toString())
    connect()
    // The service exists once the controller is connected
    withTimeoutOrNull(CONNECT_TIMEOUT_MS) { controller.first { it != null } } ?: throw IllegalStateException("Player unavailable")
    PlaybackService.current?.load(playing, startTime)
  }

  private suspend fun resumeTime(book: BookDetail): Double {
    // Send listening done offline first so the server's progress is current
    app.sessionSync.flushPending()
    val local = app.progress.get(book.mediaKey)
    val server = try {
      app.api.progress(book.mediaKey)
    } catch (e: Exception) {
      Log.i(TAG, "Server progress unavailable, using watch progress: ${e.message}")
      null
    }
    val time = if (server != null && (local == null || server.lastUpdate > local.updatedAt)) {
      if (server.isFinished) 0.0 else server.currentTime
    } else {
      local?.currentTime ?: 0.0
    }
    // Start over instead of at the very end of a finished book
    return if (time >= book.duration - 5) 0.0 else time
  }

  companion object {
    private const val TAG = "PlayerConnection"
    private const val CONNECT_TIMEOUT_MS = 10_000L
  }
}
