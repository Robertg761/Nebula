package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.stremioshell.host.tv.data.addon.StreamAutoPick
import com.stremioshell.host.tv.data.addon.StreamQuality

@Composable
fun StreamsScreen(
  viewModel: TvAppViewModel,
  screen: Screen.Streams,
  onStreamClick: (AddonStream) -> Unit,
) {
  val streams by viewModel.streams.collectAsState()
  val notice by viewModel.streamsNotice.collectAsState()
  val addons by viewModel.addonManifestUrls.collectAsState()
  val remembered by viewModel.rememberedPicks.collectAsState()
  val addonCount = addons?.size ?: 1
  val firstStreamFocus = rememberInitialFocusTarget()
  val goBack = rememberBackAction()
  val listState = rememberLazyListState()

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

  // The row matching what was last picked for this series, which focus starts on instead
  // of the top of the list. Deliberately only preselected, never auto-played: the addon's
  // best row for *this* episode may well be better than a memory two episodes old, and a
  // list that played itself would take that choice away.
  val list = (state as? LoadState.Ready)?.value.orEmpty()
  val memory = if (screen.season != null) remembered[screen.imdbId] else null
  val preselected = remember(list, memory) {
    val match = memory?.let { StreamAutoPick.pick(list, bingeGroup = null, remembered = it) }
    match?.let { list.indexOf(it) }?.takeIf { it > 0 } ?: 0
  }

  // A LazyColumn only composes what is on screen, so a preselected row further down the
  // list has no node for the focus request to reach until it has been scrolled to.
  LaunchedEffect(preselected) {
    if (preselected > 0) listState.scrollToItem(preselected)
  }

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

    // Above the rows and outside LoadStateContent: it qualifies the list rather than
    // replacing it, and an addon that went down is not a reason to hide the ones that
    // answered. Only shown while a list is actually up; an all-addons failure is the
    // Failed state's message, not a footnote on an empty screen.
    val partialFailure = notice?.takeIf { loadIssued && state is LoadState.Ready }
    if (partialFailure != null) {
      Text(
        partialFailure,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 12.dp),
      )
    }

    LoadStateContent(
      state,
      loadingText = if (addonCount > 1) {
        "Asking $addonCount addons for streams..."
      } else {
        "Asking the addon for streams..."
      },
      onRetry = { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) },
    ) { list ->
      if (list.isEmpty()) {
        // An empty result used to render a plain message with nothing focusable, which left
        // the D-pad dead on this route.
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            if (addonCount > 1) {
              "No addon returned a playable stream for this title."
            } else {
              "The addon returned no playable streams for this title."
            },
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
          state = listState,
          verticalArrangement = Arrangement.spacedBy(10.dp),
          contentPadding = PaddingValues(bottom = 32.dp),
        ) {
          // Debrid addons hand back the same resolved URL under several quality labels, and the
          // addon client only drops blank URLs - so a url-only key throws "Key was already used"
          // and takes the screen down. The position prefix keeps keys unique there while staying
          // stable for recompositions of the same list.
          itemsIndexed(list, key = { index, s -> "$index:${s.url ?: s.label}" }) { index, stream ->
            val badges = remember(stream) { StreamQuality.parse(stream).badges }
            Card(
              onClick = {
                // Recorded before the launch, and only for a series: this is the choice the
                // next episode's autoplay resolves against.
                if (screen.season != null) viewModel.rememberStreamPick(screen.imdbId, stream)
                onStreamClick(stream)
              },
              modifier = Modifier.fillMaxWidth(0.85f)
                .initialFocusTarget(if (index == preselected) firstStreamFocus else null),
            ) {
              Column(modifier = Modifier.padding(14.dp)) {
                Text(stream.label, style = MaterialTheme.typography.titleMedium)
                // Set only when more than one addon is configured, so a single-addon
                // list keeps the rows it has always had.
                val source = stream.source
                if (badges.isNotEmpty() || source != null || index == preselected && preselected > 0) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source != null) {
                      Text(
                        source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                    badges.forEach { badge -> QualityBadge(badge) }
                    if (index == preselected && preselected > 0) {
                      Text(
                        "last used",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                  }
                }
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

/** Resolution / dynamic range / size chip, so a row's quality reads at a glance. */
@Composable
private fun QualityBadge(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    modifier = Modifier
      .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 2.dp),
  )
}
