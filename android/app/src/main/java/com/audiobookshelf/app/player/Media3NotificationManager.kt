package com.audiobookshelf.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.audiobookshelf.app.R
import com.audiobookshelf.app.player.media3.CustomMediaNotificationProvider
import com.audiobookshelf.app.player.media3.Media3PlaybackSpeedButtonProvider
import com.google.common.collect.ImmutableList

@UnstableApi
class Media3NotificationManager(
  private val context: Context,
  private val service: MediaLibraryService,
  private val cyclePlaybackSpeedCommand: SessionCommand,
  private val seekBackIncrementCommand: SessionCommand,
  private val seekForwardIncrementCommand: SessionCommand,
  private val jumpBackwardMsProvider: () -> Long,
  private val jumpForwardMsProvider: () -> Long,
  private val currentPlaybackSpeedProvider: () -> Float,
  private val cyclePlaybackSpeedAction: () -> Float,
  private val updatePlaybackSpeedButtonAction: (Float) -> Unit,
  private val debugLog: (String) -> Unit
) {
  companion object {
    private const val NOTIFICATION_ID = 100
    private const val CHANNEL_ID = "media3_playback_channel"
  }

  private lateinit var notificationProvider: androidx.media3.session.MediaNotification.Provider
  private lateinit var playbackSpeedButtonProvider: Media3PlaybackSpeedButtonProvider
  private var playbackSpeedCommandButton: CommandButton? = null

  @Volatile
  private var foregroundStarted = false

  fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channelName = "Media Playback"
      val importance = NotificationManager.IMPORTANCE_LOW
      val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
        description = "Playback controls and progress"
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
      }
      val notificationManager = context.getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
      debugLog("Notification channel created: $CHANNEL_ID")
    }
  }

  @OptIn(UnstableApi::class)
  fun createNotificationProvider(): CustomMediaNotificationProvider {
    val provider = CustomMediaNotificationProvider(
      context,
      CHANNEL_ID,
      androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID,
      NOTIFICATION_ID
    )
    this.notificationProvider = provider
    debugLog("CustomMediaNotificationProvider created")
    return provider
  }

  fun configureCommandButtons() {
    playbackSpeedButtonProvider =
      Media3PlaybackSpeedButtonProvider(cyclePlaybackSpeedCommand, PlaybackConstants.DISPLAY_SPEED)
    playbackSpeedButtonProvider.alignTo(currentPlaybackSpeedProvider())
    playbackSpeedCommandButton = null
  }

  fun ensureForegroundNotification() {
    if (foregroundStarted) return
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_preparing_playback))
        .setSmallIcon(R.drawable.icon_monochrome)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
    service.startForeground(NOTIFICATION_ID, notification)
    foregroundStarted = true
  }

  fun stopForegroundNotification() {
    if (foregroundStarted) {
      service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
      foregroundStarted = false
    }
  }

  fun buildServiceMediaButtons(): List<CommandButton> {
    val buttons = mutableListOf<CommandButton>()

    // Always include Back/Forward regardless of allowSeekingOnMediaControls for notifications
    val backMs = jumpBackwardMsProvider().coerceAtLeast(1_000L)
    val fwdMs = jumpForwardMsProvider().coerceAtLeast(1_000L)
    val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
      .setSessionCommand(seekBackIncrementCommand)
      .setDisplayName("Back ${backMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_rewind)
      .setSlots(CommandButton.SLOT_BACK)
      .build()
    val fwd = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
      .setSessionCommand(seekForwardIncrementCommand)
      .setDisplayName("Forward ${fwdMs / 1000}s")
      .setCustomIconResId(R.drawable.exo_icon_fastforward)
      .setSlots(CommandButton.SLOT_FORWARD)
      .build()
    buttons.add(back)
    buttons.add(fwd)

    val speedBtn = playbackSpeedCommandButton ?: run {
      CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
        .setSessionCommand(cyclePlaybackSpeedCommand)
        .setDisplayName("Speed")
        .build()
    }
    buttons.add(speedBtn)

    return buttons
  }

  fun updatePlaybackSpeedButton(speed: Float) {
    playbackSpeedButtonProvider.alignTo(speed)
    val speedButton = playbackSpeedButtonProvider.createButton(speed)
    playbackSpeedCommandButton = speedButton
    // Refresh media button preferences so controllers/notifications get updated icon/label
    updateMediaButtonPreferencesAfterSpeedChange(null, speed)
  }

  fun cyclePlaybackSpeed(): Float {
    val newSpeed = playbackSpeedButtonProvider.cycleSpeed()
    cyclePlaybackSpeedAction()
    return newSpeed
  }

  fun getPlaybackSpeedCommandButton(): CommandButton? = playbackSpeedCommandButton

  fun setPlaybackSpeedCommandButton(button: CommandButton?) {
    playbackSpeedCommandButton = button
  }

  fun applyInitialMediaButtonPreferences(mediaSession: MediaSession?) {
    runCatching {
      val prefs = ImmutableList.copyOf(buildServiceMediaButtons())
      mediaSession?.setMediaButtonPreferences(prefs)
      debugLog("Initial media button preferences applied: ${prefs.size}")
    }.onFailure { t ->
      debugLog("Failed to apply initial media button preferences: ${t.message}")
    }
  }

  fun updateMediaButtonPreferencesAfterSpeedChange(mediaSession: MediaSession?, speed: Float) {
    runCatching {
      val prefs = ImmutableList.copyOf(buildServiceMediaButtons())
      mediaSession?.setMediaButtonPreferences(prefs)
      debugLog("Updated media button preferences after speed change: ${speed}x")
    }.onFailure { t ->
      debugLog("Failed to update media button preferences after speed change: ${t.message}")
    }
  }
}
