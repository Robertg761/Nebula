package com.stremioshell.host.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.tmdb.MediaType

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(viewModel: TvAppViewModel, onItemClick: (MediaType, Int) -> Unit) {
  val results by viewModel.searchResults.collectAsState()
  var query by rememberSaveable { mutableStateOf("") }
  val firstResultFocus = remember { FocusRequester() }

  // Debounce keystrokes so we do not hit TMDB on every character.
  LaunchedEffect(Unit) {
    snapshotFlow { query }
      .debounce(400)
      .collect { viewModel.search(it) }
  }

  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      singleLine = true,
      placeholder = { Text("Search movies and shows") },
      colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 20.dp)
        // A material3 text field traps the D-pad on TV, so move focus into the
        // results grid explicitly before the field consumes the key (same
        // workaround as SettingsScreen's verticalFieldNav).
        .verticalFieldNav(down = firstResultFocus, up = null),
    )

    LoadStateContent(results, loadingText = "Searching...") { items ->
      if (items.isEmpty() && query.isNotBlank()) {
        CenteredMessage("No results for \"$query\"")
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 140.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(20.dp),
          contentPadding = PaddingValues(bottom = 32.dp),
        ) {
          items(items.size, key = { "${items[it].type}:${items[it].tmdbId}" }) { index ->
            val item = items[index]
            MediaCard(
              item = item,
              onClick = { onItemClick(item.type, item.tmdbId) },
              // Landing spot for D-pad down out of the query field; up from the
              // first row falls back to the default focus search, which finds it.
              modifier = if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier,
            )
          }
        }
      }
    }
  }
}

/** Redirect D-pad up/down to explicit neighbours so text fields can't trap focus on TV. */
private fun Modifier.verticalFieldNav(down: FocusRequester?, up: FocusRequester?): Modifier {
  return onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
      // requestFocus throws when nothing is attached (e.g. no results yet); in that
      // case leave the key unconsumed instead of pretending we moved.
      Key.DirectionDown -> down != null && runCatching { down.requestFocus() }.isSuccess
      Key.DirectionUp -> up != null && runCatching { up.requestFocus() }.isSuccess
      else -> false
    }
  }
}
