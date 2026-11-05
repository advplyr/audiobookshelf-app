package com.audiobookshelf.app

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.managers.DbManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseLockInstrumentedTest {
  @Test
  fun playbackProgressDoesNotWaitForLibraryStorageWork() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    DbManager.initialize(instrumentation.targetContext)
    val database = DbManager()
    val id = "progress-lock-${UUID.randomUUID()}"
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workers = Executors.newFixedThreadPool(2)
    val storage = workers.submit {
      synchronized(database) {
        locked.countDown()
        check(release.await(10, TimeUnit.SECONDS))
      }
    }
    try {
      assertTrue(locked.await(5, TimeUnit.SECONDS))
      val playback = workers.submit {
        instrumentation.runOnMainSync {
          database.saveLocalMediaProgress(progress(id, 20))
          assertFalse(database.saveDownloadedProgressIfNewer(progress(id, 10)))
          assertEquals(20L, database.getLocalMediaProgress(id)!!.lastUpdate)
          assertTrue(database.saveDownloadedProgressIfNewer(progress(id, 30)))
          assertEquals(30L, database.getLocalMediaProgress(id)!!.lastUpdate)
        }
      }
      // The library monitor remains held until after the UI-thread progress operations finish.
      playback.get(3, TimeUnit.SECONDS)
    } finally {
      release.countDown()
      storage.get(5, TimeUnit.SECONDS)
      workers.shutdown()
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
      database.removeLocalMediaProgress(id)
    }
  }

  @Test
  fun offlineBrowserListingWaitsForStorageWithoutBlockingTheMainThread() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    DbManager.initialize(context)
    val database = DeviceManager.dbManager
    val id = "browser-lock-${UUID.randomUUID()}"
    database.saveLocalLibraryItem(LocalLibraryItem(
            id, "internal-podcast", context.cacheDir.path, context.cacheDir.path, "", false, "podcast",
            Podcast(PodcastMetadata("Browser lock test", null, null, mutableListOf(), false),
                    null, mutableListOf(), mutableListOf(), false, 0),
            mutableListOf(), null, null, true, "server", "https://example.test", "account", id
    ))
    val connected = CountDownLatch(1)
    val loaded = CountDownLatch(1)
    val responsive = CountDownLatch(1)
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val worker = Executors.newSingleThreadExecutor()
    var browser: MediaBrowserCompat? = null
    try {
      instrumentation.runOnMainSync {
        browser = MediaBrowserCompat(context, ComponentName(context, PlayerNotificationService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                  override fun onConnected() { connected.countDown() }
                }, null)
        browser!!.connect()
      }
      assertTrue("Media browser did not connect", connected.await(10, TimeUnit.SECONDS))
      val storage = worker.submit {
        synchronized(database) {
          locked.countDown()
          check(release.await(10, TimeUnit.SECONDS))
        }
      }
      assertTrue(locked.await(5, TimeUnit.SECONDS))
      instrumentation.runOnMainSync {
        browser!!.subscribe("__DOWNLOADS__", object : MediaBrowserCompat.SubscriptionCallback() {
          override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
            if (children.any { it.mediaId == id }) loaded.countDown()
          }
        })
        // Allow the service to receive the browse request before checking the main looper.
        Handler(Looper.getMainLooper()).postDelayed({ responsive.countDown() }, 200)
      }
      assertTrue("Offline listing blocked the main thread on library storage", responsive.await(3, TimeUnit.SECONDS))
      release.countDown()
      storage.get(5, TimeUnit.SECONDS)
      assertTrue("Offline listing did not return the saved podcast", loaded.await(10, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      worker.shutdown()
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
      instrumentation.runOnMainSync { browser?.disconnect() }
      database.removeLocalLibraryItem(id)
    }
  }

  private fun progress(id: String, updated: Long) = LocalMediaProgress(
          id, id, null, 60.0, 0.5, 30.0, false, null, null, updated, 1L, null,
          null, null, null, null, null
  )
}
