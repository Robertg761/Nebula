package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
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
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.tv.ui.theme.nebulaFocusBorder

/** How long typing has to pause before the query costs a TMDB request. */
private const val SEARCH_DEBOUNCE_MS = 400L

/**
 * How long the spoken-query focus request stays armed once results are up.
 *
 * Long enough to cover [RequestInitialFocus]'s own 30-frame retry on this hardware, short enough
 * that a query the viewer goes on to *type* can never be mistaken for a second utterance and yank
 * focus off the card they are standing on.
 */
private const val RESULT_FOCUS_SETTLE_MS = 1500L

/** One full row of the grid, which is what the searching state stands in for. */
private const val SEARCH_SKELETON_CARDS = 5

/**
 * Vertical gap between grid rows.
 *
 * Wider than the horizontal [NebulaDimens.CardGap] on purpose: a focused card grows about its own
 * centre, and a grid is the one place in the app where that growth lands on the caption of the card
 * below it. Anything that changes the focus scale has to move this with it.
 */
private val GRID_ROW_GAP = 28.dp

/** Slack above and below the grid for a focused card's ring and glow, which a lazy grid clips. */
private val GRID_TOP_SLACK = 24.dp
private val GRID_BOTTOM_SLACK = 40.dp

@OptIn(FlowPreview::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
  viewModel: TvAppViewModel,
  onItemClick: (MediaType, Int) -> Unit,
  onOpenSettings: () -> Unit = {},
  focusQueryRequest: Int = 0,
) {
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
  // A spoken query is a whole request - the viewer said what they wanted and expects to be looking
  // at it. Arrival by any other route (pressing Search in the rail) legitimately leaves focus in
  // the rail, which is why the grab below is armed only here.
  var resultFocusTick by remember { mutableIntStateOf(0) }
  var pendingResultFocusQuery by remember { mutableStateOf<String?>(null) }
  var voiceStatusFocusPending by remember { mutableStateOf(false) }
  LaunchedEffect(voiceQuery) {
    val spoken = voiceQuery ?: return@LaunchedEffect
    query = spoken
    viewModel.clearVoiceQuery()
    resultFocusTick++
    pendingResultFocusQuery = spoken.trim().takeIf { it.isNotEmpty() }
    voiceStatusFocusPending = true
  }
  // Saved by name; see [SearchFilter.of] for why the enum itself is not.
  var filterName by rememberSaveable { mutableStateOf(SearchFilter.All.name) }
  val filter = SearchFilter.of(filterName)

  val queryField = rememberInitialFocusTarget()
  val filterRowFocus = rememberInitialFocusTarget()
  // The first result card. An InitialFocusTarget rather than a bare FocusRequester because two
  // different arrivals want it: D-pad down out of the chips (which asks by requester) and a spoken
  // query (which has to wait for the node to be placed).
  val firstResult = rememberInitialFocusTarget()
  val statusFocus = rememberInitialFocusTarget()
  // The grid as a whole, so stepping back down into it can return to the card the viewer was on
  // rather than rewinding to the first result every time they touch the chips.
  val resultsFocus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current

  // A bare search-key launch is an explicit request to type, including when Search is already the
  // current destination. The monotonically increasing request from TvApp makes repeated presses
  // re-aim focus instead of being collapsed as the same empty query.
  RequestInitialFocus(
    target = queryField,
    key = "launch:$focusQueryRequest",
    label = "Search query field",
    enabled = focusQueryRequest > 0,
  )

  // Pressing Retry disposes the only focusable the failed state had, and what replaces it is a
  // spinner with none at all - which leaves the D-pad pointing at nothing until something asks for
  // focus. The field is the one thing on this screen that is always there to hand it to.
  var retryTick by remember { mutableIntStateOf(0) }
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
  val pendingFocusQuery = pendingResultFocusQuery
  val focusIntentMatchesField = pendingFocusQuery != null && query.trim() == pendingFocusQuery
  val focusIntentMatchesRequest =
    focusIntentMatchesField && requested.trim() == pendingFocusQuery
  val exactResultsReady =
    focusIntentMatchesRequest && ui is SearchUi.Results && !ui.refreshing
  val exactEmpty = focusIntentMatchesRequest && ui is SearchUi.Empty
  val voiceStatusReady = voiceStatusFocusPending &&
    focusIntentMatchesField &&
    (ui is SearchUi.Searching || exactEmpty)
  val statusKind = when (ui) {
    SearchUi.Searching -> "searching"
    is SearchUi.Empty -> "empty"
    else -> "other"
  }

  val cancelResultFocusIntent: () -> Unit = {
    pendingResultFocusQuery = null
    voiceStatusFocusPending = false
  }

  // Both the chips' Down key and the keyboard's Search action want the same thing: the grid, on the
  // card the viewer was last on if there is one. restoreFocusedChild reports whether it had
  // anything to restore and requestFocus throws when nothing is attached, so both need catching
  // before the key is claimed - with no grid at all this does nothing and consumes nothing.
  val focusResults: () -> Boolean = remember {
    {
      runCatching { resultsFocus.restoreFocusedChild() }.getOrDefault(false) ||
        runCatching {
          firstResult.requester.requestFocus()
          firstResult.focused
        }.getOrDefault(false)
    }
  }

  // A submitted focus intent belongs to one exact normalized query. Stale results remain visible
  // while a new request is in flight, but can never satisfy this request and steal focus.
  RequestInitialFocus(
    target = firstResult,
    key = "result:$resultFocusTick:$pendingFocusQuery",
    label = "First search result after query submission",
    enabled = exactResultsReady,
  )
  RequestInitialFocus(
    target = statusFocus,
    key = "status:$resultFocusTick:$pendingFocusQuery:$statusKind",
    label = "Search status after voice query",
    enabled = voiceStatusReady,
  )
  LaunchedEffect(ui, pendingFocusQuery, query, requested) {
    when {
      !focusIntentMatchesField -> cancelResultFocusIntent()
      exactResultsReady || exactEmpty -> {
        // Leave the target mounted for RequestInitialFocus's bounded retry window.
        delay(RESULT_FOCUS_SETTLE_MS)
        if (pendingResultFocusQuery == pendingFocusQuery) cancelResultFocusIntent()
      }
      focusIntentMatchesRequest && ui is SearchUi.Failed -> {
        // FailureMessage owns a real recovery action and requests it itself.
        cancelResultFocusIntent()
      }
    }
  }

  // Edge padding is carried by the children rather than by this column, so the results grid can pad
  // its own contents instead: a lazy list clips to its bounds, and the focus ring sits outside the
  // card it belongs to. No bottom padding - the grid's own bottom slack is the only one wanted, and
  // doubling them left 68dp of dead space under the last visible row.
  Column(modifier = Modifier.fillMaxSize().padding(top = NebulaDimens.ScreenEdgeVertical)) {
    // Title and field share a line. Stacked, the chrome above the first poster came to ~260dp of a
    // ~540dp viewport - exactly one row of results, with the second starting below the fold and
    // nothing on screen hinting it was there.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(end = NebulaDimens.ScreenEdge),
    ) {
      // No start padding here: ScreenHeader hangs its accent tick in the margin and pads itself, so
      // its *text* lands on the same content line as the posters below.
      ScreenHeader(title = "Search")
      SearchField(
        value = query,
        onValueChange = {
          cancelResultFocusIntent()
          query = it
        },
        onSearch = {
          viewModel.search(query)
          resultFocusTick++
          pendingResultFocusQuery = query.trim().takeIf { it.isNotEmpty() }
          voiceStatusFocusPending = false
          keyboard?.hide()
        },
        modifier = Modifier
          .weight(1f)
          .padding(start = NebulaSpace.xl)
          .initialFocusTarget(queryField)
          // A material3 text field traps the D-pad on TV, so move focus down explicitly before the
          // field consumes the key (same workaround as SettingsScreen's fieldNav). The chips below
          // are always present, so unlike the results grid this target always exists.
          .verticalFieldNav(down = filterRowFocus, up = null),
      )
    }

    // The filters, the clear and the count share one line, so none of them costs the grid a band of
    // its own height.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = NebulaDimens.ScreenEdge)
        .padding(top = NebulaSpace.lg, bottom = NebulaSpace.md),
    ) {
      SearchFilterRow(
        selected = filter,
        onSelect = {
          cancelResultFocusIntent()
          filterName = it.name
        },
        firstChipFocus = filterRowFocus,
        focusResults = focusResults,
      )
      // Always mounted, never conditionally composed: a control that removes itself on click is
      // exactly how focus gets stranded. It hands focus back to the field on the way out, which is
      // also what stops the D-pad dying on the button as it goes disabled.
      NebulaButton(
        text = "Clear",
        onClick = {
          cancelResultFocusIntent()
          query = ""
          runCatching { queryField.requester.requestFocus() }
        },
        style = NebulaButtonStyle.Ghost,
        enabled = query.isNotEmpty(),
        modifier = Modifier.padding(start = NebulaDimens.ControlGap),
      )
      Spacer(Modifier.weight(1f))
      // The count keeps its number through a keystroke. It used to be replaced by the word
      // "Searching", so the one fact the line exists to hold vanished on every letter typed;
      // staleness is said in colour instead, which costs no layout.
      if (ui is SearchUi.Results) {
        Text(
          text = SearchResults.countLabel(ui.items.size),
          style = MaterialTheme.typography.labelMedium,
          color = if (ui.refreshing) NebulaPalette.TextFaint else NebulaPalette.TextMuted,
        )
      }
    }

    when (ui) {
      // No icon and no second magnifier: the field above carries one in its leading slot and its
      // placeholder already says "Search movies and shows", which this line used to repeat word for
      // word 200dp lower down. The hint is also the only place the app admits the mic key works
      // here - material-icons-core has no microphone glyph, and one vector is not worth pulling in
      // material-icons-extended for.
      SearchUi.Idle -> CenteredEmptyState(
        title = "What do you want to watch?",
        hint = "Type a title, or press the mic key on your remote.",
      )
      // Not a centred spinner: the first search of a session used to hard-cut from a centred prompt
      // to a centred ring to a top-anchored grid, three layouts in the same region inside 400ms.
      SearchUi.Searching -> SearchStatusAnchor(
        title = "Searching…",
        target = statusFocus,
        background = { SearchSkeleton() },
      )
      is SearchUi.Empty -> SearchStatusAnchor(
        title = ui.title,
        hint = ui.hint,
        icon = Icons.Filled.Search,
        target = statusFocus,
      )
      // Retry re-runs the query directly: the debounce only fires on a *change* to the field, so
      // nothing would retry a failure on its own. Kept as FailureMessage rather than an empty
      // state because its Retry button is the only focusable a failed search has.
      is SearchUi.Failed -> FailureMessage(
        ui.message,
        onRetry = if (apiKey.isNullOrBlank()) {
          null
        } else {
          {
            cancelResultFocusIntent()
            retryTick++
            viewModel.search(query)
          }
        },
        actionLabel = "Open Settings".takeIf { apiKey.isNullOrBlank() },
        onAction = onOpenSettings.takeIf { apiKey.isNullOrBlank() },
      )
      is SearchUi.Results -> {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = NebulaDimens.PosterWidth),
          horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
          verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
          // The slack is in the padding, not the arrangement: a focused card's ring and glow spill
          // past its bounds and a lazy grid clips to its own edges.
          contentPadding = PaddingValues(
            start = NebulaDimens.ScreenEdge,
            end = NebulaDimens.ScreenEdge,
            top = GRID_TOP_SLACK,
            bottom = GRID_BOTTOM_SLACK,
          ),
          modifier = Modifier.focusRequester(resultsFocus).restoreRowFocus(),
        ) {
          items(ui.items.size, key = { ui.items[it].key }) { index ->
            val item = ui.items[index]
            // Adaptive cells run a few dp wider than the 144dp card inside them, and a
            // start-aligned card banks all of that slack at the right-hand margin: five across, the
            // gaps read as ~20dp against the 16dp every rail on Home uses and the last column
            // stopped short of the screen edge. Centring spreads it instead. (Not GridCells.Fixed:
            // the column count then stops adapting if the layout width ever changes.)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
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
                // Landing spot for D-pad down out of the chips and for a spoken query; up from the
                // first row falls back to the default focus search, which finds the chips.
                modifier = if (index == 0) Modifier.initialFocusTarget(firstResult) else Modifier,
              )
            }
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
        CardAction(
          label = if (inList) "Remove from My List" else "Add to My List",
          destructive = inList,
        ) {
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
 * the palette instead. It also carries the app's own focus language: a violet bloom, the same
 * platform shadow renderer tv-material3's Glow uses, because a 2dp recoloured border was the one
 * focus state on this screen that did not visibly light up at three metres. Nothing here changes
 * how the field behaves, which is deliberate given what its key handling is holding together.
 *
 * The corner is [NebulaShapes] medium, matching Settings' field: two text fields in one app used to
 * carry two different radii.
 */
@Composable
private fun SearchField(
  value: String,
  onValueChange: (String) -> Unit,
  onSearch: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var focused by remember { mutableStateOf(false) }
  // Nothing in this app provides a material3 theme, so without this the field inherits foundation's
  // default selection blue - the one colour on the screen that is not in the palette.
  val selectionColors = remember {
    TextSelectionColors(
      handleColor = NebulaPalette.VioletBright,
      backgroundColor = NebulaPalette.Violet.copy(alpha = 0.4f),
    )
  }
  CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      shape = NebulaShapes.medium,
      textStyle = MaterialTheme.typography.bodyLarge,
      leadingIcon = {
        Icon(
          Icons.Filled.Search,
          // Decorative: the placeholder beside it says what the field is for.
          contentDescription = null,
          tint = NebulaPalette.TextMuted,
          modifier = Modifier.size(NebulaIcon.sm),
        )
      },
      placeholder = {
        Text(
          "Search movies and shows",
          style = MaterialTheme.typography.bodyLarge,
          color = NebulaPalette.TextMuted,
        )
      },
      // A magnifier on the IME's action key rather than a generic Done, and autocorrect off:
      // film titles are exactly what a TV keyboard's autocorrect ruins.
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Words,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Search,
      ),
      // "Done, show me" is the one gesture every viewer makes after typing, and it used to land
      // them back on the field two Downs away from the results they had just asked for. Moving
      // focus out of the field is also what dismisses the keyboard.
      keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
      modifier = modifier
        .semantics { contentDescription = "Search movies and shows" }
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        // A layer block rather than Modifier.shadow: the block re-runs in the draw phase when
        // `focused` changes, so lighting the field up costs a redraw and never a relayout.
        .graphicsLayer {
          shadowElevation = if (focused) NebulaDimens.FocusGlow.toPx() else 0f
          shape = NebulaShapes.medium
          clip = false
          ambientShadowColor = NebulaPalette.Violet
          spotShadowColor = NebulaPalette.Violet
        },
    )
  }
}

/**
 * The type filter above the results, plus the row's Down key.
 *
 * Chips rather than tabs: the results below are the same destination narrowed, not a different one,
 * and a filter applies to results already in hand - switching it never refetches and never touches
 * the query.
 *
 * @param focusResults aims Down into the grid rather than letting the default focus search pick its
 *   way into a lazy one. It asks the grid to restore the card focus was last on, which is what
 *   makes changing a filter and coming back not lose the viewer's place.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchFilterRow(
  selected: SearchFilter,
  onSelect: (SearchFilter) -> Unit,
  firstChipFocus: InitialFocusTarget,
  focusResults: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
    modifier = modifier
      // When there is no grid - the empty, failed and idle states - focusResults reports false and
      // the key falls through to whatever else is below.
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
          false
        } else {
          focusResults()
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
          selectedContentColor = NebulaPalette.OnAccent,
          focusedSelectedContainerColor = NebulaPalette.VioletBright,
          focusedSelectedContentColor = NebulaPalette.OnAccent,
          pressedSelectedContainerColor = NebulaPalette.VioletBright,
          pressedSelectedContentColor = NebulaPalette.OnAccent,
        ),
        border = FilterChipDefaults.border(
          focusedBorder = nebulaFocusBorder(chipShape),
          focusedSelectedBorder = nebulaFocusBorder(chipShape),
        ),
        glow = FilterChipDefaults.glow(
          focusedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = NebulaDimens.FocusGlowCompact),
          focusedSelectedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = NebulaDimens.FocusGlowCompact),
        ),
        // A tick as well as the container colour: the focused chip is also filled, so colour alone
        // does not say which one is actually applied. The slot is reserved on every chip and only
        // the ink changes - composing it on the selected one alone took ~26dp off one chip and
        // added it to another, so the row slid sideways under the viewer's thumb at the exact
        // moment they pressed OK. Three chips then share one baseline and read as a segmented
        // control rather than as three sizes of chip.
        leadingIcon = {
          Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = if (isSelected) NebulaPalette.OnAccent else Color.Transparent,
            modifier = Modifier.size(FilterChipDefaults.IconSize),
          )
        },
        modifier = if (index == 0) Modifier.initialFocusTarget(firstChipFocus) else Modifier,
      ) {
        Text(option.label, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

/**
 * A visible, focusable status for voice/search submissions that produce no card to receive focus.
 * It intentionally has no click action: loading and an empty result are information, not buttons.
 */
@Composable
private fun SearchStatusAnchor(
  title: String,
  target: InitialFocusTarget,
  hint: String? = null,
  icon: ImageVector? = null,
  background: @Composable () -> Unit = {},
) {
  var focused by remember { mutableStateOf(false) }
  val description = listOfNotNull(title, hint).joinToString(". ")
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    background()
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
      modifier = Modifier
        .widthIn(max = 640.dp)
        .initialFocusTarget(target)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .background(
          if (focused) NebulaPalette.SurfaceRaised else NebulaPalette.Surface,
          NebulaShapes.large,
        )
        .border(
          if (focused) 3.dp else 1.dp,
          if (focused) NebulaPalette.VioletBright else NebulaPalette.Outline,
          NebulaShapes.large,
        )
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = NebulaSpace.xl, vertical = NebulaSpace.lg),
    ) {
      if (icon != null) {
        Icon(
          icon,
          contentDescription = null,
          tint = NebulaPalette.TextMuted,
          modifier = Modifier.size(NebulaIcon.lg),
        )
      }
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = NebulaPalette.TextHigh,
        textAlign = TextAlign.Center,
      )
      if (hint != null) {
        Text(
          hint,
          style = MaterialTheme.typography.bodyMedium,
          color = NebulaPalette.TextMuted,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/**
 * The grid's geometry while a search with nothing to keep on screen is in flight.
 *
 * Static - no shimmer, no animation, nothing invalidating a frame, which is the same discipline
 * [RailSkeleton] follows and the reason this hardware can afford it at all. It occupies the slots
 * the results will land in, so the region does not jump twice on the first search of a session.
 */
@Composable
private fun SearchSkeleton() {
  Row(
    horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = NebulaDimens.ScreenEdge)
      .padding(top = GRID_TOP_SLACK)
      // Nothing here is content; a screen reader announcing five placeholders says nothing.
      .clearAndSetSemantics {},
  ) {
    repeat(SEARCH_SKELETON_CARDS) {
      Box(
        modifier = Modifier
          .width(NebulaDimens.PosterWidth)
          .height(NebulaDimens.PosterHeight)
          .background(NebulaPalette.Surface, NebulaDimens.PosterShape)
          .border(1.dp, NebulaPalette.Outline, NebulaDimens.PosterShape),
      )
    }
  }
}

/** Redirect D-pad up/down to explicit neighbours so text fields can't trap focus on TV. */
private fun Modifier.verticalFieldNav(
  down: InitialFocusTarget?,
  up: InitialFocusTarget?,
): Modifier {
  return onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
      // requestFocus throws when nothing is attached; in that case leave the key unconsumed
      // instead of pretending we moved.
      Key.DirectionDown -> down != null && runCatching {
        down.requester.requestFocus()
        down.focused
      }.getOrDefault(false)
      Key.DirectionUp -> up != null && runCatching {
        up.requester.requestFocus()
        up.focused
      }.getOrDefault(false)
      else -> false
    }
  }
}
