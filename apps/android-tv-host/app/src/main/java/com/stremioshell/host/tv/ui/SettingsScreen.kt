package com.stremioshell.host.tv.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremioshell.host.BuildConfig
import com.stremioshell.host.R
import com.stremioshell.host.tv.SettingsMutationRequest
import com.stremioshell.host.tv.SettingsMutationResult
import com.stremioshell.host.tv.SettingsSaveUpdate
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.PlaybackPreferencePolicy
import com.stremioshell.host.tv.data.PlayerPrefs
import com.stremioshell.host.tv.data.SettingsSaveGuard
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import com.stremioshell.host.tv.diagnostics.NebulaDiagnostics
import com.stremioshell.host.tv.player.AudioOutputMode
import com.stremioshell.host.tv.player.SubtitleSize
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaMotion
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.update.UpdateFailureKind
import com.stremioshell.host.update.UpdateStatus
import com.stremioshell.host.update.UpdateStatusPhase
import com.stremioshell.host.update.UpdateStatusStore
import com.stremioshell.host.update.UpdateWorkScheduler
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CredentialRevealMs = 30_000L
private const val UpdateStatusPollMs = 2_000L

/** Null while the durable write is complete but connection checks are still running. */
internal fun SettingsSaveUpdate.completionSuccess(): Boolean? = when (this) {
  is SettingsSaveUpdate.Persisted -> null
  is SettingsSaveUpdate.Complete -> true
  is SettingsSaveUpdate.Partial -> false
  is SettingsSaveUpdate.Failed -> false
}

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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
  viewModel: TvAppViewModel,
  onPairWithPhone: () -> Unit = {},
  onDirtyChanged: (Boolean) -> Unit = {},
  saveRequest: Int = 0,
  onSaveRequestHandled: (Int) -> Unit = {},
  resetRequest: Int = 0,
  onSaveComplete: (Boolean) -> Unit = {},
) {
  val storedKey by viewModel.tmdbApiKey.collectAsStateWithLifecycle()
  val storedAddons by viewModel.addonManifestUrls.collectAsStateWithLifecycle()
  val storedSubtitles by viewModel.subtitlesBaseUrl.collectAsStateWithLifecycle()
  val storedPlayerPrefs by viewModel.playerPrefs.collectAsStateWithLifecycle()
  val saveOperation by viewModel.settingsSaveOperation.collectAsStateWithLifecycle()
  val mutationOperation by viewModel.settingsMutationOperation.collectAsStateWithLifecycle()

  var tmdbKey by rememberSaveable { mutableStateOf("") }
  var newAddonUrl by rememberSaveable { mutableStateOf("") }
  var subtitlesUrl by rememberSaveable { mutableStateOf("") }
  var audioLanguage by rememberSaveable { mutableStateOf("") }
  var subtitleLanguage by rememberSaveable { mutableStateOf("") }
  var saveStatus by rememberSaveable { mutableStateOf("") }
  var seeded by rememberSaveable { mutableStateOf(false) }
  var appliedResetRequest by rememberSaveable { mutableIntStateOf(-1) }
  var advanced by rememberSaveable { mutableStateOf(false) }
  var playbackSeeded by rememberSaveable { mutableStateOf(false) }

  // Deliberately not saveable, unlike the field values above. A notice describes something that
  // just happened. The save itself is ViewModel-owned and reattached below by its saveable id.
  var showTmdbKey by remember { mutableStateOf(false) }
  var showAddonUrl by remember { mutableStateOf(false) }
  var showSubtitlesUrl by remember { mutableStateOf(false) }
  var tmdbNotice by remember { mutableStateOf<Notice?>(null) }
  var addonNotice by remember { mutableStateOf<Notice?>(null) }
  var playbackNotice by remember { mutableStateOf<Notice?>(null) }
  var supportNotice by remember { mutableStateOf<Notice?>(null) }
  var exportingDiagnostics by remember { mutableStateOf(false) }
  var activeSaveId by rememberSaveable { mutableStateOf<Long?>(null) }
  var completedSaveId by rememberSaveable { mutableStateOf<Long?>(null) }
  var pendingRemoval by remember { mutableStateOf<String?>(null) }
  var pendingRemovalFeedback by remember { mutableStateOf<String?>(null) }
  var pendingClearKey by remember { mutableStateOf(false) }
  var pendingClearKeyFeedback by remember { mutableStateOf<String?>(null) }
  var pendingPlaybackReset by remember { mutableStateOf(false) }
  var pendingPlaybackResetFeedback by remember { mutableStateOf<String?>(null) }
  var pendingAppReset by remember { mutableStateOf(false) }
  var pendingAppResetFeedback by remember { mutableStateOf<String?>(null) }
  var addonEditTick by remember { mutableIntStateOf(0) }
  // Which addon a reorder is holding focus for, and the direction that was pressed. Identity is
  // the URL rather than the index, because the index is the one thing the press changed. Never
  // cleared: the target simply follows the last addon that moved, and a row that is removed takes
  // it with it. See the recovery effect below for why it exists at all.
  var movedAddonUrl by remember { mutableStateOf<String?>(null) }
  var movedAddonDirection by remember { mutableIntStateOf(0) }
  var addonMoveTick by remember { mutableIntStateOf(0) }
  var playbackControls by remember { mutableStateOf<PlayerPrefs?>(null) }
  // A focused field is a D-pad navigation stop until the viewer explicitly presses Center/Enter.
  // Not saveable: recreation and leaving Settings both end any active input session.
  var editingField by remember { mutableStateOf<SettingsTextField?>(null) }
  val currentOnSaveComplete by rememberUpdatedState(onSaveComplete)
  val currentOnSaveRequestHandled by rememberUpdatedState(onSaveRequestHandled)
  val context = LocalContext.current
  val softwareKeyboard = LocalSoftwareKeyboardController.current
  val supportScope = rememberCoroutineScope()
  val updateScope = rememberCoroutineScope()
  val updateStatusStore = remember(context.applicationContext) {
    UpdateStatusStore(context.applicationContext)
  }
  val updateStatus by updateStatusStore.updates.collectAsStateWithLifecycle(
    initialValue = updateStatusStore.current(),
  )

  val addons = storedAddons.orEmpty()
  val addonLabels = remember(addons) { AddonList.labels(addons) }
  val playbackReady = storedPlayerPrefs != null
  val playback = playbackControls ?: storedPlayerPrefs ?: PlayerPrefs()
  val activeSave = saveOperation?.takeIf { it.requestId == activeSaveId }
  val saving = activeSave?.running == true
  // A completed immediate operation stays busy until this composition consumes its result. That
  // one-slot handoff is what lets a replacement composition reconnect after activity recreation.
  val mutationBusy = mutationOperation != null
  val playbackBusy = mutationBusy || activeSave?.savingPlaybackLanguages == true
  val playbackSubtitleSize = SubtitleSize.fromStorage(playback.subtitleSize)
  val playbackAudioOutput = AudioOutputMode.fromStorage(playback.audioOutput)
  val listState = rememberLazyListState()
  // Own scope, not the one the diagnostics export borrows: these coroutines are the D-pad's
  // recovery path and must not be cancelled or queued behind a share sheet.
  val focusScope = rememberCoroutineScope()
  // Whether any control in the form holds focus. Read by the two effects that scroll the page on
  // their own initiative, so they can tell "the viewer was on a node we just disposed" from "a
  // dialog owns focus and this scroll is happening behind it".
  var formFocused by remember { mutableStateOf(false) }

  // DownloadManager does not expose a Flow. Reconcile only while a transfer can change state, plus
  // once on entry to recover a ready APK that predates this status ledger.
  LaunchedEffect(updateStatus.phase, updateStatus.targetVersionName) {
    if (updateStatus.phase != UpdateStatusPhase.IDLE &&
      updateStatus.phase != UpdateStatusPhase.DOWNLOAD_QUEUED &&
      updateStatus.phase != UpdateStatusPhase.DOWNLOADING &&
      updateStatus.phase != UpdateStatusPhase.READY
    ) {
      return@LaunchedEffect
    }
    do {
      val reconciled = withContext(Dispatchers.IO) {
        updateStatusStore.reconcileDownloadState()
      }
      if (reconciled.phase != UpdateStatusPhase.DOWNLOAD_QUEUED &&
        reconciled.phase != UpdateStatusPhase.DOWNLOADING
      ) {
        break
      }
      delay(UpdateStatusPollMs)
    } while (true)
  }

  // Mostly the nodes a text field has to aim at: button-to-button moves are left to the default
  // focus search, which handles them, while a text field is the one thing on the screen that
  // would swallow the key first. Two of them are not that: addButtonFocus is also where focus is
  // sent after a removal destroys the row it was sitting on, and movedAddonFocus belongs to no
  // fixed node at all - it migrates to whichever control of whichever row now holds the addon a
  // reorder just moved.
  val pairInitialFocus = rememberInitialFocusTarget()
  val tmdbFieldFocus = rememberInitialFocusTarget()
  val clearKeyFocus = rememberInitialFocusTarget()
  val lastRemoveFocus = rememberInitialFocusTarget()
  val movedAddonFocus = rememberInitialFocusTarget()
  val addonUrlFieldFocus = rememberInitialFocusTarget()
  val addonRevealFocus = rememberInitialFocusTarget()
  val addButtonFocus = rememberInitialFocusTarget()
  val audioLanguageFocus = rememberInitialFocusTarget()
  val subtitleLanguageFocus = rememberInitialFocusTarget()
  val playbackLanguageSaveFocus = rememberInitialFocusTarget()
  val advancedFocus = rememberInitialFocusTarget()
  val subtitlesUrlFieldFocus = rememberInitialFocusTarget()
  val subtitlesRevealFocus = rememberInitialFocusTarget()
  val saveFocus = rememberInitialFocusTarget()

  // Which row of the list composes each of those targets. Every explicit focus jump on this screen
  // goes through here, because a target whose section is scrolled off no longer exists - see
  // [SettingsFocusJumper]. The targets are remembered objects whose identity never changes, so the
  // map is built once for the life of the screen.
  val focusJumper = remember(listState) {
    SettingsFocusJumper(
      scope = focusScope,
      listState = listState,
      itemOf = mapOf(
        pairInitialFocus to SettingsItem.QuickSetup,
        clearKeyFocus to SettingsItem.Tmdb,
        lastRemoveFocus to SettingsItem.Addons,
        movedAddonFocus to SettingsItem.Addons,
        addonRevealFocus to SettingsItem.Addons,
        addButtonFocus to SettingsItem.Addons,
        audioLanguageFocus to SettingsItem.Playback,
        subtitleLanguageFocus to SettingsItem.Playback,
        playbackLanguageSaveFocus to SettingsItem.Playback,
        advancedFocus to SettingsItem.Advanced,
        subtitlesRevealFocus to SettingsItem.Advanced,
        saveFocus to SettingsItem.Save,
      ),
    )
  }

  val stopEditing: (SettingsTextField) -> Unit = { field ->
    if (editingField == field) {
      editingField = null
      softwareKeyboard?.hide()
    }
  }
  val startEditing: (SettingsTextField, InitialFocusTarget) -> Unit = { field, target ->
    if (editingField == field) {
      // BACK hides Gboard without notifying Compose that the input session ended. Center on the
      // still-focused editable field is therefore an explicit reopen request.
      softwareKeyboard?.show()
    } else {
      editingField = field
      // Accessibility ACTION_CLICK does not guarantee input focus. Remote Center arrives on an
      // already-focused field, so this is a no-op there and makes the non-key path deterministic.
      if (target.placed && !target.focused) {
        runCatching { target.requester.requestFocus() }
      }
    }
  }

  // Material3's value-based text field starts an input connection on TV even when
  // showKeyboardOnFocus=false. `readOnly` is the hard boundary; after recomposition has made the
  // selected field editable, wait one frame so the new input connection exists before showing IME.
  LaunchedEffect(editingField, softwareKeyboard) {
    if (editingField != null) {
      withFrameNanos { }
      softwareKeyboard?.show()
    }
  }

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

  LaunchedEffect(storedPlayerPrefs) {
    val prefs = storedPlayerPrefs
    if (prefs != null) {
      if (!playbackSeeded) {
        audioLanguage = prefs.audioLanguage
        subtitleLanguage = prefs.subtitleLanguage
        playbackSeeded = true
      }
      // A mutation callback installs its authoritative post-write value directly. Do not replace
      // that with an older queued StateFlow emission while the write is still completing.
      if (!playbackBusy) playbackControls = prefs
    }
  }

  // Reconnect the current composition to a ViewModel-owned save. No callback is retained by the
  // ViewModel: recreation disposes this observer and the replacement observes the same request id.
  LaunchedEffect(activeSaveId, saveOperation) {
    val requestId = activeSaveId ?: return@LaunchedEffect
    val operation = saveOperation
    if (operation?.requestId != requestId) {
      activeSaveId = null
      saveStatus = context.getString(
        R.string.settings_save_watchdog_unconfirmed,
        context.getString(R.string.app_name),
      )
      if (completedSaveId != requestId) {
        completedSaveId = requestId
        currentOnSaveComplete(false)
      }
      return@LaunchedEffect
    }

    operation.playerPrefs?.let { saved ->
      playbackControls = saved
      if (audioLanguage == operation.submittedAudioLanguage) {
        audioLanguage = saved.audioLanguage
      }
      if (subtitleLanguage == operation.submittedSubtitleLanguage) {
        subtitleLanguage = saved.subtitleLanguage
      }
    }
    val update = operation.update ?: return@LaunchedEffect
    saveStatus = update.message
    val success = update.completionSuccess() ?: return@LaunchedEffect
    if (completedSaveId != requestId) {
      completedSaveId = requestId
      activeSaveId = null
      currentOnSaveComplete(success)
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
  // is exactly where a viewer looks first. A single scroll to the top does not fix it either,
  // because the bringIntoView animation finishes afterwards and puts it back.
  //
  // So the page is held at its top until the viewer actually drives - the same one-shot idiom
  // HomeScreen uses to stop a late-arriving rail from yanking focus. The moment they press a
  // direction, the scroll is theirs.
  //
  // The lazy list states the same position as two numbers rather than one, and both have to be
  // watched: an item-sized nudge and a sub-item one are equally capable of cutting the heading.
  //
  // Saveable, because the position it argues with is saveable: the lazy list restores its offset
  // through SaveableStateProvider on a Settings -> Pair -> BACK round trip, and through the whole
  // saved hierarchy when the activity is recreated behind the player's display-mode switch. A
  // plainly remembered flag came back false in both cases, so the hold re-armed over a scroll
  // position the viewer had already chosen and snapped the page to item 0 under them. HomeScreen
  // keeps its own one-shot flags saveable for exactly this reason; see the comment there.
  var userNavigated by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(userNavigated) {
    if (userNavigated) return@LaunchedEffect
    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
      .collect { (index, offset) ->
        // Re-read the flag rather than trusting this effect's key alone. Releasing the hold cancels
        // this collector, but only once the composition has run again - and the scroll that
        // released it (the save verdict below) is already moving by then. Without the check, an
        // emission from that first frame would snap the page back to the top and take the verdict
        // off screen.
        if (userNavigated) return@collect
        if (index != 0 || offset != 0) listState.scrollToItem(0)
      }
  }

  // The return trip deserves the same courtesy as the arrival: walking focus back up the form
  // stops at the first control, and bringIntoView reveals exactly that control's rect - which
  // leaves the screen's own heading cut off one item above it. When focus comes back to the
  // top control after the viewer has driven away, put the heading back on screen too.
  LaunchedEffect(Unit) {
    snapshotFlow { pairInitialFocus.focused }.collect { focused ->
      // Both coordinates, for the same reason the hold-at-top watches both: a heading cut off
      // by half an item is exactly as absent as one whole items away.
      val scrolled = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 0
      if (focused && userNavigated && scrolled) listState.animateScrollToItem(0)
    }
  }

  // Removing a row destroys the node focus is sitting on, and the confirmation dialog owns focus
  // until it leaves the composition, so the recovery has to wait for a frame in which the Add
  // button is the nearest live target. Without it the D-pad is dead after a removal.
  LaunchedEffect(addonEditTick) {
    if (addonEditTick == 0) return@LaunchedEffect
    repeat(FocusRecoveryFrames) {
      withFrameNanos { }
      if (addButtonFocus.focused) return@LaunchedEffect
      runCatching { addButtonFocus.requester.requestFocus() }
    }
    // Since the page became a lazy list the Add button can also be gone rather than merely busy:
    // a viewer who scrolled the addon card off while the dialog was up leaves this requester
    // attached to nothing. Bring the card back rather than ending a removal with a dead D-pad.
    focusJumper.jump(addButtonFocus)
  }

  // The same problem one step gentler: a reorder does not destroy the row focus is on, it moves it.
  // The row is keyed by URL, so its subtree survives the reshuffle - but the button that was
  // pressed can be disabled by the very press that moved it, because an addon that reaches the top
  // has no Up left and one that reaches the bottom has no Down, and a disabled node cannot hold
  // focus. That strands the D-pad at exactly the two positions a viewer reorders towards.
  //
  // So movedAddonFocus migrates: the row owning movedAddonUrl claims it on whichever of its two
  // controls is still live (see [addonMoveControl]), and this asks that node for focus. Unlike the
  // removal recovery it does not stop at the first frame that reports focus - the write callback,
  // the list emission and the relayout land on different frames, so a request satisfied against the
  // pre-move layout can be undone a frame later. Re-asking for the whole budget gives the settled
  // list the last word; a request is skipped on any frame the target already holds focus, so a move
  // that lands immediately costs nothing but the check.
  LaunchedEffect(addonMoveTick) {
    if (addonMoveTick == 0) return@LaunchedEffect
    var requested = false
    var recovered = false
    repeat(FocusRecoveryFrames) {
      withFrameNanos { }
      when {
        // Focus is where it belongs. Only counts as a recovery once we have actually asked for
        // it: the first frames of a move are the pressed button still holding focus, which says
        // nothing about where the reshuffle will leave it.
        movedAddonFocus.focused -> if (requested) recovered = true
        movedAddonFocus.placed -> {
          requested = true
          runCatching { movedAddonFocus.requester.requestFocus() }
        }
      }
    }
    // And the same last resort, for the same reason: the addon card can have been scrolled off
    // while the write was in flight, which leaves this requester attached to nothing at all.
    // Skipped once a recovery has landed, so a viewer who moved on within the budget - the row is
    // theirs again the moment focus returns to it - is not dragged back to it.
    if (!recovered && !movedAddonFocus.focused) focusJumper.jump(movedAddonFocus)
  }

  // The strip renders under Save, which is now the last thing on the page. Nothing below Save is
  // focusable, so no bringIntoView would ever pull the result of the press into view on a screen
  // taller than the viewport. Keyed on the status so it only fires for a save, never for the
  // in-card notices, which appear beside the button that produced them.
  //
  // Save is the last item, and a lazy list will not scroll past its own end, so asking for that
  // item is the same destination the old animateScrollTo(maxValue) reached. What is new is that
  // the scroll can dispose whatever the viewer was focused on - the watchdog fires a minute after
  // the press, from anywhere on the page - so focus is handed to Save when the form had it and
  // lost it on the way down. A save started from the leave dialog is left alone: the form never
  // held focus, so nothing here takes it away from the dialog.
  //
  // It also ends the hold-at-top. That hold exists to survive the opening frames, when the initial
  // focus request is still dragging a settling layout about; a save verdict is the page moving
  // because the viewer pressed something, which is the same evidence a D-pad press gives. Leaving
  // the hold armed would put the two in a tug of war over the scroll position and the hold would
  // win, taking the verdict straight back off screen.
  LaunchedEffect(saveStatus) {
    if (saveStatus.isBlank()) return@LaunchedEffect
    val hadFocus = formFocused
    userNavigated = true
    listState.animateScrollToItem(SettingsItem.Save.ordinal)
    if (hadFocus && !formFocused) focusJumper.jump(saveFocus)
  }

  // Draft text counts even where the save guard will protect a stored key. Otherwise deleting the
  // visible key or typing an addon and leaving without pressing Add would silently discard work
  // while the screen claimed there was nothing pending.
  val playbackLanguageDirty = playbackReady && (
    audioLanguage.trim().lowercase(Locale.ROOT) != storedPlayerPrefs?.audioLanguage.orEmpty() ||
      subtitleLanguage.trim().lowercase(Locale.ROOT) !=
      storedPlayerPrefs?.subtitleLanguage.orEmpty()
    )
  val dirty = seeded && (
    tmdbKey.trim() != storedKey.orEmpty().trim() ||
      newAddonUrl.isNotBlank() ||
      SettingsSaveGuard.normalizeSubtitlesBase(subtitlesUrl) !=
      SettingsSaveGuard.normalizeSubtitlesBase(storedSubtitles.orEmpty()) ||
      playbackLanguageDirty
    )

  LaunchedEffect(dirty) { onDirtyChanged(dirty) }

  // A connection verdict describes the exact values that were tested. Editing any of them, or
  // changing the immediately-persisted addon list, invalidates both that verdict and an in-flight
  // probe so a late callback cannot paint the new draft as connected.
  val invalidateConnectionVerdict: () -> Unit = {
    val wasSaving = saving
    activeSaveId?.let(viewModel::cancelSettingsSave)
    activeSaveId = null
    saveStatus = ""
    if (wasSaving) currentOnSaveComplete(false)
  }

  // Immediate mutations use the same ViewModel-owned handoff as the long Settings save. The old
  // callback shape retained this composition's mutable state from persistenceScope; recreation
  // disposed that state and silently lost both busy and failure feedback.
  LaunchedEffect(mutationOperation) {
    val operation = mutationOperation ?: return@LaunchedEffect
    val result = operation.result ?: return@LaunchedEffect
    val request = operation.request
    val prefs = operation.playerPrefs
    prefs?.let { playbackControls = it }
    try {
      when (request) {
        is SettingsMutationRequest.AddAddon -> {
          val normalized = AddonList.normalize(request.submittedUrl)
          addonNotice = when (result) {
            SettingsMutationResult.Changed -> {
              invalidateConnectionVerdict()
              if (newAddonUrl == request.submittedUrl) {
                newAddonUrl = ""
                showAddonUrl = false
              }
              Notice(
                context.getString(R.string.settings_addon_added, AddonList.label(normalized)),
                StatusTone.Success,
              )
            }
            SettingsMutationResult.Unchanged -> Notice(
              context.getString(R.string.settings_addon_already_in_list),
              StatusTone.Caution,
            )
            SettingsMutationResult.Failed -> Notice(
              context.getString(
                R.string.settings_addon_add_failed,
                AddonList.label(normalized),
              ),
              StatusTone.Danger,
            )
          }
        }
        is SettingsMutationRequest.MoveAddon -> {
          val label = AddonList.label(request.url)
          addonNotice = when (result) {
            SettingsMutationResult.Changed -> {
              invalidateConnectionVerdict()
              if (movedAddonUrl == request.url) addonMoveTick++
              Notice(
                context.getString(
                  if (request.direction < 0) {
                    R.string.settings_addon_moved_earlier
                  } else {
                    R.string.settings_addon_moved_later
                  },
                  label,
                ),
                StatusTone.Success,
              )
            }
            SettingsMutationResult.Unchanged -> Notice(
              context.getString(R.string.settings_addon_already_positioned, label),
              StatusTone.Info,
            )
            SettingsMutationResult.Failed -> Notice(
              context.getString(R.string.settings_addon_move_failed, label),
              StatusTone.Danger,
            )
          }
        }
        is SettingsMutationRequest.RemoveAddon -> {
          val label = AddonList.label(request.url)
          when (result) {
            SettingsMutationResult.Changed -> {
              invalidateConnectionVerdict()
              addonNotice = Notice(
                context.getString(R.string.settings_addon_removed, label),
                StatusTone.Info,
              )
              pendingRemovalFeedback = null
              pendingRemoval = null
              addonEditTick++
            }
            SettingsMutationResult.Unchanged -> {
              val message = context.getString(R.string.settings_addon_not_found, label)
              if (pendingRemoval == request.url) pendingRemovalFeedback = message
              else addonNotice = Notice(message, StatusTone.Info)
            }
            SettingsMutationResult.Failed -> {
              val message = context.getString(R.string.settings_remove_addon_failed, label)
              if (pendingRemoval == request.url) pendingRemovalFeedback = message
              else addonNotice = Notice(message, StatusTone.Danger)
            }
          }
        }
        SettingsMutationRequest.ClearTmdbKey -> when (result) {
          SettingsMutationResult.Changed -> {
            invalidateConnectionVerdict()
            tmdbKey = ""
            showTmdbKey = false
            tmdbNotice = Notice(
              context.getString(R.string.settings_tmdb_key_cleared),
              StatusTone.Caution,
            )
            pendingClearKeyFeedback = null
            pendingClearKey = false
          }
          SettingsMutationResult.Unchanged -> {
            val message = context.getString(R.string.settings_no_saved_tmdb_key)
            if (pendingClearKey) pendingClearKeyFeedback = message
            else tmdbNotice = Notice(message, StatusTone.Info)
          }
          SettingsMutationResult.Failed -> {
            val message = context.getString(R.string.settings_clear_tmdb_key_failed)
            if (pendingClearKey) pendingClearKeyFeedback = message
            else tmdbNotice = Notice(message, StatusTone.Danger)
          }
        }
        is SettingsMutationRequest.PlaybackLanguages -> {
          if (result == SettingsMutationResult.Changed) {
            prefs?.let { saved ->
              if (audioLanguage == request.audio) audioLanguage = saved.audioLanguage
              if (subtitleLanguage == request.subtitles) {
                subtitleLanguage = saved.subtitleLanguage
              }
            }
          }
          playbackNotice = when (result) {
            SettingsMutationResult.Changed -> Notice(
              context.getString(R.string.settings_language_preferences_saved),
              StatusTone.Success,
            )
            SettingsMutationResult.Unchanged -> Notice(
              context.getString(R.string.settings_language_preferences_unchanged),
              StatusTone.Info,
            )
            SettingsMutationResult.Failed -> Notice(
              context.getString(R.string.settings_language_preferences_failed),
              StatusTone.Danger,
            )
          }
        }
        is SettingsMutationRequest.PlaybackSubtitleSize -> {
          val size = SubtitleSize.fromStorage(request.storageName)
          playbackNotice = if (result == SettingsMutationResult.Failed) {
            Notice(context.getString(R.string.settings_subtitle_size_failed), StatusTone.Danger)
          } else {
            Notice(
              context.getString(
                R.string.settings_subtitle_size_saved,
                context.getString(size.labelResource()),
              ),
              StatusTone.Success,
            )
          }
        }
        is SettingsMutationRequest.PlaybackAudioOutput -> {
          val output = AudioOutputMode.fromStorage(request.storageName)
          playbackNotice = if (result == SettingsMutationResult.Failed) {
            Notice(context.getString(R.string.settings_audio_output_failed), StatusTone.Danger)
          } else {
            Notice(
              context.getString(
                R.string.settings_audio_output_saved,
                context.getString(output.labelResource()),
              ),
              StatusTone.Success,
            )
          }
        }
        is SettingsMutationRequest.AutoPlayNext -> {
          playbackNotice = if (result == SettingsMutationResult.Failed) {
            Notice(context.getString(R.string.settings_autoplay_failed), StatusTone.Danger)
          } else {
            Notice(
              context.getString(
                if (request.enabled) {
                  R.string.settings_autoplay_enabled
                } else {
                  R.string.settings_autoplay_disabled
                },
              ),
              StatusTone.Success,
            )
          }
        }
        is SettingsMutationRequest.UpNextCountdown -> {
          playbackNotice = if (result == SettingsMutationResult.Failed) {
            Notice(context.getString(R.string.settings_countdown_failed), StatusTone.Danger)
          } else {
            Notice(
              context.resources.getQuantityString(
                R.plurals.settings_countdown_saved,
                request.seconds,
                request.seconds,
              ),
              StatusTone.Success,
            )
          }
        }
        SettingsMutationRequest.ResetPlayback -> when (result) {
          SettingsMutationResult.Changed, SettingsMutationResult.Unchanged -> {
            audioLanguage = ""
            subtitleLanguage = ""
            playbackNotice = Notice(
              context.getString(R.string.settings_playback_defaults_reset),
              StatusTone.Info,
            )
            pendingPlaybackResetFeedback = null
            pendingPlaybackReset = false
          }
          SettingsMutationResult.Failed -> {
            val message = context.getString(R.string.settings_reset_playback_failed)
            if (pendingPlaybackReset) pendingPlaybackResetFeedback = message
            else playbackNotice = Notice(message, StatusTone.Danger)
          }
        }
      }
    } finally {
      viewModel.consumeSettingsMutation(operation.requestId)
    }
  }

  val startSave: () -> Unit = save@{
    if (!saving) {
      if (mutationBusy) {
        saveStatus = context.getString(R.string.settings_save_playback_in_progress)
        currentOnSaveComplete(false)
        return@save
      }
      if (newAddonUrl.isNotBlank()) {
        addonNotice = Notice(
          context.getString(R.string.settings_addon_draft_pending),
          StatusTone.Caution,
        )
        // The notice it just wrote is inside the addon card, which the viewer is standing on Save
        // to have missed entirely. The jump scrolls that card back before asking for focus, so the
        // refusal is answered where the answer is written.
        focusJumper.jump(addButtonFocus)
        currentOnSaveComplete(false)
      } else {
        completedSaveId = null
        saveStatus = ""
        activeSaveId = viewModel.startSettingsSave(
          tmdbKey = tmdbKey,
          subtitlesBaseUrl = subtitlesUrl,
          audioLanguage = audioLanguage.takeIf { playbackReady },
          subtitleLanguage = subtitleLanguage.takeIf { playbackReady },
        )
      }
    }
  }

  LaunchedEffect(saveRequest) {
    if (saveRequest > 0) {
      // Consume before starting: both calls are synchronous, and a recreation can then either
      // restore this unconsumed token or restore activeSaveId, never replay both.
      currentOnSaveRequestHandled(saveRequest)
      startSave()
    }
  }

  // Deliberately a plain LazyColumn with no LocalBringIntoViewSpec override. Home provides one
  // because its rails want a fixed focus line; this page is a stack of cards of wildly different
  // heights, and a vertical spec is also inherited by every text field on it as that field's own
  // horizontal spec. The default "scroll the minimum needed" is what the focus behaviour above was
  // measured against.
  LazyColumn(
    state = listState,
    verticalArrangement = Arrangement.spacedBy(NebulaDimens.RailGap),
    // Where the Column applied its padding: inside the scroll container, so the screen margins
    // scroll with the content instead of cropping it.
    contentPadding = PaddingValues(
      horizontal = NebulaDimens.ScreenEdge,
      vertical = NebulaDimens.ScreenEdgeVertical,
    ),
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
      // Whether anything in the form holds focus at all. A programmatic scroll can now dispose
      // the node the viewer was sitting on, and this is what tells that apart from a scroll that
      // ran while a dialog or the nav rail owned focus and must not be interrupted.
      .onFocusChanged { formFocused = it.hasFocus },
  ) {
    settingsItem(SettingsItem.Header) {
      // ScreenHeader pads itself to the content line and hangs its tick in the margin, so it
      // expects a container with no padding of its own. The list's contentPadding has already
      // inset this item by ScreenEdge, which would indent the heading a second time; the offset
      // cancels exactly that, landing the words flush with the cards below and the tick
      // TickInset to their left.
      ScreenHeader(
        title = stringResource(R.string.nav_settings),
        // The only place the running build is stated anywhere in the app; without it the owner of a
        // sideloaded, self-updating APK cannot find out what they have without adb.
        subtitle = stringResource(
          R.string.settings_version,
          stringResource(R.string.app_name),
          BuildConfig.VERSION_NAME,
        ),
        modifier = Modifier.offset(x = -NebulaDimens.ScreenEdge),
      )
    }

    settingsItem(SettingsItem.QuickSetup) {
      SettingsSection(
        title = stringResource(R.string.settings_quick_setup_title),
        description = stringResource(R.string.settings_quick_setup_description),
      ) {
        NebulaButton(
          // Same words as the button on Home that opens the same screen. It used to carry a
          // parenthetical here and not there, which is two names for one destination.
          text = stringResource(R.string.home_action_setup_phone),
          onClick = onPairWithPhone,
          icon = Icons.Filled.Phone,
          modifier = Modifier.initialFocusTarget(pairInitialFocus),
        )
      }
    }

    settingsItem(SettingsItem.Tmdb) {
      SettingsSection(
        title = stringResource(R.string.settings_tmdb_title),
        description = stringResource(
          R.string.settings_tmdb_description,
          stringResource(R.string.app_name),
          stringResource(R.string.settings_tmdb_key_path),
        ),
      ) {
        OutlinedTextField(
          value = tmdbKey,
          onValueChange = {
            if (it != tmdbKey) invalidateConnectionVerdict()
            tmdbKey = it
          },
          readOnly = editingField != SettingsTextField.TmdbKey,
          singleLine = true,
          placeholder = { Text(stringResource(R.string.settings_tmdb_key_placeholder)) },
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
            showKeyboardOnFocus = false,
          ),
          keyboardActions = KeyboardActions(
            onDone = { stopEditing(SettingsTextField.TmdbKey) },
          ),
          modifier = Modifier
            // Sized to the 32 characters it holds rather than to a fraction of the card, which left
            // a field twice as wide as any value it can ever contain.
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .initialFocusTarget(tmdbFieldFocus)
            .semantics {
              contentDescription = context.getString(R.string.settings_tmdb_api_key)
            }
            // A material3 text field traps the D-pad on TV, so move focus
            // between fields explicitly before it consumes the key.
            .fieldNav(
              focusJumper,
              down = clearKeyFocus,
              up = pairInitialFocus,
              isEditing = editingField == SettingsTextField.TmdbKey,
              onStartEditing = {
                startEditing(SettingsTextField.TmdbKey, tmdbFieldFocus)
              },
              onStopEditing = { stopEditing(SettingsTextField.TmdbKey) },
            ),
        )
        // Under the field rather than beside it. Every button on this screen is reachable
        // by pressing down, which is the only direction a text field can be talked out of:
        // left and right are the caret's, and a button that needed one of those to reach
        // would be unreachable from a focused field.
        Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
          NebulaButton(
            text = stringResource(
              if (showTmdbKey) R.string.settings_hide_key else R.string.settings_show_key,
            ),
            onClick = { showTmdbKey = !showTmdbKey },
            style = NebulaButtonStyle.Ghost,
            modifier = Modifier
              .initialFocusTarget(clearKeyFocus)
              .semantics {
                contentDescription = if (showTmdbKey) {
                  context.getString(R.string.settings_hide_tmdb_key_description)
                } else {
                  context.getString(R.string.settings_show_tmdb_key_description)
                }
              },
          )
          NebulaButton(
            text = stringResource(R.string.settings_clear_key),
            onClick = {
              // A key takes minutes to re-enter on a remote. Confirm a real deletion instead of
              // making the most destructive control in the section a one-press action.
              if (tmdbKey.isBlank() && storedKey.isNullOrBlank()) {
                tmdbNotice = Notice(
                  context.getString(R.string.settings_no_tmdb_key_to_clear),
                  StatusTone.Info,
                )
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
    }

    settingsItem(SettingsItem.Addons) {
      SettingsSection(
        title = stringResource(R.string.settings_stream_addons_title),
        description = stringResource(R.string.settings_stream_addons_description),
        // The two commit models on this screen were silently different. Now they are visibly
        // different: this list writes on press, the fields below Save do not.
        badge = {
          NebulaBadge(
            text = stringResource(R.string.settings_saves_immediately),
            tone = BadgeTone.Good,
          )
        },
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
              stringResource(R.string.settings_no_addons),
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
            // Which of this row's two controls, if either, the recovery after a move is aiming at.
            // Computed from the settled list, so it answers for the row's position *after* the
            // reshuffle rather than the one the press was made from.
            val moveFocusControl = addonMoveControl(
              recovering = url == movedAddonUrl,
              direction = movedAddonDirection,
              index = index,
              lastIndex = addons.lastIndex,
            )
            NebulaButton(
              text = stringResource(R.string.settings_move_up),
              icon = Icons.Filled.KeyboardArrowUp,
              enabled = index > 0 && !mutationBusy,
              // Keep the pressed node focused while its ViewModel-owned mutation is in flight.
              focusableWhenDisabled = mutationBusy && index > 0,
              style = NebulaButtonStyle.Ghost,
              onClick = {
                // Claimed before the write, not in its callback: the target then attaches to the
                // button that already holds focus and the reshuffle migrates it, rather than the
                // recovery having to find focus again from wherever the reorder dropped it.
                movedAddonUrl = url
                movedAddonDirection = -1
                viewModel.moveAddon(url, -1)
              },
              modifier = Modifier
                .initialFocusTarget(
                  movedAddonFocus.takeIf { moveFocusControl == AddonMoveControl.Up },
                )
                .semantics {
                  contentDescription = context.getString(
                    R.string.settings_move_addon_earlier_description,
                    addonLabels[index],
                  )
                },
            )
            NebulaButton(
              text = stringResource(R.string.settings_move_down),
              icon = Icons.Filled.KeyboardArrowDown,
              enabled = index < addons.lastIndex && !mutationBusy,
              focusableWhenDisabled = mutationBusy && index < addons.lastIndex,
              style = NebulaButtonStyle.Ghost,
              onClick = {
                movedAddonUrl = url
                movedAddonDirection = 1
                viewModel.moveAddon(url, 1)
              },
              modifier = Modifier
                .initialFocusTarget(
                  movedAddonFocus.takeIf { moveFocusControl == AddonMoveControl.Down },
                )
                .semantics {
                  contentDescription = context.getString(
                    R.string.settings_move_addon_later_description,
                    addonLabels[index],
                  )
                },
            )
            NebulaButton(
              text = stringResource(R.string.settings_remove),
              enabled = !mutationBusy,
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
                .semantics {
                  contentDescription = context.getString(
                    R.string.settings_remove_addon_description,
                    addonLabels[index],
                  )
                },
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
          readOnly = editingField != SettingsTextField.AddonUrl,
          singleLine = true,
          placeholder = { Text(stringResource(R.string.settings_addon_url_placeholder)) },
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
            showKeyboardOnFocus = false,
          ),
          keyboardActions = KeyboardActions(
            onDone = { stopEditing(SettingsTextField.AddonUrl) },
          ),
          modifier = Modifier
            // Shares its right edge with the rows above it, which are fillMaxWidth. At 0.8f the
            // list terminated 151dp further right than the field that adds to it.
            .fillMaxWidth()
            .initialFocusTarget(addonUrlFieldFocus)
            .semantics {
              contentDescription = context.getString(R.string.settings_new_addon_url_description)
            }
            .fieldNav(
              focusJumper,
              down = addonRevealFocus,
              up = if (addons.isEmpty()) clearKeyFocus else lastRemoveFocus,
              isEditing = editingField == SettingsTextField.AddonUrl,
              onStartEditing = {
                startEditing(SettingsTextField.AddonUrl, addonUrlFieldFocus)
              },
              onStopEditing = { stopEditing(SettingsTextField.AddonUrl) },
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
          NebulaButton(
            text = stringResource(
              if (showAddonUrl) R.string.settings_hide_url else R.string.settings_show_url,
            ),
            onClick = { showAddonUrl = !showAddonUrl },
            style = NebulaButtonStyle.Ghost,
            modifier = Modifier
              .initialFocusTarget(addonRevealFocus)
              .semantics {
                contentDescription = if (showAddonUrl) {
                  context.getString(R.string.settings_hide_new_addon_url_description)
                } else {
                  context.getString(R.string.settings_show_new_addon_url_description)
                }
              },
          )
          NebulaButton(
            text = stringResource(R.string.settings_add_addon),
            enabled = !mutationBusy,
            onClick = {
              // Persisted on press rather than staged behind Save: a list whose edits only
              // land later shows a configuration that is not the one being used.
              val submittedUrl = newAddonUrl
              val normalized = AddonList.normalize(submittedUrl)
              addonNotice = when {
                normalized.isEmpty() -> Notice(
                  context.getString(R.string.settings_enter_addon_url_first),
                  StatusTone.Caution,
                )
                normalized in addons -> Notice(
                  context.getString(R.string.settings_addon_already_in_list),
                  StatusTone.Caution,
                )
                addons.size >= AddonList.MAX_ADDONS ->
                  Notice(
                    context.getString(R.string.settings_addon_limit_reached),
                    StatusTone.Caution,
                  )
                else -> {
                  viewModel.addAddon(submittedUrl)
                  null
                }
              }
            },
            modifier = Modifier.initialFocusTarget(addButtonFocus),
          )
        }
        addonNotice?.let { SectionNotice(it) }
      }
    }

    settingsItem(SettingsItem.Playback) {
      SettingsSection(
        title = stringResource(R.string.settings_playback_title),
        description = stringResource(R.string.settings_playback_description),
        badge = {
          NebulaBadge(
            text = stringResource(R.string.settings_saves_immediately),
            tone = BadgeTone.Good,
          )
        },
      ) {
        Text(
          stringResource(R.string.settings_audio_language_label),
          style = MaterialTheme.typography.labelMedium,
          color = NebulaPalette.TextMuted,
        )
        OutlinedTextField(
          value = audioLanguage,
          onValueChange = {
            if (it != audioLanguage) invalidateConnectionVerdict()
            audioLanguage = it
            playbackNotice = null
          },
          enabled = playbackReady && !playbackBusy,
          readOnly = editingField != SettingsTextField.AudioLanguage,
          singleLine = true,
          placeholder = {
            Text(
              stringResource(
                R.string.settings_audio_language_placeholder,
                stringResource(R.string.settings_language_code_english),
              ),
            )
          },
          shape = NebulaShapes.medium,
          colors = settingsFieldColors(),
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
            showKeyboardOnFocus = false,
          ),
          keyboardActions = KeyboardActions(
            onNext = {
              // Next is a real edit transition, not a navigation-mode landing. Make the subtitle
              // field editable before moving focus; its frame-delayed effect keeps Gboard open.
              editingField = SettingsTextField.SubtitleLanguage
              focusJumper.leaveTextField(subtitleLanguageFocus)
            },
          ),
          modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .initialFocusTarget(audioLanguageFocus)
            .semantics {
              contentDescription = context.getString(R.string.settings_audio_language_label)
            }
            .fieldNav(
              focusJumper,
              down = subtitleLanguageFocus,
              up = addButtonFocus,
              isEditing = editingField == SettingsTextField.AudioLanguage,
              onStartEditing = {
                startEditing(SettingsTextField.AudioLanguage, audioLanguageFocus)
              },
              onStopEditing = { stopEditing(SettingsTextField.AudioLanguage) },
            ),
        )
        Text(
          stringResource(R.string.settings_subtitle_language_label),
          style = MaterialTheme.typography.labelMedium,
          color = NebulaPalette.TextMuted,
        )
        OutlinedTextField(
          value = subtitleLanguage,
          onValueChange = {
            if (it != subtitleLanguage) invalidateConnectionVerdict()
            subtitleLanguage = it
            playbackNotice = null
          },
          enabled = playbackReady && !playbackBusy,
          readOnly = editingField != SettingsTextField.SubtitleLanguage,
          singleLine = true,
          placeholder = {
            Text(
              stringResource(
                R.string.settings_subtitle_language_placeholder,
                stringResource(R.string.settings_language_code_english),
                stringResource(R.string.settings_subtitle_off_code),
              ),
            )
          },
          shape = NebulaShapes.medium,
          colors = settingsFieldColors(),
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
            showKeyboardOnFocus = false,
          ),
          keyboardActions = KeyboardActions(
            onDone = { stopEditing(SettingsTextField.SubtitleLanguage) },
          ),
          modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .initialFocusTarget(subtitleLanguageFocus)
            .semantics {
              contentDescription = context.getString(R.string.settings_subtitle_language_label)
            }
            .fieldNav(
              focusJumper,
              down = playbackLanguageSaveFocus,
              up = audioLanguageFocus,
              isEditing = editingField == SettingsTextField.SubtitleLanguage,
              onStartEditing = {
                startEditing(SettingsTextField.SubtitleLanguage, subtitleLanguageFocus)
              },
              onStopEditing = { stopEditing(SettingsTextField.SubtitleLanguage) },
            ),
        )
        NebulaButton(
          text = stringResource(R.string.settings_apply_language_preferences),
          enabled = playbackReady && !playbackBusy,
          onClick = {
            val submittedAudio = audioLanguage
            val submittedSubtitles = subtitleLanguage
            viewModel.savePlaybackLanguages(submittedAudio, submittedSubtitles)
          },
          modifier = Modifier.initialFocusTarget(playbackLanguageSaveFocus),
        )

        Row(
          horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          NebulaButton(
            text = stringResource(
              R.string.settings_subtitle_size,
              stringResource(playbackSubtitleSize.labelResource()),
            ),
            enabled = playbackReady && !playbackBusy,
            onClick = {
              val next = SubtitleSize.stepped(playbackSubtitleSize, 1)
              viewModel.setPlaybackSubtitleSize(next.storageName)
            },
          )
          NebulaButton(
            text = stringResource(
              R.string.settings_audio_output,
              stringResource(playbackAudioOutput.labelResource()),
            ),
            enabled = playbackReady && !playbackBusy,
            onClick = {
              val next = AudioOutputMode.stepped(playbackAudioOutput, 1)
              viewModel.setPlaybackAudioOutput(next.storageName)
            },
          )
        }
        if (playbackAudioOutput == AudioOutputMode.Passthrough) {
          Text(
            stringResource(
              R.string.settings_passthrough_description,
              stringResource(R.string.app_name),
              stringResource(R.string.settings_audio_output_decode),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NebulaPalette.Caution,
          )
        }

        Row(
          horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          NebulaButton(
            text = stringResource(
              if (playback.autoPlayNext) {
                R.string.settings_autoplay_next_on
              } else {
                R.string.settings_autoplay_next_off
              },
            ),
            enabled = playbackReady && !playbackBusy,
            onClick = {
              val next = !playback.autoPlayNext
              viewModel.setAutoPlayNext(next)
            },
          )
          NebulaButton(
            text = pluralStringResource(
              R.plurals.settings_countdown,
              playback.upNextCountdownSeconds,
              playback.upNextCountdownSeconds,
            ),
            enabled = playbackReady && playback.autoPlayNext && !playbackBusy,
            onClick = {
              val next = PlaybackPreferencePolicy.nextCountdownSeconds(
                playback.upNextCountdownSeconds,
              )
              viewModel.setUpNextCountdownSeconds(next)
            },
          )
        }
        NebulaButton(
          text = stringResource(R.string.settings_reset_playback_defaults),
          enabled = playbackReady && !playbackBusy,
          style = NebulaButtonStyle.Ghost,
          onClick = {
            pendingPlaybackResetFeedback = null
            pendingPlaybackReset = true
          },
        )
        playbackNotice?.let { SectionNotice(it) }
      }
    }

    settingsItem(SettingsItem.Updates) {
      val checkBusy = updateStatus.phase == UpdateStatusPhase.CHECK_QUEUED ||
        updateStatus.phase == UpdateStatusPhase.CHECKING
      SettingsSection(
        title = stringResource(R.string.settings_updates_title),
        description = stringResource(R.string.settings_updates_description),
      ) {
        SectionNotice(updateStatusNotice(context, updateStatus))
        updateStatus.failureKind?.let { failure ->
          SectionNotice(updateFailureNotice(context, failure))
        }
        updateStatus.lastSuccessfulCheckAtMs?.let { checkedAtMs ->
          Text(
            stringResource(
              R.string.settings_update_last_successful_check,
              formatUpdateTimestamp(context, checkedAtMs),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NebulaPalette.TextMuted,
          )
        }
        updateStatus.lastFailedCheckAtMs?.let { checkedAtMs ->
          Text(
            stringResource(
              R.string.settings_update_last_failed_check,
              formatUpdateTimestamp(context, checkedAtMs),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NebulaPalette.TextMuted,
          )
        }
        NebulaButton(
          text = stringResource(
            if (checkBusy) {
              R.string.settings_update_check_in_progress
            } else {
              R.string.settings_check_for_updates
            },
          ),
          enabled = !checkBusy,
          onClick = {
            updateScope.launch {
              withContext(Dispatchers.IO) {
                UpdateWorkScheduler.requestManualCheck(context)
              }
            }
          },
        )
      }
    }

    settingsItem(SettingsItem.Support) {
      SettingsSection(
        title = stringResource(R.string.settings_support_title),
        description = stringResource(R.string.settings_support_description),
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          NebulaButton(
            text = stringResource(
              if (exportingDiagnostics) {
                R.string.settings_preparing_diagnostics
              } else {
                R.string.settings_share_diagnostics
              },
            ),
            enabled = !exportingDiagnostics,
            onClick = {
              exportingDiagnostics = true
              supportNotice = null
              supportScope.launch {
                val report = NebulaDiagnostics.export(context)
                exportingDiagnostics = false
                report.onSuccess { uri ->
                  val chooser = Intent.createChooser(
                    NebulaDiagnostics.shareIntent(uri),
                    context.getString(
                      R.string.settings_share_diagnostics_chooser,
                      context.getString(R.string.app_name),
                    ),
                  )
                  if (runCatching { context.startActivity(chooser) }.isFailure) {
                    supportNotice = Notice(
                      context.getString(R.string.settings_share_app_failed),
                      StatusTone.Danger,
                    )
                  }
                }.onFailure {
                  supportNotice = Notice(
                    context.getString(
                      R.string.settings_create_diagnostics_failed,
                      context.getString(R.string.app_name),
                    ),
                    StatusTone.Danger,
                  )
                }
              }
            },
          )
          NebulaButton(
            text = stringResource(R.string.settings_erase_all_app_data),
            style = NebulaButtonStyle.Danger,
            onClick = {
              pendingAppResetFeedback = null
              pendingAppReset = true
            },
          )
        }
        supportNotice?.let { SectionNotice(it) }
      }
    }

    settingsItem(SettingsItem.Advanced) {
      // The toggle and the card it opens are one row of the list. Two rows would let the
      // subtitles field aim its D-pad up at a toggle the list had disposed, and would put a
      // height animation across an item boundary; this way the disclosure resizes one item.
      Column(verticalArrangement = Arrangement.spacedBy(NebulaDimens.RailGap)) {
        NebulaButton(
          text = stringResource(R.string.settings_advanced),
          icon = if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
          style = NebulaButtonStyle.Ghost,
          onClick = { advanced = !advanced },
          // The chevron is the only thing that says which way this button goes, and it is drawn
          // decoratively, so open/closed has to be spelled out for anything not looking at it.
          modifier = Modifier
            .initialFocusTarget(advancedFocus)
            .semantics(mergeDescendants = true) {
              contentDescription = context.getString(
                if (advanced) {
                  R.string.settings_advanced_expanded
                } else {
                  R.string.settings_advanced_collapsed
                },
              )
            },
        )

        // The one layout animation in the app. Affordable here and nowhere else: this screen
        // decodes no images, the expansion runs for ~13 frames on a six-child Column, and it is
        // confined to this one list item - the lazy list around it re-measures a single row per
        // frame rather than a page. Without it the whole lower half of the page teleports.
        AnimatedVisibility(
          visible = advanced,
          // From the top, not the default bottom: a disclosure unrolls downward from the button
          // that opened it, and expanding from the bottom edge reads as the card sliding up out
          // of Save.
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
            // Named for its subject. It used to be titled "Advanced" directly under a button
            // reading "Advanced", so the word appeared twice in 60dp and the card's actual topic
            // never did.
            title = stringResource(R.string.settings_subtitles_addon_title),
            description = stringResource(R.string.settings_subtitles_addon_description),
          ) {
            // Above the field, not below it. As the last element of a scrolling page with
            // nothing focusable after it, the only feedback this field has could never be
            // scrolled into view.
            Text(
              stringResource(
                R.string.settings_currently_using,
                AddonList.safeDisplay(SettingsSaveGuard.normalizeSubtitlesBase(subtitlesUrl)),
              ),
              style = MaterialTheme.typography.bodySmall,
              color = NebulaPalette.TextMuted,
            )
            OutlinedTextField(
              value = subtitlesUrl,
              onValueChange = {
                if (it != subtitlesUrl) invalidateConnectionVerdict()
                subtitlesUrl = it
              },
              readOnly = editingField != SettingsTextField.SubtitlesUrl,
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
                showKeyboardOnFocus = false,
              ),
              keyboardActions = KeyboardActions(
                onDone = { stopEditing(SettingsTextField.SubtitlesUrl) },
              ),
              // Down goes to Save, which is the only thing that commits this value - the reason the
              // button now sits below the field instead of above it.
              modifier = Modifier
                .fillMaxWidth()
                .initialFocusTarget(subtitlesUrlFieldFocus)
                .semantics {
                  contentDescription = context.getString(
                    R.string.settings_subtitles_addon_url_description,
                  )
                }
                .fieldNav(
                  focusJumper,
                  down = subtitlesRevealFocus,
                  up = advancedFocus,
                  isEditing = editingField == SettingsTextField.SubtitlesUrl,
                  onStartEditing = {
                    startEditing(SettingsTextField.SubtitlesUrl, subtitlesUrlFieldFocus)
                  },
                  onStopEditing = { stopEditing(SettingsTextField.SubtitlesUrl) },
                ),
            )
            NebulaButton(
              text = stringResource(
                if (showSubtitlesUrl) R.string.settings_hide_url else R.string.settings_show_url,
              ),
              onClick = { showSubtitlesUrl = !showSubtitlesUrl },
              style = NebulaButtonStyle.Ghost,
              modifier = Modifier
                .initialFocusTarget(subtitlesRevealFocus)
                .semantics {
                  contentDescription = if (showSubtitlesUrl) {
                    context.getString(R.string.settings_hide_subtitles_url_description)
                  } else {
                    context.getString(R.string.settings_show_subtitles_url_description)
                  }
                },
            )
          }
        }
      }
    }

    settingsItem(SettingsItem.Save) {
      // Save and the verdict it produces are one row, so the scroll that reveals the verdict
      // is a scroll to the button that was pressed.
      Column(verticalArrangement = Arrangement.spacedBy(NebulaDimens.RailGap)) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(NebulaSpace.md),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          NebulaButton(
            // Says what the wait is for: this writes, then probes every addon manifest and TMDB,
            // each of which can block for ~30s.
            text = stringResource(R.string.settings_save_and_test),
            onClick = startSave,
            style = NebulaButtonStyle.Primary,
            modifier = Modifier.initialFocusTarget(saveFocus),
          )
          // Warn, not Bad: an edit that has not been written yet is a caveat, not a failure.
          if (dirty) {
            NebulaBadge(
              text = stringResource(R.string.settings_unsaved_changes),
              tone = BadgeTone.Warn,
            )
          }
        }

        if (saveStatus.isNotBlank()) {
          StatusStrip(status = saveStatus, busy = saving)
        }
      }
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
      message = dialogMessageWithFeedback(
        stringResource(R.string.settings_remove_addon_message),
        pendingRemovalFeedback,
      ),
      focusKey = url,
      focusLabel = "Addon removal options",
      actions = listOf(
        CardAction(stringResource(R.string.settings_remove_addon), destructive = true) {
          viewModel.removeAddon(url)
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
      title = stringResource(R.string.settings_tmdb_api_key),
      message = dialogMessageWithFeedback(
        stringResource(R.string.settings_clear_key_message),
        pendingClearKeyFeedback,
      ),
      focusKey = "clear-tmdb-key",
      focusLabel = "TMDB key removal options",
      actions = listOf(
        CardAction(stringResource(R.string.settings_clear_key), destructive = true) {
          viewModel.clearTmdbKey()
        },
      ),
      onDismiss = {
        pendingClearKeyFeedback = null
        pendingClearKey = false
      },
    )
  }

  if (pendingPlaybackReset) {
    CardOptionsDialog(
      title = stringResource(R.string.settings_playback_defaults_title),
      message = dialogMessageWithFeedback(
        stringResource(R.string.settings_reset_playback_message),
        pendingPlaybackResetFeedback,
      ),
      focusKey = "reset-playback-defaults",
      focusLabel = "Playback reset options",
      actions = listOf(
        CardAction(stringResource(R.string.settings_reset_playback_defaults), destructive = true) {
          viewModel.resetPlaybackPreferences()
        },
      ),
      onDismiss = {
        pendingPlaybackResetFeedback = null
        pendingPlaybackReset = false
      },
    )
  }

  if (pendingAppReset) {
    CardOptionsDialog(
      title = stringResource(
        R.string.settings_erase_all_data_title,
        stringResource(R.string.app_name),
      ),
      message = dialogMessageWithFeedback(
        stringResource(
          R.string.settings_erase_all_data_message,
          stringResource(R.string.app_name),
        ),
        pendingAppResetFeedback,
      ),
      focusKey = "erase-all-app-data",
      focusLabel = "App data reset options",
      actions = listOf(
        CardAction(stringResource(R.string.settings_erase_all_app_data), destructive = true) {
          val accepted = runCatching {
            context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
          }.getOrDefault(false)
          if (!accepted) {
            pendingAppResetFeedback = context.getString(
              R.string.settings_reset_request_rejected,
              context.getString(R.string.app_name),
            )
          }
        },
      ),
      onDismiss = {
        pendingAppResetFeedback = null
        pendingAppReset = false
      },
    )
  }
}

/** Screen-local labels keep persisted enum names stable while allowing the UI to be translated. */
private fun SubtitleSize.labelResource(): Int = when (this) {
  SubtitleSize.Small -> R.string.settings_subtitle_size_small
  SubtitleSize.Medium -> R.string.settings_subtitle_size_medium
  SubtitleSize.Large -> R.string.settings_subtitle_size_large
  SubtitleSize.Huge -> R.string.settings_subtitle_size_huge
}

/** See [SubtitleSize.labelResource]. */
private fun AudioOutputMode.labelResource(): Int = when (this) {
  AudioOutputMode.Decode -> R.string.settings_audio_output_decode
  AudioOutputMode.Passthrough -> R.string.settings_audio_output_passthrough
}

private fun updateStatusNotice(context: Context, status: UpdateStatus): Notice {
  val version = status.targetVersionName
  val text = when (status.phase) {
    UpdateStatusPhase.IDLE -> context.getString(R.string.settings_update_status_idle)
    UpdateStatusPhase.CHECK_QUEUED -> context.getString(R.string.settings_update_status_queued)
    UpdateStatusPhase.CHECKING -> context.getString(R.string.settings_update_status_checking)
    UpdateStatusPhase.UP_TO_DATE -> context.getString(R.string.settings_update_status_up_to_date)
    UpdateStatusPhase.DOWNLOAD_QUEUED -> if (version == null) {
      context.getString(R.string.settings_update_status_download_queued)
    } else {
      context.getString(R.string.settings_update_status_download_queued_version, version)
    }
    UpdateStatusPhase.DOWNLOADING -> if (version == null) {
      context.getString(R.string.settings_update_status_downloading)
    } else {
      context.getString(R.string.settings_update_status_downloading_version, version)
    }
    UpdateStatusPhase.READY -> if (version == null) {
      context.getString(R.string.settings_update_status_ready)
    } else {
      context.getString(R.string.settings_update_status_ready_version, version)
    }
    UpdateStatusPhase.RETRY_SCHEDULED -> {
      context.getString(R.string.settings_update_status_retry_scheduled)
    }
    UpdateStatusPhase.FAILED -> context.getString(R.string.settings_update_status_failed)
  }
  val tone = when (status.phase) {
    UpdateStatusPhase.UP_TO_DATE, UpdateStatusPhase.READY -> StatusTone.Success
    UpdateStatusPhase.RETRY_SCHEDULED -> StatusTone.Caution
    UpdateStatusPhase.FAILED -> StatusTone.Danger
    else -> StatusTone.Info
  }
  return Notice(text, tone)
}

/** Failure text is selected from a persisted category, never from Throwable.message. */
private fun updateFailureNotice(context: Context, failure: UpdateFailureKind): Notice {
  val textResource = when (failure) {
    UpdateFailureKind.NETWORK -> R.string.settings_update_failure_network
    UpdateFailureKind.RATE_LIMITED -> R.string.settings_update_failure_rate_limited
    UpdateFailureKind.SERVER -> R.string.settings_update_failure_server
    UpdateFailureKind.CONFIGURATION -> R.string.settings_update_failure_configuration
    UpdateFailureKind.DOWNLOAD -> R.string.settings_update_failure_download
    UpdateFailureKind.REJECTED_RELEASE -> R.string.settings_update_failure_rejected_release
    UpdateFailureKind.SCHEDULING -> R.string.settings_update_failure_scheduling
    UpdateFailureKind.UNKNOWN -> R.string.settings_update_failure_unknown
  }
  val tone = when (failure) {
    UpdateFailureKind.NETWORK,
    UpdateFailureKind.RATE_LIMITED,
    UpdateFailureKind.SERVER,
    UpdateFailureKind.DOWNLOAD,
    UpdateFailureKind.SCHEDULING -> StatusTone.Caution
    UpdateFailureKind.CONFIGURATION,
    UpdateFailureKind.REJECTED_RELEASE,
    UpdateFailureKind.UNKNOWN -> StatusTone.Danger
  }
  return Notice(context.getString(textResource), tone)
}

private fun formatUpdateTimestamp(context: Context, timestampMs: Long): String {
  val instant = Date(timestampMs)
  return context.getString(
    R.string.settings_update_time_value,
    DateFormat.getMediumDateFormat(context).format(instant),
    DateFormat.getTimeFormat(context).format(instant),
  )
}

@Composable
private fun dialogMessageWithFeedback(message: String, feedback: String?): String =
  if (feedback == null) {
    message
  } else {
    stringResource(R.string.settings_dialog_with_feedback, message, feedback)
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
 * Gives a Settings field two TV modes: D-pad focus for navigation, explicit Center/Enter editing.
 *
 * Every caller is `readOnly` in navigation mode. That is intentionally stronger than
 * [KeyboardOptions.showKeyboardOnFocus]: the value-based Material3 field ignored that option on
 * Google TV and still opened Gboard as soon as D-pad focus landed. Center/Enter first makes only
 * this field editable, then its caller opens the keyboard after a frame. Up/Down ends editing
 * before going to a named neighbour, which also recovers after BACK hid Gboard without changing
 * Compose focus. Losing focus is the final reset path.
 *
 * Since the page became a lazy list, "attached to nothing" also covers a section that is merely
 * off screen, which is why the move goes through [SettingsFocusJumper] rather than straight to the
 * requester: only it can tell a target that does not exist from one that has been scrolled out and
 * disposed, and bring the second kind back.
 */
internal enum class SettingsTextField {
  TmdbKey,
  AddonUrl,
  AudioLanguage,
  SubtitleLanguage,
  SubtitlesUrl,
}

internal enum class SettingsFieldKeyAction { NavigateUp, NavigateDown, Edit }

internal fun settingsFieldKeyAction(
  key: Key,
  type: KeyEventType,
  isEditing: Boolean,
): SettingsFieldKeyAction? = when {
  type != KeyEventType.KeyDown -> null
  key == Key.DirectionDown -> SettingsFieldKeyAction.NavigateDown
  key == Key.DirectionUp -> SettingsFieldKeyAction.NavigateUp
  key == Key.DirectionCenter -> SettingsFieldKeyAction.Edit
  (key == Key.Enter || key == Key.NumPadEnter) && !isEditing -> SettingsFieldKeyAction.Edit
  else -> null
}

private fun Modifier.fieldNav(
  jumper: SettingsFocusJumper,
  down: InitialFocusTarget?,
  up: InitialFocusTarget?,
  isEditing: Boolean,
  onStartEditing: () -> Unit,
  onStopEditing: () -> Unit,
): Modifier =
  onFocusChanged { state ->
    if (!state.hasFocus) onStopEditing()
  }
    // Preserve activation for TalkBack/switch access while readOnly removes the field's editing
    // action. This action exposes no field value and uses the same delayed start path as Center.
    .semantics {
      onClick {
        onStartEditing()
        true
      }
    }
    .onPreviewKeyEvent { event ->
      val action = settingsFieldKeyAction(event.key, event.type, isEditing)
      val target = when (action) {
        SettingsFieldKeyAction.NavigateDown -> down
        SettingsFieldKeyAction.NavigateUp -> up
        // Left and right belong to the caret. Hardware Enter belongs to the IME once editable, so
        // Done/Next can fire. Key-up is ignored to prevent a second move.
        SettingsFieldKeyAction.Edit, null -> null
      }
      if (action == SettingsFieldKeyAction.Edit) {
        onStartEditing()
        true
      } else if (target != null) {
        // BACK hides Gboard but leaves this field editable and focused. Flip readOnly first so an
        // input connection cannot follow focus to the next navigation stop.
        onStopEditing()
        jumper.leaveTextField(target)
      } else {
        false
      }
    }

/** The two reorder controls an addon row carries, and so the two nodes focus can land on. */
internal enum class AddonMoveControl { Up, Down }

/**
 * Which control of an addon row should hold focus once that row's addon has been moved.
 *
 * The one that was pressed, wherever it is still live: focus belongs where the viewer left it, and
 * a second press then carries the addon another step the same way. At the two edges that
 * control is precisely the one the move just disabled - an addon at the top has no Up, one at the
 * bottom has no Down - and a disabled button cannot take focus, so the answer there is the opposite
 * control. That is both the only direction the row has left and the one that undoes the move, which
 * is what a viewer who has just overshot is reaching for.
 *
 * Null for every row but the one that moved, and for a list of one, which cannot be reordered at
 * all and whose row therefore has nothing focusable to offer.
 */
internal fun addonMoveControl(
  recovering: Boolean,
  direction: Int,
  index: Int,
  lastIndex: Int,
): AddonMoveControl? = when {
  !recovering -> null
  direction < 0 && index > 0 -> AddonMoveControl.Up
  direction > 0 && index < lastIndex -> AddonMoveControl.Down
  index > 0 -> AddonMoveControl.Up
  index < lastIndex -> AddonMoveControl.Down
  else -> null
}

/**
 * The rows of the settings list, in the order they are emitted.
 *
 * Every one of these is always emitted - the Advanced disclosure expands inside its row rather
 * than adding one - which is what lets a focus jump turn a target into a scroll index without
 * asking the list what it happens to be showing. A section that could come and go would have to be
 * counted at runtime instead, and it would be counted on the frame *after* the one that needed it.
 */
private enum class SettingsItem {
  Header,
  QuickSetup,
  Tmdb,
  Addons,
  Playback,
  Updates,
  Support,
  Advanced,
  Save,
}

/**
 * One row of the settings list.
 *
 * The key is stable across every recomposition and the contentType is unique per section, so no
 * two sections ever share a reuse slot: these subtrees have nothing in common, and a form field
 * recomposed into the position of a button is exactly the kind of reuse that strands focus.
 */
private fun LazyListScope.settingsItem(section: SettingsItem, content: @Composable () -> Unit) =
  item(key = section.name, contentType = section.name) { content() }

/**
 * Frames an explicit focus move waits for a node it had to bring back.
 *
 * The same budget the post-removal recovery uses, and for the same reason: long enough for a
 * subcomposition to compose, lay out and accept focus on a slow frame, short enough that a move
 * which is never going to land does not sit on the remote for half a second first.
 */
private const val FocusRecoveryFrames = 10

/**
 * Moves focus to a target that the list may have disposed.
 *
 * When every section was composed eagerly an explicit focus move was just `requestFocus()`: the
 * node it aimed at existed whether or not it was on screen. Under a lazy list an off-screen
 * section is *disposed*, its requester is attached to nothing, and the same call throws - which is
 * how the cross-section moves this screen is built on (the TMDB field's up to the pair button, the
 * audio language field's up to Add, the recovery after a removal, Save's refusal of a draft addon)
 * would each end with a dead D-pad.
 *
 * So a move that finds its target gone scrolls the section that owns it back into the list and
 * asks again over the next few frames - the same retry [RequestInitialFocus] runs for a node that
 * has not been placed yet. A move whose target is present behaves exactly as it always did,
 * including when the target refuses.
 */
private class SettingsFocusJumper(
  private val scope: CoroutineScope,
  private val listState: LazyListState,
  /** Which row composes each target, for the scroll that brings it back. */
  private val itemOf: Map<InitialFocusTarget, SettingsItem>,
) {
  // One in flight at a time. Held down, the D-pad can ask for two different sections inside a
  // frame of each other, and the loser would scroll the winner's target straight back off.
  private var pending: Job? = null

  /**
   * A text field's preview handler must decide whether to consume the D-pad event immediately.
   * [InitialFocusTarget.focused] is updated by onFocusChanged after requestFocus returns, so reading
   * it in that same handler reports the old field as focused and hands the key back to EditText.
   *
   * Every field neighbour is an explicit target whose enabled state matches the source field. A
   * successfully dispatched request is therefore the correct synchronous handled result; focus
   * landing is still observed normally on the following focus event.
   */
  fun leaveTextField(target: InitialFocusTarget): Boolean =
    jump(target, consumePlacedRequest = true)

  /**
   * @return true when the key that asked for this move has been dealt with - either focus moved
   *   now, or a scroll that will move it is running. False means nothing happened and the key
   *   still belongs to whoever else wants it, which is the verdict a live-but-unfocusable target
   *   has always produced.
   */
  fun jump(target: InitialFocusTarget): Boolean = jump(target, consumePlacedRequest = false)

  private fun jump(
    target: InitialFocusTarget,
    consumePlacedRequest: Boolean,
  ): Boolean {
    // `placed` is cleared on dispose, so it reads as "there is a node here now", not "there was
    // one once". A live node answers within this frame exactly as it did before the page was
    // lazy - and a live node that refuses (disabled, not focusable yet) also answers as it did.
    if (target.placed) {
      val dispatched = runCatching {
        target.requester.requestFocus()
      }.isSuccess
      if (!dispatched) return false
      if (target.focused) return true
      if (!consumePlacedRequest) return false

      // requestFocus() returns before InitialFocusTarget's onFocusChanged callback runs. That is
      // why a text field must consume a successfully dispatched direction immediately, but it also
      // means a placed lazy-list item just outside the viewport can report success and never take
      // focus. Verify the handoff on the next frame; if it did not land, bring the target's section
      // back and use the same bounded recovery as a fully disposed item. This also makes a quick
      // double-Up after closing the TV keyboard as reliable as two slower remote presses.
      val section = itemOf[target] ?: return true
      pending?.cancel()
      pending = scope.launch {
        withFrameNanos { }
        if (target.focused) return@launch
        listState.scrollToItem(section.ordinal)
        repeat(FocusRecoveryFrames) {
          withFrameNanos { }
          if (target.focused) return@launch
          if (target.placed) runCatching { target.requester.requestFocus() }
        }
      }
      return true
    }
    // Not placed and not ours: the caller aimed at something this screen never composes, which is
    // the case the old runCatching existed for. Leave the key alone.
    val section = itemOf[target] ?: return false
    pending?.cancel()
    pending = scope.launch {
      listState.scrollToItem(section.ordinal)
      repeat(FocusRecoveryFrames) {
        withFrameNanos { }
        if (target.focused) return@launch
        if (target.placed) runCatching { target.requester.requestFocus() }
      }
      // Nothing left to aim at that would not be a guess. Focus is still on whatever held it, or
      // on the list itself if the scroll disposed that node, and the section asked for is now at
      // the top of the viewport - so the next press searches from something the viewer can see
      // rather than from nowhere. Never a throw, and never a jump into an unrelated section.
    }
    return true
  }
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
