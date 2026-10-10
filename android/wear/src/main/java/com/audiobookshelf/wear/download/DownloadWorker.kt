package com.audiobookshelf.wear.download

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.audiobookshelf.wear.R
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.data.BookDetail
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.data.Track
import com.audiobookshelf.wear.sync.DirectFirst
import com.audiobookshelf.wear.tile.TileRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Downloads every audio file of a book or podcast episode (plus its cover) to watch storage for offline listening. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val app = context.applicationContext as WearApp
  private val itemId = params.inputData.getString(KEY_ITEM_ID)!!
  private val key = MediaKey(itemId, params.inputData.getString(KEY_EPISODE_ID))
  private val title = params.inputData.getString(KEY_TITLE).orEmpty()
  private val notificationId = key.key.hashCode()

  override suspend fun getForegroundInfo() = foregroundInfo(0)

  override suspend fun doWork(): Result {
    try {
      setForeground(foregroundInfo(0))
    } catch (e: IllegalStateException) {
      // Android 12+ blocks starting a foreground service while the app is in the background (e.g. a retry)
      Log.w(TAG, "Downloading without a foreground notification: ${e.message}")
    }

    val book = try {
      app.api.playable(key)
    } catch (e: Exception) {
      Log.e(TAG, "Could not load ${key.key}", e)
      return retryOrFail("Could not reach server")
    }
    if (book.tracks.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "No audio files"))

    val store = app.downloads
    val dir = store.bookDir(key).apply { mkdirs() }
    val remainingBytes = book.tracks.sumOf { track ->
      val file = store.trackFile(key, track)
      if (file.length() == track.size) 0L else track.size - partFile(file).length()
    }
    if (remainingBytes > store.freeBytes() - FREE_SPACE_MARGIN) {
      return Result.failure(workDataOf(KEY_ERROR to "Not enough storage"))
    }

    TileRefresher.downloadsChanged(applicationContext)
    val fastNetwork = FastNetwork.acquire(applicationContext)
    val client = fastNetwork?.bind(app.api.client) ?: app.api.client
    // With Wi-Fi or LTE brought up for this download, try it before relaying through the phone
    directFirst = fastNetwork != null
    try {
      downloadCover(client, store.coverFile(key))
      downloadTracks(client, book)
      store.markComplete(book)
      return Result.success()
    } catch (e: IOException) {
      Log.e(TAG, "Download failed for ${key.key}", e)
      return retryOrFail(e.message ?: "Download failed")
    } finally {
      fastNetwork?.release()
      // Keep partial files when the system pauses the download so it can resume, but not when the user cancels
      if (isStopped && wasCancelled() && !store.isDownloaded(key)) dir.deleteRecursively()
    }
  }

  private suspend fun downloadTracks(client: OkHttpClient, book: BookDetail) = withContext(Dispatchers.IO) {
    val totalBytes = book.totalSize.coerceAtLeast(1)
    var completedBytes = 0L
    var lastReported = -1

    for (track in book.tracks) {
      val file = app.downloads.trackFile(key, track)
      if (file.exists() && file.length() == track.size) {
        completedBytes += track.size
        continue
      }
      downloadTrack(client, track, file) { trackBytes ->
        val percent = ((completedBytes + trackBytes) * 100 / totalBytes).toInt()
        if (percent != lastReported) {
          // The tile shows progress in coarse steps
          if (percent / 10 != lastReported / 10) TileRefresher.downloadsChanged(applicationContext)
          lastReported = percent
          setProgressAsync(workDataOf(KEY_PROGRESS to percent))
          notify(percent)
        }
      }
      completedBytes += track.size
    }
  }

  private suspend fun downloadTrack(client: OkHttpClient, track: Track, file: File, onBytes: (Long) -> Unit) {
    val part = partFile(file)
    val resumeFrom = part.length()
    val request = Request.Builder()
      .url(app.api.absoluteUrl(track.contentUrl))
      .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
      .tag(DirectFirst::class.java, if (directFirst) DirectFirst else null)
      .build()

    withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
        // Server ignored the range request, so start over
        val append = resumeFrom > 0 && response.code == 206
        var written = if (append) resumeFrom else 0L
        val body = response.body ?: throw IOException("Empty response")
        FileOutputStream(part, append).use { out ->
          body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              ensureActive()
              val read = input.read(buffer)
              if (read == -1) break
              out.write(buffer, 0, read)
              written += read
              onBytes(written)
            }
          }
        }
      }
    }
    if (!part.renameTo(file)) throw IOException("Could not save ${file.name}")
  }

  private suspend fun downloadCover(client: OkHttpClient, file: File) = withContext(Dispatchers.IO) {
    try {
      client.newCall(Request.Builder().url(app.api.coverUrl(itemId, 400)).tag(DirectFirst::class.java, if (directFirst) DirectFirst else null).build()).execute().use { response ->
        if (response.isSuccessful) response.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
      }
    } catch (e: IOException) {
      Log.w(TAG, "Cover download failed", e)
    }
  }

  private fun wasCancelled() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

  private var directFirst = false

  private fun partFile(file: File) = File(file.path + ".part")

  private fun retryOrFail(message: String): Result =
    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to message))

  private fun notification(percent: Int): Notification =
    NotificationCompat.Builder(applicationContext, WearApp.DOWNLOAD_CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title.ifEmpty { "Downloading" })
      .setContentText("$percent%")
      .setProgress(100, percent, false)
      .setOngoing(true)
      .setSilent(true)
      .build()

  private fun notify(percent: Int) {
    applicationContext.getSystemService(NotificationManager::class.java).notify(notificationId, notification(percent))
  }

  private fun foregroundInfo(percent: Int) =
    ForegroundInfo(notificationId, notification(percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

  companion object {
    private const val TAG = "DownloadWorker"
    private const val MAX_ATTEMPTS = 3
    private const val FREE_SPACE_MARGIN = 200L * 1024 * 1024
    const val KEY_ITEM_ID = "itemId"
    const val KEY_EPISODE_ID = "episodeId"
    const val KEY_TITLE = "title"
    const val KEY_PROGRESS = "progress"
    const val KEY_ERROR = "error"
  }
}
