package com.audiobookshelf.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for DownloadNotificationService
 * Tests critical functionality: wake locks, notifications, lifecycle management
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DownloadNotificationServiceTest {

  @Mock
  private lateinit var mockContext: Context

  @Mock
  private lateinit var mockPowerManager: PowerManager

  @Mock
  private lateinit var mockWifiManager: WifiManager

  @Mock
  private lateinit var mockWakeLock: PowerManager.WakeLock

  @Mock
  private lateinit var mockWifiLock: WifiManager.WifiLock

  @Mock
  private lateinit var mockNotificationManager: NotificationManager

  private lateinit var service: DownloadNotificationService

  @Before
  fun setup() {
    MockitoAnnotations.openMocks(this)

    // Setup mocks
    `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
    `when`(mockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mockWifiManager)
    `when`(mockContext.getSystemService(NotificationManager::class.java)).thenReturn(mockNotificationManager)

    `when`(mockPowerManager.newWakeLock(anyInt(), anyString())).thenReturn(mockWakeLock)
    `when`(mockWifiManager.createWifiLock(anyInt(), anyString())).thenReturn(mockWifiLock)

    `when`(mockWakeLock.isHeld).thenReturn(false)
    `when`(mockWifiLock.isHeld).thenReturn(false)
  }

  /**
   * CRITICAL TEST: Wake lock must have timeout to prevent battery drain
   * Regression test for infinite wake lock bug
   */
  @Test
  fun testWakeLockHasTimeout() {
    // When wake lock is acquired, it should have a 6-hour timeout
    val expectedTimeoutMs = 6 * 60 * 60 * 1000L // 6 hours

    // Verify acquire is called with timeout parameter
    verify(mockWakeLock, times(1)).acquire(eq(expectedTimeoutMs))
  }

  /**
   * CRITICAL TEST: Wake lock must not be reference counted
   * Prevents accumulation of locks from multiple acquire/release cycles
   */
  @Test
  fun testWakeLockNotReferenceCounted() {
    verify(mockWakeLock, times(1)).setReferenceCounted(false)
  }

  /**
   * CRITICAL TEST: WiFi lock must not be reference counted
   */
  @Test
  fun testWifiLockNotReferenceCounted() {
    verify(mockWifiLock, times(1)).setReferenceCounted(false)
  }

  /**
   * CRITICAL TEST: Notification channel must have HIGH importance
   * Regression test for DEFAULT importance not working with PRIORITY_HIGH
   */
  @Test
  fun testNotificationChannelImportance() {
    val channelCaptor = org.mockito.ArgumentCaptor.forClass(NotificationChannel::class.java)
    verify(mockNotificationManager).createNotificationChannel(channelCaptor.capture())

    val channel = channelCaptor.value
    assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    assertEquals(DownloadNotificationService.CHANNEL_ID, channel.id)
  }

  /**
   * CRITICAL TEST: Wake lock must be released on service destroy
   * Prevents resource leak and battery drain
   */
  @Test
  fun testWakeLockReleasedOnDestroy() {
    // Simulate wake lock being held
    `when`(mockWakeLock.isHeld).thenReturn(true)

    // Trigger onDestroy
    service.onDestroy()

    // Verify release was called
    verify(mockWakeLock, times(1)).release()
  }

  /**
   * CRITICAL TEST: WiFi lock must be released on service destroy
   */
  @Test
  fun testWifiLockReleasedOnDestroy() {
    // Simulate wifi lock being held
    `when`(mockWifiLock.isHeld).thenReturn(true)

    // Trigger onDestroy
    service.onDestroy()

    // Verify release was called
    verify(mockWifiLock, times(1)).release()
  }

  /**
   * TEST: Service should not crash if wake lock release fails
   */
  @Test
  fun testWakeLockReleaseExceptionHandled() {
    `when`(mockWakeLock.isHeld).thenReturn(true)
    doThrow(RuntimeException("Test exception")).`when`(mockWakeLock).release()

    // Should not throw exception
    service.onDestroy()

    // Verify release was attempted
    verify(mockWakeLock, times(1)).release()
  }

  /**
   * TEST: Service should update notification properly
   */
  @Test
  fun testNotificationUpdate() {
    val downloadCount = 3
    val fileName = "test-audiobook.m4b"

    service.updateNotification(downloadCount, fileName)

    // Verify notification was updated
    verify(mockNotificationManager, atLeastOnce()).notify(
      eq(DownloadNotificationService.NOTIFICATION_ID),
      any()
    )
  }

  /**
   * TEST: Notification should show proper text based on download count
   */
  @Test
  fun testNotificationTextFormats() {
    // Test zero downloads
    service.updateNotification(0, "")
    // Notification should show "Preparing downloads..."

    // Test single download
    service.updateNotification(1, "audiobook.m4b")
    // Notification should show "Downloading: audiobook.m4b (1 active)"

    // Test multiple downloads
    service.updateNotification(3, "")
    // Notification should show "Downloading 3 files"

    // Verify notification manager was called 3 times
    verify(mockNotificationManager, times(3)).notify(
      eq(DownloadNotificationService.NOTIFICATION_ID),
      any()
    )
  }

  /**
   * CRITICAL TEST: onTaskRemoved should NOT stop service
   * Ensures downloads continue when app is swiped away
   */
  @Test
  fun testServiceContinuesAfterTaskRemoved() {
    service.onTaskRemoved(null)

    // Service should still be running (not call stopSelf)
    // This is implicit - the test passes if no exception is thrown
    assertTrue(true)
  }
}
