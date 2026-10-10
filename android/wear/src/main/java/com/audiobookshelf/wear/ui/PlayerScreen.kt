package com.audiobookshelf.wear.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import com.audiobookshelf.wear.data.Chapter
import com.audiobookshelf.wear.playback.HeadphoneGuard
import com.audiobookshelf.wear.playback.NowPlaying
import com.audiobookshelf.wear.playback.PlaybackService
import com.audiobookshelf.wear.playback.PlayingBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

private val SPEEDS = listOf(0.8f, 1.0f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2.0f)
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)

private data class PlayerState(
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val time: Double = 0.0,
  val speed: Float = 1f
)

@Composable
private fun rememberPlayerState(controller: MediaController?, playing: PlayingBook?): State<PlayerState> =
  produceState(PlayerState(), controller, playing) {
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

private fun MediaController.seekBook(playing: PlayingBook, time: Double) {
  val (index, positionMs) = playing.trackPosition(time)
  seekTo(index, positionMs)
}

private fun chapterBounds(playing: PlayingBook, time: Double): Chapter =
  playing.book.chapterAt(time) ?: Chapter(0.0, playing.book.duration, "")

private fun MediaController.skipChapter(playing: PlayingBook, time: Double, forward: Boolean) {
  val chapters = playing.book.chapters
  val chapter = chapterBounds(playing, time)
  if (forward) {
    chapters.firstOrNull { it.start > chapter.start }?.let { seekBook(playing, it.start) }
  } else {
    // Restart the chapter, or go to the previous one when already near its start
    val previous = chapters.lastOrNull { it.start < chapter.start }
    seekBook(playing, if (time - chapter.start > 3 || previous == null) chapter.start else previous.start)
  }
}

/**
 * Now playing screen. Sizes are fractions of the screen so the layout stays inside the round
 * display on every watch size. The outer ring shows progress through the book, the ring around the
 * play button shows progress through the chapter, and the crown/bezel controls the volume.
 */
@Composable
fun PlayerScreen(nav: NavHostController) {
  val context = LocalContext.current
  val controller by LocalPlayer.current.controller.collectAsState()
  val playing by NowPlaying.book.collectAsState()
  val state by rememberPlayerState(controller, playing)
  val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
  val focusRequester = remember { FocusRequester() }
  var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
  var volume by remember { mutableFloatStateOf(currentVolume(audioManager)) }
  var volumeShownAt by remember { mutableLongStateOf(0L) }
  val showVolume = volumeShownAt > 0

  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  // No headphones: ask where to play
  val waitingForHeadphones by NowPlaying.waitingForHeadphones.collectAsState()
  LaunchedEffect(waitingForHeadphones) {
    if (waitingForHeadphones) nav.navigate(Routes.AUDIO_OUTPUT) { launchSingleTop = true }
  }
  LaunchedEffect(volumeShownAt) {
    if (volumeShownAt > 0) {
      delay(1500)
      volumeShownAt = 0
    }
  }

  ScreenScaffold(
    modifier = Modifier
      .onRotaryScrollEvent {
        rotaryAccumulator += it.verticalScrollPixels
        if (abs(rotaryAccumulator) > 40f) {
          val direction = if (rotaryAccumulator > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
          audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
          volume = currentVolume(audioManager)
          volumeShownAt = System.currentTimeMillis()
          rotaryAccumulator = 0f
        }
        true
      }
      .focusRequester(focusRequester)
      .focusable()
  ) {
    val mc = controller
    val current = playing
    if (current == null || mc == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Nothing playing", style = MaterialTheme.typography.titleMedium)
      }
      return@ScreenScaffold
    }
    val book = current.book
    val chapter = chapterBounds(current, state.time)
    val chapterIndex = book.chapters.indexOf(chapter)
    val chapterLength = (chapter.end - chapter.start).coerceAtLeast(1.0)
    val ringAlpha by animateFloatAsState(if (showVolume) 0f else 1f, label = "ring")

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      val size = maxWidth

      // Book progress around the edge, leaving a gap at the top for the clock
      CircularProgressIndicator(
        progress = { if (book.duration > 0) (state.time / book.duration).toFloat() else 0f },
        startAngle = 300f,
        endAngle = 240f,
        strokeWidth = 4.dp,
        modifier = Modifier.fillMaxSize().padding(2.dp).alpha(ringAlpha)
      )

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = size * 0.14f)
      ) {
        // The cover scrolls together with a long title because the marquee is on the whole row
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
        ) {
          val app = LocalApp.current
          val coverFile = app.downloads.coverFile(book.mediaKey)
          Cover(
            model = if (current.isLocal && coverFile.exists()) coverFile else app.api.coverUrl(book.id, 64),
            size = 18.dp
          )
          Spacer(Modifier.width(4.dp))
          Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
        }
        Text(
          listOfNotNull(
            if (chapterIndex >= 0) "Ch ${chapterIndex + 1}" else null,
            "${formatClock((chapter.end - state.time) / state.speed)} left",
            if (state.speed != 1f) formatSpeed(state.speed) else null
          ).joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(size * 0.03f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size * 0.025f)) {
          FilledTonalIconButton(
            onClick = { mc.seekBack() },
            onLongClick = { mc.skipChapter(current, state.time, forward = false) },
            onLongClickLabel = "Previous chapter",
            modifier = Modifier.size(size * 0.19f)
          ) { Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds", modifier = Modifier.size(size * 0.1f)) }

          PlayPauseButton(
            size = size * 0.31f,
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering && state.isPlaying,
            // Read state inside the lambda so the ring follows playback
            chapterProgress = { ((state.time - chapter.start) / chapterLength).toFloat() },
            onClick = { if (mc.playWhenReady) mc.pause() else mc.play() }
          )

          FilledTonalIconButton(
            onClick = { mc.seekForward() },
            onLongClick = { mc.skipChapter(current, state.time, forward = true) },
            onLongClickLabel = "Next chapter",
            modifier = Modifier.size(size * 0.19f)
          ) { Icon(Icons.Default.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(size * 0.1f)) }
        }
        Spacer(Modifier.height(size * 0.02f))
        Row(horizontalArrangement = Arrangement.spacedBy(size * 0.06f)) {
          IconButton(
            onClick = { nav.navigate(Routes.CHAPTERS) },
            enabled = book.chapters.isNotEmpty(),
            modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
          ) { Icon(Icons.AutoMirrored.Default.FormatListBulleted, contentDescription = "Chapters") }
          IconButton(
            onClick = { nav.navigate(Routes.PLAYER_OPTIONS) },
            modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
          ) { Icon(Icons.Default.Tune, contentDescription = "Playback options") }
        }
      }

      // Volume appears along the edge while the crown is turned
      AnimatedVisibility(visible = showVolume, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        LevelIndicator(value = { volume })
      }
    }
  }
}

@Composable
private fun PlayPauseButton(size: Dp, isPlaying: Boolean, isBuffering: Boolean, chapterProgress: () -> Float, onClick: () -> Unit) {
  Box(Modifier.size(size), contentAlignment = Alignment.Center) {
    if (isBuffering) {
      CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp)
    } else {
      CircularProgressIndicator(
        progress = { chapterProgress().coerceIn(0f, 1f) },
        strokeWidth = 3.dp,
        modifier = Modifier.fillMaxSize()
      )
    }
    FilledIconButton(onClick = onClick, modifier = Modifier.size(size - 12.dp)) {
      Icon(
        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
        contentDescription = if (isPlaying) "Pause" else "Play",
        modifier = Modifier.size(size * 0.45f)
      )
    }
  }
}

private fun currentVolume(audioManager: AudioManager): Float {
  val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
  return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

@Composable
fun PlayerOptionsScreen(nav: NavHostController) {
  val context = LocalContext.current
  val controller by LocalPlayer.current.controller.collectAsState()
  val playing by NowPlaying.book.collectAsState()
  val state by rememberPlayerState(controller, playing)
  val sleepRemaining by PlaybackService.sleepRemainingMs.collectAsState()

  ListScreen {
    header("Playback")
    item { MenuButton(spec, Icons.Default.Speed, "Speed", formatSpeed(state.speed)) { nav.navigate(Routes.SPEED) } }
    item {
      MenuButton(spec, Icons.Default.Bedtime, "Sleep timer", sleepRemaining?.let { "${formatClock(it / 1000.0)} left" } ?: "Off") {
        nav.navigate(Routes.SLEEP)
      }
    }
    item {
      val output by remember { PlaybackService.current?.guard?.chosen ?: MutableStateFlow(null) }.collectAsState()
      val outputs by remember { PlaybackService.current?.guard?.outputs ?: MutableStateFlow(emptyList()) }.collectAsState()
      MenuButton(spec, Icons.Default.Bluetooth, "Audio output", (output ?: outputs.firstOrNull())?.let(::outputName)) { nav.navigate(Routes.AUDIO_OUTPUT) }
    }
    if (playing != null) {
      item {
        OutlinedButton(
          onClick = {
            PlaybackService.current?.unload()
            nav.popBackStack(Routes.HOME, inclusive = false)
          },
          modifier = itemModifier(spec),
          transformation = SurfaceTransformation(spec),
          icon = { Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
          secondaryLabel = { Text("Your place is saved") },
          label = { Text("Stop playback") }
        )
      }
    }
  }
}

@Composable
fun SpeedScreen(nav: NavHostController) {
  val context = LocalContext.current
  val controller by LocalPlayer.current.controller.collectAsState()
  val playing by NowPlaying.book.collectAsState()
  val state by rememberPlayerState(controller, playing)

  ListScreen {
    header("Speed")
    items(SPEEDS) { speed ->
      SelectableButton(spec, formatSpeed(speed), selected = abs(speed - state.speed) < 0.01f) {
        controller?.setPlaybackSpeed(speed)
        PlaybackService.savePlaybackSpeed(context, speed)
        nav.popBackStack()
      }
    }
  }
}

@Composable
fun SleepTimerScreen(nav: NavHostController) {
  val controller by LocalPlayer.current.controller.collectAsState()
  val playing by NowPlaying.book.collectAsState()
  val state by rememberPlayerState(controller, playing)
  val sleepRemaining by PlaybackService.sleepRemainingMs.collectAsState()

  ListScreen {
    header(sleepRemaining?.let { "Sleep in ${formatClock(it / 1000.0)}" } ?: "Sleep timer")
    if (sleepRemaining != null) {
      item {
        MenuButton(spec, Icons.Default.TimerOff, "Turn off") {
          PlaybackService.current?.cancelSleepTimer()
          nav.popBackStack()
        }
      }
    }
    playing?.let { current ->
      item {
        MenuButton(spec, Icons.Default.Timer, "End of chapter") {
          val remaining = (chapterBounds(current, state.time).end - state.time) / state.speed
          PlaybackService.current?.startSleepTimer((remaining * 1000).toLong())
          nav.popBackStack()
        }
      }
    }
    items(SLEEP_MINUTES) { minutes ->
      MenuButton(spec, Icons.Default.Timer, "$minutes minutes") {
        PlaybackService.current?.startSleepTimer(minutes * 60_000L)
        nav.popBackStack()
      }
    }
  }
}

@Composable
fun ChaptersScreen(nav: NavHostController) {
  val controller by LocalPlayer.current.controller.collectAsState()
  val playing by NowPlaying.book.collectAsState()
  val state by rememberPlayerState(controller, playing)
  val listState = rememberTransformingLazyColumnState()
  val current = playing
  val chapters = current?.book?.chapters.orEmpty()

  // Open with the current chapter in view (item 0 is the header)
  LaunchedEffect(current) {
    val time = controller?.let { mc -> current?.bookTime(mc) } ?: return@LaunchedEffect
    val index = chapters.indexOf(current?.book?.chapterAt(time))
    if (index > 0) listState.scrollToItem(index + 1)
  }

  ListScreen(state = listState) {
    header("Chapters")
    if (current == null) caption("Nothing playing")
    val currentChapter = current?.book?.chapterAt(state.time)
    items(chapters) { chapter ->
      SelectableButton(spec, chapter.title.ifEmpty { "Chapter" }, selected = chapter == currentChapter, secondary = formatClock(chapter.start)) {
        if (current != null) controller?.seekBook(current, chapter.start)
        nav.popBackStack()
      }
    }
  }
}

/** Single choice row; the radio control keeps labels aligned whether or not they're selected. */
@Composable
private fun TransformingLazyColumnItemScope.SelectableButton(
  spec: TransformationSpec,
  label: String,
  selected: Boolean,
  secondary: String? = null,
  onClick: () -> Unit
) {
  RadioButton(
    selected = selected,
    onSelect = onClick,
    modifier = itemModifier(spec),
    transformation = SurfaceTransformation(spec),
    secondaryLabel = secondary?.let { text -> { Text(text) } },
    label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
  )
}

fun outputName(device: AudioDeviceInfo): String = when (device.type) {
  AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Watch speaker"
  AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
  else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Headphones"
}

/**
 * Where to play: connected headphones, the watch speaker, or connecting a device. Opens by itself
 * when play is pressed without headphones, and goes back once an output is picked or connects.
 */
@Composable
fun AudioOutputScreen(nav: NavHostController) {
  val guard = PlaybackService.current?.guard
  val outputs by remember { guard?.outputs ?: MutableStateFlow(emptyList()) }.collectAsState()
  val chosen by remember { guard?.chosen ?: MutableStateFlow(null) }.collectAsState()
  val waiting by NowPlaying.waitingForHeadphones.collectAsState()
  // Opened because play was pressed without headphones
  val openedToPlay = remember { waiting }

  LaunchedEffect(waiting) {
    if (openedToPlay && !waiting) nav.popBackStack()
  }

  ListScreen {
    header("Play on")
    if (openedToPlay) caption("No headphones connected")
    // Without a choice the system plays on the first one: headphones if connected, else the speaker
    val current = chosen ?: outputs.firstOrNull().takeUnless { waiting }
    items(outputs, key = { it.id }) { device ->
      val speaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
      Button(
        onClick = {
          guard?.useOutput(device)
          if (!openedToPlay) nav.popBackStack()
        },
        modifier = itemModifier(spec),
        transformation = SurfaceTransformation(spec),
        colors = if (device.id == current?.id) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = {
          Icon(
            if (speaker) Icons.AutoMirrored.Default.VolumeUp else Icons.Default.Headphones,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize)
          )
        },
        secondaryLabel = if (device.id == current?.id) ({ Text("Playing here") }) else null,
        label = { Text(outputName(device), maxLines = 2, overflow = TextOverflow.Ellipsis) }
      )
    }
    item { MenuButton(spec, Icons.AutoMirrored.Default.BluetoothSearching, "Connect device", "Bluetooth headphones") { guard?.openBluetoothPicker() ?: nav.context.startActivity(HeadphoneGuard.bluetoothSettingsIntent()) } }
  }
}
