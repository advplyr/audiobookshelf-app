package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.audiobookshelf.app.player.PlaybackConstants

/**
 * The app's own UI always gets seeking; notification and Android Auto controllers only get it
 * when the user has enabled it, so a stray seek on a car head unit can't scrub an audiobook.
 * Controllers are updated individually because that permission differs per controller.
 */
@OptIn(UnstableApi::class)
class MediaSessionCommandPolicy(
  private val logTag: String,
  private val allowSeekingOnMediaControls: () -> Boolean,
  private val refreshNotificationButtons: () -> Unit
) {
  fun applyTo(session: MediaLibrarySession?, player: Player?) {
    val mediaSession = session ?: return
    val allowSeeking = allowSeekingOnMediaControls()

    mediaSession.connectedControllers.forEach { controllerInfo ->
      runCatching {
        val isAppUiController = controllerInfo.connectionHints
          .getBoolean(PlaybackConstants.KEY_IS_APP_UI_CONTROLLER, false)
        val sessionCommands = SessionController.buildSessionCommands(
          isAppUiController,
          MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        )
        val playerCommands = SessionController.buildBasePlayerCommands(
          player,
          isAppUiController || allowSeeking
        )
        mediaSession.setAvailableCommands(controllerInfo, sessionCommands, playerCommands)
      }.onFailure { t ->
        Log.w(logTag, "command policy failed for controller=${controllerInfo.packageName}: ${t.message}")
      }
    }

    refreshNotificationButtons()
  }
}
