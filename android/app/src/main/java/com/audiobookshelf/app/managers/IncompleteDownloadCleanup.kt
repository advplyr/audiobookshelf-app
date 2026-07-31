package com.audiobookshelf.app.managers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.models.DownloadItem
import java.io.File
import java.util.concurrent.TimeUnit

/** Removes terminally failed downloads after their retention window elapses. */
object IncompleteDownloadCleanup {
  private const val tag = "IncompleteDownloadCleanup"
  private const val RETENTION_MS = 24L * 60L * 60L * 1000L
  private const val WORK_PREFIX = "incomplete-download-"

  fun schedule(context: Context, item: DownloadItem) {
    val failedAt = item.terminalFailureAt ?: return
    val delay = (failedAt + RETENTION_MS - System.currentTimeMillis()).coerceAtLeast(0L)
    val request = OneTimeWorkRequestBuilder<IncompleteDownloadCleanupWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_PREFIX + item.id, ExistingWorkPolicy.REPLACE, request)
  }

  fun cancel(context: Context, itemId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + itemId)
  }

  /** Removes failures retained longer than 24 hours when scheduled work did not run. */
  fun cleanupExpired(context: Context) {
    val now = System.currentTimeMillis()
    DeviceManager.dbManager.getDownloadItems()
            .filter { item -> isEligible(item, now) }
            .forEach { item ->
              deleteItem(context, item)
            }
  }

  private fun isEligible(item: DownloadItem, now: Long): Boolean {
    val failedAt = item.terminalFailureAt ?: return false
    if (now - failedAt < RETENTION_MS) return false
    return item.downloadItemParts.all { part ->
      part.moved || (part.failed && !part.isMoving)
    }
  }

  private fun deleteItem(context: Context, item: DownloadItem) {
    item.downloadItemParts.forEach { part ->
      deleteAppOwnedFile(context, File(part.destinationPath))
      if (part.isInternalStorage && part.moved) {
        deleteAppOwnedFile(context, File(part.finalDestinationPath))
      } else if (!part.isInternalStorage && part.moved) {
        part.completedDestinationUri?.let { uriString ->
          try {
            DocumentFile.fromSingleUri(context, Uri.parse(uriString))?.delete()
          } catch (e: Exception) {
            Log.w(tag, "Could not delete expired SAF document for ${part.filename}", e)
          }
        }
      }
    }
    DeviceManager.dbManager.removeDownloadItem(item.id)
    cancel(context, item.id)
    Log.i(tag, "Deleted terminally failed download item ${item.id}")
  }

  private fun deleteAppOwnedFile(context: Context, file: File) {
    val path = file.absolutePath
    val internal = context.filesDir.absolutePath
    val external = context.getExternalFilesDir(null)?.absolutePath
    if (path.startsWith(internal) || (external != null && path.startsWith(external))) {
      if (file.exists() && !file.delete()) Log.w(tag, "Could not delete expired staging file $path")
      file.parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
    } else {
      Log.w(tag, "Refusing to delete non-app-owned path $path")
    }
  }
}

class IncompleteDownloadCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    DbManager.initialize(applicationContext)
    IncompleteDownloadCleanup.cleanupExpired(applicationContext)
    return Result.success()
  }
}
