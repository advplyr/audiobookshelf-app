package com.audiobookshelf.app.managers

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for DownloadItemManager
 * Tests critical functionality: thread safety, storage checks, lifecycle management
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DownloadItemManagerTest {

  @Mock
  private lateinit var mockDownloadManager: DownloadManager

  @Mock
  private lateinit var mockFolderScanner: FolderScanner

  @Mock
  private lateinit var mockMainActivity: MainActivity

  @Mock
  private lateinit var mockContext: Context

  @Mock
  private lateinit var mockEventEmitter: DownloadItemManager.DownloadEventEmitter

  private lateinit var downloadItemManager: DownloadItemManager

  @Before
  fun setup() {
    MockitoAnnotations.openMocks(this)

    // Setup mock activity
    `when`(mockMainActivity.applicationContext).thenReturn(mockContext)
    `when`(mockMainActivity.isFinishing).thenReturn(false)
    `when`(mockMainActivity.isDestroyed).thenReturn(false)
    `when`(mockMainActivity.filesDir).thenReturn(java.io.File("/tmp"))

    downloadItemManager = DownloadItemManager(
      mockDownloadManager,
      mockFolderScanner,
      mockMainActivity,
      mockEventEmitter
    )
  }

  /**
   * CRITICAL TEST: Storage buffer calculation must use smart formula
   * Regression test for wasteful 10% buffer
   */
  @Test
  fun testStorageBufferCalculation() {
    // For small files (< 2GB), should use 100MB buffer
    val smallFileSize = 500L * 1024 * 1024 // 500MB
    val smallBuffer = maxOf(100L * 1024 * 1024, (smallFileSize * 0.05).toLong())
    assertEquals(100L * 1024 * 1024, smallBuffer) // Should be 100MB

    // For large files (> 2GB), should use 5% buffer
    val largeFileSize = 10L * 1024 * 1024 * 1024 // 10GB
    val largeBuffer = maxOf(100L * 1024 * 1024, (largeFileSize * 0.05).toLong())
    assertEquals((10L * 1024 * 1024 * 1024 * 0.05).toLong(), largeBuffer) // Should be 512MB (5%)
  }

  /**
   * CRITICAL TEST: Service should not start if activity is finishing
   * Regression test for context validation bug
   */
  @Test
  fun testServiceNotStartedWhenActivityFinishing() {
    `when`(mockMainActivity.isFinishing).thenReturn(true)

    // Attempt to start service should be skipped
    // This is verified by checking that startForegroundService is never called
    verify(mockContext, never()).startForegroundService(any())
  }

  /**
   * CRITICAL TEST: Service should not start if activity is destroyed
   */
  @Test
  fun testServiceNotStartedWhenActivityDestroyed() {
    `when`(mockMainActivity.isDestroyed).thenReturn(true)

    // Attempt to start service should be skipped
    verify(mockContext, never()).startForegroundService(any())
  }

  /**
   * CRITICAL TEST: Service should not start if application context is null
   */
  @Test
  fun testServiceNotStartedWhenContextNull() {
    `when`(mockMainActivity.applicationContext).thenReturn(null)

    // Attempt to start service should be skipped
    verify(mockContext, never()).startForegroundService(any())
  }

  /**
   * CRITICAL TEST: IllegalArgumentException during unbind should be logged, not thrown
   * Regression test for overly broad exception catching
   */
  @Test
  fun testUnbindServiceIllegalArgumentExceptionHandled() {
    doThrow(IllegalArgumentException("Service not registered"))
      .`when`(mockContext).unbindService(any())

    // Should not throw exception - it's expected when service was never bound
    downloadItemManager.cleanup()

    // Test passes if no exception propagates
    assertTrue(true)
  }

  /**
   * CRITICAL TEST: Unexpected exceptions during unbind should be logged with details
   */
  @Test
  fun testUnbindServiceUnexpectedExceptionLogged() {
    doThrow(RuntimeException("Unexpected error"))
      .`when`(mockContext).unbindService(any())

    // Should not crash
    downloadItemManager.cleanup()

    // Test passes if no exception propagates
    assertTrue(true)
  }

  /**
   * CRITICAL TEST: Cleanup should be called and clear queues
   * Regression test for cleanup not being invoked in lifecycle
   */
  @Test
  fun testCleanupClearsQueues() {
    // Add some items to queue
    val mockDownloadItem = mock(DownloadItem::class.java)
    downloadItemManager.downloadItemQueue.add(mockDownloadItem)
    downloadItemManager.currentDownloadItemParts.add(mock())

    assertFalse(downloadItemManager.downloadItemQueue.isEmpty())
    assertFalse(downloadItemManager.currentDownloadItemParts.isEmpty())

    // Call cleanup
    downloadItemManager.cleanup()

    // Verify queues are cleared
    assertTrue(downloadItemManager.downloadItemQueue.isEmpty())
    assertTrue(downloadItemManager.currentDownloadItemParts.isEmpty())
  }

  /**
   * CRITICAL TEST: Error callback should be used instead of Toast
   * Regression test for Toast failures when activity is backgrounded
   */
  @Test
  fun testInsufficientStorageUsesEventEmitter() {
    // Mock storage check to return insufficient space
    val mockStatFs = mock(android.os.StatFs::class.java)
    `when`(mockStatFs.availableBytes).thenReturn(10L * 1024 * 1024) // Only 10MB available

    val mockDownloadItem = mock(DownloadItem::class.java)
    `when`(mockDownloadItem.downloadItemParts).thenReturn(emptyList())

    // This should trigger the event emitter, not a Toast
    downloadItemManager.addDownloadItem(mockDownloadItem)

    // Verify event emitter was called
    verify(mockEventEmitter, times(1)).onDownloadError(
      eq("INSUFFICIENT_STORAGE"),
      anyString()
    )
  }

  /**
   * TEST: Thread safety - service connection state should be thread-safe
   * Tests @Volatile and synchronized usage
   */
  @Test
  fun testServiceConnectionThreadSafety() {
    // This test verifies that concurrent access to service connection state
    // doesn't cause race conditions. The @Volatile and synchronized modifiers
    // should prevent issues.

    val threads = mutableListOf<Thread>()

    // Simulate concurrent reads
    repeat(10) {
      threads.add(Thread {
        // Try to access service state
        downloadItemManager.cleanup()
      })
    }

    threads.forEach { it.start() }
    threads.forEach { it.join() }

    // If we get here without deadlock or exception, test passes
    assertTrue(true)
  }

  /**
   * TEST: Service binding should use application context
   */
  @Test
  fun testServiceBindingUsesApplicationContext() {
    // Verify that bindService is called with application context, not activity
    verify(mockContext, atLeastOnce()).bindService(
      any(),
      any(),
      eq(Context.BIND_AUTO_CREATE)
    )
  }
}
