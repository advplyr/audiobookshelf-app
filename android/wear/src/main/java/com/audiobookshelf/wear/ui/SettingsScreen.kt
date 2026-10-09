package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  val account by app.auth.account.collectAsState()
  var pending by remember { mutableIntStateOf(app.progress.pendingSessions().size) }
  var syncing by remember { mutableStateOf(false) }
  var confirmSignOut by remember { mutableStateOf(false) }

  ListScreen {
    item { ListHeader { Text("Settings") } }
    item {
      Text(
        "${account?.username}\n${account?.serverAddress}",
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center
      )
    }
    item {
      Chip(
        label = { Text(if (syncing) "Syncing…" else "Sync progress now") },
        secondaryLabel = { Text(if (pending == 0) "Everything synced" else "$pending sessions waiting") },
        icon = { Icon(Icons.Default.Sync, contentDescription = null) },
        onClick = {
          syncing = true
          scope.launch {
            app.sessionSync.flushPending()
            app.phoneConfig.refreshFromPhone()
            pending = app.progress.pendingSessions().size
            syncing = false
          }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
    item {
      Chip(
        label = { Text(if (confirmSignOut) "Tap again to sign out" else "Sign out") },
        secondaryLabel = { Text("Downloads stay on the watch") },
        icon = { Icon(Icons.AutoMirrored.Default.Logout, contentDescription = null) },
        onClick = { if (confirmSignOut) app.auth.clear() else confirmSignOut = true },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}
