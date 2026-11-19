package com.audiobookshelf.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Warm gold palette taken from the Audiobookshelf app icon, tuned for an always-dark watch face. */
private val AbsColorScheme = ColorScheme(
  primary = Color(0xFFF2C063),
  primaryDim = Color(0xFFD9A84F),
  primaryContainer = Color(0xFF5C4300),
  onPrimary = Color(0xFF402D00),
  onPrimaryContainer = Color(0xFFFFDEA6),
  secondary = Color(0xFFDCC5A1),
  secondaryDim = Color(0xFFC2AC89),
  secondaryContainer = Color(0xFF3E3424),
  onSecondary = Color(0xFF3B2F1A),
  onSecondaryContainer = Color(0xFFF2E1C2),
  tertiary = Color(0xFF7DD8B0),
  tertiaryDim = Color(0xFF64BD97),
  tertiaryContainer = Color(0xFF00513A),
  onTertiary = Color(0xFF00382A),
  onTertiaryContainer = Color(0xFF99F5CA),
  surfaceContainerLow = Color(0xFF1A1611),
  surfaceContainer = Color(0xFF26211A),
  surfaceContainerHigh = Color(0xFF332C23),
  onSurface = Color(0xFFF0E6DA),
  onSurfaceVariant = Color(0xFFD3C4B2),
  outline = Color(0xFF9C8F7E),
  outlineVariant = Color(0xFF4F4539)
)

@Composable
fun AbsTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = AbsColorScheme, content = content)
}
