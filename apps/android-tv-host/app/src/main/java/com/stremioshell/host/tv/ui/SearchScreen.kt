package com.stremioshell.host.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.SearchFilter
import com.stremioshell.host.tv.data.tmdb.SearchResults

/** How long typing has to pause before the query costs a TMDB request. */
private const val SEARCH_DEBOUNCE_MS = 400L

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(viewModel: TvAppViewModel, onItemClick: (MediaType, Int) -> Unit) {
  val results by viewModel.searchResults.collectAsState()
  val requested by viewModel.searchQuery.collectAsState()
  val apiKey by viewModel.tmdbApiKey.collectAsState()

  var query by rememberSaveable { mutableStateOf("") }
  // Saved by name; see [SearchFilter.of] for why the enum itself is not.
  var filterName by rememberSaveable { mutableStateOf(SearchFilter.All.name) }
  val filter = SearchFilter.of(filterName)

  val queryField = rememberInitialFocusTarget()
  val filterRowFocus = remember { FocusRequester() }
  val firstResultFocus = remember { FocusRequester() }

  // Pressing Retry disposes the only focusable the failed state had, and what replaces it is a
  // spinner with none at all - which leaves the D-pad pointing at nothing until something asks for
  // focus. The field is the one thing on this screen that is always there to hand it to.
  var retryTick by remember { mutableStateOf(0) }
  RequestInitialFocus(
    target = queryField,
    key = "retry:$retryTick",
    label = "Search query field after retry",
    enabled = retryTick > 0,
  )

  // Debounced so a five-letter word costs one request, not five. Re-armed when the key lands: the
  // stored key resolves asynchronously, and on a cold start the first thing typed can beat it -
  // that query would otherwise sit unanswered until the viewer typed something else.
  LaunchedEffect(apiKey) {
    snapshotFlow { query }
      .debounce(SEARCH_DEBOUNCE_MS)
      .collect { viewModel.search(it) }
  }

  // Remembered so ranking and filtering run when one of their inputs changes, not on every
  // recomposition a focus move causes.
  val ui = remember(query, requested, results, filter) {
    SearchPresentation.resolve(typed = query, requested = requested, state = results, filter = filter)
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
        .initialFocusTarget(queryField)
        // A material3 text field traps the D-pad on TV, so move focus down explicitly before the
        // field consumes the key (same workaround as SettingsScreen's verticalFieldNav). The chips
        // below are always present, so unlike the results grid this target always exists.
        .verticalFieldNav(down = filterRowFocus, up = null),
    )

    SearchFilterRow(
      selected = filter,
      onSelect = { filterName = it.name },
      firstChipFocus = filterRowFocus,
      resultsFocus = firstResultFocus,
      modifier = Modifier.padding(top = 18.dp, bottom = 18.dp),
    )

    when (ui) {
      SearchUi.Idle -> CenteredMessage(
        "Search movies and shows",
        "Start typing - results appear as you go.",
      )
      SearchUi.Searching -> CenteredLoading("Searching...")
      is SearchUi.Empty -> CenteredMessage(ui.title, ui.hint)
      // Retry re-runs the query directly: the debounce only fires on a *change* to the field, so
      // nothing would retry a failure on its own.
      is SearchUi.Failed -> FailureMessage(
        ui.message,
        onRetry = {
          retryTick++
          viewModel.search(query)
        },
      )
      is SearchUi.Results -> {
        // The line is here whether or not a newer query is in flight: a header that appeared and
        // disappeared as the viewer typed would shift the whole grid under them.
        Text(
          text = if (ui.refreshing) "Searching..." else SearchResults.countLabel(ui.items.size),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 140.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(20.dp),
          contentPadding = PaddingValues(bottom = 32.dp),
        ) {
          items(ui.items.size, key = { ui.items[it].key }) { index ->
            val item = ui.items[index]
            MediaCard(
              item = item,
              onClick = { onItemClick(item.type, item.tmdbId) },
              // Two same-named remakes are one of the things search is for, so the year and the
              // kind of title ride under every card.
              subtitle = SearchResults.caption(item),
              // Landing spot for D-pad down out of the chips; up from the first row falls back to
              // the default focus search, which finds them.
              modifier = if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier,
            )
          }
        }
      }
    }
  }
}

/**
 * The type filter above the results.
 *
 * Chips rather than tabs: the results below are the same destination narrowed, not a different one,
 * and a filter applies to results already in hand - switching it never refetches and never touches
 * the query.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchFilterRow(
  selected: SearchFilter,
  onSelect: (SearchFilter) -> Unit,
  firstChipFocus: FocusRequester,
  resultsFocus: FocusRequester,
  modifier: Modifier = Modifier,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = modifier
      // Down out of the chips aims at the first card rather than letting the default focus search
      // pick its way into a lazy grid. When there is no grid - the empty, failed and idle states -
      // the requester is unattached and the key falls through to whatever else is below.
      .onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
          event.key == Key.DirectionDown &&
          runCatching { resultsFocus.requestFocus() }.isSuccess
      },
  ) {
    SearchFilter.values().forEachIndexed { index, option ->
      val isSelected = option == selected
      // One FilterChip per option whatever the selection, rather than swapping composable types on
      // the selected one: a chip that changed identity when picked would take the focus with it.
      FilterChip(
        selected = isSelected,
        onClick = { onSelect(option) },
        // A tick as well as the container colour: the focused chip is also filled, so colour alone
        // does not say which one is actually applied.
        leadingIcon = if (!isSelected) null else {
          {
            Icon(
              imageVector = Icons.Filled.Check,
              contentDescription = null,
              modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
          }
        },
        modifier = if (index == 0) Modifier.focusRequester(firstChipFocus) else Modifier,
      ) {
        Text(option.label)
      }
    }
  }
}

/** Redirect D-pad up/down to explicit neighbours so text fields can't trap focus on TV. */
private fun Modifier.verticalFieldNav(down: FocusRequester?, up: FocusRequester?): Modifier {
  return onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
      // requestFocus throws when nothing is attached; in that case leave the key unconsumed
      // instead of pretending we moved.
      Key.DirectionDown -> down != null && runCatching { down.requestFocus() }.isSuccess
      Key.DirectionUp -> up != null && runCatching { up.requestFocus() }.isSuccess
      else -> false
    }
  }
}
