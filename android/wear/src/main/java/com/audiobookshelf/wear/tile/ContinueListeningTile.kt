package com.audiobookshelf.wear.tile

import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import com.audiobookshelf.wear.playback.NowPlaying
import com.audiobookshelf.wear.ui.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Covers of the books and episodes in progress; tapping one plays it. */
class ContinueListeningTile : AbsTileService() {

  override suspend fun content(device: DeviceParameters, clickedId: String?): TileContent {
    val entries = entries()
    val covers = covers(entries.orEmpty().map { it.key.itemId })
    return TileContent(
      layout(this, device, entries, covers.keys),
      covers.mapKeys { (itemId, _) -> coverId(itemId) }
    )
  }

  /** Null while the first load from the server is still running. */
  private suspend fun entries(): List<ContinueEntry>? {
    val nowPlaying = NowPlaying.book.value?.book?.let { book ->
      ContinueEntry(book.mediaKey, book.title, ContinueEntries.fraction(app.progress.get(book.mediaKey)?.currentTime ?: 0.0, book.duration))
    }
    val cached = app.tileCache.continueEntries
    val list = if (cached != null) {
      // Show what we have now; redraw if the server has something newer
      refreshInBackground(cached)
      cached
    } else {
      fetch(QUICK_TIMEOUT_MS) ?: run {
        // Slow first load (e.g. the app process just started): keep trying and redraw when done
        refreshInBackground(null)
        offline().ifEmpty { if (nowPlaying == null) return null else emptyList() }
      }
    }
    return ContinueEntries.build(nowPlaying, list)
  }

  private fun refreshInBackground(shown: List<ContinueEntry>?) {
    app.appScope.launch {
      val fresh = fetch(BACKGROUND_TIMEOUT_MS)
      if (fresh != null && fresh != shown) TileRefresher.request(app, ContinueListeningTile::class.java)
    }
  }

  private suspend fun fetch(timeoutMs: Long): List<ContinueEntry>? {
    if (app.auth.current == null) return null
    val result = withTimeoutOrNull(timeoutMs) {
      try {
        coroutineScope {
          val progress = async { app.api.allProgress() }
          val items = async { app.api.itemsInProgress() }
          items.await().map { item ->
            ContinueEntry(item.mediaKey, item.title, progress.await()[item.mediaKey.key]?.progress?.toFloat() ?: 0f)
          }
        }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "Could not load books in progress: ${e.message}")
        null
      }
    }
    if (result == null) Log.d(TAG, "Books in progress not loaded within ${timeoutMs}ms")
    return result?.also { app.tileCache.continueEntries = it }
  }

  /** Never reached the server: downloads the watch has listened to, latest first. */
  private fun offline(): List<ContinueEntry> = app.downloads.books.value
    .mapNotNull { downloaded -> app.progress.get(downloaded.book.mediaKey)?.let { downloaded.book to it } }
    .sortedByDescending { (_, local) -> local.updatedAt }
    .map { (book, local) -> ContinueEntry(book.mediaKey, book.title, ContinueEntries.fraction(local.currentTime, book.duration)) }

  companion object {
    private const val TAG = "ContinueListeningTile"
    private const val QUICK_TIMEOUT_MS = 4_000L
    private const val BACKGROUND_TIMEOUT_MS = 30_000L
    private const val COVER_DP = 44f

    private fun coverId(itemId: String) = "cover_$itemId"

    /** [entries] is null while the first load is still running. */
    fun layout(context: Context, device: DeviceParameters, entries: List<ContinueEntry>?, withCovers: Set<String>): LayoutElementBuilders.LayoutElement {
      val colors = Colors.DEFAULT
      val label = Text.Builder(context, "Continue")
        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
        .setColor(argb(colors.primary))
        .build()
      if (entries.isNullOrEmpty()) {
        return PrimaryLayout.Builder(device)
          .setPrimaryLabelTextContent(label)
          .setContent(
            Text.Builder(context, if (entries == null) "Loading…" else "Nothing in progress")
              .setTypography(Typography.TYPOGRAPHY_BODY1)
              .setColor(argb(colors.onSurface))
              .setMaxLines(2)
              .build()
          )
          .setPrimaryChipContent(
            CompactChip.Builder(context, "Browse", TileActions.open(context, MainActivity.ROUTE_LIBRARIES), device)
              .setChipColors(ChipColors.primaryChipColors(colors))
              .build()
          )
          .build()
      }

      val grid = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
      entries.chunked(2).forEachIndexed { row, pair ->
        if (row > 0) grid.addContent(TileLayout.spacer(height = 6f))
        val line = LayoutElementBuilders.Row.Builder()
        pair.forEachIndexed { i, entry ->
          if (i > 0) line.addContent(TileLayout.spacer(width = 8f))
          line.addContent(cell(context, colors, entry, entry.key.itemId in withCovers))
        }
        grid.addContent(line.build())
      }

      return PrimaryLayout.Builder(device)
        .setPrimaryLabelTextContent(label)
        .setContent(grid.build())
        .setPrimaryChipContent(
          CompactChip.Builder(context, "More", TileActions.open(context, MainActivity.ROUTE_CONTINUE), device)
            .setChipColors(ChipColors.secondaryChipColors(colors))
            .build()
        )
        .build()
    }

    /** A cover with a progress bar underneath, and a play badge on what's in the player. */
    private fun cell(context: Context, colors: Colors, entry: ContinueEntry, hasCover: Boolean): LayoutElementBuilders.LayoutElement {
      val cover = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(COVER_DP))
        .setHeight(dp(COVER_DP))
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_END)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
        .addContent(TileLayout.cover(if (hasCover) coverId(entry.key.itemId) else null, COVER_DP, ModifiersBuilders.Modifiers.Builder()))
      if (!hasCover) {
        // No cover: the title stands in for it
        cover.addContent(
          LayoutElementBuilders.Box.Builder()
            .setWidth(dp(COVER_DP))
            .setHeight(dp(COVER_DP))
            .addContent(
              Text.Builder(context, entry.title)
                .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                .setColor(argb(colors.onSurface))
                .setMaxLines(3)
                .build()
            )
            .build()
        )
      }
      if (entry.playing) {
        cover.addContent(
          LayoutElementBuilders.Box.Builder()
            .setWidth(dp(16f))
            .setHeight(dp(16f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setBackground(TileLayout.rounded(8f, colors.primary)).build())
            .addContent(
              Text.Builder(context, "▶")
                .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                .setColor(argb(colors.onPrimary))
                .build()
            )
            .build()
        )
      }

      val bar = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(COVER_DP))
        .setHeight(dp(3f))
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setBackground(TileLayout.rounded(1.5f, 0xFF444444.toInt())).build())
      val filled = (COVER_DP * entry.progress).coerceAtLeast(0f)
      if (filled >= 1f) {
        bar.addContent(
          LayoutElementBuilders.Box.Builder()
            .setWidth(dp(filled))
            .setHeight(dp(3f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setBackground(TileLayout.rounded(1.5f, colors.primary)).build())
            .build()
        )
      }

      return LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(TileActions.clickable(TileActions.play(context, entry.key.key)))
        .addContent(cover.build())
        .addContent(TileLayout.spacer(height = 3f))
        .addContent(bar.build())
        .build()
    }
  }
}
