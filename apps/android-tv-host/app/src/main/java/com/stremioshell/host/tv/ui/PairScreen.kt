package com.stremioshell.host.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.pairing.encodeQrBitmap
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import androidx.compose.foundation.BorderStroke

@Composable
fun PairScreen(viewModel: TvAppViewModel, onPaired: () -> Unit) {
  val state by viewModel.pairing.collectAsState()

  LaunchedEffect(Unit) { viewModel.startPairing() }
  DisposableEffect(Unit) { onDispose { viewModel.stopPairing() } }

  LaunchedEffect(state) {
    if (state is TvAppViewModel.PairingState.Received) onPaired()
  }

  val goBack = rememberBackAction()
  val cancelFocus = rememberInitialFocusTarget()

  // The pairing screen had no focusable node at all, so the D-pad was dead until the phone
  // posted its config. Cancel is always present and always takes the initial focus.
  RequestInitialFocus(target = cancelFocus, key = Unit, label = "Pair cancel")

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Surface(
      shape = NebulaShapes.extraLarge,
      colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Surface),
      border = Border(
        border = BorderStroke(1.dp, NebulaPalette.Outline),
        shape = NebulaShapes.extraLarge,
      ),
      modifier = Modifier.width(880.dp),
    ) {
      Column(modifier = Modifier.padding(40.dp)) {
        ScreenHeader("Set up with your phone")
        Row(
          modifier = Modifier.padding(top = 28.dp),
          horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(18.dp),
          ) {
            when (val s = state) {
              is TvAppViewModel.PairingState.Ready -> ReadyInstructions(s.url)
              is TvAppViewModel.PairingState.Failed -> Text(
                s.message,
                style = MaterialTheme.typography.titleMedium,
                color = NebulaPalette.Danger,
              )
              else -> PairingProgress()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              NebulaButton(
                text = "Cancel",
                onClick = goBack,
                style = NebulaButtonStyle.Ghost,
                modifier = Modifier.initialFocusTarget(cancelFocus),
              )
              if (state is TvAppViewModel.PairingState.Failed) {
                NebulaButton(
                  text = "Retry",
                  onClick = { viewModel.startPairing() },
                  style = NebulaButtonStyle.Primary,
                )
              }
            }
          }
          val ready = state as? TvAppViewModel.PairingState.Ready
          if (ready != null) {
            QrPanel(ready.url)
          }
        }
      }
    }
  }
}

@Composable
private fun PairingProgress() {
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Text(
      "Starting pairing...",
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextMuted,
    )
  }
}

@Composable
private fun ReadyInstructions(url: String) {
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      "Scan this code with your phone's camera, then paste your TMDB key and addon URLs there.",
      style = MaterialTheme.typography.bodyLarge,
      color = NebulaPalette.TextMuted,
    )
    // Read out loud across a room, not just scanned - the token-bearing path is long, so it is
    // set apart as its own chip rather than run into the sentence around it.
    Text(
      "or open this in your phone's browser",
      style = MaterialTheme.typography.bodySmall,
      color = NebulaPalette.TextFaint,
    )
    Text(
      url,
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextHigh,
      modifier = Modifier
        .background(NebulaPalette.SurfaceVariant, NebulaShapes.small)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Text(
      "Your phone must be on the same Wi-Fi as this TV.",
      style = MaterialTheme.typography.bodySmall,
      color = NebulaPalette.TextFaint,
    )
  }
}

@Composable
private fun QrPanel(url: String) {
  // Encode off the main thread; 520x520 pixel fill would otherwise hitch.
  val qr: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, url) {
    value = withContext(Dispatchers.Default) { encodeQrBitmap(url, 520).asImageBitmap() }
  }
  Box(
    modifier = Modifier
      .background(Color.White, RoundedCornerShape(20.dp))
      .padding(20.dp),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = qr
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = "Pairing QR code",
        modifier = Modifier.size(260.dp),
      )
    } else {
      Box(modifier = Modifier.size(260.dp))
    }
  }
}
