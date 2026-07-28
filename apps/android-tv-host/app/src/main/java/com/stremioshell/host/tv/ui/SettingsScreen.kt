package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.SettingsSaveGuard
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes

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
      .padding(horizontal = NebulaDimens.ScreenEdge, vertical = 32.dp),
    verticalArrangement = Arrangement.spacedBy(NebulaDimens.RailGap),
  ) {
    ScreenHeader("Settings")

    NebulaButton(
      text = "Set up with phone (scan a QR code)",
      onClick = onPairWithPhone,
      icon = Icons.Filled.Phone,
    )

    SettingsSection(
      title = "TMDB",
      description = "TMDB API key (themoviedb.org > Settings > API)",
    ) {
      OutlinedTextField(
        value = tmdbKey,
        onValueChange = { tmdbKey = it },
        singleLine = true,
        placeholder = { Text("TMDB API key") },
        shape = NebulaShapes.medium,
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
      NebulaButton(
        text = "Clear key",
        onClick = {
          tmdbKey = ""
          viewModel.clearTmdbKey()
          status = "TMDB key cleared."
        },
        style = NebulaButtonStyle.Ghost,
        modifier = Modifier.focusRequester(clearKeyFocus),
      )
    }

    SettingsSection(
      title = "Stream addons",
      description = "Asked in this order. The first addon offering a release is the one you get; " +
        "everything else is merged in and sorted by quality.",
    ) {
      if (addons.isEmpty()) {
        Text(
          "No addons yet. Add your Comet manifest URL (configure it at your Comet " +
            "instance with your Real-Debrid key).",
          style = MaterialTheme.typography.bodySmall,
          color = NebulaPalette.TextMuted,
        )
      }

      addons.forEachIndexed { index, url ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .background(NebulaPalette.SurfaceVariant, NebulaShapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          NebulaBadge(text = "${index + 1}", tone = BadgeTone.Accent)
          Column(modifier = Modifier.fillMaxWidth(0.65f)) {
            Text(
              addonLabels[index],
              style = MaterialTheme.typography.titleSmall,
              color = NebulaPalette.TextHigh,
            )
            Text(
              url,
              style = MaterialTheme.typography.bodySmall,
              color = NebulaPalette.TextMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          NebulaButton(
            text = "Remove",
            style = NebulaButtonStyle.Ghost,
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
          )
        }
      }

      OutlinedTextField(
        value = newAddonUrl,
        onValueChange = { newAddonUrl = it },
        singleLine = true,
        placeholder = { Text("https://comet.../<config>/manifest.json") },
        shape = NebulaShapes.medium,
        colors = settingsFieldColors(),
        modifier = Modifier
          .fillMaxWidth(0.8f)
          .fieldNav(down = addButtonFocus, up = if (addons.isEmpty()) clearKeyFocus else lastRemoveFocus),
      )
      NebulaButton(
        text = "Add addon",
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
      )
    }

    NebulaButton(
      text = "Save",
      onClick = { viewModel.saveSettings(tmdbKey, addons, subtitlesUrl) { status = it } },
      style = NebulaButtonStyle.Primary,
    )

    if (status.isNotBlank()) {
      StatusStrip(status)
    }

    NebulaButton(
      text = "Advanced",
      icon = if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
      style = NebulaButtonStyle.Ghost,
      onClick = { advanced = !advanced },
      // The chevron is the only thing that says which way this button goes, and it is drawn
      // decoratively, so open/closed has to be spelled out for anything not looking at it.
      modifier = Modifier
        .focusRequester(advancedFocus)
        .semantics(mergeDescendants = true) {
          contentDescription = if (advanced) "Advanced, expanded" else "Advanced, collapsed"
        },
    )

    if (advanced) {
      SettingsSection(
        title = "Advanced",
        description = "Subtitles addon URL - leave blank for the built-in OpenSubtitles v3 addon " +
          "(${SubtitlesClient.OPENSUBTITLES_V3_BASE}).",
      ) {
        OutlinedTextField(
          value = subtitlesUrl,
          onValueChange = { subtitlesUrl = it },
          singleLine = true,
          placeholder = { Text(SubtitlesClient.OPENSUBTITLES_V3_BASE) },
          shape = NebulaShapes.medium,
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
          color = NebulaPalette.TextMuted,
        )
      }
    }
  }
}

/**
 * A card for one group of related settings. Splitting the screen into these is what turned it
 * from one undifferentiated scroll into something a viewer can scan section by section from
 * across the room.
 */
@Composable
private fun SettingsSection(
  title: String,
  description: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    shape = NebulaShapes.large,
    colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Surface),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, color = NebulaPalette.TextHigh)
      if (description != null) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = NebulaPalette.TextMuted)
      }
      content()
    }
  }
}

/** What a status line is telling the viewer, which decides its colour and icon. */
private enum class StatusTone { Info, Success, Danger }

// The ViewModel's wording is the only source of truth here - "failed" and "connected" are
// substrings of the sentences SettingsStatus already builds, not a new vocabulary this screen
// invents, so a copy change over there does not silently break the colour it renders in here.
private fun statusTone(status: String): StatusTone = when {
  status.contains("failed", ignoreCase = true) -> StatusTone.Danger
  status.contains("connected", ignoreCase = true) -> StatusTone.Success
  else -> StatusTone.Info
}

@Composable
private fun StatusStrip(status: String) {
  val tone = statusTone(status)
  val pair: Pair<androidx.compose.ui.graphics.Color, ImageVector?> = when (tone) {
    StatusTone.Success -> NebulaPalette.Success to Icons.Filled.CheckCircle
    StatusTone.Danger -> NebulaPalette.Danger to Icons.Filled.Warning
    StatusTone.Info -> NebulaPalette.TextMuted to null
  }
  val (color, icon) = pair
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier
      .background(NebulaPalette.SurfaceVariant, NebulaShapes.small)
      .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
    Text(status, style = MaterialTheme.typography.bodyMedium, color = color)
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
  focusedTextColor = NebulaPalette.TextHigh,
  unfocusedTextColor = NebulaPalette.TextHigh,
  focusedContainerColor = NebulaPalette.SurfaceVariant,
  unfocusedContainerColor = NebulaPalette.SurfaceVariant,
  focusedBorderColor = NebulaPalette.VioletBright,
  unfocusedBorderColor = NebulaPalette.Outline,
  focusedPlaceholderColor = NebulaPalette.TextFaint,
  unfocusedPlaceholderColor = NebulaPalette.TextMuted,
  cursorColor = NebulaPalette.VioletBright,
)
