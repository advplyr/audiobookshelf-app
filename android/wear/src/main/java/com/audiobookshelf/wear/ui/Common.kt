package com.audiobookshelf.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
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
      Load.Failed(e.message?.take(80) ?: "Something went wrong")
    }
  }
  return state to { attempt++ }
}

/** Standard scrolling screen with the time at the top and a scroll indicator. */
@Composable
fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
  val listState = rememberScalingLazyListState()
  Scaffold(
    timeText = { TimeText() },
    positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
  ) {
    ScalingLazyColumn(
      modifier = Modifier.fillMaxWidth(),
      state = listState,
      content = content
    )
  }
}

fun ScalingLazyListScope.loadingItem() {
  item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
}

fun ScalingLazyListScope.errorItem(message: String, onRetry: () -> Unit) {
  item {
    Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption2)
  }
  item {
    Chip(label = { Text("Retry") }, onClick = onRetry, colors = ChipDefaults.secondaryChipColors())
  }
}

@Composable
fun WearTextField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  password: Boolean = false,
  keyboardType: KeyboardType = KeyboardType.Text,
  onDone: () -> Unit = {}
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colors.surface, RoundedCornerShape(24.dp))
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    if (value.isEmpty()) {
      Text(placeholder, color = MaterialTheme.colors.onSurfaceVariant, fontSize = 14.sp)
    }
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
      cursorBrush = SolidColor(MaterialTheme.colors.primary),
      visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
      keyboardOptions = KeyboardOptions(
        keyboardType = if (password) KeyboardType.Password else keyboardType,
        imeAction = ImeAction.Done,
        autoCorrectEnabled = false
      ),
      keyboardActions = KeyboardActions(onDone = { onDone() }),
      modifier = Modifier.fillMaxWidth()
    )
  }
}

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
