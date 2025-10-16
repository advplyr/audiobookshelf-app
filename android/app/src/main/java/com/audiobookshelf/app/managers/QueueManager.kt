package com.audiobookshelf.app.managers

import android.app.Activity
import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.PlaybackQueueItem

/** Manages playback queue stored in Capacitor SharedPreferences. */
class QueueManager(private val context: Context) {
    companion object {
        private const val TAG = "QueueManager"
        private const val CAPACITOR_STORAGE_NAME = "CapacitorStorage"
        private const val QUEUE_KEY = "playbackQueue"
    }

    private val sharedPrefs = context.getSharedPreferences(CAPACITOR_STORAGE_NAME, Activity.MODE_PRIVATE)

    fun getQueue(): List<PlaybackQueueItem> {
        try {
            val queueJson = sharedPrefs.getString(QUEUE_KEY, null)
            if (queueJson.isNullOrEmpty()) {
                Log.d(TAG, "getQueue: No queue found in SharedPreferences")
                return emptyList()
            }

            val queue = PlaybackQueueItem.fromJsonArray(queueJson)
            Log.d(TAG, "getQueue: Found ${queue.size} items in queue")
            return queue
        } catch (e: Exception) {
            Log.e(TAG, "getQueue: Failed to read queue from SharedPreferences", e)
            return emptyList()
        }
    }

    fun getNextItem(): PlaybackQueueItem? {
        val queue = getQueue()
        return if (queue.isNotEmpty()) {
            queue.first()
        } else {
            null
        }
    }

    fun hasQueueItems(): Boolean {
        return getQueue().isNotEmpty()
    }

    fun getQueueSize(): Int {
        return getQueue().size
    }
}
