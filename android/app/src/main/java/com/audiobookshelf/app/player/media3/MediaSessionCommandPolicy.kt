package com.audiobookshelf.app.player.media3

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommands
import com.audiobookshelf.app.player.PlaybackConstants

/**
 * Decides which commands each connected controller may use.
 *
 * The app's own UI always gets seeking; notification and Android Auto controllers only get it
 * when the user has enabled it, so a stray seek on a car head unit can't scrub an audiobook.
 * Controllers are updated individually because that permission differs per controller.
 */
class MediaSessionCommandPolicy(
  private val logTag: String,
  private val allowSeekingOnMediaControls: () -> Boolean,
  private val refreshNotificationButtons: () -> Unit
) {
  private val sessionCommands: SessionCommands
    get() = SessionCommands.Builder()
      .add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CYCLE_PLAYBACK_SPEED))
      .add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_BACK_INCREMENT))
      .add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_FORWARD_INCREMENT))
      .add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_PREVIOUS_TRACK))
      .add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.SEEK_TO_NEXT_TRACK))
      .add(PlaybackConstants.sessionCommand(PlaybackConstants.Commands.CLOSE_PLAYBACK))
      .build()

  fun applyTo(session: MediaLibrarySession?, player: Player?) {
    val mediaSession = session ?: return
    runCatching {
      val allowSeeking = allowSeekingOnMediaControls()
      val commands = sessionCommands

      mediaSession.connectedControllers.forEach { controllerInfo ->
        runCatching {
          val isAppUiController = controllerInfo.connectionHints
            .getBoolean(PlaybackConstants.KEY_IS_APP_UI_CONTROLLER, false)
          val playerCommands = SessionController.buildBasePlayerCommands(
            player,
            isAppUiController || allowSeeking
          )
          mediaSession.setAvailableCommands(controllerInfo, commands, playerCommands)
        }.onFailure { t ->
          Log.w(logTag, "command policy failed for controller=${controllerInfo.packageName}: ${t.message}")
        }
      }

      runCatching { refreshNotificationButtons() }.onFailure { t ->
        Log.w(logTag, "command policy failed to refresh notification buttons: ${t.message}")
      }
    }.onFailure { t ->
      Log.w(logTag, "command policy: ${t.message}")
    }
  }
}
