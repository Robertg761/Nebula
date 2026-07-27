package com.stremioshell.host.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.SettingsSaveGuard
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient

@Composable
fun SettingsScreen(viewModel: TvAppViewModel, onPairWithPhone: () -> Unit = {}) {
  val storedKey by viewModel.tmdbApiKey.collectAsState()
  val storedAddons by viewModel.addonManifestUrls.collectAsState()
  val storedSubtitles by viewModel.subtitlesBaseUrl.collectAsState()

  var tmdbKey by rememberSaveable { mutableStateOf("") }
  var newAddonUrl by rememberSaveable { mutableStateOf("") }
  var subtitlesUrl by rememberSaveable { mutableStateOf("") }
  var status by rememberSaveable { mutableStateOf("") }
  var seeded by rememberSaveable { mutableStateOf(false) }
  var advanced by rememberSaveable { mutableStateOf(false) }

  val addons = storedAddons.orEmpty()
  val addonLabels = remember(addons) { AddonList.labels(addons) }

  // Only the nodes a text field has to aim at need one of these. Button-to-button
  // moves are left to the default focus search, which handles them; a text field is
  // the one thing on the screen that would swallow the key first.
  val tmdbInitialFocus = rememberInitialFocusTarget()
  val clearKeyFocus = remember { FocusRequester() }
  val lastRemoveFocus = remember { FocusRequester() }
  val addButtonFocus = remember { FocusRequester() }
  val advancedFocus = remember { FocusRequester() }

  LaunchedEffect(storedKey, storedSubtitles) {
    if (!seeded && storedKey != null && storedSubtitles != null) {
      tmdbKey = storedKey.orEmpty()
      // Shown blank when it is the built-in default, so the field reads as "nothing
      // to configure here" rather than daring the viewer to edit a URL that works.
      subtitlesUrl = storedSubtitles.orEmpty()
        .takeIf { it != SubtitlesClient.OPENSUBTITLES_V3_BASE }
        .orEmpty()
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
        .fieldNav(down = clearKeyFocus, up = null),
    )
    // Under the field rather than beside it. Every button on this screen is reachable
    // by pressing down, which is the only direction a text field can be talked out of:
    // left and right are the caret's, and a button that needed one of those to reach
    // would be unreachable from a focused field.
    Button(
      onClick = {
        tmdbKey = ""
        viewModel.clearTmdbKey()
        status = "TMDB key cleared."
      },
      modifier = Modifier.focusRequester(clearKeyFocus),
    ) {
      Text("Clear key")
    }

    Text("Stream addons", style = MaterialTheme.typography.titleSmall)
    Text(
      "Asked in this order. The first addon offering a release is the one you get; " +
        "everything else is merged in and sorted by quality.",
      style = MaterialTheme.typography.bodySmall,
    )

    if (addons.isEmpty()) {
      Text(
        "No addons yet. Add your Comet manifest URL (configure it at your Comet " +
          "instance with your Real-Debrid key).",
        style = MaterialTheme.typography.bodySmall,
      )
    }

    addons.forEachIndexed { index, url ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(0.85f),
      ) {
        Column(modifier = Modifier.fillMaxWidth(0.72f)) {
          Text("${index + 1}. ${addonLabels[index]}", style = MaterialTheme.typography.titleSmall)
          Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Button(
          onClick = {
            viewModel.removeAddon(url)
            status = "Removed ${addonLabels[index]}."
            // Rows are positional, so removing one from the middle hands this node to
            // the row that shifts up and focus rides along. The bottom row has nothing
            // to hand it to and its node just goes, which leaves the D-pad dead until
            // something else claims focus - so aim it somewhere that will still exist.
            if (index == addons.lastIndex) runCatching { addButtonFocus.requestFocus() }
          },
          // Where the field below the list sends its D-pad up, so it lands on the
          // nearest row rather than skipping the whole list.
          modifier = if (index == addons.lastIndex) {
            Modifier.focusRequester(lastRemoveFocus)
          } else {
            Modifier
          },
        ) {
          Text("Remove")
        }
      }
    }

    OutlinedTextField(
      value = newAddonUrl,
      onValueChange = { newAddonUrl = it },
      singleLine = true,
      placeholder = { Text("https://comet.../<config>/manifest.json") },
      colors = settingsFieldColors(),
      modifier = Modifier
        .fillMaxWidth(0.8f)
        .fieldNav(down = addButtonFocus, up = if (addons.isEmpty()) clearKeyFocus else lastRemoveFocus),
    )
    Button(
      onClick = {
        // Persisted on press rather than staged behind Save: a list whose edits only
        // land later shows a configuration that is not the one being used.
        val normalized = AddonList.normalize(newAddonUrl)
        status = when {
          normalized.isEmpty() -> "Enter an addon URL first."
          normalized in addons -> "That addon is already in the list."
          addons.size >= AddonList.MAX_ADDONS -> "That's the most addons the list holds."
          else -> {
            viewModel.addAddon(newAddonUrl)
            newAddonUrl = ""
            "Added ${AddonList.label(normalized)}."
          }
        }
      },
      modifier = Modifier.focusRequester(addButtonFocus),
    ) {
      Text("Add addon")
    }

    Button(onClick = { viewModel.saveSettings(tmdbKey, addons, subtitlesUrl) { status = it } }) {
      Text("Save")
    }

    if (status.isNotBlank()) {
      Text(status, style = MaterialTheme.typography.bodyMedium)
    }

    Button(
      onClick = { advanced = !advanced },
      modifier = Modifier.focusRequester(advancedFocus),
    ) {
      Text(if (advanced) "Advanced (hide)" else "Advanced")
    }

    if (advanced) {
      Text("Subtitles addon URL", style = MaterialTheme.typography.titleSmall)
      Text(
        "Leave blank for the built-in OpenSubtitles v3 addon " +
          "(${SubtitlesClient.OPENSUBTITLES_V3_BASE}).",
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedTextField(
        value = subtitlesUrl,
        onValueChange = { subtitlesUrl = it },
        singleLine = true,
        placeholder = { Text(SubtitlesClient.OPENSUBTITLES_V3_BASE) },
        colors = settingsFieldColors(),
        // Reached by the default focus search from the Advanced button above it; only
        // the way back out has to be spelled, because the field would eat it.
        modifier = Modifier
          .fillMaxWidth(0.8f)
          .fieldNav(down = null, up = advancedFocus),
      )
      Text(
        "Saved as: ${SettingsSaveGuard.normalizeSubtitlesBase(subtitlesUrl)}",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/**
 * Sends D-pad up and down to explicit neighbours before the text field can eat them.
 *
 * A key is only reported handled when focus actually moved. requestFocus throws for
 * a requester attached to nothing - the addon list is empty, the Advanced section is
 * collapsed - and the previous version of this claimed those keys anyway, which left
 * the viewer pressing a direction that did nothing at all.
 */
private fun Modifier.fieldNav(down: FocusRequester?, up: FocusRequester?): Modifier =
  onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val target = when (event.key) {
      Key.DirectionDown -> down
      Key.DirectionUp -> up
      // Left and right belong to the caret. Nothing on this screen needs them: every
      // button sits under the field it belongs to, not beside it.
      else -> null
    }
    target != null && runCatching { target.requestFocus() }.isSuccess
  }

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = Color.White,
  unfocusedTextColor = Color.White,
)
