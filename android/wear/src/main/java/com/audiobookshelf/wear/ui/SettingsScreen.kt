package com.audiobookshelf.wear.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonDefaults
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.audiobookshelf.wear.sync.ServerRoute
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  val account by app.auth.account.collectAsState()
  var pending by remember { mutableIntStateOf(app.progress.pendingSessions().size) }
  var syncing by remember { mutableStateOf(false) }
  var confirmSignOut by remember { mutableStateOf(false) }
  val classicHome by app.contentSettings.classicHome.collectAsState()
  val route by app.serverRoute.route.collectAsState()

  ListScreen(
    edgeButton = {
      EdgeButton(
        onClick = { confirmSignOut = true },
        buttonSize = EdgeButtonSize.Medium,
        colors = ButtonDefaults.filledTonalButtonColors()
      ) {
        // Small watches only have room for the label
        if (LocalConfiguration.current.screenWidthDp >= 210) {
          Icon(
            Icons.AutoMirrored.Default.Logout,
            contentDescription = null,
            modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize).align(Alignment.CenterVertically)
          )
        }
        Text("Sign out", maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterVertically))
      }
    }
  ) {
    header("Settings")
    caption(account?.username.orEmpty() + if (account?.linkedToPhone == true) " · via phone" else "")
    caption(account?.serverAddress?.removePrefix("https://")?.removePrefix("http://").orEmpty())
    item {
      val (icon, label) = when (route) {
        ServerRoute.Route.DIRECT -> Icons.Default.Wifi to "Direct"
        ServerRoute.Route.PHONE -> Icons.Default.Smartphone to "Through your phone"
        ServerRoute.Route.OFFLINE -> Icons.Default.CloudOff to "Offline"
      }
      // Tapping checks again, e.g. right after joining Wi-Fi
      MenuButton(spec, icon, "Connection", label) {
        app.serverRoute.probeSoon()
      }
    }
    item {
      MenuButton(
        spec,
        if (pending == 0) Icons.Default.CloudDone else Icons.Default.Sync,
        if (syncing) "Syncing…" else "Sync progress",
        if (pending == 0) "Everything is synced" else "$pending sessions waiting"
      ) {
        if (syncing) return@MenuButton
        syncing = true
        scope.launch {
          app.sessionSync.flushPending()
          app.phoneConfig.refreshFromPhone()
          pending = app.progress.pendingSessions().size
          syncing = false
        }
      }
    }
    item {
      SwitchButton(
        checked = classicHome,
        onCheckedChange = { app.contentSettings.setClassicHome(it) },
        modifier = itemModifier(spec),
        transformation = SurfaceTransformation(spec),
        icon = { Icon(Icons.AutoMirrored.Default.ViewList, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
        label = { Text("Scrollable homepage") }
      )
    }
  }

  AlertDialog(
    visible = confirmSignOut,
    onDismissRequest = { confirmSignOut = false },
    icon = { Icon(Icons.AutoMirrored.Default.Logout, contentDescription = null) },
    title = { Text("Sign out?") },
    text = { Text("Downloaded books stay on the watch.") },
    confirmButton = {
      AlertDialogDefaults.ConfirmButton(onClick = {
        confirmSignOut = false
        app.auth.signOut()
      })
    },
    dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmSignOut = false }) }
  )
}
