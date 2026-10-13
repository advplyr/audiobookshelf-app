package com.audiobookshelf.wear.tile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.wear.tiles.TileService
import com.audiobookshelf.wear.WearApp
import kotlinx.coroutines.launch

/** Asks Wear OS to redraw tiles whose data just changed. */
object TileRefresher {
  private const val STATS_MIN_INTERVAL_MS = 60_000L
  @Volatile private var statsRequestedAt = 0L

  fun request(context: Context, vararg tiles: Class<out TileService>) {
    for (tile in tiles) {
      try {
        TileService.getUpdater(context).requestUpdate(tile)
      } catch (e: Exception) {
        Log.d("TileRefresher", "Tile update not possible: ${e.message}")
      }
    }
  }

  /** A book was loaded, unloaded, played, paused or finished. */
  fun playbackChanged(context: Context) = request(context, ContinueListeningTile::class.java, DownloadsTile::class.java)

  /** A download was added, removed or made progress. */
  fun downloadsChanged(context: Context) = request(context, DownloadsTile::class.java)

  /**
   * Listening time reached the server: fetch the new totals, then redraw. At most once a minute,
   * since syncs happen every 15 seconds while playing.
   */
  fun statsChanged(context: Context) {
    val now = SystemClock.elapsedRealtime()
    if (statsRequestedAt != 0L && now - statsRequestedAt < STATS_MIN_INTERVAL_MS) return
    statsRequestedAt = now
    val app = context.applicationContext as WearApp
    app.appScope.launch {
      if (StatsTile.fetchStats(app, 30_000L) != null) request(app, StatsTile::class.java)
    }
  }
}
