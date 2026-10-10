package com.audiobookshelf.wear.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.compose.foundation.layout.size
import com.audiobookshelf.wear.data.ApiException
import kotlinx.coroutines.launch

private enum class Field { SERVER, USERNAME, PASSWORD }

/**
 * Signs in through the phone app when it's signed in, with no typing. Otherwise the watch can
 * sign in on its own; the server address and username are prefilled from the phone when known.
 */
@Composable
fun LoginScreen() {
  val app = LocalApp.current
  val scope = rememberCoroutineScope()
  val phoneConfig by app.phoneConfig.config.collectAsState()

  var address by rememberSaveable { mutableStateOf("") }
  var username by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var editing by rememberSaveable { mutableStateOf(Field.SERVER) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var phoneBusy by remember { mutableStateOf(false) }
  var phoneError by remember { mutableStateOf<String?>(null) }

  val input = rememberTextInput { text ->
    when (editing) {
      Field.SERVER -> address = text
      Field.USERNAME -> username = text
      Field.PASSWORD -> password = text
    }
    error = null
  }

  fun signInWithPhone() {
    if (phoneBusy) return
    phoneBusy = true
    phoneError = null
    scope.launch {
      try {
        app.auth.save(app.phoneLink.requestAccount())
      } catch (e: Exception) {
        phoneError = describeError(e)
      } finally {
        phoneBusy = false
      }
    }
  }

  LaunchedEffect(Unit) {
    // Not right after the user signed out on the watch, or it would sign straight back in
    if (!app.auth.signedOutByUser) signInWithPhone()
    app.phoneConfig.refreshFromPhone()
  }
  LaunchedEffect(phoneConfig) {
    phoneConfig?.let {
      if (address.isEmpty()) address = it.serverAddress
      if (username.isEmpty()) username = it.username
    }
  }

  val canSignIn = address.isNotBlank() && username.isNotBlank() && !busy

  fun signIn() {
    if (!canSignIn) return
    busy = true
    error = null
    scope.launch {
      try {
        val headers = phoneConfig?.takeIf { it.serverAddress == address }?.customHeaders ?: emptyMap()
        app.auth.save(app.api.login(address, username, password, headers))
      } catch (e: ApiException) {
        error = if (e.code == 401) "Wrong username or password" else "Server error (${e.code})"
      } catch (e: Exception) {
        error = describeError(e)
      } finally {
        busy = false
      }
    }
  }

  ListScreen(
    edgeButton = {
      EdgeButton(
        onClick = ::signInWithPhone,
        enabled = !phoneBusy,
        buttonSize = EdgeButtonSize.Medium,
        colors = ButtonDefaults.filledTonalButtonColors()
      ) {
        if (phoneBusy) CircularProgressIndicator(modifier = Modifier.size(24.dp)) else Text("Use phone", maxLines = 1, softWrap = false)
      }
    }
  ) {
    header("Sign in")
    when {
      phoneBusy -> caption("Signing in with your phone…")
      phoneError != null -> caption(phoneError!!) { MaterialTheme.colorScheme.error }
      else -> caption("Sign in on your phone, then tap Use phone")
    }
    caption("Or sign in on the watch")
    item {
      FieldButton(spec, Icons.Default.Dns, "Server", address.removePrefix("https://").removePrefix("http://")) {
        editing = Field.SERVER
        input("Server address")
      }
    }
    item {
      FieldButton(spec, Icons.Default.Person, "Username", username) {
        editing = Field.USERNAME
        input("Username")
      }
    }
    item {
      FieldButton(spec, Icons.Default.Password, "Password", "•".repeat(password.length.coerceAtMost(12))) {
        editing = Field.PASSWORD
        input("Password")
      }
    }
    item {
      Button(
        onClick = ::signIn,
        enabled = canSignIn,
        modifier = itemModifier(spec),
        transformation = SurfaceTransformation(spec),
        icon = {
          if (busy) CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
          else Icon(Icons.AutoMirrored.Default.Login, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        },
        label = { Text("Sign in") }
      )
    }
    error?.let { message -> caption(message) { MaterialTheme.colorScheme.error } }
  }
}

@Composable
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope.FieldButton(
  spec: androidx.wear.compose.material3.lazy.TransformationSpec,
  icon: ImageVector,
  label: String,
  value: String,
  onClick: () -> Unit
) {
  FilledTonalButton(
    onClick = onClick,
    modifier = itemModifier(spec),
    transformation = SurfaceTransformation(spec),
    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
    secondaryLabel = {
      Text(
        value.ifEmpty { "Tap to enter" },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (value.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
      )
    },
    label = { Text(label) }
  )
}
