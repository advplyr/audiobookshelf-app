package com.audiobookshelf.wear.ui

import android.app.RemoteInput
import android.content.Intent
import android.util.Log
import com.audiobookshelf.wear.data.ApiException
import com.audiobookshelf.wear.sync.PhoneSignedOutException
import com.audiobookshelf.wear.sync.PhoneUnavailableException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import coil.compose.AsyncImage
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.playback.PlayerConnection
import java.util.Locale

val LocalApp = staticCompositionLocalOf<WearApp> { error("No app") }
val LocalPlayer = staticCompositionLocalOf<PlayerConnection> { error("No player") }

sealed interface Load<out T> {
  data object Loading : Load<Nothing>
  data class Ready<T>(val value: T) : Load<T>
  data class Failed(val message: String) : Load<Nothing>
}

/** Runs [block] when first shown and whenever retry() is called. */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
  var attempt by remember { mutableIntStateOf(0) }
  var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
  LaunchedEffect(*keys, attempt) {
    state = Load.Loading
    state = try {
      Load.Ready(block())
    } catch (e: Exception) {
      Log.w("Load", "Loading failed", e)
      Load.Failed(describeError(e))
    }
  }
  return state to { attempt++ }
}

/** A short, human readable reason for a failed request. */
fun describeError(e: Exception): String = when {
  e is ApiException && e.code == 401 -> "Session expired"
  e is ApiException -> "Server error (${e.code})"
  // Already written for the user, e.g. "Phone not connected"
  e is PhoneUnavailableException || e is PhoneSignedOutException -> e.message.orEmpty()
  e is java.io.IOException -> "Can't reach the server"
  else -> e.message?.take(60) ?: "Something went wrong"
}

/** Gives list items access to the shared edge-morphing transformation. */
class ListScope(val scope: TransformingLazyColumnScope, val spec: TransformationSpec)

/**
 * Standard scrolling screen. Items shrink and fade as they reach the round edges, the scroll
 * indicator follows the bezel, and an optional [edgeButton] hugs the bottom curve of the screen.
 */
@Composable
fun ListScreen(
  state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
  edgeButton: (@Composable BoxScope.() -> Unit)? = null,
  content: ListScope.() -> Unit
) {
  val spec = rememberTransformationSpec()
  val list: @Composable BoxScope.(androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
    TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
      ListScope(this, spec).content()
    }
  }
  if (edgeButton != null) {
    ScreenScaffold(scrollState = state, edgeButton = edgeButton, content = list)
  } else {
    ScreenScaffold(scrollState = state, content = list)
  }
}

fun ListScope.item(key: Any? = null, content: @Composable TransformingLazyColumnItemScope.() -> Unit) =
  scope.item(key = key) { content() }

fun <T> ListScope.items(items: List<T>, key: ((T) -> Any)? = null, content: @Composable TransformingLazyColumnItemScope.(T) -> Unit) =
  scope.items(items.size, key = key?.let { k -> { index: Int -> k(items[index]) } }) { index -> content(items[index]) }

/** Modifier for a full width item that morphs at the screen edges. */
@Composable
fun TransformingLazyColumnItemScope.itemModifier(spec: TransformationSpec): Modifier =
  Modifier.fillMaxWidth().transformedHeight(this, spec)

/** Modifier for plain (non surface) content such as text, which fades and scales at the edges. */
@Composable
fun TransformingLazyColumnItemScope.contentModifier(spec: TransformationSpec): Modifier =
  Modifier.fillMaxWidth().transformedHeight(this, spec).graphicsLayer {
    with(spec) { applyContentTransformation(scrollProgress) }
  }

fun ListScope.header(text: String) = item {
  ListHeader(modifier = itemModifier(spec), transformation = SurfaceTransformation(spec)) {
    Text(text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

fun ListScope.caption(text: String, color: @Composable () -> androidx.compose.ui.graphics.Color = { MaterialTheme.colorScheme.onSurfaceVariant }) = item {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = color(),
    textAlign = TextAlign.Center,
    modifier = contentModifier(spec).padding(horizontal = 8.dp)
  )
}

fun ListScope.loadingItem() = item {
  CircularProgressIndicator(modifier = contentModifier(spec).size(36.dp))
}

fun ListScope.errorItem(message: String, onRetry: () -> Unit) {
  caption(message) { MaterialTheme.colorScheme.error }
  item {
    FilledTonalButton(
      onClick = onRetry,
      modifier = itemModifier(spec),
      transformation = SurfaceTransformation(spec),
      icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
      label = { Text("Try again") }
    )
  }
}

/** A list button with a book cover on the left. */
@Composable
fun TransformingLazyColumnItemScope.BookButton(
  spec: TransformationSpec,
  title: String,
  subtitle: String,
  cover: Any?,
  onClick: () -> Unit,
  primary: Boolean = false
) {
  Button(
    onClick = onClick,
    modifier = itemModifier(spec),
    transformation = SurfaceTransformation(spec),
    colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
    icon = { Cover(cover, ButtonDefaults.LargeIconSize) },
    secondaryLabel = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    label = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
  )
}

/** Small determinate progress ring for icon slots, where the full size indicator doesn't fit. */
@Composable
fun ProgressRing(progress: Float, size: Dp, modifier: Modifier = Modifier) {
  val track = MaterialTheme.colorScheme.outlineVariant
  val indicator = MaterialTheme.colorScheme.primary
  androidx.compose.foundation.Canvas(modifier.size(size)) {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val inset = stroke.width / 2
    val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
    drawArc(track, 0f, 360f, false, topLeft, arcSize, style = stroke)
    drawArc(indicator, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, arcSize, style = stroke)
  }
}

@Composable
fun Cover(model: Any?, size: Dp, modifier: Modifier = Modifier) {
  AsyncImage(
    model = model,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = modifier.size(size).clip(RoundedCornerShape(size / 6))
  )
}

/**
 * Opens the system's full screen keyboard (with voice input) and returns the text. Inline text
 * fields don't fit on a round watch, so this is the Wear OS way to enter text.
 */
@Composable
fun rememberTextInput(onResult: (String) -> Unit): (label: String) -> Unit {
  val callback by rememberUpdatedState(onResult)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()
    if (text != null) callback(text.trim())
  }
  return { label ->
    val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    val remoteInput = RemoteInput.Builder(INPUT_KEY)
      .setLabel(label)
      // Emoji make no sense for addresses, passwords or book searches; keyboard and voice remain
      .wearableExtender { setEmojisAllowed(false) }
      .build()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    launcher.launch(intent)
  }
}

private const val INPUT_KEY = "text"

fun formatDuration(seconds: Double): String {
  val total = seconds.toLong().coerceAtLeast(0)
  val h = total / 3600
  val m = (total % 3600) / 60
  return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun formatClock(seconds: Double): String {
  val total = seconds.toLong().coerceAtLeast(0)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatBytes(bytes: Long): String {
  val mb = bytes / (1024.0 * 1024.0)
  return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
}

fun formatSpeed(speed: Float): String = String.format(Locale.US, "%.1f×", speed).replace(".0×", "×")
