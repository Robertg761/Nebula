package com.stremioshell.host.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.stremioshell.host.R
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.pairing.PairingConnectionCheck
import com.stremioshell.host.tv.pairing.PairingVisibilityGate
import com.stremioshell.host.tv.pairing.encodeQrBitmap
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace

/** The QR image itself. Encoded at 520px and drawn nearest-neighbour, so the modules stay hard. */
private val QrCodeSize = 260.dp

/**
 * The panel beside the instructions, at one size for every state.
 *
 * [QrCodeSize] plus a 24dp white border, which takes the quiet zone past the 4-module minimum the
 * spec asks for (the encoder itself only leaves 1). The size is fixed and the panel is always
 * composed because the card used to be half empty in Idle and Failed and then jump when a 300dp
 * white block appeared - on the first screen a new owner ever sees.
 */
private val PairPanelSize = QrCodeSize + 48.dp

/**
 * The title is a 38dp line; 48dp leaves room for rounding and font metrics without spending the
 * last 10dp of a 540dp viewport's safe area.
 */
private val PairHeaderHeightBudget = 48.dp
private val PairMinimumInstructionPaneHeight = 160.dp

/**
 * Caps the variable-height instructions beside the fixed QR panel.
 *
 * At the app's minimum 540dp TV viewport this is exactly 308dp: 48dp safe area and 32dp card
 * padding at each edge, plus the header and its 24dp gap, leave one full QR-panel height. A result
 * containing TMDB plus all eight addons scrolls inside that pane instead of growing the card past
 * the bottom edge.
 */
internal fun pairInstructionPaneMaxHeight(viewportHeight: Dp): Dp {
  val available = viewportHeight -
    NebulaDimens.ScreenEdge * 2 -
    NebulaDimens.DialogPadding * 2 -
    PairHeaderHeightBudget -
    NebulaSpace.lg
  return available.coerceIn(PairMinimumInstructionPaneHeight, PairPanelSize)
}

internal enum class PairScrollDirection { Up, Down }

/**
 * Pages a non-focusable result list while its action keeps TV focus.
 *
 * The policy deliberately sees only geometry, never connection labels, URLs or credentials.
 */
internal fun pairDpadScrollTarget(
  current: Int,
  max: Int,
  viewport: Int,
  direction: PairScrollDirection,
): Int? {
  if (max <= 0 || viewport <= 0) return null
  val page = maxOf(1, viewport * 3 / 4)
  val target = when (direction) {
    PairScrollDirection.Up -> current - page
    PairScrollDirection.Down -> current + page
  }.coerceIn(0, max)
  return target.takeIf { it != current }
}

@Composable
fun PairScreen(viewModel: TvAppViewModel, onPaired: () -> Unit) {
  val state by viewModel.pairing.collectAsStateWithLifecycle()

  // Composition survives HOME, app switching and screen-off. Resume scope does not: the LAN
  // listener is live only while the QR screen is actually visible, and every resume gets a fresh
  // one-shot token instead of reviving the URL that was hidden from the TV.
  //
  // Except once the phone has answered. A confirmation is the receipt for a config that is already
  // committed to DataStore, and a screensaver two minutes into reading it is not a reason to
  // discard it: the pause still takes the server and its spent token down, but the resume leaves
  // the receipt alone rather than replacing it with a QR that implies nothing was saved. The state
  // is read live off the flow rather than captured, because this effect block outlives the
  // composition that set it up.
  val visibilityGate = remember(viewModel) {
    PairingVisibilityGate(
      startPairing = viewModel::startPairing,
      stopPairing = viewModel::pausePairing,
      confirmationShowing = { viewModel.pairing.value is TvAppViewModel.PairingState.Received },
    )
  }
  LifecycleResumeEffect(visibilityGate) {
    visibilityGate.onVisible()
    onPauseOrDispose { visibilityGate.onHidden() }
  }
  // Leaving is the other half of that rule: a receipt kept across a pause must not still be
  // sitting there the next time this screen is opened, where it would show a stale confirmation
  // and - because the gate reads it - refuse to start a new pairing at all. Disposal is the
  // difference between "the viewer is elsewhere for a moment" and "the viewer left".
  DisposableEffect(viewModel) {
    onDispose { viewModel.stopPairing() }
  }

  val goBack = rememberBackAction()
  val failed = state is TvAppViewModel.PairingState.Failed
  val received = state is TvAppViewModel.PairingState.Received

  // The pairing screen had no focusable node at all, so the D-pad was dead until the phone
  // posted its config. There is always exactly one button that is the right thing to press, and
  // the target moves to it: on a failure that is Retry, not the Cancel beside it.
  val primaryFocus = rememberInitialFocusTarget()
  RequestInitialFocus(target = primaryFocus, key = failed, label = "Pair primary action")
  val instructionScroll = rememberScrollState()
  val instructionScrollScope = rememberCoroutineScope()

  // Validation replaces short instructions with as many as nine connection rows. Start at the end
  // nearest the focused action, then let UP page back through every earlier result. The action is
  // pinned below this scroll viewport, so it stays visible throughout.
  LaunchedEffect(state, primaryFocus.focused, instructionScroll.maxValue) {
    if (primaryFocus.focused) instructionScroll.animateScrollTo(instructionScroll.maxValue)
  }

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize(),
  ) {
    val instructionPaneMaxHeight = pairInstructionPaneMaxHeight(maxHeight)
    val instructionPaneHeightPx = with(LocalDensity.current) {
      instructionPaneMaxHeight.roundToPx()
    }
    Box(
      // Every other screen honours the overscan margin through this token. The card used to be a
      // fixed 880dp inside a 898dp content area - 9dp of margin a set with overscan enabled eats
      // whole, taking the card's border and corners with it.
      modifier = Modifier.fillMaxSize().padding(NebulaDimens.ScreenEdge),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        shape = NebulaShapes.extraLarge,
        colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Surface),
        border = Border(
          border = BorderStroke(1.dp, NebulaPalette.Outline),
          shape = NebulaShapes.extraLarge,
        ),
        modifier = Modifier.widthIn(max = 780.dp),
      ) {
        Column(modifier = Modifier.padding(NebulaDimens.DialogPadding)) {
          // ScreenHeader hangs its tick in the margin by padding itself to the screen's content
          // line. Inside a card that indent is the card's padding instead, so the header is pulled
          // back by its own inset: the words land flush with the copy below and the tick hangs.
          ScreenHeader(
            title = stringResource(R.string.pair_title),
            modifier = Modifier.offset(x = -NebulaDimens.ScreenEdge),
          )
          Row(
            modifier = Modifier.padding(top = NebulaSpace.lg),
            horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xl),
            // The panel is the taller child in every state, so the copy beside it is centred
            // against it rather than left hanging off the top of a half-empty card.
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(
              modifier = Modifier
                .weight(1f)
                .height(instructionPaneMaxHeight)
                // Validation rows are informational, so making each one focusable would turn nine
                // status lines into nine fake actions. Instead, UP/DOWN pages their viewport while
                // Retry/Leave/Continue keeps real focus and stays visible below it.
                .onPreviewKeyEvent { event ->
                  if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                  val direction = when (event.key) {
                    Key.DirectionUp -> PairScrollDirection.Up
                    Key.DirectionDown -> PairScrollDirection.Down
                    else -> return@onPreviewKeyEvent false
                  }
                  val target = pairDpadScrollTarget(
                    current = instructionScroll.value,
                    max = instructionScroll.maxValue,
                    viewport = instructionPaneHeightPx,
                    direction = direction,
                  ) ?: return@onPreviewKeyEvent false
                  instructionScrollScope.launch { instructionScroll.scrollTo(target) }
                  true
                },
              verticalArrangement = Arrangement.spacedBy(NebulaSpace.md),
            ) {
              Column(
                modifier = Modifier
                  .weight(1f)
                  .verticalScroll(instructionScroll),
              ) {
                when (val s = state) {
                  is TvAppViewModel.PairingState.Ready -> ReadyInstructions(s.url)
                  is TvAppViewModel.PairingState.Failed -> PairingFailure(s.message)
                  is TvAppViewModel.PairingState.Validating ->
                    PairingValidationProgress(s.addonCount)
                  is TvAppViewModel.PairingState.ValidationFailed ->
                    PairingValidationFailure(s.message, s.checks)
                  is TvAppViewModel.PairingState.Received -> PairedConfirmation(s)
                  else -> PairingProgress()
                }
              }
              Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
                // Leads with the action, like every other surface in the app. The remote used to
                // start on "give up" with the obvious move sitting to its right.
                if (failed) {
                  NebulaButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { viewModel.startPairing() },
                    style = NebulaButtonStyle.Primary,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.initialFocusTarget(primaryFocus.takeIf { failed }),
                  )
                }
                // One call site rather than two, so the node - and the focus sitting on it -
                // survives the handoff instead of being destroyed at the moment the phone
                // answers. In Received this is the only thing on screen a remote can land on,
                // which is what keeps the D-pad alive during the wait.
                NebulaButton(
                  text = if (received) {
                    stringResource(R.string.action_continue)
                  } else {
                    // An already-started atomic DataStore commit cannot be rolled back. "Leave"
                    // accurately describes closing this screen/server without promising otherwise.
                    stringResource(R.string.action_leave_pairing)
                  },
                  onClick = if (received) onPaired else goBack,
                  style = if (received) NebulaButtonStyle.Primary else NebulaButtonStyle.Ghost,
                  modifier = Modifier.initialFocusTarget(primaryFocus.takeIf { !failed }),
                )
              }
            }
            PairPanel(state)
          }
        }
      }
    }
  }
}

@Composable
private fun PairingProgress() {
  Text(
    stringResource(R.string.pair_opening),
    style = MaterialTheme.typography.bodyLarge,
    color = NebulaPalette.TextMuted,
  )
}

@Composable
private fun PairingValidationProgress(addonCount: Int) {
  Column(
    verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    Text(
      stringResource(R.string.pair_testing_title),
      style = MaterialTheme.typography.titleMedium,
      color = NebulaPalette.TextHigh,
    )
    Text(
      if (addonCount == 0) {
        stringResource(R.string.pair_validation_tmdb_only)
      } else {
        pluralStringResource(
          R.plurals.pair_validation_progress,
          addonCount,
          addonCount,
        )
      },
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextMuted,
    )
  }
}

@Composable
private fun PairingValidationFailure(
  message: String,
  checks: List<PairingConnectionCheck>,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
  ) {
    Text(
      stringResource(R.string.pair_attention_title),
      style = MaterialTheme.typography.titleMedium,
      color = NebulaPalette.TextHigh,
    )
    Text(
      message,
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextMuted,
    )
    ConnectionChecks(checks)
    Text(
      stringResource(R.string.pair_fix_hint),
      style = MaterialTheme.typography.bodySmall,
      color = NebulaPalette.TextMuted,
    )
  }
}

/**
 * What the phone's submission actually did, in the TV's own words.
 *
 * There is no "addons were cleared" wording here, and there cannot be: a blank box on the phone
 * form means "keep what you have" (see [com.stremioshell.host.tv.pairing.PairingSubmission]), so
 * a changed addon list always has at least one URL in it. Removing every addon stays a deliberate
 * act on the TV's own Settings screen, where the viewer can see what they are removing. Two
 * branches used to claim otherwise and were unreachable in every state the pairing form can
 * produce.
 */
@Composable
private fun PairedConfirmation(receipt: TvAppViewModel.PairingState.Received) {
  val summary = when {
    receipt.tmdbKeyChanged && receipt.addonUrlsChanged ->
      pluralStringResource(
        R.plurals.pair_key_and_addons_saved,
        receipt.addonCount,
        receipt.addonCount,
      )
    receipt.tmdbKeyChanged ->
      stringResource(R.string.pair_key_saved_addons_unchanged)
    receipt.addonUrlsChanged ->
      pluralStringResource(
        R.plurals.pair_addons_saved_key_unchanged,
        receipt.addonCount,
        receipt.addonCount,
      )
    else ->
      stringResource(R.string.pair_settings_unchanged)
  }
  val readiness = when {
    !receipt.hasTmdbKey && receipt.addonCount == 0 ->
      stringResource(R.string.pair_needs_key_and_addon)
    !receipt.hasTmdbKey ->
      stringResource(R.string.pair_needs_key)
    receipt.addonCount == 0 ->
      stringResource(R.string.pair_needs_addon)
    else ->
      stringResource(R.string.pair_complete)
  }
  Column(
    verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    Text(
      stringResource(R.string.pair_received_title),
      style = MaterialTheme.typography.titleMedium,
      color = NebulaPalette.TextHigh,
    )
    Text(
      stringResource(R.string.pair_confirmation_body, summary, readiness),
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextMuted,
    )
    ConnectionChecks(receipt.checks)
  }
}

@Composable
private fun ConnectionChecks(checks: List<PairingConnectionCheck>) {
  if (checks.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs)) {
    checks.forEach { check ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          if (check.connected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
          contentDescription = null,
          tint = if (check.connected) NebulaPalette.Success else NebulaPalette.Danger,
          modifier = Modifier.size(NebulaIcon.sm),
        )
        Text(
          if (check.connected) {
            stringResource(R.string.pair_status_connected, check.label)
          } else {
            stringResource(R.string.pair_status_failed, check.label)
          },
          style = MaterialTheme.typography.bodySmall,
          color = if (check.connected) NebulaPalette.Success else NebulaPalette.Danger,
        )
      }
    }
  }
}

/**
 * A failure with a heading, not a bare pink sentence.
 *
 * The glyph lives in the panel beside this rather than above it: the panel is a fixed 308dp square
 * that would otherwise be empty in this state, and one warning icon per screen is enough.
 */
@Composable
private fun PairingFailure(message: String) {
  Column(verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs)) {
    Text(
      stringResource(R.string.pair_start_failed_title),
      style = MaterialTheme.typography.titleMedium,
      color = NebulaPalette.TextHigh,
    )
    Text(
      message,
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextMuted,
    )
  }
}

@Composable
private fun ReadyInstructions(url: String) {
  Column(verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm)) {
    Text(
      stringResource(R.string.pair_scan_instructions),
      style = MaterialTheme.typography.bodyLarge,
      color = NebulaPalette.TextMuted,
    )
    // Read out loud across a room, not just scanned - the token-bearing path is long, so it is
    // set apart as its own chip rather than run into the sentence around it. TextMuted, not
    // TextFaint: this is the alternative route in, not decoration.
    Text(
      stringResource(R.string.pair_browser_alternative),
      style = MaterialTheme.typography.bodySmall,
      color = NebulaPalette.TextMuted,
    )
    Text(
      url,
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextHigh,
      modifier = Modifier
        .background(NebulaPalette.SurfaceVariant, NebulaShapes.small)
        .padding(horizontal = NebulaSpace.md, vertical = NebulaSpace.sm),
    )
    // The single most common reason pairing fails, and it used to be set as a footnote in the
    // palette's quietest tone. Promoted to body copy with an icon, so it reads as a precondition.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    ) {
      Icon(
        Icons.Filled.Info,
        // Decorative: the sentence beside it is the content.
        contentDescription = null,
        tint = NebulaPalette.TextMuted,
        modifier = Modifier.size(NebulaIcon.sm),
      )
      Text(
        stringResource(R.string.pair_network_warning),
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
      )
    }
  }
}

/**
 * The right-hand square, at a constant size in all four states.
 *
 * Only the fill and the child change, so the card's geometry never moves - the QR arriving no
 * longer relays the whole screen.
 */
@Composable
private fun PairPanel(state: TvAppViewModel.PairingState) {
  val ready = state as? TvAppViewModel.PairingState.Ready
  Box(
    modifier = Modifier
      .size(PairPanelSize)
      .background(
        // White only under a code that has to be scanned; everything else stays in the palette.
        if (ready != null) Color.White else NebulaPalette.SurfaceVariant,
        NebulaShapes.large,
      ),
    contentAlignment = Alignment.Center,
  ) {
    when {
      ready != null -> QrImage(ready.url)
      state is TvAppViewModel.PairingState.Failed ||
        state is TvAppViewModel.PairingState.ValidationFailed -> Icon(
        Icons.Filled.Warning,
        contentDescription = null,
        tint = NebulaPalette.Danger,
        modifier = Modifier.size(NebulaIcon.lg),
      )
      state is TvAppViewModel.PairingState.Received -> Icon(
        Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = NebulaPalette.Success,
        modifier = Modifier.size(NebulaIcon.lg),
      )
      // Matches CenteredLoading exactly - the app had two different spinners in it.
      else -> CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        trackColor = NebulaPalette.TrackInactive,
        strokeWidth = 3.dp,
        strokeCap = StrokeCap.Round,
        modifier = Modifier.size(40.dp),
      )
    }
  }
}

@Composable
private fun QrImage(url: String) {
  // Encode off the main thread; 520x520 pixel fill would otherwise hitch. Deliberately not
  // encoded at the panel's physical pixel size: on a 4K output that would be a 4MB IntArray plus
  // a 2MB bitmap on a box with ~600MB free.
  val qr: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, url) {
    value = withContext(Dispatchers.Default) { encodeQrBitmap(url, 520).asImageBitmap() }
  }
  val bitmap = qr
  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = stringResource(R.string.pair_qr_description),
      // Nearest-neighbour. The default bilinear filter turns every module edge into a grey ramp
      // when 520px is drawn at 260dp on a 4K panel, which is a code that looks soft and takes
      // longer to lock. Costs nothing.
      filterQuality = FilterQuality.None,
      modifier = Modifier.size(QrCodeSize),
    )
  } else {
    // Holds the code's footprint while it encodes, so the white plate does not appear empty and
    // then fill.
    Box(modifier = Modifier.size(QrCodeSize))
  }
}
