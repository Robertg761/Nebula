package com.stremioshell.host.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.addon.AddonStream

@Composable
fun StreamsScreen(
  viewModel: TvAppViewModel,
  screen: Screen.Streams,
  onStreamClick: (AddonStream) -> Unit,
) {
  val streams by viewModel.streams.collectAsState()
  val firstStreamFocus = rememberInitialFocusTarget()
  val goBack = rememberBackAction()

  // The shared streams flow still holds the *previous* title's Ready list while this screen first
  // composes - loadStreams only resets it to Loading from the effect below. Rendering that list
  // (and auto-focusing it) under the new header lets a fast OK press play the wrong stream and
  // record its position against the new title's watch key, so nothing but Loading is shown until
  // this screen instance has issued its own load.
  var loadIssued by remember(screen) { mutableStateOf(false) }

  LaunchedEffect(screen) {
    viewModel.loadStreams(screen.imdbId, screen.season, screen.episode)
    loadIssued = true
  }

  val state: LoadState<List<AddonStream>> = if (loadIssued) streams else LoadState.Loading

  RequestInitialFocus(
    target = firstStreamFocus,
    key = state,
    label = "Streams first row",
    enabled = state is LoadState.Ready,
  )

  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
    val suffix = if (screen.season != null) "  S${screen.season}E${screen.episode}" else ""
    Text(
      text = "Streams - ${screen.title}$suffix",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.padding(bottom = 16.dp),
    )

    LoadStateContent(
      state,
      loadingText = "Asking the addon for streams...",
      onRetry = { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) },
    ) { list ->
      if (list.isEmpty()) {
        // An empty result used to render a plain message with nothing focusable, which left
        // the D-pad dead on this route.
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            "The addon returned no playable streams for this title.",
            style = MaterialTheme.typography.titleMedium,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
              onClick = { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) },
              modifier = Modifier.initialFocusTarget(firstStreamFocus),
            ) {
              Text("Retry")
            }
            Button(onClick = goBack) { Text("Back") }
          }
        }
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(10.dp),
          contentPadding = PaddingValues(bottom = 32.dp),
        ) {
          // Debrid addons hand back the same resolved URL under several quality labels, and the
          // addon client only drops blank URLs - so a url-only key throws "Key was already used"
          // and takes the screen down. The position prefix keeps keys unique there while staying
          // stable for recompositions of the same list.
          itemsIndexed(list, key = { index, s -> "$index:${s.url ?: s.label}" }) { index, stream ->
            Card(
              onClick = { onStreamClick(stream) },
              modifier = Modifier.fillMaxWidth(0.85f)
                .initialFocusTarget(if (index == 0) firstStreamFocus else null),
            ) {
              Column(modifier = Modifier.padding(14.dp)) {
                Text(stream.label, style = MaterialTheme.typography.titleMedium)
                if (stream.detail.isNotBlank()) {
                  Text(
                    stream.detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
