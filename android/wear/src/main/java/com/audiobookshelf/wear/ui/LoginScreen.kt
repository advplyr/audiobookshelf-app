package com.audiobookshelf.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.audiobookshelf.wear.data.ApiException
import kotlinx.coroutines.launch

/**
 * The watch keeps its own session with the server so it keeps working away from the phone.
 * The server address and username come from the phone app, so usually only the password is needed.
 */
@Composable
fun LoginScreen() {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  val phoneConfig by app.phoneConfig.config.collectAsState()

  var address by remember { mutableStateOf("") }
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) { app.phoneConfig.refreshFromPhone() }
  LaunchedEffect(phoneConfig) {
    phoneConfig?.let {
      if (address.isEmpty()) address = it.serverAddress
      if (username.isEmpty()) username = it.username
    }
  }

  fun signIn() {
    if (busy || address.isBlank() || username.isBlank()) return
    busy = true
    error = null
    scope.launch {
      try {
        val headers = phoneConfig?.takeIf { it.serverAddress == address }?.customHeaders ?: emptyMap()
        app.auth.save(app.api.login(address, username.trim(), password, headers))
      } catch (e: ApiException) {
        error = if (e.code == 401) "Wrong username or password" else "Server error (${e.code})"
      } catch (e: Exception) {
        error = "Can't reach server"
      } finally {
        busy = false
      }
    }
  }

  ListScreen {
    item { ListHeader { Text("Sign in") } }
    item {
      val hint = if (phoneConfig != null) "Server details from your phone" else "Open Audiobookshelf on your phone to fill these in"
      Text(hint, style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center)
    }
    item { WearTextField(address, { address = it }, "Server address", keyboardType = KeyboardType.Uri) }
    item { WearTextField(username, { username = it }, "Username") }
    item { WearTextField(password, { password = it }, "Password", password = true, onDone = ::signIn) }
    error?.let { message ->
      item { Text(message, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
    }
    item {
      if (busy) {
        CircularProgressIndicator()
      } else {
        Chip(
          label = { Text("Sign in") },
          onClick = ::signIn,
          enabled = address.isNotBlank() && username.isNotBlank(),
          colors = ChipDefaults.primaryChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }
}
