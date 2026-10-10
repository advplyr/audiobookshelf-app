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
import com.audiobookshelf.wear.data.MediaKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import java.util.concurrent.TimeUnit

data class ActiveDownload(val key: MediaKey, val title: String, val percent: Int)

sealed interface DownloadState {
  data object None : DownloadState
  data class Running(val percent: Int) : DownloadState
  data class Failed(val message: String) : DownloadState
}

object Downloads {
  private fun workName(key: MediaKey) = "download_${key.key}"

  /** Downloads a book, or a podcast episode when [key] has an episode id. */
  fun start(context: Context, key: MediaKey, title: String) {
    val request = OneTimeWorkRequestBuilder<DownloadWorker>()
      .setInputData(
        workDataOf(
          DownloadWorker.KEY_ITEM_ID to key.itemId,
          DownloadWorker.KEY_EPISODE_ID to key.episodeId,
          DownloadWorker.KEY_TITLE to title
        )
      )
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .addTag(TAG)
      .addTag(ITEM_TAG_PREFIX + key.key)
      .addTag(TITLE_TAG_PREFIX + title)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(workName(key), ExistingWorkPolicy.REPLACE, request)
  }

  fun cancel(context: Context, key: MediaKey) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(key))
  }

  fun state(context: Context, key: MediaKey): Flow<DownloadState> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(key)).map { infos ->
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
    WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map(::toActive)

  /** [active] read once, e.g. for a tile. */
  suspend fun activeNow(context: Context): List<ActiveDownload> =
    toActive(WorkManager.getInstance(context).getWorkInfosByTag(TAG).await())

  private fun toActive(infos: List<WorkInfo>): List<ActiveDownload> =
    infos.filter { !it.state.isFinished }.mapNotNull { info ->
      val key = info.tags.firstOrNull { it.startsWith(ITEM_TAG_PREFIX) }?.removePrefix(ITEM_TAG_PREFIX) ?: return@mapNotNull null
      val title = info.tags.firstOrNull { it.startsWith(TITLE_TAG_PREFIX) }?.removePrefix(TITLE_TAG_PREFIX).orEmpty()
      ActiveDownload(MediaKey.parse(key), title, info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0))
    }

  private const val TAG = "book_download"
  private const val ITEM_TAG_PREFIX = "item:"
  private const val TITLE_TAG_PREFIX = "title:"
}
