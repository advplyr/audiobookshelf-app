package com.audiobookshelf.app.player

import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Media3 MediaSession.Callback implementation that routes playback commands
 * to the PlayerNotificationService's existing methods.
 * 
 * This bridges Media3's session API to our existing service logic, allowing
 * standard playback controls (play, pause, seek, speed) to work through the
 * Media3 notification system without rewriting service internals.
 */
class Media3SessionCallback(
  private val service: PlayerNotificationService
) : MediaSession.Callback {
  
  private val tag = "Media3SessionCallback"
  
  // To maximize compatibility across Media3 versions and avoid override mismatches,
  // we keep only custom command handling here for Phase 2. Standard controls
  // continue to work via controller->player direct mapping.
  
  // Custom commands for Phase 3 (bookmarks, sleep timer, etc.)
  // Currently returning NOT_SUPPORTED - will be implemented in Phase 3
  
  override fun onCustomCommand(
    session: MediaSession,
    controller: androidx.media3.session.MediaSession.ControllerInfo,
    customCommand: SessionCommand,
    args: android.os.Bundle
  ): ListenableFuture<SessionResult> {
    Log.d(tag, "onCustomCommand: ${customCommand.customAction}")
    // Phase 3: Implement custom actions (bookmarks, sleep timer, queue management)
    return Futures.immediateFuture(
      SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
    )
  }
}
