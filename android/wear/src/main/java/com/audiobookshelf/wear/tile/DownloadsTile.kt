package com.audiobookshelf.wear.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.download.Downloads
import com.audiobookshelf.wear.ui.MainActivity
import com.audiobookshelf.wear.ui.formatBytes

/** What's downloaded to the watch and how much space it takes. Works entirely offline. */
class DownloadsTile : AbsTileService() {

  override suspend fun content(device: DeviceParameters, clickedId: String?): TileContent {
    val summary = DownloadsSummary.from(
      app.downloads.books.value,
      Downloads.activeNow(this),
      app.downloads.freeBytes()
    ) { key -> app.progress.get(key)?.updatedAt }
    // Downloads always have their cover on the watch
    val covers = summary.recent.mapNotNull { key -> app.downloads.coverFile(key).takeIf { it.exists() }?.let { key to it } }.toMap()
    return TileContent(layout(this, device, summary, covers.keys), covers.mapKeys { (key, _) -> coverId(key) })
  }

  companion object {
    private const val COVER_DP = 34f

    private fun coverId(key: MediaKey) = "download_${key.key.hashCode()}"

    fun layout(context: Context, device: DeviceParameters, summary: DownloadsSummary, withCovers: Set<MediaKey>): LayoutElementBuilders.LayoutElement {
      val colors = Colors.DEFAULT
      if (summary.count == 0 && summary.activeCount == 0) {
        return PrimaryLayout.Builder(device)
          .setPrimaryLabelTextContent(label(context, colors))
          .setContent(
            LayoutElementBuilders.Column.Builder()
              .addContent(text(context, "No downloads yet", Typography.TYPOGRAPHY_TITLE3, colors.onSurface))
              .addContent(TileLayout.spacer(height = 4f))
              .addContent(text(context, "Download books to listen without your phone", Typography.TYPOGRAPHY_CAPTION2, colors.onSurface, maxLines = 3))
              .build()
          )
          .setPrimaryChipContent(
            CompactChip.Builder(context, "Browse", TileActions.open(context, MainActivity.ROUTE_LIBRARIES), device)
              .setChipColors(ChipColors.primaryChipColors(colors))
              .build()
          )
          .build()
      }

      val content = LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .addContent(label(context, colors))
        .addContent(TileLayout.spacer(height = 2f))
        .addContent(text(context, "${summary.count} ${if (summary.count == 1) "download" else "downloads"} · ${formatBytes(summary.bytes)}", Typography.TYPOGRAPHY_TITLE3, colors.onSurface))
        .addContent(text(context, "${formatBytes(summary.freeBytes)} free", Typography.TYPOGRAPHY_CAPTION2, colors.onSurface))

      if (summary.recent.isNotEmpty()) {
        val row = LayoutElementBuilders.Row.Builder()
        summary.recent.forEachIndexed { i, key ->
          if (i > 0) row.addContent(TileLayout.spacer(width = 6f))
          row.addContent(
            TileLayout.cover(
              if (key in withCovers) coverId(key) else null,
              COVER_DP,
              ModifiersBuilders.Modifiers.Builder().setClickable(TileActions.play(context, key.key))
            )
          )
        }
        content.addContent(TileLayout.spacer(height = 6f)).addContent(row.build())
      }

      val chipLabel = if (summary.activeCount > 0) "Downloading ${summary.activePercent}%" else "Open"
      content.addContent(TileLayout.spacer(height = 6f)).addContent(
        CompactChip.Builder(context, chipLabel, TileActions.open(context, MainActivity.ROUTE_DOWNLOADS), device)
          .setChipColors(if (summary.activeCount > 0) ChipColors.primaryChipColors(colors) else ChipColors.secondaryChipColors(colors))
          .build()
      )

      return EdgeContentLayout.Builder(device)
        .setEdgeContent(
          CircularProgressIndicator.Builder()
            .setProgress(summary.storageFraction.coerceAtLeast(0.01f))
            .setCircularProgressIndicatorColors(ProgressIndicatorColors.progressIndicatorColors(colors))
            .build()
        )
        .setContent(content.build())
        .build()
    }

    private fun label(context: Context, colors: Colors) = text(context, "On this watch", Typography.TYPOGRAPHY_CAPTION1, colors.primary)

    private fun text(context: Context, value: String, typography: Int, color: Int, maxLines: Int = 1) =
      Text.Builder(context, value).setTypography(typography).setColor(argb(color)).setMaxLines(maxLines).build()
  }
}
