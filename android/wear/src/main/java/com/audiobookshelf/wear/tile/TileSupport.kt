package com.audiobookshelf.wear.tile

import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.ui.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** A tile's layout plus the images it shows, by resource id. */
class TileContent(val layout: LayoutElementBuilders.LayoutElement, val images: Map<String, File> = emptyMap())

/**
 * Base for the app's tiles: builds the layout on the main thread (where the player can be read)
 * and serves the cover images it refers to.
 */
abstract class AbsTileService : TileService() {
  protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  protected val app get() = application as WearApp

  /** The images of the last layout, by resources version, for the resources request that follows. */
  private val imagesByVersion = mutableMapOf<String, Map<String, File>>()

  /** [clickedId] is the id of the clickable that asked for this redraw, if any (see [TileActions.refresh]). */
  protected abstract suspend fun content(device: DeviceParameters, clickedId: String?): TileContent

  override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
    val content = content(requestParams.deviceConfiguration, requestParams.currentState.lastClickableId.ifEmpty { null })
    val version = version(content.images)
    imagesByVersion[version] = content.images
    TileBuilders.Tile.Builder()
      .setResourcesVersion(version)
      .setFreshnessIntervalMillis(FRESHNESS_MS)
      .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(content.layout))
      .build()
  }

  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> = scope.future {
    val images = imagesByVersion[requestParams.version] ?: content(requestParams.deviceConfiguration, null).images
    val builder = ResourceBuilders.Resources.Builder().setVersion(requestParams.version)
    withContext(Dispatchers.IO) {
      images.forEach { (id, file) -> runCatching { file.readBytes() }.getOrNull()?.let { builder.addIdToImageMapping(id, image(it)) } }
    }
    builder.build()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  /** Covers for [itemIds], fetched in parallel; items without one are left out. */
  protected suspend fun covers(itemIds: List<String>): Map<String, File> = coroutineScope {
    itemIds.distinct().map { id -> async { id to CoverCache.coverFor(app, id) } }.awaitAll()
      .mapNotNull { (id, file) -> file?.let { id to it } }
      .toMap()
  }

  companion object {
    private const val FRESHNESS_MS = 30 * 60_000L
    const val COVER_PX = 96

    private fun version(images: Map<String, File>) =
      images.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value.name}:${it.value.length()}" }.hashCode().toString()

    private fun image(bytes: ByteArray) = ResourceBuilders.ImageResource.Builder()
      .setInlineResource(
        ResourceBuilders.InlineImageResource.Builder()
          .setData(bytes)
          .setWidthPx(COVER_PX)
          .setHeightPx(COVER_PX)
          // Compressed (JPEG) data, decoded by the tile renderer
          .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
          .build()
      )
      .build()
  }
}

object TileActions {
  /** Opens the app, telling it what the tile asked for (see [MainActivity.EXTRA_TILE_ACTION]). */
  fun launch(context: Context, action: String, arg: String? = null): ModifiersBuilders.Clickable {
    val activity = ActionBuilders.AndroidActivity.Builder()
      .setPackageName(context.packageName)
      .setClassName(MainActivity::class.java.name)
      .addKeyToExtraMapping(MainActivity.EXTRA_TILE_ACTION, ActionBuilders.AndroidStringExtra.Builder().setValue(action).build())
    if (arg != null) activity.addKeyToExtraMapping(MainActivity.EXTRA_TILE_ARG, ActionBuilders.AndroidStringExtra.Builder().setValue(arg).build())
    return ModifiersBuilders.Clickable.Builder()
      .setId("$action:${arg.orEmpty()}")
      .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
      .build()
  }

  fun play(context: Context, key: String) = launch(context, MainActivity.TILE_PLAY, key)

  fun open(context: Context, route: String) = launch(context, MainActivity.TILE_ROUTE, route)

  /** Redraws the tile, e.g. to fetch fresh stats. */
  const val REFRESH_ID = "refresh"

  fun refresh(): ModifiersBuilders.Clickable = ModifiersBuilders.Clickable.Builder()
    .setId(REFRESH_ID)
    .setOnClick(ActionBuilders.LoadAction.Builder().build())
    .build()

  fun clickable(clickable: ModifiersBuilders.Clickable) = ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build()
}

/** Cover images for tiles: the downloaded cover, or one fetched once and kept in the cache. */
object CoverCache {
  suspend fun coverFor(app: WearApp, itemId: String): File? = withContext(Dispatchers.IO) {
    app.downloads.coverFile(itemId).takeIf { it.exists() }?.let { return@withContext it }
    val dir = File(app.cacheDir, "tile_covers").apply { mkdirs() }
    val cached = File(dir, "$itemId.jpg")
    if (cached.exists()) return@withContext cached
    if (app.auth.current == null) return@withContext null
    try {
      val client = app.api.client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
      client.newCall(Request.Builder().url(app.api.coverUrl(itemId, AbsTileService.COVER_PX)).build()).execute().use { response ->
        val body = response.body
        if (!response.isSuccessful || body == null) return@withContext null
        val part = File(dir, "$itemId.part")
        part.outputStream().use { body.byteStream().copyTo(it) }
        part.renameTo(cached)
      }
      cached.takeIf { it.exists() }
    } catch (e: Exception) {
      Log.d("CoverCache", "No cover for $itemId: ${e.message}")
      null
    }
  }
}

/** Small layout helpers shared by the tiles. */
object TileLayout {
  fun spacer(height: Float = 0f, width: Float = 0f): LayoutElementBuilders.Spacer =
    LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).setWidth(dp(width)).build()

  fun rounded(radius: Float, color: Int? = null): ModifiersBuilders.Background {
    val builder = ModifiersBuilders.Background.Builder().setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(radius)).build())
    if (color != null) builder.setColor(androidx.wear.protolayout.ColorBuilders.argb(color))
    return builder.build()
  }

  /** A cover image, or a plain rounded square when the item has none. */
  fun cover(id: String?, size: Float, modifiers: ModifiersBuilders.Modifiers.Builder): LayoutElementBuilders.LayoutElement =
    if (id != null) {
      LayoutElementBuilders.Image.Builder()
        .setResourceId(id)
        .setWidth(dp(size))
        .setHeight(dp(size))
        .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP)
        .setModifiers(modifiers.setBackground(rounded(6f)).build())
        .build()
    } else {
      LayoutElementBuilders.Box.Builder()
        .setWidth(dp(size))
        .setHeight(dp(size))
        .setModifiers(modifiers.setBackground(rounded(6f, 0xFF3A3A3A.toInt())).build())
        .build()
    }
}
