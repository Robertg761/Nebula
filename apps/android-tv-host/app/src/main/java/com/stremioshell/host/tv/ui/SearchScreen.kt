package com.stremioshell.host.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchlistEntry
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.SearchFilter
import com.stremioshell.host.tv.data.tmdb.SearchResults
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.nebulaFocusBorder

/** How long typing has to pause before the query costs a TMDB request. */
private const val SEARCH_DEBOUNCE_MS = 400L

/**
 * Ink for anything sitting on an accent fill.
 *
 * Near-black rather than the palette's own text colours: violet at full strength is bright enough
 * that TextHigh on top of it fails contrast at three metres. Same value the primary button uses.
 */
private val OnAccent = Color(0xFF120A2E)

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(viewModel: TvAppViewModel, onItemClick: (MediaType, Int) -> Unit) {
  val results by viewModel.searchResults.collectAsState()
  val requested by viewModel.searchQuery.collectAsState()
  val apiKey by viewModel.tmdbApiKey.collectAsState()
  val voiceQuery by viewModel.voiceQuery.collectAsState()
  // Membership only, the same flow the Details toggle reads: saving from here and saving from
  // there have to be the same act, or a title could be in My List twice over.
  val watchlistKeys by viewModel.watchlistKeys.collectAsState()

  // The card a long press opened the options for. Unlike Home's rows, toggling here never removes
  // the card the viewer is standing on, so there is no focus to re-aim afterwards.
  var options by remember { mutableStateOf<MediaItem?>(null) }

  // Seeded so a voice launch shows its words immediately; without it the field starts blank and
  // the debounce below would fire search("") over the results the ViewModel already has.
  var query by rememberSaveable { mutableStateOf(viewModel.voiceQuery.value.orEmpty()) }
  LaunchedEffect(voiceQuery) {
    val spoken = voiceQuery ?: return@LaunchedEffect
    query = spoken
    viewModel.clearVoiceQuery()
  }
  // Saved by name; see [SearchFilter.of] for why the enum itself is not.
  var filterName by rememberSaveable { mutableStateOf(SearchFilter.All.name) }
  val filter = SearchFilter.of(filterName)

  val queryField = rememberInitialFocusTarget()
  val filterRowFocus = remember { FocusRequester() }
  val firstResultFocus = remember { FocusRequester() }
  // The grid as a whole, so stepping back down into it can return to the card the viewer was on
  // rather than rewinding to the first result every time they touch the chips.
  val resultsFocus = remember { FocusRequester() }

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

  // Edge padding is carried by the children rather than by this column, so the results grid can pad
  // its own contents instead: a lazy list clips to its bounds, and the focus ring sits outside the
  // card it belongs to.
  Column(modifier = Modifier.fillMaxSize().padding(vertical = 28.dp)) {
    ScreenHeader(
      title = "Search",
      modifier = Modifier.padding(start = NebulaDimens.ScreenEdge, bottom = 22.dp),
    )

    SearchField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = NebulaDimens.ScreenEdge)
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
      resultsFocus = resultsFocus,
      firstResultFocus = firstResultFocus,
      modifier = Modifier.padding(start = NebulaDimens.ScreenEdge, top = 20.dp, bottom = 20.dp),
    )

    when (ui) {
      SearchUi.Idle -> CenteredEmptyState(
        "Search movies and shows",
        "Start typing - results appear as you go.",
      )
      SearchUi.Searching -> CenteredLoading("Searching...")
      is SearchUi.Empty -> CenteredEmptyState(ui.title, ui.hint)
      // Retry re-runs the query directly: the debounce only fires on a *change* to the field, so
      // nothing would retry a failure on its own. Kept as FailureMessage rather than an empty
      // state because its Retry button is the only focusable a failed search has.
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
          color = NebulaPalette.TextMuted,
          modifier = Modifier.padding(start = NebulaDimens.ScreenEdge, bottom = 12.dp),
        )
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = NebulaDimens.PosterWidth),
          horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
          // Wider apart down the page than across it: a focused card grows about its own centre,
          // and a grid is the one place where that growth lands on another card's caption.
          verticalArrangement = Arrangement.spacedBy(28.dp),
          // The slack is in the padding, not the arrangement: a focused card's ring and glow spill
          // past its bounds and a lazy grid clips to its own edges.
          contentPadding = PaddingValues(
            start = NebulaDimens.ScreenEdge,
            end = NebulaDimens.ScreenEdge,
            top = 10.dp,
            bottom = 40.dp,
          ),
          modifier = Modifier.focusRequester(resultsFocus).restoreRowFocus(),
        ) {
          items(ui.items.size, key = { ui.items[it].key }) { index ->
            val item = ui.items[index]
            MediaCard(
              item = item,
              onClick = { onItemClick(item.type, item.tmdbId) },
              // Two same-named remakes are one of the things search is for, so the year and the
              // kind of title ride under every card.
              subtitle = SearchResults.caption(item),
              // Same held-OK affordance the managed Home rows have: a result the viewer is not
              // ready to watch is exactly the thing My List is for, and making them open Details
              // to save it costs two screens and a load.
              onLongClick = { options = item },
              // Landing spot for D-pad down out of the chips; up from the first row falls back to
              // the default focus search, which finds them.
              modifier = if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier,
            )
          }
        }
      }
    }
  }

  options?.let { item ->
    val inList = WatchlistEntry.keyOf(item.type, item.tmdbId) in watchlistKeys
    CardOptionsDialog(
      title = item.title,
      message = if (inList) "This title is in My List." else "Save this title for later.",
      focusKey = item.key,
      focusLabel = "Search result options",
      actions = listOf(
        CardAction(if (inList) "Remove from My List" else "Add to My List") {
          viewModel.toggleWatchlist(item)
          options = null
        },
      ),
      onDismiss = { options = null },
    )
  }
}

/**
 * The query field.
 *
 * A stock text field is the one control on this screen Material draws its own way, and next to the
 * cards it reads as part of a different app - so the fill, the corner and the border all come from
 * the palette instead. The focused border is Material's own 2dp indicator recoloured; nothing here
 * changes how the field behaves, which is deliberate given what its key handling is holding
 * together.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    shape = NebulaShapes.large,
    textStyle = MaterialTheme.typography.bodyLarge,
    leadingIcon = {
      Icon(
        Icons.Filled.Search,
        // Decorative: the placeholder beside it says what the field is for.
        contentDescription = null,
        tint = NebulaPalette.TextMuted,
        modifier = Modifier.size(22.dp),
      )
    },
    placeholder = {
      Text(
        "Search movies and shows",
        style = MaterialTheme.typography.bodyLarge,
        color = NebulaPalette.TextMuted,
      )
    },
    colors = OutlinedTextFieldDefaults.colors(
      focusedTextColor = NebulaPalette.TextHigh,
      unfocusedTextColor = NebulaPalette.TextHigh,
      // Lifts a step on focus, so the field reads as live even from across the room where a
      // border alone is a hairline.
      focusedContainerColor = NebulaPalette.SurfaceVariant,
      unfocusedContainerColor = NebulaPalette.Surface,
      focusedBorderColor = NebulaPalette.VioletBright,
      unfocusedBorderColor = NebulaPalette.Outline,
      cursorColor = NebulaPalette.VioletBright,
    ),
    modifier = modifier,
  )
}

/**
 * The type filter above the results.
 *
 * Chips rather than tabs: the results below are the same destination narrowed, not a different one,
 * and a filter applies to results already in hand - switching it never refetches and never touches
 * the query.
 */
/**
 * @param resultsFocus the grid itself. Down asks it to restore the card focus was last on, which
 *   is what makes changing a filter and coming back not lose the viewer's place.
 * @param firstResultFocus the first card, for the first trip down - there is nothing saved yet.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchFilterRow(
  selected: SearchFilter,
  onSelect: (SearchFilter) -> Unit,
  firstChipFocus: FocusRequester,
  resultsFocus: FocusRequester,
  firstResultFocus: FocusRequester,
  modifier: Modifier = Modifier,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
    modifier = modifier
      // Down out of the chips aims into the grid rather than letting the default focus search pick
      // its way into a lazy one. When there is no grid - the empty, failed and idle states - both
      // requesters are unattached and the key falls through to whatever else is below.
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
          false
        } else {
          // restoreFocusedChild reports whether it had anything to restore; requestFocus throws
          // when nothing is attached, so both need catching before the key is claimed.
          runCatching { resultsFocus.restoreFocusedChild() }.getOrDefault(false) ||
            runCatching { firstResultFocus.requestFocus() }.isSuccess
        }
      },
  ) {
    val chipShape = FilterChipDefaults.ContainerShape
    SearchFilter.values().forEachIndexed { index, option ->
      val isSelected = option == selected
      // One FilterChip per option whatever the selection, rather than swapping composable types on
      // the selected one: a chip that changed identity when picked would take the focus with it.
      FilterChip(
        selected = isSelected,
        onClick = { onSelect(option) },
        // Selection is the accent fill and focus is the ring, so the two never have to be told
        // apart by brightness - the remote can be parked on "Movies" while "Shows" is the filter
        // actually applied, and that pair has to be readable at a glance.
        colors = FilterChipDefaults.colors(
          containerColor = NebulaPalette.SurfaceVariant,
          contentColor = NebulaPalette.TextMuted,
          focusedContainerColor = NebulaPalette.SurfaceVariant,
          focusedContentColor = NebulaPalette.TextHigh,
          pressedContainerColor = NebulaPalette.SurfaceVariant,
          pressedContentColor = NebulaPalette.TextHigh,
          selectedContainerColor = NebulaPalette.Violet,
          selectedContentColor = OnAccent,
          focusedSelectedContainerColor = NebulaPalette.VioletBright,
          focusedSelectedContentColor = OnAccent,
          pressedSelectedContainerColor = NebulaPalette.VioletBright,
          pressedSelectedContentColor = OnAccent,
        ),
        border = FilterChipDefaults.border(
          focusedBorder = nebulaFocusBorder(chipShape),
          focusedSelectedBorder = nebulaFocusBorder(chipShape),
        ),
        glow = FilterChipDefaults.glow(
          focusedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = 10.dp),
          focusedSelectedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = 10.dp),
        ),
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
        Text(option.label, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

/**
 * The shared [CenteredMessage] carries no icon, and a bare line of text alone in the middle of the
 * screen reads as something having gone wrong rather than as a prompt.
 */
@Composable
private fun CenteredEmptyState(title: String, hint: String?) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    EmptyState(title = title, hint = hint, icon = Icons.Filled.Search)
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
