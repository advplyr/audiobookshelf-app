package com.audiobookshelf.wear.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import com.audiobookshelf.wear.data.ListeningStats
import com.audiobookshelf.wear.ui.formatDuration
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Listening stats: time today, the last 7 days and the current streak. The only place the watch
 * shows stats. Tap to refresh.
 */
class StatsTile : AbsTileService() {

  override suspend fun content(device: DeviceParameters, clickedId: String?): TileContent {
    val cached = app.tileCache.stats()
    val now = System.currentTimeMillis()
    val (json, fetchedAt) = when {
      // Tapped: fetch now, falling back to what we have
      clickedId == TileActions.REFRESH_ID -> fetch()?.let { it to now } ?: cached
      cached == null -> fetch()?.let { it to now }
      now - cached.second > STALE_MS -> {
        // Show the cached stats now and redraw once fresh ones arrive
        app.appScope.launch { if (fetch() != null) TileRefresher.request(app, StatsTile::class.java) }
        cached
      }
      else -> cached
    } ?: (null to 0L)
    val stats = json?.let { ListeningStats.from(it, LocalDate.now()) }
    return TileContent(layout(this, device, stats, updatedAgo = if (stats != null && now - fetchedAt > STALE_MS) now - fetchedAt else null))
  }

  private suspend fun fetch() = fetchStats(app, FETCH_TIMEOUT_MS)

  companion object {
    private const val FETCH_TIMEOUT_MS = 4_000L

    /** Fetches the stats and caches them; null when the server can't be reached in time. */
    suspend fun fetchStats(app: com.audiobookshelf.wear.WearApp, timeoutMs: Long): org.json.JSONObject? =
      if (app.auth.current == null) null else withTimeoutOrNull(timeoutMs) {
        runCatching { app.api.listeningStats() }.getOrNull()
      }?.also { app.tileCache.saveStats(it, System.currentTimeMillis()) }

    /** Older stats are refreshed in the background and labelled with their age. */
    private const val STALE_MS = 5 * 60_000L
    private const val BAR_MAX_DP = 34f
    private const val BAR_WIDTH_DP = 9f

    fun layout(context: Context, device: DeviceParameters, stats: ListeningStats?, updatedAgo: Long?): LayoutElementBuilders.LayoutElement {
      val colors = Colors.DEFAULT
      val refresh = TileActions.clickable(TileActions.refresh())
      val label = text(context, "Today", Typography.TYPOGRAPHY_CAPTION1, colors.primary)
      if (stats == null) {
        return PrimaryLayout.Builder(device)
          .setPrimaryLabelTextContent(label)
          .setContent(
            LayoutElementBuilders.Box.Builder()
              .setModifiers(refresh)
              .addContent(text(context, "Connect to see your listening stats", Typography.TYPOGRAPHY_BODY1, colors.onSurface, maxLines = 3))
              .build()
          )
          .build()
      }

      val chart = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
      val best = stats.last7Days.maxOrNull()?.takeIf { it > 0 } ?: 1L
      stats.last7Days.forEachIndexed { i, seconds ->
        val day = stats.today.minusDays((6 - i).toLong())
        val isToday = i == 6
        if (i > 0) chart.addContent(TileLayout.spacer(width = 5f))
        chart.addContent(
          LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
              LayoutElementBuilders.Box.Builder()
                .setWidth(dp(BAR_WIDTH_DP))
                .setHeight(dp(BAR_MAX_DP))
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
                .addContent(
                  LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(BAR_WIDTH_DP))
                    // Days without listening still show a stub so the week reads as a row
                    .setHeight(dp((BAR_MAX_DP * seconds / best).coerceAtLeast(2f)))
                    .setModifiers(
                      ModifiersBuilders.Modifiers.Builder()
                        .setBackground(TileLayout.rounded(3f, if (isToday) colors.primary else 0xFF5F5F5F.toInt()))
                        .build()
                    )
                    .build()
                )
                .build()
            )
            .addContent(TileLayout.spacer(height = 2f))
            .addContent(
              text(
                context,
                day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Typography.TYPOGRAPHY_CAPTION3,
                if (isToday) colors.primary else colors.onSurface
              )
            )
            .build()
        )
      }

      val summary = listOfNotNull(
        "Week ${formatDuration(stats.weekSeconds.toDouble())}",
        if (stats.streakDays > 1) "${stats.streakDays}-day streak" else "Total ${formatDuration(stats.totalSeconds.toDouble())}"
      ).joinToString(" · ")

      val content = LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(refresh)
        .addContent(text(context, formatDuration(stats.todaySeconds.toDouble()), Typography.TYPOGRAPHY_DISPLAY3, colors.onSurface))
        .addContent(TileLayout.spacer(height = 4f))
        .addContent(chart.build())
        .addContent(TileLayout.spacer(height = 4f))
        .addContent(text(context, summary, Typography.TYPOGRAPHY_CAPTION2, colors.onSurface))
      if (updatedAgo != null) {
        content.addContent(text(context, "Updated ${formatDuration(updatedAgo / 1000.0)} ago", Typography.TYPOGRAPHY_CAPTION3, 0xFF9E9E9E.toInt()))
      }

      return PrimaryLayout.Builder(device)
        .setPrimaryLabelTextContent(label)
        .setContent(content.build())
        .build()
    }

    private fun text(context: Context, value: String, typography: Int, color: Int, maxLines: Int = 1) =
      Text.Builder(context, value).setTypography(typography).setColor(argb(color)).setMaxLines(maxLines).build()
  }
}
