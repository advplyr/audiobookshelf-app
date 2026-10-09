package com.audiobookshelf.wear.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.audiobookshelf.wear.data.Chapter
import com.audiobookshelf.wear.playback.NowPlaying
import com.audiobookshelf.wear.playback.PlaybackService
import com.audiobookshelf.wear.playback.PlayingBook
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private val SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)
private val SLEEP_MINUTES = listOf(10, 15, 30, 45, 60)

private data class PlayerState(
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val time: Double = 0.0,
  val speed: Float = 1f
)

@Composable
private fun rememberPlayerState(controller: MediaController?, playing: PlayingBook?): PlayerState {
  val state by produceState(PlayerState(), controller, playing) {
    while (true) {
      value = if (controller == null || playing == null) PlayerState() else PlayerState(
        isPlaying = controller.playWhenReady,
        isBuffering = controller.playbackState == Player.STATE_BUFFERING,
        time = playing.bookTime(controller),
        speed = controller.playbackParameters.speed
      )
      delay(500)
    }
  }
  return state
}

private fun MediaController.seekBook(playing: PlayingBook, time: Double) {
  val (index, positionMs) = playing.trackPosition(time)
  seekTo(index, positionMs)
}

private fun chapterBounds(playing: PlayingBook, time: Double): Chapter {
  return playing.book.chapterAt(time) ?: Chapter(0.0, playing.book.duration, playing.book.title)
}

@Composable
fun PlayerScreen(nav: NavHostController) {
  val context = LocalContext.current
  val controller by LocalPlayer.current.controller.collectAsState()
  val playing by NowPlaying.book.collectAsState()
  val state = rememberPlayerState(controller, playing)
  val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
  val focusRequester = remember { FocusRequester() }
  val rotaryAccumulator = remember { floatArrayOf(0f) }

  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Scaffold(
    timeText = { TimeText() },
    modifier = Modifier
      // Turning the crown or bezel changes the volume
      .onRotaryScrollEvent {
        rotaryAccumulator[0] += it.verticalScrollPixels
        if (abs(rotaryAccumulator[0]) > 40f) {
          val direction = if (rotaryAccumulator[0] > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
          audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
          rotaryAccumulator[0] = 0f
        }
        true
      }
      .focusRequester(focusRequester)
      .focusable()
  ) {
    val book = playing?.book
    val mc = controller
    if (book == null || mc == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing playing") }
      return@Scaffold
    }
    val chapter = chapterBounds(playing!!, state.time)
    val chapters = book.chapters

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(
        progress = if (book.duration > 0) (state.time / book.duration).toFloat() else 0f,
        startAngle = 300f,
        endAngle = 240f,
        strokeWidth = 4.dp,
        modifier = Modifier.fillMaxSize().padding(3.dp)
      )
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp)
      ) {
        Text(
          chapter.title.ifEmpty { book.title },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.caption1,
          textAlign = TextAlign.Center
        )
        Text(
          "${formatClock(state.time - chapter.start)} / ${formatClock(chapter.end - chapter.start)}",
          style = MaterialTheme.typography.caption3,
          color = MaterialTheme.colors.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Button(
            onClick = { mc.seekBack() },
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
          ) { Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds") }
          Button(
            onClick = { if (mc.playWhenReady) mc.pause() else mc.play() },
            modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
          ) {
            if (state.isBuffering && state.isPlaying) {
              CircularProgressIndicator(modifier = Modifier.size(32.dp), indicatorColor = MaterialTheme.colors.onPrimary)
            } else {
              Icon(
                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(32.dp)
              )
            }
          }
          Button(
            onClick = { mc.seekForward() },
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
          ) { Icon(Icons.Default.Forward30, contentDescription = "Forward 30 seconds") }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Button(
            onClick = {
              // Restart the chapter, or go to the previous one if near its start
              val previous = chapters.lastOrNull { it.start < chapter.start }
              val target = if (state.time - chapter.start > 3 || previous == null) chapter.start else previous.start
              mc.seekBook(playing!!, target)
            },
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize)
          ) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter") }
          Button(
            onClick = { nav.navigate(Routes.PLAYER_OPTIONS) },
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize)
          ) { Icon(Icons.Default.MoreHoriz, contentDescription = "More") }
          Button(
            onClick = { chapters.firstOrNull { it.start > chapter.start }?.let { mc.seekBook(playing!!, it.start) } },
            enabled = chapters.any { it.start > chapter.start },
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize)
          ) { Icon(Icons.Default.SkipNext, contentDescription = "Next chapter") }
        }
        Text(
          "${formatSpeed(state.speed)} · ${formatDuration((book.duration - state.time) / state.speed)} left",
          style = MaterialTheme.typography.caption3,
          color = MaterialTheme.colors.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
fun PlayerOptionsScreen() {
  val context = LocalContext.current
  val controller by LocalPlayer.current.controller.collectAsState()
  val playing by NowPlaying.book.collectAsState()
  val state = rememberPlayerState(controller, playing)
  val sleepRemaining by PlaybackService.sleepRemainingMs.collectAsState()
  val mc = controller

  ListScreen {
    item { ListHeader { Text("Playback") } }
    item {
      Chip(
        label = { Text("Speed ${formatSpeed(state.speed)}") },
        secondaryLabel = { Text("Tap to change") },
        icon = { Icon(Icons.Default.Speed, contentDescription = null) },
        onClick = {
          val next = SPEEDS.firstOrNull { it > state.speed + 0.01f } ?: SPEEDS.first()
          mc?.setPlaybackSpeed(next)
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
    item {
      Chip(
        label = { Text("Audio output") },
        secondaryLabel = { Text("Bluetooth headphones") },
        icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
        onClick = { context.startActivity(bluetoothSettingsIntent()) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }

    item { ListHeader { Text(sleepRemaining?.let { "Sleep in ${formatClock(it / 1000.0)}" } ?: "Sleep timer") } }
    if (sleepRemaining != null) {
      item {
        Chip(
          label = { Text("Cancel sleep timer") },
          icon = { Icon(Icons.Default.Bedtime, contentDescription = null) },
          onClick = { PlaybackService.current?.cancelSleepTimer() },
          colors = ChipDefaults.secondaryChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
    SLEEP_MINUTES.forEach { minutes ->
      item {
        Chip(
          label = { Text("$minutes minutes") },
          onClick = { PlaybackService.current?.startSleepTimer(minutes) },
          colors = ChipDefaults.childChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    val book = playing?.book
    if (book != null) {
      item {
        Chip(
          label = { Text("Stop and close") },
          icon = { Icon(Icons.Default.Stop, contentDescription = null) },
          onClick = { PlaybackService.current?.unload() },
          colors = ChipDefaults.secondaryChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    if (book != null && book.chapters.isNotEmpty() && mc != null) {
      item { ListHeader { Text("Chapters") } }
      val current = book.chapterAt(state.time)
      itemsIndexed(book.chapters) { _, chapter ->
        Chip(
          label = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
          secondaryLabel = { Text(formatClock(chapter.start)) },
          onClick = { mc.seekBook(playing!!, chapter.start) },
          colors = if (chapter == current) ChipDefaults.primaryChipColors() else ChipDefaults.childChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }
}

private fun formatSpeed(speed: Float) = String.format(Locale.US, "%.1fx", speed)

/** Wear OS output switcher that closes once headphones connect. */
private fun bluetoothSettingsIntent() = Intent("android.settings.BLUETOOTH_SETTINGS").apply {
  putExtra("EXTRA_CONNECTION_ONLY", true)
  putExtra("EXTRA_CLOSE_ON_CONNECT", true)
  putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
}
