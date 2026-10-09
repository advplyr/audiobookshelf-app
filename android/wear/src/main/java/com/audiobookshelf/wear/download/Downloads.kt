package com.audiobookshelf.wear.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

data class ActiveDownload(val itemId: String, val title: String, val percent: Int)

sealed interface DownloadState {
  data object None : DownloadState
  data class Running(val percent: Int) : DownloadState
  data class Failed(val message: String) : DownloadState
}

object Downloads {
  private fun workName(itemId: String) = "download_$itemId"

  fun start(context: Context, itemId: String, title: String) {
    val request = OneTimeWorkRequestBuilder<DownloadWorker>()
      .setInputData(workDataOf(DownloadWorker.KEY_ITEM_ID to itemId, DownloadWorker.KEY_TITLE to title))
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .addTag(TAG)
      .addTag(ITEM_TAG_PREFIX + itemId)
      .addTag(TITLE_TAG_PREFIX + title)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.REPLACE, request)
  }

  fun cancel(context: Context, itemId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(itemId))
  }

  fun state(context: Context, itemId: String): Flow<DownloadState> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(itemId)).map { infos ->
      val info = infos.firstOrNull() ?: return@map DownloadState.None
      when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Running(0)
        WorkInfo.State.RUNNING -> DownloadState.Running(info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0))
        WorkInfo.State.FAILED -> DownloadState.Failed(info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Download failed")
        else -> DownloadState.None
      }
    }

  /** Downloads that are queued or in progress. */
  fun active(context: Context): Flow<List<ActiveDownload>> =
    WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
      infos.filter { !it.state.isFinished }.mapNotNull { info ->
        val itemId = info.tags.firstOrNull { it.startsWith(ITEM_TAG_PREFIX) }?.removePrefix(ITEM_TAG_PREFIX) ?: return@mapNotNull null
        val title = info.tags.firstOrNull { it.startsWith(TITLE_TAG_PREFIX) }?.removePrefix(TITLE_TAG_PREFIX).orEmpty()
        ActiveDownload(itemId, title, info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0))
      }
    }

  private const val TAG = "book_download"
  private const val ITEM_TAG_PREFIX = "item:"
  private const val TITLE_TAG_PREFIX = "title:"
}
