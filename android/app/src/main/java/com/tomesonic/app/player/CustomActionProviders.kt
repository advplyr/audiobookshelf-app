package com.tomesonic.app.player

import android.os.Bundle
import androidx.media3.common.Player
import android.support.v4.media.MediaMetadataCompat

// MIGRATION-DEFERRED: CAST - Stub implementations for Media3 migration
// Original functionality will be restored and converted to Media3 session callbacks in Step 7

// Abstract base for custom action providers in Media3 context
abstract class CustomActionProvider {
  abstract fun onCustomAction(player: Player, action: String, extras: Bundle?)
  abstract fun getCustomAction(player: Player): MediaMetadataCompat.Builder
}

class JumpBackwardCustomActionProvider(private val service: PlayerNotificationService) : CustomActionProvider() {
  override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
    // Stub implementation
  }

  override fun getCustomAction(player: Player): MediaMetadataCompat.Builder {
    // Stub implementation
    return MediaMetadataCompat.Builder()
  }
}

class JumpForwardCustomActionProvider(private val service: PlayerNotificationService) : CustomActionProvider() {
  override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
    // Stub implementation
  }

  override fun getCustomAction(player: Player): MediaMetadataCompat.Builder {
    // Stub implementation
    return MediaMetadataCompat.Builder()
  }
}

class SkipForwardCustomActionProvider(private val service: PlayerNotificationService) : CustomActionProvider() {
  override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
    // Stub implementation
  }

  override fun getCustomAction(player: Player): MediaMetadataCompat.Builder {
    // Stub implementation
    return MediaMetadataCompat.Builder()
  }
}

class SkipBackwardCustomActionProvider(private val service: PlayerNotificationService) : CustomActionProvider() {
  override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
    // Stub implementation
  }

  override fun getCustomAction(player: Player): MediaMetadataCompat.Builder {
    // Stub implementation
    return MediaMetadataCompat.Builder()
  }
}

class ChangePlaybackSpeedCustomActionProvider(private val service: PlayerNotificationService) : CustomActionProvider() {
  override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
    // Stub implementation
  }

  override fun getCustomAction(player: Player): MediaMetadataCompat.Builder {
    // Stub implementation
    return MediaMetadataCompat.Builder()
  }
}
