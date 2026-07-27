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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.pairing.encodeQrBitmap

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
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      when (val s = state) {
        is TvAppViewModel.PairingState.Ready -> ReadyContent(s.url)
        is TvAppViewModel.PairingState.Failed -> Text(
          s.message,
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
        )
        else -> PairingProgress()
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = goBack, modifier = Modifier.initialFocusTarget(cancelFocus)) {
          Text("Cancel")
        }
        if (state is TvAppViewModel.PairingState.Failed) {
          Button(onClick = { viewModel.startPairing() }) { Text("Retry") }
        }
      }
    }
  }
}

@Composable
private fun PairingProgress() {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Text("Starting pairing...", style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ReadyContent(url: String) {
  // Encode off the main thread; 520x520 pixel fill would otherwise hitch.
  val qr: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, url) {
    value = withContext(Dispatchers.Default) { encodeQrBitmap(url, 520).asImageBitmap() }
  }
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text("Set up with your phone", style = MaterialTheme.typography.headlineMedium)
    Text(
      "Scan this code with your phone's camera, then paste your TMDB key and Comet URL there.",
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 40.dp),
    )
    val bitmap = qr
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = "Pairing QR code",
        modifier = Modifier
          .size(260.dp)
          .background(Color.White)
          .padding(10.dp),
      )
    } else {
      Box(modifier = Modifier.size(260.dp))
    }
    // The URL carries the one-time pairing token, so it is long enough to need wrapping.
    Text(
      "or open  $url  in your phone browser",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 40.dp),
    )
    Text(
      "Your phone must be on the same Wi-Fi as this TV.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
