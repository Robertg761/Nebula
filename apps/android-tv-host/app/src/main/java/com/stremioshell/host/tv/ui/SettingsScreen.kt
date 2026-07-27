package com.stremioshell.host.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel

@Composable
fun SettingsScreen(viewModel: TvAppViewModel, onPairWithPhone: () -> Unit = {}) {
  val storedKey by viewModel.tmdbApiKey.collectAsState()
  val storedAddon by viewModel.addonManifestUrl.collectAsState()

  var tmdbKey by rememberSaveable { mutableStateOf("") }
  var addonUrl by rememberSaveable { mutableStateOf("") }
  var status by rememberSaveable { mutableStateOf("") }
  var seeded by rememberSaveable { mutableStateOf(false) }

  val tmdbInitialFocus = rememberInitialFocusTarget()
  val tmdbFocus = tmdbInitialFocus.requester
  val addonFocus = remember { FocusRequester() }
  val saveFocus = remember { FocusRequester() }

  LaunchedEffect(storedKey, storedAddon) {
    if (!seeded && storedKey != null && storedAddon != null) {
      tmdbKey = storedKey.orEmpty()
      addonUrl = storedAddon.orEmpty()
      seeded = true
    }
  }

  RequestInitialFocus(
    target = tmdbInitialFocus,
    key = Unit,
    label = "Settings TMDB key field",
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 48.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text("Settings", style = MaterialTheme.typography.headlineMedium)

    Button(onClick = onPairWithPhone) {
      Text("Set up with phone (scan a QR code)")
    }

    Text("TMDB API key (themoviedb.org > Settings > API)", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
      value = tmdbKey,
      onValueChange = { tmdbKey = it },
      singleLine = true,
      placeholder = { Text("TMDB API key") },
      colors = settingsFieldColors(),
      modifier = Modifier
        .fillMaxWidth(0.8f)
        .initialFocusTarget(tmdbInitialFocus)
        // A material3 text field traps the D-pad on TV, so move focus
        // between fields explicitly before it consumes the key.
        .verticalFieldNav(down = addonFocus, up = null),
    )

    Text(
      "Comet addon manifest URL (configure at your Comet instance with your Real-Debrid key)",
      style = MaterialTheme.typography.titleSmall,
    )
    OutlinedTextField(
      value = addonUrl,
      onValueChange = { addonUrl = it },
      singleLine = true,
      placeholder = { Text("https://comet.../<config>/manifest.json") },
      colors = settingsFieldColors(),
      modifier = Modifier
        .fillMaxWidth(0.8f)
        .focusRequester(addonFocus)
        .verticalFieldNav(down = saveFocus, up = tmdbFocus),
    )

    Button(
      onClick = { viewModel.saveSettings(tmdbKey, addonUrl) { status = it } },
      modifier = Modifier.focusRequester(saveFocus),
    ) {
      Text("Save")
    }

    if (status.isNotBlank()) {
      Text(status, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** Redirect D-pad up/down to explicit neighbours so text fields can't trap focus on TV. */
private fun Modifier.verticalFieldNav(down: FocusRequester?, up: FocusRequester?): Modifier {
  return onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
      Key.DirectionDown -> down?.let { runCatching { it.requestFocus() }; true } ?: false
      Key.DirectionUp -> up?.let { runCatching { it.requestFocus() }; true } ?: false
      else -> false
    }
  }
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = Color.White,
  unfocusedTextColor = Color.White,
)
