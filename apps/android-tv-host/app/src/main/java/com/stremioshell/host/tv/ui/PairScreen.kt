package com.stremioshell.host.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
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

@Composable
fun PairScreen(viewModel: TvAppViewModel, onPaired: () -> Unit) {
  val state by viewModel.pairing.collectAsState()

  LaunchedEffect(Unit) { viewModel.startPairing() }
  DisposableEffect(Unit) { onDispose { viewModel.stopPairing() } }

  val goBack = rememberBackAction()
  val failed = state is TvAppViewModel.PairingState.Failed
  val received = state is TvAppViewModel.PairingState.Received

  // The pairing screen had no focusable node at all, so the D-pad was dead until the phone
  // posted its config. There is always exactly one button that is the right thing to press, and
  // the target moves to it: on a failure that is Retry, not the Cancel beside it.
  val primaryFocus = rememberInitialFocusTarget()
  RequestInitialFocus(target = primaryFocus, key = failed, label = "Pair primary action")

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
          title = "Set up with your phone",
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
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NebulaSpace.md),
          ) {
            when (val s = state) {
              is TvAppViewModel.PairingState.Ready -> ReadyInstructions(s.url)
              is TvAppViewModel.PairingState.Failed -> PairingFailure(s.message)
              is TvAppViewModel.PairingState.Received -> PairedConfirmation(s)
              else -> PairingProgress()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
              // Leads with the action, like every other surface in the app. The remote used to
              // start on "give up" with the obvious move sitting to its right.
              if (failed) {
                NebulaButton(
                  text = "Retry",
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
                text = if (received) "Continue" else "Cancel",
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

@Composable
private fun PairingProgress() {
  Text(
    "Opening a pairing link on this TV...",
    style = MaterialTheme.typography.bodyLarge,
    color = NebulaPalette.TextMuted,
  )
}

@Composable
private fun PairedConfirmation(receipt: TvAppViewModel.PairingState.Received) {
  val summary = when {
    receipt.tmdbKeyChanged && receipt.addonUrlsChanged ->
      "Your TMDB key and ${addonCountLabel(receipt.addonCount)} were saved."
    receipt.tmdbKeyChanged ->
      "Your TMDB key was saved. Stream addons were unchanged."
    receipt.addonUrlsChanged ->
      "${addonCountLabel(receipt.addonCount, capitalized = true)} " +
        "${if (receipt.addonCount == 1) "was" else "were"} saved. Your TMDB key was unchanged."
    else ->
      "Those settings already matched what was saved on this TV."
  }
  val readiness = when {
    !receipt.hasTmdbKey && receipt.addonCount == 0 ->
      "A TMDB key and stream addon are still needed before setup is complete."
    !receipt.hasTmdbKey ->
      "A TMDB key is still needed to load catalogs and search."
    receipt.addonCount == 0 ->
      "A stream addon is still needed before Play can find releases."
    else ->
      "Setup is complete. Continue when you're ready."
  }
  Column(
    verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    Text(
      "Settings received",
      style = MaterialTheme.typography.titleMedium,
      color = NebulaPalette.TextHigh,
    )
    Text(
      "$summary $readiness",
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextMuted,
    )
  }
}

private fun addonCountLabel(count: Int, capitalized: Boolean = false): String {
  val label = if (count == 1) "1 stream addon" else "$count stream addons"
  return if (capitalized) label.replaceFirstChar { it.uppercase() } else label
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
      "Pairing could not start",
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
      "Scan this code with your phone's camera, then paste your TMDB key and addon URLs there.",
      style = MaterialTheme.typography.bodyLarge,
      color = NebulaPalette.TextMuted,
    )
    // Read out loud across a room, not just scanned - the token-bearing path is long, so it is
    // set apart as its own chip rather than run into the sentence around it. TextMuted, not
    // TextFaint: this is the alternative route in, not decoration.
    Text(
      "or open this in your phone's browser",
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
        "Your phone must be on the same trusted private Wi-Fi as this TV. The local setup page " +
          "is not encrypted.",
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
      state is TvAppViewModel.PairingState.Failed -> Icon(
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
      contentDescription = "Pairing QR code",
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
