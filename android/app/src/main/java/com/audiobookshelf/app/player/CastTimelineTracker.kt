/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.audiobookshelf.app.player

import android.util.SparseArray
import com.google.android.exoplayer2.C
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * Creates [CastTimelines][CastTimeline] from cast receiver app status updates.
 *
 *
 * This class keeps track of the duration reported by the current item to fill any missing
 * durations in the media queue items [See internal: b/65152553].
 */
/* package */
class CastTimelineTracker {
  private val itemIdToData: SparseArray<CastTimeline.ItemData>

  /**
   * Returns a [CastTimeline] that represents the state of the given `remoteMediaClient`.
   *
   *
   * Returned timelines may contain values obtained from `remoteMediaClient` in previous
   * invocations of this method.
   *
   * @param remoteMediaClient The Cast media client.
   * @return A [CastTimeline] that represents the given `remoteMediaClient` status.
   */
  fun getCastTimeline(remoteMediaClient: RemoteMediaClient): CastTimeline {
    val itemIds = remoteMediaClient.mediaQueue.itemIds
    if (itemIds.size > 0) {
      // Only remove unused items when there is something in the queue to avoid removing all entries
      // if the remote media client clears the queue temporarily. See [Internal ref: b/128825216].
      removeUnusedItemDataEntries(itemIds)
    }

    // TODO: Reset state when the app instance changes [Internal ref: b/129672468].
    val mediaStatus = remoteMediaClient.mediaStatus
      ?: return CastTimeline.EMPTY_CAST_TIMELINE
    val currentItemId = mediaStatus.currentItemId
    updateItemData(
      currentItemId, mediaStatus.mediaInfo,  /* defaultPositionUs= */C.TIME_UNSET)
    for (item in mediaStatus.queueItems) {
      val defaultPositionUs = (item.startTime * C.MICROS_PER_SECOND).toLong()
      updateItemData(item.itemId, item.media, defaultPositionUs)
    }
    return CastTimeline(itemIds, itemIdToData)
  }

  private fun updateItemData(itemId: Int, mediaInfo: MediaInfo?, defaultPositionUs: Long) {
    var defaultPositionUs = defaultPositionUs
    val previousData = itemIdToData[itemId, CastTimeline.ItemData.EMPTY]
    var durationUs = getStreamDurationUs(mediaInfo)
    if (durationUs == C.TIME_UNSET) {
      durationUs = previousData.durationUs
    }
    val isLive = if (mediaInfo == null) previousData.isLive else mediaInfo.streamType == MediaInfo.STREAM_TYPE_LIVE
    if (defaultPositionUs == C.TIME_UNSET) {
      defaultPositionUs = previousData.defaultPositionUs
    }
    itemIdToData.put(itemId, previousData.copyWithNewValues(durationUs, defaultPositionUs, isLive))
  }

  private fun getStreamDurationUs(mediaInfo: MediaInfo?): Long {
    if (mediaInfo == null) {
      return C.TIME_UNSET
    }
    val durationMs = mediaInfo.streamDuration
    return if (durationMs != MediaInfo.UNKNOWN_DURATION) msToUs(durationMs) else C.TIME_UNSET
  }

  fun msToUs(timeMs: Long): Long {
    return if (timeMs == C.TIME_UNSET || timeMs == C.TIME_END_OF_SOURCE) timeMs else timeMs * 1000
  }

  private fun removeUnusedItemDataEntries(itemIds: IntArray) {
    val scratchItemIds = HashSet<Int>( /* initialCapacity= */itemIds.size * 2)
    for (id in itemIds) {
      scratchItemIds.add(id)
    }
    var index = 0
    while (index < itemIdToData.size()) {
      if (!scratchItemIds.contains(itemIdToData.keyAt(index))) {
        itemIdToData.removeAt(index)
      } else {
        index++
      }
    }
  }

  init {
    itemIdToData = SparseArray()
  }
}
