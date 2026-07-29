package com.stremioshell.host.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.HomeRail
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchlistEntry
import com.stremioshell.host.tv.data.tmdb.DetailsMetadata
import com.stremioshell.host.tv.data.tmdb.HeroPick
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrush
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaHeroScrim
import com.stremioshell.host.tv.ui.theme.NebulaHeroScrimRtl
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.tv.ui.theme.nebulaCardBorder
import com.stremioshell.host.tv.ui.theme.nebulaCardGlow
import com.stremioshell.host.tv.ui.theme.wordmark
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
  viewModel: TvAppViewModel,
  onItemClick: (MediaType, Int) -> Unit,
  onResumeClick: (WatchEntry) -> Unit,
  onPairWithPhone: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val rails by viewModel.homeRails.collectAsState()
  val railsNotice by viewModel.railsNotice.collectAsState()
  val railPaging by viewModel.railPaging.collectAsState()
  val continueWatching by viewModel.continueWatching.collectAsState()
  val watchlist by viewModel.watchlistEntries.collectAsState()
  val apiKey by viewModel.tmdbApiKey.collectAsState()
  val addonUrls by viewModel.addonManifestUrls.collectAsState()
  val heroLogoUrl by viewModel.heroLogoUrl.collectAsState()
  val firstContentFocus = rememberInitialFocusTarget()
  // Own targets for the two managed rows, so a card removed by the options dialog hands focus back
  // to the row it was removed from rather than to the billboard. See the row-edit request below.
  val continueRowFocus = rememberInitialFocusTarget()
  val watchlistRowFocus = rememberInitialFocusTarget()
  // Deliberately *not* [firstContentFocus]: the skeleton exists to keep the remote lit while TMDB
  // answers, and if the skeleton counted as "focus has landed on content" the request that aims at
  // the real first card would be suppressed and the D-pad would be dead the moment it disposed.
  val skeletonFocus = rememberInitialFocusTarget()
  val retryFocus = rememberInitialFocusTarget()

  // Once the user has driven the D-pad, initial focus is their business: rails re-emitting
  // (e.g. after a settings save) must not yank focus back to the first card.
  //
  // Both flags are saveable so the request is one-shot per *saved* Home state: the back stack
  // restores this screen's scroll position through SaveableStateProvider, and re-requesting
  // first-card focus would immediately scroll the restored LazyColumn back to the top.
  var userNavigated by rememberSaveable { mutableStateOf(false) }
  var landedFocus by rememberSaveable { mutableStateOf(false) }

  // The card a long press opened the options for, and a counter that re-aims focus after
  // one of those options removes it: the focused node disappearing leaves the D-pad dead
  // until something asks for focus again. One slot per row, because the two rows hold
  // different records and only ever have one dialog open between them.
  var options by remember { mutableStateOf<WatchEntry?>(null) }
  var watchlistOptions by remember { mutableStateOf<WatchlistEntry?>(null) }
  var rowEditTick by remember { mutableIntStateOf(0) }
  var editedRow by remember { mutableStateOf<String?>(null) }
  // Pressing Retry on the whole-page failure disposes the only focusable that state had; the tick
  // re-aims at the new one so a second failure does not paint with nothing lit.
  var retryTick by remember { mutableIntStateOf(0) }
  // The status row at the foot of the column is removed the instant a retry succeeds - out from
  // under the button that is holding focus. See the hand-back effect below.
  var statusFocused by remember { mutableStateOf(false) }

  // Hoisted so BACK can walk the page up to the billboard, and so the hand-back below has a group
  // to restore into.
  val listState = rememberLazyListState()
  val columnFocus = remember { FocusRequester() }
  val scope = rememberCoroutineScope()

  LaunchedEffect(apiKey) { viewModel.loadHomeRails() }

  val settingsLoaded = apiKey != null && addonUrls != null
  val missingTmdbKey = settingsLoaded && apiKey!!.isBlank()
  val missingAddon = settingsLoaded && addonUrls!!.isEmpty()
  val needsSetup = missingTmdbKey || missingAddon
  // Continue Watching resolves asynchronously and takes over the first-card slot, so the
  // target is only known once both it and the rails have settled; re-aim when it appears.
  // My List reads from the same store and behaves the same way.
  val hasContinueWatching = continueWatching.isNotEmpty()
  val hasWatchlist = watchlist.isNotEmpty()

  val railList = (rails as? LoadState.Ready)?.value.orEmpty()
  // Recomputed only when the rail list changes identity, which a page append does - but the pick
  // only ever reads the head of each rail, which an append never touches.
  val hero = remember(railList) { pickHero(railList) }
  // The billboard is the top of the screen, so it owns first focus when it exists: landing on a
  // rail below it would scroll it off before the user ever saw it.
  val hasHero = hero != null

  // Fetched once per featured title, and written through the same cache the Details screen
  // reads - so the one extra request also makes pressing OK on the billboard instant.
  LaunchedEffect(hero?.key) {
    val featured = hero ?: return@LaunchedEffect
    viewModel.loadHeroArt(featured.type, featured.tmdbId)
  }

  // Hoisted out of the row loop so each row's paging callback stays a memoizable lambda. Captured
  // straight from `viewModel` it would be a fresh instance on every recomposition, and one rail
  // appending a page would then recompose all nine rows.
  val paginateRail = remember(viewModel) { viewModel::paginateRail }

  // Only the rails need the one-shot treatment; the setup screen has no scroll position to
  // protect, so it stays free to re-aim at its button on every visit.
  LaunchedEffect(firstContentFocus.focused, needsSetup) {
    if (firstContentFocus.focused && !needsSetup) landedFocus = true
  }

  // Land focus on content (not the nav rail) once something focusable exists. Continue Watching
  // and My List count: they render from local storage, so offline they are the only focusable
  // content there is.
  RequestInitialFocus(
    target = firstContentFocus,
    key = if (needsSetup) "setup" else "content:$hasHero:$hasContinueWatching:$hasWatchlist",
    label = "Home first content card",
    enabled = !userNavigated &&
      (
        needsSetup ||
          (!landedFocus && (rails is LoadState.Ready || hasContinueWatching || hasWatchlist))
        ),
  )

  // Separate request, because the one above is deliberately dead once the user has driven
  // focus themselves - which they always have by the time they long-press a card.
  //
  // Aimed at the edited row rather than at the top of the page: re-requesting first-content focus
  // would scroll Home back to the billboard, which is exactly the bug the comment above the
  // one-shot flags describes. Only when the removal emptied the row is the billboard right.
  RequestInitialFocus(
    target = when (editedRow) {
      ROW_CONTINUE -> if (hasContinueWatching) continueRowFocus else null
      ROW_WATCHLIST -> if (hasWatchlist) watchlistRowFocus else null
      else -> null
    } ?: firstContentFocus,
    key = "row-edit:$rowEditTick",
    label = "Home focus after row edit",
    enabled = rowEditTick > 0 && options == null && watchlistOptions == null,
  )

  if (needsSetup) {
    Box(
      modifier = Modifier.fillMaxSize().padding(horizontal = NebulaDimens.ScreenEdge),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The wordmark, set the way the launcher banner sets it - a first run is the one moment
        // the app has to introduce itself, and it is also the only screen with room to do it.
        Text(
          text = "NEBULA",
          style = MaterialTheme.typography.displayLarge.wordmark(),
          color = NebulaPalette.TextHigh,
          // Tracking is added after the final A as well, so the text node is one whole space wider
          // than the glyphs and a centred wordmark sits visibly left of the rule under it. An
          // offset rather than padding, so nothing re-measures.
          modifier = Modifier.offset(x = WORDMARK_TRACK_CORRECTION),
        )
        Box(
          modifier = Modifier
            .padding(top = NebulaSpace.md)
            .size(width = 128.dp, height = 4.dp)
            .background(NebulaAccentBrush, RoundedCornerShape(2.dp))
            .clearAndSetSemantics {},
        )
        Text(
          // Says the same thing without "TMDB key" or "stream addon", which are the app's
          // vocabulary rather than the vocabulary of the person holding the remote.
          text = when {
            missingTmdbKey && missingAddon ->
              "Add a TMDB key and a stream addon to browse and play. Use your phone, or enter " +
                "them with the remote."
            missingTmdbKey ->
              "Add a TMDB key to load catalogs and search. Your stream addon is already saved."
            else ->
              "Add a stream addon before you start browsing, so Play can always lead somewhere."
          },
          style = MaterialTheme.typography.bodyLarge,
          color = NebulaPalette.TextMuted,
          textAlign = TextAlign.Center,
          // A measure, or this sets as one 700dp line across the middle of the panel - and a
          // wrapped second line would be left-ragged inside a centred column.
          modifier = Modifier
            .padding(top = NebulaSpace.lg, bottom = NebulaSpace.xl)
            .widthIn(max = 520.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
          NebulaButton(
            text = "Set up with phone",
            onClick = onPairWithPhone,
            style = NebulaButtonStyle.Primary,
            icon = Icons.Filled.Phone,
            modifier = Modifier.initialFocusTarget(firstContentFocus),
          )
          NebulaButton(
            text = "Enter manually",
            onClick = onOpenSettings,
            icon = Icons.Filled.Edit,
          )
        }
      }
    }
    return
  }

  // Continue Watching and My List come from local storage, so a TMDB outage must not take them
  // down with the rails. Only when there is nothing local to show does the rails' state own the
  // whole screen; otherwise it is reported inline, under the rows the user can still use.
  if (!hasContinueWatching && !hasWatchlist && rails !is LoadState.Ready) {
    val failure = rails as? LoadState.Failed
    if (failure != null) {
      // The Retry button is the only focusable thing this state has, and nothing asked it for
      // focus - so the screen painted with nothing lit anywhere and the first D-pad press was
      // spent picking an entry point (geometrically the nav rail, not Retry).
      RequestInitialFocus(
        target = retryFocus,
        key = "home-failed:$retryTick",
        label = "Home retry",
        enabled = true,
      )
      Box(modifier = Modifier.fillMaxSize().initialFocusTarget(retryFocus)) {
        FailureMessage(
          message = failure.message,
          onRetry = {
            // Incremented first: a second failure has to re-aim at the button that replaced this
            // one, and the key would otherwise be unchanged.
            retryTick++
            viewModel.loadHomeRails(force = true)
          },
        )
      }
    } else {
      // The shape of the page that is coming, rather than a spinner on black: this is the frame
      // the owner sees on every cold launch, and a skeleton also means the layout does not snap
      // from "one centred ring" to "billboard plus rails" when the data lands.
      RequestInitialFocus(
        target = skeletonFocus,
        key = "home-skeleton",
        label = "Home loading skeleton",
        enabled = true,
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          // Focusable so the remote is never pointing at nothing while TMDB answers. The handover
          // is automatic: initialFocusTarget clears its flags on dispose, focus is lost, and the
          // first-content request above lands on the billboard.
          .initialFocusTarget(skeletonFocus)
          .focusable()
          .semantics { contentDescription = "Loading Home" },
      ) {
        HomeSkeleton()
      }
    }
    return
  }

  if (!hasContinueWatching && !hasWatchlist && rails is LoadState.Ready && railList.isEmpty()) {
    Box(
      modifier = Modifier.fillMaxSize().padding(horizontal = NebulaDimens.ScreenEdge),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NebulaSpace.lg),
      ) {
        EmptyState(
          title = "Nothing to browse yet",
          hint = "TMDB returned no catalog titles. Check your key or try refreshing.",
          icon = Icons.Filled.Warning,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
          NebulaButton(
            text = "Refresh",
            onClick = { viewModel.loadHomeRails(force = true) },
            style = NebulaButtonStyle.Primary,
            icon = Icons.Filled.Refresh,
            modifier = Modifier.initialFocusTarget(firstContentFocus),
          )
          NebulaButton(
            text = "Open Settings",
            onClick = onOpenSettings,
            style = NebulaButtonStyle.Secondary,
          )
        }
      }
    }
    return
  }

  // A failed load with Continue Watching up degrades to the same inline notice a partial load uses.
  val inlineNotice = railsNotice ?: (rails as? LoadState.Failed)?.message

  // A successful retry clears the notice, which removes the status row - and with it the button
  // the viewer is still holding focus on. focusRestorer() does not cover this: it restores on
  // group *entry*, not on a child being removed. Hand focus back to the rail they walked down
  // from instead, and to the billboard only if there is nothing saved.
  LaunchedEffect(inlineNotice) {
    if (inlineNotice != null || !statusFocused) return@LaunchedEffect
    statusFocused = false
    // One frame, so the item has actually left the column before we ask for focus back.
    withFrameNanos { }
    val restored = runCatching { columnFocus.restoreFocusedChild() }.getOrDefault(false)
    if (!restored) runCatching { firstContentFocus.requester.requestFocus() }
  }

  // BACK walks the page up to the billboard before it leaves the app, which is what every TV
  // surface the viewer already uses does. Derived, so a scroll invalidates this only when the
  // answer actually flips rather than on every frame of it.
  val scrolled by remember {
    derivedStateOf {
      listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    }
  }
  BackHandler(enabled = scrolled) {
    scope.launch {
      listState.animateScrollToItem(0)
      // The focused card was scrolled off and disposed on the way up, so something has to take
      // focus or the remote is dead at the top of the page.
      runCatching { firstContentFocus.requester.requestFocus() }
    }
  }

  // One smooth scroll that settles the focused row at a stable "focus line"
  // ~18% down, so up/down is consistent instead of jamming rows at the edge.
  CompositionLocalProvider(LocalBringIntoViewSpec provides FocusLineBringIntoViewSpec) {
    LazyColumn(
      state = listState,
      verticalArrangement = Arrangement.spacedBy(NebulaDimens.RailGap),
      contentPadding = PaddingValues(
        top = NebulaDimens.ScreenEdgeVertical,
        bottom = NebulaDimens.ScreenEdgeVertical,
      ),
      modifier = Modifier
        .fillMaxSize()
        // Each row already remembers its card; this is what makes the column remember the row, so
        // returning from Details or the nav rail lands where the viewer left rather than back at
        // the billboard. The one-shot initial-focus request above still wins on a cold open:
        // nothing has been focused yet, so there is nothing here to restore.
        .focusRequester(columnFocus)
        .restoreColumnFocus()
        // Notes that the user is driving focus themselves; observed only, never consumed.
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key.isDirectional()) {
            userNavigated = true
          }
          false
        },
    ) {
      // contentType on every item kind: without it Compose treats a 320dp billboard and a poster
      // rail as interchangeable subcomposition slots and recomposes one into the other on the
      // app's hottest scroll path.
      if (hero != null) {
        item(key = "hero", contentType = "hero") {
          HeroBillboard(
            featured = hero,
            logoUrl = heroLogoUrl,
            onClick = { onItemClick(hero.type, hero.tmdbId) },
            focusTarget = firstContentFocus,
          )
        }
      }
      if (hasContinueWatching) {
        item(key = "continue", contentType = "continue") {
          ContinueWatchingRow(
            entries = continueWatching,
            onResumeClick = onResumeClick,
            onOptions = { options = it },
            firstCardFocus = if (hasHero) null else firstContentFocus,
            rowFocus = continueRowFocus,
          )
        }
      }
      // Between the row about what you were doing and the rows about what exists: a saved
      // title is something the viewer already decided on, so it outranks any catalog.
      if (hasWatchlist) {
        item(key = "watchlist", contentType = "watchlist") {
          WatchlistRow(
            entries = watchlist,
            onItemClick = { entry -> onItemClick(entry.type, entry.tmdbId) },
            onOptions = { watchlistOptions = it },
            firstCardFocus = if (hasHero || hasContinueWatching) null else firstContentFocus,
            rowFocus = watchlistRowFocus,
          )
        }
      }
      items(
        railList.size,
        key = { railList[it].title },
        contentType = { "rail" },
      ) { index ->
        val rail = railList[index]
        MediaRowFocusable(
          title = rail.title,
          items = rail.items,
          firstCardFocus = if (index == 0 && !hasHero && !hasContinueWatching && !hasWatchlist) {
            firstContentFocus
          } else {
            null
          },
          loadingMore = railPaging[rail.title]?.loading == true,
          onLastVisibleIndex = { last -> paginateRail(rail.title, last) },
          onItemClick = { item -> onItemClick(item.type, item.tmdbId) },
        )
      }
      if (rails is LoadState.Loading) {
        item(key = "rails-status", contentType = "status") {
          RailsStatusRow("Loading the rest of your catalogs…", onRetry = null)
        }
      } else if (inlineNotice != null) {
        item(key = "rails-status", contentType = "status") {
          RailsStatusRow(
            message = inlineNotice,
            onRetry = { viewModel.loadHomeRails(force = true) },
            onFocusedChange = { statusFocused = it },
          )
        }
      }
    }
  }

  options?.let { entry ->
    CardOptionsDialog(
      // Just the title: the episode belongs in the message, where there is room to say it in
      // words rather than as a code welded to the show's name.
      title = entry.title,
      message = remember(entry) { continueDialogMessage(entry) },
      focusKey = entry.key,
      focusLabel = "Continue Watching options",
      actions = listOf(
        // Named after the shelf the viewer is looking at. "Remove from row" was the developer's
        // word for it.
        CardAction("Remove from Continue Watching", destructive = true) {
          viewModel.forgetWatchEntry(entry)
          options = null
          editedRow = ROW_CONTINUE
          rowEditTick++
        },
        CardAction("Mark watched") {
          viewModel.markWatched(entry)
          options = null
          editedRow = ROW_CONTINUE
          rowEditTick++
        },
      ),
      onDismiss = { options = null },
    )
  }

  watchlistOptions?.let { entry ->
    CardOptionsDialog(
      title = entry.title,
      message = remember(entry) { watchlistDialogMessage(entry) },
      focusKey = entry.key,
      focusLabel = "My List options",
      actions = listOf(
        CardAction("Remove from My List", destructive = true) {
          viewModel.removeFromWatchlist(entry)
          watchlistOptions = null
          editedRow = ROW_WATCHLIST
          rowEditTick++
        },
      ),
      onDismiss = { watchlistOptions = null },
    )
  }
}

/** Which row the options dialog last edited, so focus can be handed back to it. */
private const val ROW_CONTINUE = "continue"
private const val ROW_WATCHLIST = "watchlist"

/**
 * Half the trailing letter-space of the first-run wordmark.
 *
 * [wordmark] tracks at 0.30em, and tracking is applied after the final A as well, so a 57sp
 * "NEBULA" measures ~17dp wider than its glyphs and centres ~8.5dp left of everything under it.
 */
private val WORDMARK_TRACK_CORRECTION = 8.5.dp

/**
 * Compact status line for the rails, shown below content that is already usable (Continue
 * Watching, or the rails that did load) so a partial failure never blanks what worked.
 *
 * One row rather than a stack: a single sentence used to produce a ~100dp plate that was mostly
 * empty, on a borderless [NebulaPalette.Surface] block that has no visible edge against the page at
 * three metres. It leads with a state glyph so "still working" and "gave up" are told apart at a
 * glance rather than by reading.
 */
@Composable
private fun RailsStatusRow(
  message: String,
  onRetry: (() -> Unit)?,
  onFocusedChange: (Boolean) -> Unit = {},
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.md),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = NebulaDimens.ScreenEdge)
      .background(NebulaPalette.Surface, NebulaShapes.medium)
      .border(1.dp, NebulaPalette.Outline, NebulaShapes.medium)
      .padding(horizontal = NebulaSpace.lg, vertical = NebulaSpace.md)
      .onFocusChanged { onFocusedChange(it.hasFocus) },
  ) {
    if (onRetry == null) {
      CircularProgressIndicator(
        color = NebulaPalette.Violet,
        trackColor = NebulaPalette.TrackInactive,
        strokeWidth = 2.dp,
        modifier = Modifier.size(NebulaIcon.sm),
      )
    } else {
      Icon(
        Icons.Filled.Warning,
        // Decorative: the message beside it is the content.
        contentDescription = null,
        tint = NebulaPalette.TextMuted,
        modifier = Modifier.size(NebulaIcon.sm),
      )
    }
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      // Muted rather than the error colour: by the time this row renders there is already usable
      // content above it, so a red bar would overstate what went wrong.
      color = NebulaPalette.TextMuted,
      modifier = Modifier.weight(1f),
    )
    if (onRetry != null) {
      NebulaButton(text = "Retry", onClick = onRetry, icon = Icons.Filled.Refresh)
    }
  }
}

/** Brings a focused row's top to a fixed line ~18% down the viewport in one scroll. */
@OptIn(ExperimentalFoundationApi::class)
private val FocusLineBringIntoViewSpec = object : BringIntoViewSpec {
  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    return offset - containerSize * 0.18f
  }
}

/**
 * How Home's rails scroll when focus moves along them.
 *
 * A copy of the spec [MediaRow] provides, which is private to Components.kt. Home's rows cannot go
 * without one: the LazyColumn provides [FocusLineBringIntoViewSpec] for the vertical axis and every
 * row inside it inherits that on the *horizontal* axis too, which pins the focused card 18% in from
 * the left and leaves a sliced poster sitting in the margin. This scrolls the minimum needed to
 * keep the focused card, its ring and its glow a screen margin clear of both edges, and does
 * nothing when the card is already comfortably in view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberHomeRailBringIntoViewSpec(): BringIntoViewSpec {
  val margin = with(LocalDensity.current) { (NebulaDimens.ScreenEdge + 10.dp).toPx() }
  return remember(margin) {
    object : BringIntoViewSpec {
      override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leadingGap = offset - margin
        val trailingGap = offset + size - (containerSize - margin)
        return when {
          leadingGap < 0f -> leadingGap
          trailingGap > 0f -> trailingGap
          else -> 0f
        }
      }
    }
  }
}

/** Keeps the billboard's copy clear of the right-hand half, where the subject of a still sits. */
private const val HERO_TEXT_WIDTH_FRACTION = 0.55f

/**
 * How many rails the billboard rotates across, one candidate per rail.
 *
 * See [pickHero]: the point is that the face of Home changes, not that it is random.
 */
private const val HERO_CANDIDATES = 6

/** Milliseconds in a day, which is the rate the billboard rotates at. */
private const val DAY_MS = 86_400_000L

/**
 * The billboard's title for today.
 *
 * [HeroPick] alone takes the head of the first rail, which changes about weekly - so the owner
 * opens the app to an identical banner for days on end and learns to scroll past it. This takes the
 * first artwork-bearing title from each of the first [HERO_CANDIDATES] rails and indexes them by
 * the day number: deterministic, stable for the whole session (so the banner never swaps under the
 * viewer), free to compute, and a different face every morning. Falls back to [HeroPick] when no
 * rail has artwork at all, which is the only case it still has to answer for.
 */
private fun pickHero(rails: List<HomeRail>): MediaItem? {
  val candidates = rails.asSequence()
    .mapNotNull { rail -> rail.items.firstOrNull { !it.backdropUrl.isNullOrBlank() } }
    .take(HERO_CANDIDATES)
    .toList()
  if (candidates.isEmpty()) return HeroPick.from(rails.map { it.items })
  return candidates[((System.currentTimeMillis() / DAY_MS) % candidates.size).toInt()]
}

/**
 * Splits [DetailsMetadata]'s joined line back into the facts it was built from, so the billboard can
 * set them as chips. Reading the formatter's output rather than re-deriving the fields keeps the
 * ordering and omission rules in the one tested place they belong.
 */
private val HERO_METADATA_SEPARATOR = Regex("""\s*•\s*""")

/** True for the four D-pad arrows, which is all we need to detect deliberate navigation. */
private fun Key.isDirectional(): Boolean =
  this == Key.DirectionUp || this == Key.DirectionDown ||
    this == Key.DirectionLeft || this == Key.DirectionRight

/**
 * Second scrim pass over the billboard, confined to the band the copy occupies.
 *
 * This used to be the shared bottom scrim over the whole card, which reaches full opacity at its
 * last stop - so composited with the horizontal wash the bottom-left quadrant was flat black and
 * the banner read as a panel with a photograph glued to its right edge. It also carried a comment
 * about
 * settling the banner into the page, which a card clipped to a rounded shape with 34dp of Void
 * under it cannot do. All it is for is holding the copy legible, so it now covers only the copy.
 */
private val HeroCopyScrim: Brush
  get() = Brush.verticalGradient(
    0.0f to Color.Transparent,
    1.0f to NebulaPalette.Void.copy(alpha = 0.7f),
  )

/**
 * Home's billboard: the day's featured title, at the size a TV expects it.
 *
 * The whole banner is the focusable node rather than a button inside it. Two reasons: the focus
 * line the LazyColumn scrolls to is measured from the focused node's top, so a button sitting 250dp
 * down the banner would scroll the artwork off the screen the moment it took focus; and a banner
 * whose only focusable is a small button gives the D-pad somewhere to get stuck on the way past.
 *
 * The pill is therefore an affordance, not a control - which is why it now *answers* to the
 * banner's focus instead of permanently wearing the accent fill of a focused button. That fill is
 * the app's "the remote is here" colour, and a banner claiming it while the remote is three rails
 * down is the one thing that undoes it everywhere else. Focus is otherwise a 3dp ring around an
 * 800dp card, i.e. two hairlines at the extreme edges: the banner deliberately has no focus scale,
 * so it is exactly the element that most needs a second cue.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroBillboard(
  featured: MediaItem,
  logoUrl: String?,
  onClick: () -> Unit,
  focusTarget: InitialFocusTarget?,
) {
  val metadata = DetailsMetadata.ofItem(featured)
  val chips = remember(metadata) {
    metadata.split(HERO_METADATA_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
  }
  val score = remember(featured.rating) { DetailsMetadata.scoreLabel(featured.rating) }
  val heroScrim = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
    NebulaHeroScrimRtl
  } else {
    NebulaHeroScrim
  }
  // One boolean driving a background and two ink colours: a draw-phase change on three nodes, no
  // measure, no animation.
  var focused by remember { mutableStateOf(false) }
  Box(
    modifier = Modifier
      .padding(horizontal = NebulaDimens.ScreenEdge)
      .initialFocusTarget(focusTarget),
  ) {
    Card(
      onClick = onClick,
      // No focus scale: a banner this wide would grow past the screen edges, and TV overscan
      // would clip the border that says it is focused.
      scale = CardDefaults.scale(focusedScale = 1f),
      shape = CardDefaults.shape(shape = NebulaShapes.large),
      border = nebulaCardBorder(NebulaShapes.large),
      glow = nebulaCardGlow(),
      // One focusable banner, so it announces as one sentence rather than as an image followed by
      // four loose lines of text. The synopsis is left out on purpose: it is read in full one
      // screen later, and here it would stand between the viewer and every rail below.
      modifier = Modifier.fillMaxWidth().height(NebulaDimens.HeroHeight)
        .onFocusChanged { focused = it.isFocused }
        .semantics(mergeDescendants = true) {
          contentDescription = A11yLabels.hero(featured.title, metadata)
        },
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop, falling back to the poster: HeroPick already prefers anything with a backdrop,
        // so this only fires on a genuinely artless catalog.
        ArtworkImage(
          url = featured.backdropUrl ?: featured.posterUrl,
          // Decorative: the banner itself is labelled.
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
        ) {
          // Void rather than the shared missing-artwork glyph: over a flat SurfaceVariant slab the
          // horizontal wash below draws a hard vertical band that looks like a rendering fault.
          Box(modifier = Modifier.fillMaxSize().background(NebulaPalette.Void))
        }
        // TMDB backdrops are full-frame stills with no safe area for text, so the readable side is
        // manufactured rather than trusted to the image. Two passes: the horizontal wash carries
        // the copy, the vertical one holds the last few lines off the brightest part of a still.
        // The wash is drawn at 0.9 so its opaque end keeps a trace of the artwork rather than
        // deleting the left third of the banner; TextHigh over it still clears 9:1.
        Box(modifier = Modifier.fillMaxSize().background(heroScrim, alpha = 0.9f))
        Box(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .background(HeroCopyScrim),
        )
        Column(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(HERO_TEXT_WIDTH_FRACTION)
            // The top pad is the headroom, stated rather than assumed: the copy is bottom-aligned
            // in a fixed-height box, so anything that overflows is clipped off the *top* and it is
            // the title that loses its head, not the pill. Worst case (two-line title, chips, two
            // lines of synopsis, pill) now measures ~296dp of the 320dp banner; it was 308dp.
            .padding(
              start = NebulaSpace.xl,
              top = NebulaSpace.md,
              end = NebulaSpace.lg,
              bottom = NebulaSpace.xl,
            ),
        ) {
          // The billboard is the largest, most-looked-at thing in the app, so it is where a
          // typeset title costs most and where the real logotype pays most.
          TitleTreatment(
            title = featured.title,
            logoUrl = logoUrl,
            style = MaterialTheme.typography.displaySmall,
            logoHeight = 88.dp,
          )
          if (chips.isNotEmpty()) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
              modifier = Modifier.padding(top = NebulaSpace.sm),
            ) {
              chips.forEach { chip ->
                NebulaBadge(
                  text = chip,
                  // The score is the one fact a viewer scans for, so it is the only chip that
                  // gets to be coloured; the rest would just be noise in violet.
                  tone = if (chip == score) BadgeTone.Accent else BadgeTone.Neutral,
                )
              }
            }
          }
          if (featured.overview.isNotBlank()) {
            Text(
              text = featured.overview,
              style = MaterialTheme.typography.bodyMedium,
              color = NebulaPalette.TextMuted,
              // Two lines, not three: at three the copy filled 308dp of the 320dp banner, so a
              // two-line title clipped and the artwork had nowhere to breathe. The full synopsis
              // is one press away.
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = NebulaSpace.sm),
            )
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xxs),
            modifier = Modifier
              .padding(top = NebulaSpace.md)
              // Both states occupy the same box - a border draws inside the bounds - so focus
              // costs a redraw and never a re-measure.
              .then(
                if (focused) {
                  Modifier.background(NebulaAccentBrush, RoundedCornerShape(50))
                } else {
                  Modifier.border(1.5.dp, NebulaPalette.TextMuted, RoundedCornerShape(50))
                },
              )
              .padding(horizontal = NebulaSpace.lg, vertical = NebulaSpace.xs),
          ) {
            Text(
              text = "View details",
              style = MaterialTheme.typography.labelLarge,
              // Dark ink on the accent gradient, the same pairing a focused NebulaButton uses.
              color = if (focused) NebulaPalette.OnAccent else NebulaPalette.TextMuted,
            )
            Icon(
              Icons.AutoMirrored.Filled.KeyboardArrowRight,
              // Decorative: the label beside it says what the banner does.
              contentDescription = null,
              tint = if (focused) NebulaPalette.OnAccent else NebulaPalette.TextMuted,
              // A drawn glyph rather than the "›" character this used to set, which sits above the
              // label's optical centre in Outfit and is a font's idea of an icon.
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * @param loadingMore the rail's next page is in flight, so the row ends in a placeholder.
 * @param onLastVisibleIndex fires whenever the last card in view changes, which is what drives
 *   paging. Reported from the *scroll* position rather than the focused card: holding right scrolls
 *   ahead of the focus events, and a rail that waited for focus to reach its edge would show the
 *   gap before it filled it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRowFocusable(
  title: String,
  items: List<MediaItem>,
  firstCardFocus: InitialFocusTarget?,
  loadingMore: Boolean,
  onLastVisibleIndex: (Int) -> Unit,
  onItemClick: (MediaItem) -> Unit,
) {
  if (items.isEmpty()) return
  val listState = rememberLazyListState()
  // The callback closes over the rail title and the ViewModel, both of which outlive any single
  // recomposition - but the effect must not restart on every one of them, or the flow below would
  // be torn down and rebuilt mid-scroll.
  val reportLastVisible by rememberUpdatedState(onLastVisibleIndex)
  LaunchedEffect(listState) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
      .collect { last -> if (last >= 0) reportLastVisible(last) }
  }
  Column(modifier = Modifier.fillMaxWidth()) {
    RailHeading(title)
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberHomeRailBringIntoViewSpec()) {
      LazyRow(
        state = listState,
        modifier = Modifier.restoreRowFocus(),
        // No vertical padding: a LazyRow clips its scroll axis only and inflates the cross axis by
        // 30dp of elevation headroom, so a focused poster's ring and glow render complete without
        // it. Confirmed on a device.
        contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge),
        horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
      ) {
        items(items.size, key = { items[it].key }, contentType = { "card" }) { index ->
          val item = items[index]
          MediaCard(
            item = item,
            onClick = { onItemClick(item) },
            modifier = Modifier.initialFocusTarget(if (index == 0) firstCardFocus else null),
          )
        }
        if (loadingMore) {
          item(key = "loading-more", contentType = "loading") { LoadingMoreCard() }
        }
      }
    }
  }
}

/**
 * Placeholder at the end of a rail whose next page is loading.
 *
 * Deliberately not focusable: a focusable card that vanishes the instant the page lands would leave
 * the D-pad pointing at nothing, which is the one failure mode a placeholder must not introduce.
 */
@Composable
private fun LoadingMoreCard() {
  Box(
    // Poster-shaped and poster-sized, so the rail's rhythm does not break where the page ends.
    modifier = Modifier
      .width(NebulaDimens.PosterWidth)
      .height(NebulaDimens.PosterHeight)
      .background(NebulaPalette.Surface, NebulaDimens.PosterShape)
      .border(1.dp, NebulaPalette.Outline, NebulaDimens.PosterShape),
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(
      color = NebulaPalette.Violet,
      trackColor = NebulaPalette.Outline,
      modifier = Modifier.size(28.dp),
    )
  }
}

/**
 * "My List", drawn entirely from the stored snapshots.
 *
 * No TMDB call and no key needed, which is the point: a saved title has to be reachable on a
 * TV that has just woken up with no network, exactly like Continue Watching.
 *
 * @param rowFocus the first card, as the place focus returns to after the options dialog removes
 *   something from this row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchlistRow(
  entries: List<WatchlistEntry>,
  onItemClick: (WatchlistEntry) -> Unit,
  onOptions: (WatchlistEntry) -> Unit,
  firstCardFocus: InitialFocusTarget?,
  rowFocus: InitialFocusTarget,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    RailHeading("My List")
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberHomeRailBringIntoViewSpec()) {
      LazyRow(
        modifier = Modifier.restoreRowFocus(),
        contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge),
        horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
      ) {
        items(entries.size, key = { entries[it].key }, contentType = { "card" }) { index ->
          val entry = entries[index]
          MediaCard(
            item = remember(entry) { entry.toMediaItem() },
            onClick = { onItemClick(entry) },
            modifier = Modifier
              .initialFocusTarget(if (index == 0) firstCardFocus else null)
              .initialFocusTarget(if (index == 0) rowFocus else null),
            onLongClick = { onOptions(entry) },
          )
        }
      }
    }
  }
}

/**
 * Where the viewer stopped, newest first.
 *
 * Built from the shared [MediaCard] rather than a hand-rolled card: the progress bar, the scrim it
 * sits on, the caption that dims when the card is not focused and the dot that says a held OK does
 * something are all part of that component now, and a Continue Watching card that carried its own
 * copies of two of them and neither of the others was the one row on Home that behaved differently
 * for no reason a viewer could see.
 *
 * @param rowFocus the first card, as the place focus returns to after the options dialog edits
 *   this row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingRow(
  entries: List<WatchEntry>,
  onResumeClick: (WatchEntry) -> Unit,
  onOptions: (WatchEntry) -> Unit,
  firstCardFocus: InitialFocusTarget?,
  rowFocus: InitialFocusTarget,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    RailHeading("Continue Watching")
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberHomeRailBringIntoViewSpec()) {
      LazyRow(
        modifier = Modifier.restoreRowFocus(),
        contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge),
        horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
      ) {
        items(entries.size, key = { entries[it].key }, contentType = { "card" }) { index ->
          val entry = entries[index]
          MediaCard(
            item = remember(entry) { entry.toMediaItem() },
            onClick = { onResumeClick(entry) },
            modifier = Modifier
              .initialFocusTarget(if (index == 0) firstCardFocus else null)
              .initialFocusTarget(if (index == 0) rowFocus else null),
            subtitle = remember(entry) { continueCaption(entry) },
            onLongClick = { onOptions(entry) },
            progress = entry.progress,
          )
        }
      }
    }
  }
}

/**
 * The stored record in the shape [MediaCard] takes.
 *
 * A watch record keeps only what a resume needs, so everything the card does not draw is absent
 * rather than invented: no backdrop, no synopsis, no rating.
 */
private fun WatchEntry.toMediaItem(): MediaItem = MediaItem(
  tmdbId = tmdbId,
  type = if (mediaType == "show") MediaType.Show else MediaType.Movie,
  title = title,
  posterUrl = posterUrl,
  backdropUrl = null,
  overview = "",
  year = null,
  rating = null,
)

/**
 * "S1E4 • 22m left" under a Continue Watching card.
 *
 * The bullet is the app's one visual separator - [A11yLabels.spoken] turns it into a pause, which
 * is why the same line reads correctly to TalkBack as well.
 */
private fun continueCaption(entry: WatchEntry): String? = listOfNotNull(
  entry.season?.let { "S${it}E${entry.episode}" },
  remainingLabel(entry),
).joinToString(" • ").ifEmpty { null }

/**
 * What the Continue Watching options dialog says under the title.
 *
 * The line it replaced restated the row heading ("Manage this title in Continue Watching.") in the
 * one place there was room to say something the viewer could not already see - where they stopped.
 */
private fun continueDialogMessage(entry: WatchEntry): String = listOfNotNull(
  A11yLabels.episodeCode(entry.season, entry.episode)?.replaceFirstChar(Char::uppercase),
  remainingLabel(entry),
).joinToString(" • ").ifEmpty { "Pick up where you left off." }

/** The same idea for My List, which stores a year rather than a position. */
private fun watchlistDialogMessage(entry: WatchlistEntry): String =
  listOfNotNull(entry.year, if (entry.type == MediaType.Show) "Series" else "Film")
    .joinToString(" • ")
    .ifEmpty { "Saved to My List." }

/**
 * "22m left" for a part-watched video, "Watched" once it is finished, null when the duration was
 * never recorded - which is the case for anything played before durations were stored, and is why
 * this returns null rather than a confident "0m left".
 */
private fun remainingLabel(entry: WatchEntry): String? {
  if (entry.watched) return "Watched"
  if (entry.durationMs <= 0) return null
  val remainingMs = entry.durationMs - entry.positionMs
  if (remainingMs <= 0) return null
  // Rounded up, so the last fifty seconds of a video read as "1m left" rather than "0m left".
  val minutes = ((remainingMs + 59_999) / 60_000).toInt()
  return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
}
