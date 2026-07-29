package com.stremioshell.host.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.stremioshell.host.BuildConfig
import com.stremioshell.host.tv.SettingsMutationResult
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.SettingsSaveGuard
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaMotion
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * How long the Save button stays blocked before it assumes its save is never going to answer.
 *
 * Every addon manifest and the TMDB probe are checked in parallel and each can block for ~30s, so
 * this is deliberately longer than the slowest honest save. It exists only as a floor under the
 * one failure mode a plain `saving` flag has: a write that throws before its first callback would
 * otherwise leave the button inert and the spinner turning for the life of the screen.
 */
private const val SaveWatchdogMs = 60_000L
private const val CredentialRevealMs = 30_000L

/**
 * Everything the app has to be told, in the order it has to be told it.
 *
 * Two commit models live on this screen and they used to be indistinguishable: the addon list
 * persists the moment you press Add or confirm a Remove, while the TMDB key and the subtitles URL
 * are only written by Save. Neither is wrong - a list whose edits land later shows a configuration
 * that is not the one in use - but the screen now *says* which is which (the "Saves immediately"
 * badge on the addon card, the "Unsaved changes" badge beside Save) instead of leaving the viewer
 * to discover it by losing an edit.
 *
 * Save is therefore the last control on the page, below everything it commits. It used to sit in
 * the middle of the stack, above the Advanced section whose field it is the only way to persist.
 */
@Composable
fun SettingsScreen(
  viewModel: TvAppViewModel,
  onPairWithPhone: () -> Unit = {},
  onDirtyChanged: (Boolean) -> Unit = {},
  saveRequest: Int = 0,
  resetRequest: Int = 0,
  onSaveComplete: (Boolean) -> Unit = {},
) {
  val storedKey by viewModel.tmdbApiKey.collectAsState()
  val storedAddons by viewModel.addonManifestUrls.collectAsState()
  val storedSubtitles by viewModel.subtitlesBaseUrl.collectAsState()

  var tmdbKey by rememberSaveable { mutableStateOf("") }
  var newAddonUrl by rememberSaveable { mutableStateOf("") }
  var subtitlesUrl by rememberSaveable { mutableStateOf("") }
  var saveStatus by rememberSaveable { mutableStateOf("") }
  var seeded by rememberSaveable { mutableStateOf(false) }
  var appliedResetRequest by rememberSaveable { mutableIntStateOf(-1) }
  var advanced by rememberSaveable { mutableStateOf(false) }

  // Deliberately not saveable, unlike the field values above. A notice describes something that
  // just happened, and `saving` tracks a coroutine a config change kills - restoring that one
  // would leave a spinner turning with nothing behind it.
  var showTmdbKey by remember { mutableStateOf(false) }
  var showAddonUrl by remember { mutableStateOf(false) }
  var showSubtitlesUrl by remember { mutableStateOf(false) }
  var tmdbNotice by remember { mutableStateOf<Notice?>(null) }
  var addonNotice by remember { mutableStateOf<Notice?>(null) }
  var saving by remember { mutableStateOf(false) }
  var saveAttempt by remember { mutableIntStateOf(0) }
  var persistedAttempt by remember { mutableIntStateOf(-1) }
  var completedAttempt by remember { mutableIntStateOf(-1) }
  var pendingRemoval by remember { mutableStateOf<String?>(null) }
  var pendingRemovalFeedback by remember { mutableStateOf<String?>(null) }
  var pendingClearKey by remember { mutableStateOf(false) }
  var pendingClearKeyFeedback by remember { mutableStateOf<String?>(null) }
  var addonEditTick by remember { mutableIntStateOf(0) }
  val currentOnSaveComplete by rememberUpdatedState(onSaveComplete)

  val addons = storedAddons.orEmpty()
  val addonLabels = remember(addons) { AddonList.labels(addons) }
  val scrollState = rememberScrollState()

  // Mostly the nodes a text field has to aim at: button-to-button moves are left to the default
  // focus search, which handles them, while a text field is the one thing on the screen that
  // would swallow the key first. The exception is addButtonFocus, which is also where focus is
  // sent after a removal destroys the row it was sitting on.
  val pairInitialFocus = rememberInitialFocusTarget()
  val clearKeyFocus = rememberInitialFocusTarget()
  val lastRemoveFocus = rememberInitialFocusTarget()
  val addonRevealFocus = rememberInitialFocusTarget()
  val addButtonFocus = rememberInitialFocusTarget()
  val advancedFocus = rememberInitialFocusTarget()
  val subtitlesRevealFocus = rememberInitialFocusTarget()
  val saveFocus = rememberInitialFocusTarget()

  LaunchedEffect(resetRequest, storedKey, storedSubtitles) {
    if (
      appliedResetRequest != resetRequest &&
      storedKey != null &&
      storedSubtitles != null
    ) {
      tmdbKey = storedKey.orEmpty()
      // Shown blank when it is the built-in default, so the field reads as "nothing
      // to configure here" rather than daring the viewer to edit a URL that works.
      subtitlesUrl = storedSubtitles.orEmpty()
        .takeIf { it != SubtitlesClient.OPENSUBTITLES_V3_BASE }
        .orEmpty()
      newAddonUrl = ""
      saveStatus = ""
      tmdbNotice = null
      addonNotice = null
      pendingRemoval = null
      pendingRemovalFeedback = null
      pendingClearKey = false
      pendingClearKeyFeedback = null
      showTmdbKey = false
      showAddonUrl = false
      showSubtitlesUrl = false
      seeded = true
      appliedResetRequest = resetRequest
    }
  }

  // Credentials are revealed only for a short, deliberate inspection. These flags are also
  // non-saveable, so leaving Settings, opening phone setup, or recreating the activity masks the
  // values immediately rather than restoring a secret on screen.
  LaunchedEffect(showTmdbKey) {
    if (showTmdbKey) {
      delay(CredentialRevealMs)
      showTmdbKey = false
    }
  }
  LaunchedEffect(showAddonUrl) {
    if (showAddonUrl) {
      delay(CredentialRevealMs)
      showAddonUrl = false
    }
  }
  LaunchedEffect(showSubtitlesUrl) {
    if (showSubtitlesUrl) {
      delay(CredentialRevealMs)
      showSubtitlesUrl = false
    }
  }

  // The first control on the page, not the TMDB field further down it. Focus drags the scroll
  // position with it, so aiming the opening request at a field two cards down opened Settings
  // with its own heading already scrolled off the top of the screen - confirmed on a device.
  RequestInitialFocus(
    target = pairInitialFocus,
    key = Unit,
    label = "Settings pair button",
  )

  // Aiming the opening request at the first control was necessary but not sufficient. Focus drags
  // the scroll position with it whatever it lands on: RequestInitialFocus retries once a frame
  // until the node accepts, every attempt runs a bringIntoView, and those run against a page that
  // is still settling - the addon list and the seeded field values arrive a frame or two later, so
  // the distance computed is against a layout that no longer exists by the time it animates. The
  // measured result was Settings opening with its own heading cut in half by the top edge, which
  // is exactly where a viewer looks first. A single scrollTo(0) does not fix it either, because
  // the bringIntoView animation finishes afterwards and puts it back.
  //
  // So the page is held at its top until the viewer actually drives - the same one-shot idiom
  // HomeScreen uses to stop a late-arriving rail from yanking focus. The moment they press a
  // direction, the scroll is theirs.
  var userNavigated by remember { mutableStateOf(false) }
  LaunchedEffect(userNavigated) {
    if (userNavigated) return@LaunchedEffect
    snapshotFlow { scrollState.value }.collect { if (it != 0) scrollState.scrollTo(0) }
  }

  // Removing a row destroys the node focus is sitting on, and the confirmation dialog owns focus
  // until it leaves the composition, so the recovery has to wait for a frame in which the Add
  // button is the nearest live target. Without it the D-pad is dead after a removal.
  LaunchedEffect(addonEditTick) {
    if (addonEditTick == 0) return@LaunchedEffect
    repeat(10) {
      withFrameNanos { }
      if (addButtonFocus.focused) return@LaunchedEffect
      runCatching { addButtonFocus.requester.requestFocus() }
    }
  }

  // The strip renders under Save, which is now the last thing on the page. Nothing below Save is
  // focusable, so no bringIntoView would ever pull the result of the press into view on a screen
  // taller than the viewport. Keyed on the status so it only fires for a save, never for the
  // in-card notices, which appear beside the button that produced them.
  LaunchedEffect(saveStatus) {
    if (saveStatus.isNotBlank()) scrollState.animateScrollTo(scrollState.maxValue)
  }

  // Draft text counts even where the save guard will protect a stored key. Otherwise deleting the
  // visible key or typing an addon and leaving without pressing Add would silently discard work
  // while the screen claimed there was nothing pending.
  val dirty = seeded && (
    tmdbKey.trim() != storedKey.orEmpty().trim() ||
      newAddonUrl.isNotBlank() ||
      SettingsSaveGuard.normalizeSubtitlesBase(subtitlesUrl) !=
      SettingsSaveGuard.normalizeSubtitlesBase(storedSubtitles.orEmpty())
    )

  LaunchedEffect(dirty) { onDirtyChanged(dirty) }

  // A connection verdict describes the exact values that were tested. Editing any of them, or
  // changing the immediately-persisted addon list, invalidates both that verdict and an in-flight
  // probe so a late callback cannot paint the new draft as connected.
  val invalidateConnectionVerdict: () -> Unit = {
    val wasSaving = saving
    saveAttempt++
    persistedAttempt = -1
    saving = false
    saveStatus = ""
    if (wasSaving) currentOnSaveComplete(false)
  }

  val startSave: () -> Unit = {
    if (!saving) {
      if (newAddonUrl.isNotBlank()) {
        addonNotice = Notice(
          "Press Add addon to use the URL you typed — Save does not add it.",
          StatusTone.Caution,
        )
        runCatching { addButtonFocus.requester.requestFocus() }
        currentOnSaveComplete(false)
      } else {
        val attempt = saveAttempt + 1
        saveAttempt = attempt
        persistedAttempt = -1
        saving = true
        viewModel.saveSettings(tmdbKey, subtitlesUrl) { status ->
          // A timed-out or superseded probe can still finish. It must neither repaint the verdict
          // for a newer draft nor complete a newer leave request.
          if (attempt == saveAttempt) {
            saveStatus = status
            if (status.contains("Checking connections")) {
              persistedAttempt = attempt
              saving = true
            } else {
              saving = false
              if (completedAttempt != attempt) {
                completedAttempt = attempt
                currentOnSaveComplete(true)
              }
            }
          }
        }
      }
    }
  }

  LaunchedEffect(saveRequest) {
    if (saveRequest > 0) startSave()
  }

  LaunchedEffect(saveAttempt, saving) {
    val attempt = saveAttempt
    if (!saving || attempt == 0) return@LaunchedEffect
    delay(SaveWatchdogMs)
    if (!saving || attempt != saveAttempt) return@LaunchedEffect
    saving = false
    val persisted = persistedAttempt == attempt
    saveStatus = if (persisted) {
      "Settings saved. Connection tests are taking too long; you can leave and test again later."
    } else {
      "Nebula could not confirm that Settings were saved. Try again."
    }
    if (completedAttempt != attempt) {
      completedAttempt = attempt
      currentOnSaveComplete(persisted)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      // Observed only, never consumed: notes that the viewer is driving, which releases the
      // hold-at-top above.
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key.isVerticalDirection()) {
          userNavigated = true
        }
        false
      }
      .verticalScroll(scrollState)
      .padding(
        horizontal = NebulaDimens.ScreenEdge,
        vertical = NebulaDimens.ScreenEdgeVertical,
      ),
    verticalArrangement = Arrangement.spacedBy(NebulaDimens.RailGap),
  ) {
    // ScreenHeader pads itself to the content line and hangs its tick in the margin, so it
    // expects a container with no padding of its own. This Column has already padded to
    // ScreenEdge, which would indent the heading a second time; the offset cancels exactly that,
    // landing the words flush with the cards below and the tick TickInset to their left.
    ScreenHeader(
      title = "Settings",
      // The only place the running build is stated anywhere in the app; without it the owner of a
      // sideloaded, self-updating APK cannot find out what they have without adb.
      subtitle = "Nebula ${BuildConfig.VERSION_NAME}",
      modifier = Modifier.offset(x = -NebulaDimens.ScreenEdge),
    )

    SettingsSection(
      title = "Quick setup",
      description = "Type your key and addon URLs on your phone instead of with the remote.",
    ) {
      NebulaButton(
        // Same words as the button on Home that opens the same screen. It used to carry a
        // parenthetical here and not there, which is two names for one destination.
        text = "Set up with phone",
        onClick = onPairWithPhone,
        icon = Icons.Filled.Phone,
        modifier = Modifier.initialFocusTarget(pairInitialFocus),
      )
    }

    SettingsSection(
      title = "TMDB",
      description = "Nebula gets every catalog, poster and description from TMDB. Create a free " +
        "key at themoviedb.org → Settings → API.",
    ) {
      OutlinedTextField(
        value = tmdbKey,
        onValueChange = {
          if (it != tmdbKey) invalidateConnectionVerdict()
          tmdbKey = it
        },
        singleLine = true,
        placeholder = { Text("Paste or type your key") },
        visualTransformation = if (showTmdbKey) {
          VisualTransformation.None
        } else {
          PasswordVisualTransformation()
        },
        shape = NebulaShapes.medium,
        colors = settingsFieldColors(),
        // A leanback IME capitalising the first character of a v3 key produces a key that fails
        // authentication with nothing on screen saying why.
        keyboardOptions = KeyboardOptions(
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Ascii,
          imeAction = ImeAction.Done,
        ),
        modifier = Modifier
          // Sized to the 32 characters it holds rather than to a fraction of the card, which left
          // a field twice as wide as any value it can ever contain.
          .widthIn(max = 460.dp)
          .fillMaxWidth()
          .semantics { contentDescription = "TMDB API key" }
          // A material3 text field traps the D-pad on TV, so move focus
          // between fields explicitly before it consumes the key.
          .fieldNav(down = clearKeyFocus, up = pairInitialFocus),
      )
      // Under the field rather than beside it. Every button on this screen is reachable
      // by pressing down, which is the only direction a text field can be talked out of:
      // left and right are the caret's, and a button that needed one of those to reach
      // would be unreachable from a focused field.
      Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
        NebulaButton(
          text = if (showTmdbKey) "Hide key" else "Show key",
          onClick = { showTmdbKey = !showTmdbKey },
          style = NebulaButtonStyle.Ghost,
          modifier = Modifier
            .initialFocusTarget(clearKeyFocus)
            .semantics {
              contentDescription = if (showTmdbKey) {
                "Hide TMDB API key"
              } else {
                "Show TMDB API key"
              }
            },
        )
        NebulaButton(
          text = "Clear key",
          onClick = {
            // A key takes minutes to re-enter on a remote. Confirm a real deletion instead of
            // making the most destructive control in the section a one-press action.
            if (tmdbKey.isBlank() && storedKey.isNullOrBlank()) {
              tmdbNotice = Notice("There is no TMDB key to clear.", StatusTone.Info)
            } else {
              pendingClearKeyFeedback = null
              pendingClearKey = true
            }
          },
          style = NebulaButtonStyle.Danger,
        )
      }
      tmdbNotice?.let { SectionNotice(it) }
    }

    SettingsSection(
      title = "Stream addons",
      description = "Asked in this order. The first addon offering a release is the one you get; " +
        "everything else is merged in and sorted by quality.",
      // The two commit models on this screen were silently different. Now they are visibly
      // different: this list writes on press, the fields below Save do not.
      badge = { NebulaBadge(text = "Saves immediately", tone = BadgeTone.Good) },
    ) {
      if (addons.isEmpty()) {
        // A placeholder shaped like a row rather than a second muted paragraph stacked on the
        // section description - two consecutive paragraphs at one size and colour are
        // indistinguishable at three metres.
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(NebulaPalette.Surface, NebulaShapes.medium)
            .border(1.dp, NebulaPalette.Outline, NebulaShapes.medium),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "No addons yet — add your Comet manifest URL below",
            style = MaterialTheme.typography.bodySmall,
            color = NebulaPalette.TextMuted,
            textAlign = TextAlign.Center,
          )
        }
      }

      addons.forEachIndexed { index, url ->
        key(url) {
          Row(
          horizontalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .background(NebulaPalette.SurfaceVariant, NebulaShapes.medium)
            .padding(horizontal = NebulaSpace.md, vertical = NebulaSpace.sm),
        ) {
          NebulaBadge(text = "${index + 1}", tone = BadgeTone.Accent)
          // weight, not a width fraction: a Row measures unweighted children against the space
          // left after its siblings, so `fillMaxWidth(0.65f)` left ~117dp of dead gutter at the
          // end of every row with the Remove button floating in the middle of it.
          Column(modifier = Modifier.weight(1f)) {
            Text(
              addonLabels[index],
              style = MaterialTheme.typography.titleSmall,
              color = NebulaPalette.TextHigh,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              AddonList.safeDisplay(url),
              style = MaterialTheme.typography.bodySmall,
              color = NebulaPalette.TextMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          NebulaButton(
            text = "Up",
            icon = Icons.Filled.KeyboardArrowUp,
            enabled = index > 0,
            style = NebulaButtonStyle.Ghost,
            onClick = {
              val label = addonLabels[index]
              viewModel.moveAddon(url, -1) { result ->
                when (result) {
                  SettingsMutationResult.Changed -> {
                    invalidateConnectionVerdict()
                    addonNotice = Notice("Moved $label earlier.", StatusTone.Success)
                  }
                  SettingsMutationResult.Unchanged -> {
                    addonNotice = Notice("$label is already in that position.", StatusTone.Info)
                  }
                  SettingsMutationResult.Failed -> {
                    addonNotice = Notice("Could not move $label. Try again.", StatusTone.Danger)
                  }
                }
              }
            },
            modifier = Modifier.semantics {
              contentDescription = "Move ${addonLabels[index]} earlier"
            },
          )
          NebulaButton(
            text = "Down",
            icon = Icons.Filled.KeyboardArrowDown,
            enabled = index < addons.lastIndex,
            style = NebulaButtonStyle.Ghost,
            onClick = {
              val label = addonLabels[index]
              viewModel.moveAddon(url, 1) { result ->
                when (result) {
                  SettingsMutationResult.Changed -> {
                    invalidateConnectionVerdict()
                    addonNotice = Notice("Moved $label later.", StatusTone.Success)
                  }
                  SettingsMutationResult.Unchanged -> {
                    addonNotice = Notice("$label is already in that position.", StatusTone.Info)
                  }
                  SettingsMutationResult.Failed -> {
                    addonNotice = Notice("Could not move $label. Try again.", StatusTone.Danger)
                  }
                }
              }
            },
            modifier = Modifier.semantics {
              contentDescription = "Move ${addonLabels[index]} later"
            },
          )
          NebulaButton(
            text = "Remove",
            // Not Ghost: its focused fill is SurfaceVariant, which is exactly the colour of the
            // row it sits on, so the one control here that destroys configuration marked focus
            // with a ring and nothing else. Danger's plate is a step down from the row at rest
            // and flips to solid pink when focused.
            style = NebulaButtonStyle.Danger,
            onClick = {
              pendingRemovalFeedback = null
              pendingRemoval = url
            },
            modifier = Modifier
              // Where the field below the list sends its D-pad up, so it lands on the
              // nearest row rather than skipping the whole list.
              .then(
                if (index == addons.lastIndex) {
                  Modifier.initialFocusTarget(lastRemoveFocus)
                } else {
                  Modifier
                }
              )
              // Every row's button is labelled "Remove", so a screen reader stepping down the
              // list heard "Remove, Remove, Remove" with no way to tell which one it was on.
              .semantics { contentDescription = "Remove ${addonLabels[index]}" },
          )
          }
        }
      }

      OutlinedTextField(
        value = newAddonUrl,
        onValueChange = {
          if (it != newAddonUrl) invalidateConnectionVerdict()
          newAddonUrl = it
        },
        singleLine = true,
        placeholder = { Text("https://comet.../<config>/manifest.json") },
        visualTransformation = if (showAddonUrl) {
          VisualTransformation.None
        } else {
          PasswordVisualTransformation()
        },
        shape = NebulaShapes.medium,
        colors = settingsFieldColors(),
        // Uri rather than plain text: the "/" and ".com" keys are dozens of D-pad presses each
        // on a leanback keyboard that does not offer them.
        keyboardOptions = KeyboardOptions(
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Uri,
          imeAction = ImeAction.Done,
        ),
        modifier = Modifier
          // Shares its right edge with the rows above it, which are fillMaxWidth. At 0.8f the
          // list terminated 151dp further right than the field that adds to it.
          .fillMaxWidth()
          .semantics { contentDescription = "New stream addon manifest URL" }
          .fieldNav(
            down = addonRevealFocus,
            up = if (addons.isEmpty()) clearKeyFocus else lastRemoveFocus,
          ),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
        NebulaButton(
          text = if (showAddonUrl) "Hide URL" else "Show URL",
          onClick = { showAddonUrl = !showAddonUrl },
          style = NebulaButtonStyle.Ghost,
          modifier = Modifier
            .initialFocusTarget(addonRevealFocus)
            .semantics {
              contentDescription = if (showAddonUrl) {
                "Hide new stream addon URL"
              } else {
                "Show new stream addon URL"
              }
            },
        )
        NebulaButton(
          text = "Add addon",
          onClick = {
            // Persisted on press rather than staged behind Save: a list whose edits only
            // land later shows a configuration that is not the one being used.
            val submittedUrl = newAddonUrl
            val normalized = AddonList.normalize(submittedUrl)
            addonNotice = when {
              normalized.isEmpty() -> Notice("Enter an addon URL first.", StatusTone.Caution)
              normalized in addons -> Notice(
                "That addon is already in the list.",
                StatusTone.Caution,
              )
              addons.size >= AddonList.MAX_ADDONS ->
                Notice("That's the most addons the list holds.", StatusTone.Caution)
              else -> {
                viewModel.addAddon(submittedUrl) { result ->
                  when (result) {
                    SettingsMutationResult.Changed -> {
                      invalidateConnectionVerdict()
                      // Do not erase a second URL typed while the first write was completing.
                      if (newAddonUrl == submittedUrl) {
                        newAddonUrl = ""
                        showAddonUrl = false
                      }
                      addonNotice = Notice(
                        "Added ${AddonList.label(normalized)}.",
                        StatusTone.Success,
                      )
                    }
                    SettingsMutationResult.Unchanged -> {
                      addonNotice = Notice(
                        "That addon was already in the list.",
                        StatusTone.Caution,
                      )
                    }
                    SettingsMutationResult.Failed -> {
                      addonNotice = Notice(
                        "Could not add ${AddonList.label(normalized)}. Check the URL and try again.",
                        StatusTone.Danger,
                      )
                    }
                  }
                }
                null
              }
            }
          },
          modifier = Modifier.initialFocusTarget(addButtonFocus),
        )
      }
      addonNotice?.let { SectionNotice(it) }
    }

    NebulaButton(
      text = "Advanced",
      icon = if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
      style = NebulaButtonStyle.Ghost,
      onClick = { advanced = !advanced },
      // The chevron is the only thing that says which way this button goes, and it is drawn
      // decoratively, so open/closed has to be spelled out for anything not looking at it.
      modifier = Modifier
        .initialFocusTarget(advancedFocus)
        .semantics(mergeDescendants = true) {
          contentDescription = if (advanced) "Advanced, expanded" else "Advanced, collapsed"
        },
    )

    // The one layout animation in the app. Affordable here and nowhere else: this screen decodes
    // no images, holds no lazy list and the expansion runs for ~13 frames on a six-child Column.
    // Without it the whole lower half of the page teleports.
    AnimatedVisibility(
      visible = advanced,
      // From the top, not the default bottom: a disclosure unrolls downward from the button that
      // opened it, and expanding from the bottom edge reads as the card sliding up out of Save.
      enter = expandVertically(
        animationSpec = NebulaMotion.enter(),
        expandFrom = Alignment.Top,
      ) + fadeIn(animationSpec = NebulaMotion.enter()),
      exit = shrinkVertically(
        animationSpec = NebulaMotion.exit(),
        shrinkTowards = Alignment.Top,
      ) + fadeOut(animationSpec = NebulaMotion.exit()),
    ) {
      SettingsSection(
        // Named for its subject. It used to be titled "Advanced" directly under a button reading
        // "Advanced", so the word appeared twice in 60dp and the card's actual topic never did.
        title = "Subtitles addon",
        description = "Leave blank for the built-in OpenSubtitles v3 addon.",
      ) {
        // Above the field, not below it. As the last element of a scrolling Column with nothing
        // focusable after it, the only feedback this field has could never be scrolled into view.
        Text(
          "Currently using: ${
            AddonList.safeDisplay(SettingsSaveGuard.normalizeSubtitlesBase(subtitlesUrl))
          }",
          style = MaterialTheme.typography.bodySmall,
          color = NebulaPalette.TextMuted,
        )
        OutlinedTextField(
          value = subtitlesUrl,
          onValueChange = {
            if (it != subtitlesUrl) invalidateConnectionVerdict()
            subtitlesUrl = it
          },
          singleLine = true,
          placeholder = { Text(SubtitlesClient.OPENSUBTITLES_V3_BASE) },
          visualTransformation = if (showSubtitlesUrl) {
            VisualTransformation.None
          } else {
            PasswordVisualTransformation()
          },
          shape = NebulaShapes.medium,
          colors = settingsFieldColors(),
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
          ),
          // Down goes to Save, which is the only thing that commits this value - the reason the
          // button now sits below the field instead of above it.
          modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Subtitles addon URL" }
            .fieldNav(down = subtitlesRevealFocus, up = advancedFocus),
        )
        NebulaButton(
          text = if (showSubtitlesUrl) "Hide URL" else "Show URL",
          onClick = { showSubtitlesUrl = !showSubtitlesUrl },
          style = NebulaButtonStyle.Ghost,
          modifier = Modifier
            .initialFocusTarget(subtitlesRevealFocus)
            .semantics {
              contentDescription = if (showSubtitlesUrl) {
                "Hide subtitles addon URL"
              } else {
                "Show subtitles addon URL"
              }
            },
        )
      }
    }

    Row(
      horizontalArrangement = Arrangement.spacedBy(NebulaSpace.md),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      NebulaButton(
        // Says what the wait is for: this writes, then probes every addon manifest and TMDB,
        // each of which can block for ~30s.
        text = "Save & test connections",
        onClick = startSave,
        style = NebulaButtonStyle.Primary,
        modifier = Modifier.initialFocusTarget(saveFocus),
      )
      // Warn, not Bad: an edit that has not been written yet is a caveat, not a failure.
      if (dirty) NebulaBadge(text = "Unsaved changes", tone = BadgeTone.Warn)
    }

    if (saveStatus.isNotBlank()) {
      StatusStrip(status = saveStatus, busy = saving)
    }
  }

  pendingRemoval?.let { url ->
    val label = addons.indexOf(url).let {
      if (it >= 0) addonLabels[it] else AddonList.label(url)
    }
    // A manifest URL takes minutes to type on a remote and there is no undo, which is a higher
    // stake than the My List entries this dialog was written for.
    CardOptionsDialog(
      title = label,
      message = "Remove this addon? Its manifest URL is deleted, and putting it back means " +
        "typing the whole thing on the remote again." +
        pendingRemovalFeedback?.let { "\n\n$it" }.orEmpty(),
      focusKey = url,
      focusLabel = "Addon removal options",
      actions = listOf(
        CardAction("Remove addon", destructive = true) {
          viewModel.removeAddon(url) { result ->
            when (result) {
              SettingsMutationResult.Changed -> {
                invalidateConnectionVerdict()
                addonNotice = Notice("Removed $label.", StatusTone.Info)
                pendingRemovalFeedback = null
                pendingRemoval = null
                addonEditTick++
              }
              SettingsMutationResult.Unchanged -> {
                pendingRemovalFeedback = "$label was not in the addon list."
              }
              SettingsMutationResult.Failed -> {
                pendingRemovalFeedback = "Could not remove $label. Try again."
              }
            }
          }
        },
      ),
      onDismiss = {
        pendingRemovalFeedback = null
        pendingRemoval = null
      },
    )
  }

  if (pendingClearKey) {
    CardOptionsDialog(
      title = "TMDB API key",
      message = "Clear the saved key? Catalogs, search, and metadata will stop loading until you " +
        "enter another one." +
        pendingClearKeyFeedback?.let { "\n\n$it" }.orEmpty(),
      focusKey = "clear-tmdb-key",
      focusLabel = "TMDB key removal options",
      actions = listOf(
        CardAction("Clear key", destructive = true) {
          viewModel.clearTmdbKey { result ->
            when (result) {
              SettingsMutationResult.Changed -> {
                invalidateConnectionVerdict()
                tmdbKey = ""
                showTmdbKey = false
                tmdbNotice = Notice(
                  "TMDB key cleared. Enter a new one to load catalogs again.",
                  StatusTone.Caution,
                )
                pendingClearKeyFeedback = null
                pendingClearKey = false
              }
              SettingsMutationResult.Unchanged -> {
                pendingClearKeyFeedback = "There was no saved TMDB key to clear."
              }
              SettingsMutationResult.Failed -> {
                pendingClearKeyFeedback = "Could not clear the TMDB key. Try again."
              }
            }
          }
        },
      ),
      onDismiss = {
        pendingClearKeyFeedback = null
        pendingClearKey = false
      },
    )
  }
}

/**
 * A card for one group of related settings. Splitting the screen into these is what turned it
 * from one undifferentiated scroll into something a viewer can scan section by section from
 * across the room.
 *
 * @param badge a chip beside the title, for the one thing a section has to say about itself that
 *   its description should not have to spend a sentence on - which commit model it uses.
 */
@Composable
private fun SettingsSection(
  title: String,
  description: String? = null,
  badge: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    shape = NebulaShapes.large,
    colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Surface),
    // The same hairline the dialogs carry, for the same reason: Surface on Void is a 1.10:1 step,
    // so without an edge three cards read as one soft grey smear rather than as three panels.
    border = Border(
      border = BorderStroke(1.dp, NebulaPalette.Outline),
      shape = NebulaShapes.large,
    ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(NebulaSpace.lg),
      verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
      ) {
        Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          color = NebulaPalette.TextHigh,
          modifier = Modifier.semantics { heading() },
        )
        badge?.invoke()
      }
      if (description != null) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = NebulaPalette.TextMuted)
      }
      content()
    }
  }
}

/** What a status line is telling the viewer, which decides its colour and icon. */
private enum class StatusTone { Info, Success, Caution, Danger }

/** One line of feedback and the outcome it describes, for the sites that know their own tone. */
private data class Notice(val text: String, val tone: StatusTone)

/**
 * Colour and glyph for a tone.
 *
 * Caution is amber and genuinely distinct from Danger: "TMDB: no key" is a configuration that has
 * not been finished, not a thing that broke, and painting the two the same made every incomplete
 * setup look like a fault.
 */
private fun toneStyle(tone: StatusTone): Pair<Color, ImageVector> = when (tone) {
  StatusTone.Success -> NebulaPalette.Success to Icons.Filled.CheckCircle
  StatusTone.Danger -> NebulaPalette.Danger to Icons.Filled.Warning
  StatusTone.Caution -> NebulaPalette.Caution to Icons.Filled.Warning
  StatusTone.Info -> NebulaPalette.TextMuted to Icons.Filled.Info
}

/**
 * Which outcome a line of the ViewModel's status describes.
 *
 * The negatives are matched *first* and against whole phrases. "Addons: none connected (check the
 * URLs)" contains the word "connected" while describing a total outage, which is how this screen
 * once rendered every addon being broken in green, with a success tick. A single word is not a
 * verdict when it turns up inside its own opposite.
 */
private fun statusTone(line: String): StatusTone = when {
  line.contains("failed", ignoreCase = true) ||
    line.contains("none connected", ignoreCase = true) -> StatusTone.Danger
  line.contains("no key", ignoreCase = true) ||
    line.contains("none configured", ignoreCase = true) -> StatusTone.Caution
  line.contains("connected", ignoreCase = true) -> StatusTone.Success
  else -> StatusTone.Info
}

/**
 * Splits the ViewModel's one-line status back into the separate facts it was built from.
 *
 * `saveSettings` composes up to three - a "kept your saved..." notice, a TMDB verdict and an addon
 * verdict - glued with a pipe and with double spaces. Each has its own outcome, so painting them
 * as one string meant a save where TMDB connected and the addon did not rendered the half that
 * worked in the failure colour too. Worst case a wording change here yields one line instead of
 * two, which is a layout difference rather than a lie.
 */
private fun statusLines(status: String): List<String> =
  status.split("|")
    .flatMap { part ->
      // The kept-notice is prefixed to the TMDB verdict, which always opens with this marker.
      val verdict = part.indexOf("TMDB:")
      if (verdict > 0) listOf(part.take(verdict), part.drop(verdict)) else listOf(part)
    }
    .map { it.trim() }
    .filter { it.isNotEmpty() }

/**
 * The result of a Save: one line per fact, each in the colour of its own outcome.
 *
 * @param busy the probes are still running. The strip is otherwise identical whether a save is
 *   half done or finished, and each addon manifest can block for ~30s, so without this the viewer
 *   has no way to tell a working screen from a finished one.
 */
@Composable
private fun StatusStrip(status: String, busy: Boolean) {
  val lines = remember(status) { statusLines(status) }
  Column(
    verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    modifier = Modifier
      .background(NebulaPalette.SurfaceVariant, NebulaShapes.small)
      .padding(horizontal = NebulaSpace.md, vertical = NebulaSpace.sm)
      // The outcome of a Save is the one thing on this screen a viewer cannot find by moving
      // focus, because nothing in here is focusable.
      .semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    lines.forEachIndexed { index, line ->
      val (color, icon) = toneStyle(statusTone(line))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
      ) {
        // Fixed slot, so the lines share a left edge whether or not they carry a glyph.
        Box(
          modifier = Modifier.size(NebulaIcon.sm),
          contentAlignment = Alignment.Center,
        ) {
          // The one animating node on an otherwise completely static screen.
          if (busy && index == 0) {
            CircularProgressIndicator(
              color = NebulaPalette.TextMuted,
              trackColor = NebulaPalette.Outline,
              strokeWidth = 2.dp,
              strokeCap = StrokeCap.Round,
              modifier = Modifier.size(16.dp),
            )
          } else {
            Icon(
              icon,
              // Decorative: the colour and the sentence beside it both already say this.
              contentDescription = null,
              tint = color,
              modifier = Modifier.size(NebulaIcon.sm),
            )
          }
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, color = color)
      }
    }
  }
}

/**
 * Feedback for an action that happened inside a card, rendered inside that card.
 *
 * Add, Remove and Clear key used to report into the same strip as Save, which lives at the foot of
 * a scrolling page - so the only response to "That addon is already in the list" was off screen,
 * and the viewer saw the press do nothing at all.
 */
@Composable
private fun SectionNotice(notice: Notice) {
  val (color, icon) = toneStyle(notice.tone)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    // Decorative: the sentence beside it is the message.
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(NebulaIcon.sm))
    Text(notice.text, style = MaterialTheme.typography.bodySmall, color = color)
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
private fun Modifier.fieldNav(down: InitialFocusTarget?, up: InitialFocusTarget?): Modifier =
  onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val target = when (event.key) {
      Key.DirectionDown -> down
      Key.DirectionUp -> up
      // Left and right belong to the caret. Nothing on this screen needs them: every
      // button sits under the field it belongs to, not beside it.
      else -> null
    }
    target != null && runCatching {
      target.requester.requestFocus()
      target.focused
    }.getOrDefault(false)
  }

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = NebulaPalette.TextHigh,
  unfocusedTextColor = NebulaPalette.TextHigh,
  // Focus brightens rather than only outlining, which is the rule every button on the screen
  // follows; a 2dp border was a far weaker focus signal than the controls sitting beside it.
  // Opaque and pre-composited, so it costs no per-frame alpha blend.
  focusedContainerColor = NebulaPalette.AccentPlateStrong,
  unfocusedContainerColor = NebulaPalette.SurfaceVariant,
  focusedBorderColor = NebulaPalette.VioletBright,
  unfocusedBorderColor = NebulaPalette.Outline,
  // The right way round. Focusing a field used to *darken* its hint to the palette's quietest
  // tone - and the addon field's placeholder is the only place the expected URL shape is
  // documented, so it is neither disabled nor decorative.
  focusedPlaceholderColor = NebulaPalette.TextMuted,
  unfocusedPlaceholderColor = NebulaPalette.TextFaint,
  cursorColor = NebulaPalette.VioletBright,
)

/** Up and down, which is the only axis this screen's scroll answers to. */
private fun Key.isVerticalDirection(): Boolean =
  this == Key.DirectionUp || this == Key.DirectionDown
