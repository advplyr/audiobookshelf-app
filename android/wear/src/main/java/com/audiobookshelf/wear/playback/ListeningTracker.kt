package com.audiobookshelf.wear.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.audiobookshelf.wear.WearApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Records listening time and position while the player runs, saving it on the watch and sending it
 * to the server every [SYNC_INTERVAL_MS] and whenever playback pauses.
 */
class ListeningTracker(
  private val app: WearApp,
  private val player: Player,
  private val scope: CoroutineScope
) : Player.Listener {
  private var session: JSONObject? = null
  private var timeListening = 0.0
  private var lastTickAt = 0L
  private var ticker: Job? = null

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (isPlaying) {
      lastTickAt = SystemClock.elapsedRealtime()
      ticker?.cancel()
      ticker = scope.launch {
        while (isActive) {
          delay(SYNC_INTERVAL_MS)
          record()
        }
      }
    } else {
      ticker?.cancel()
      record()
    }
  }

  override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
    // Save seeks right away so the phone doesn't jump back to the old position
    if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) record()
  }

  /** Called when the service shuts down. */
  fun finish() {
    ticker?.cancel()
    record()
  }

  private fun record() {
    val playing = NowPlaying.book.value ?: return
    val now = System.currentTimeMillis()
    if (lastTickAt > 0) {
      timeListening += (SystemClock.elapsedRealtime() - lastTickAt) / 1000.0
    }
    lastTickAt = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L

    val currentTime = if (player.playbackState == Player.STATE_ENDED) playing.book.duration else playing.bookTime(player)
    app.progress.save(playing.book.id, currentTime, now)

    val sessionJson = currentSession(playing, now)
      .put("currentTime", currentTime)
      .put("timeListening", timeListening.toLong())
      .put("updatedAt", now)
    // Copy so later updates don't race the network call
    val snapshot = JSONObject(sessionJson.toString())
    app.appScope.launch { app.sessionSync.sync(snapshot) }
  }

  private fun currentSession(playing: PlayingBook, now: Long): JSONObject {
    session?.takeIf { it.optString("id") == playing.sessionId }?.let { return it }
    timeListening = 0.0
    return JSONObject()
      .put("id", playing.sessionId)
      .put("libraryItemId", playing.book.id)
      .put("episodeId", JSONObject.NULL)
      .put("mediaType", "book")
      .put("displayTitle", playing.book.title)
      .put("displayAuthor", playing.book.author)
      .put("duration", playing.book.duration)
      .put("playMethod", if (playing.isLocal) PLAYMETHOD_LOCAL else PLAYMETHOD_DIRECTPLAY)
      .put("mediaPlayer", "exo-player")
      .put("startedAt", now)
      .put("deviceInfo", app.sessionSync.deviceInfo)
      .also { session = it }
  }

  companion object {
    private const val SYNC_INTERVAL_MS = 15_000L
    private const val PLAYMETHOD_DIRECTPLAY = 0
    private const val PLAYMETHOD_LOCAL = 3
  }
}
