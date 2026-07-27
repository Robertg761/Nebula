package com.stremioshell.host.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchlistEntry
import com.stremioshell.host.tv.data.tmdb.DetailsMetadata
import com.stremioshell.host.tv.data.tmdb.HeroPick
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType

@OptIn(ExperimentalFoundationApi::class)
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
  val firstContentFocus = rememberInitialFocusTarget()

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
  var rowEditTick by remember { mutableStateOf(0) }

  LaunchedEffect(apiKey) { viewModel.loadHomeRails() }

  val needsSetup = apiKey != null && apiKey!!.isBlank()
  // Continue Watching resolves asynchronously and takes over the first-card slot, so the
  // target is only known once both it and the rails have settled; re-aim when it appears.
  // My List reads from the same store and behaves the same way.
  val hasContinueWatching = continueWatching.isNotEmpty()
  val hasWatchlist = watchlist.isNotEmpty()

  val railList = (rails as? LoadState.Ready)?.value.orEmpty()
  // Recomputed only when the rail list changes identity, which a page append does - but the pick
  // only ever reads the head of each rail, which an append never touches.
  val hero = remember(railList) { HeroPick.from(railList.map { it.items }) }
  // The billboard is the top of the screen, so it owns first focus when it exists: landing on a
  // rail below it would scroll it off before the user ever saw it.
  val hasHero = hero != null

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
  RequestInitialFocus(
    target = firstContentFocus,
    key = "row-edit:$rowEditTick",
    label = "Home first content card after row edit",
    enabled = rowEditTick > 0 && options == null && watchlistOptions == null,
  )

  if (needsSetup) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Welcome", style = MaterialTheme.typography.displaySmall)
        Text(
          "Connect your TMDB account and Comet addon to start streaming.",
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
        Button(onClick = onPairWithPhone, modifier = Modifier.initialFocusTarget(firstContentFocus)) {
          Text("Set up with phone")
        }
        Button(
          onClick = onOpenSettings,
          modifier = Modifier.padding(top = 12.dp),
        ) {
          Text("Enter manually")
        }
      }
    }
    return
  }

  // Continue Watching and My List come from local storage, so a TMDB outage must not take them
  // down with the rails. Only when there is nothing local to show does the rails' state own the
  // whole screen; otherwise it is reported inline, under the rows the user can still use.
  if (!hasContinueWatching && !hasWatchlist && rails !is LoadState.Ready) {
    LoadStateContent(
      rails,
      loadingText = "Loading catalogs...",
      onRetry = { viewModel.loadHomeRails(force = true) },
    ) {}
    return
  }

  // A failed load with Continue Watching up degrades to the same inline notice a partial load uses.
  val inlineNotice = railsNotice ?: (rails as? LoadState.Failed)?.message

  // One smooth scroll that settles the focused row at a stable "focus line"
  // ~18% down, so up/down is consistent instead of jamming rows at the edge.
  CompositionLocalProvider(LocalBringIntoViewSpec provides FocusLineBringIntoViewSpec) {
    LazyColumn(
      verticalArrangement = Arrangement.spacedBy(28.dp),
      contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp),
      modifier = Modifier
        .fillMaxSize()
        // Each row already remembers its card; this is what makes the column remember the row, so
        // returning from Details or the nav rail lands where the viewer left rather than back at
        // the billboard. The one-shot initial-focus request above still wins on a cold open:
        // nothing has been focused yet, so there is nothing here to restore.
        .restoreColumnFocus()
        // Notes that the user is driving focus themselves; observed only, never consumed.
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key.isDirectional()) {
            userNavigated = true
          }
          false
        },
    ) {
      if (hero != null) {
        item(key = "hero") {
          HeroBillboard(
            featured = hero,
            onClick = { onItemClick(hero.type, hero.tmdbId) },
            focusTarget = firstContentFocus,
          )
        }
      }
      if (hasContinueWatching) {
        item(key = "continue") {
          ContinueWatchingRow(
            entries = continueWatching,
            onResumeClick = onResumeClick,
            onOptions = { options = it },
            firstCardFocus = if (hasHero) null else firstContentFocus,
          )
        }
      }
      // Between the row about what you were doing and the rows about what exists: a saved
      // title is something the viewer already decided on, so it outranks any catalog.
      if (hasWatchlist) {
        item(key = "watchlist") {
          WatchlistRow(
            entries = watchlist,
            onItemClick = { entry -> onItemClick(entry.type, entry.tmdbId) },
            onOptions = { watchlistOptions = it },
            firstCardFocus = if (hasHero || hasContinueWatching) null else firstContentFocus,
          )
        }
      }
      items(railList.size, key = { railList[it].title }) { index ->
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
        item(key = "rails-status") { RailsStatusRow("Loading catalogs...", onRetry = null) }
      } else if (inlineNotice != null) {
        item(key = "rails-status") {
          RailsStatusRow(inlineNotice, onRetry = { viewModel.loadHomeRails(force = true) })
        }
      }
    }
  }

  options?.let { entry ->
    val suffix = if (entry.season != null) " S${entry.season}E${entry.episode}" else ""
    CardOptionsDialog(
      title = entry.title + suffix,
      message = "Manage this title in Continue Watching.",
      focusKey = entry.key,
      focusLabel = "Continue Watching options",
      actions = listOf(
        CardAction("Remove from row") {
          viewModel.forgetWatchEntry(entry)
          options = null
          rowEditTick++
        },
        CardAction("Mark watched") {
          viewModel.markWatched(entry)
          options = null
          rowEditTick++
        },
      ),
      onDismiss = { options = null },
    )
  }

  watchlistOptions?.let { entry ->
    CardOptionsDialog(
      title = entry.title,
      message = "Manage this title in My List.",
      focusKey = entry.key,
      focusLabel = "My List options",
      actions = listOf(
        CardAction("Remove from My List") {
          viewModel.removeFromWatchlist(entry)
          watchlistOptions = null
          rowEditTick++
        },
      ),
      onDismiss = { watchlistOptions = null },
    )
  }
}

/**
 * Compact status line for the rails, shown below content that is already usable (Continue
 * Watching, or the rails that did load) so a partial failure never blanks what worked.
 */
@Composable
private fun RailsStatusRow(message: String, onRetry: (() -> Unit)?) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
    if (onRetry != null) {
      Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
        Text("Retry")
      }
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
 * Billboard height. Roughly 55% of a 1080p TV's 540dp-tall viewport: it leads the screen the way a
 * 10-foot layout expects while still leaving the top of the first row visible, so it is obvious that
 * pressing down goes somewhere.
 */
private val HERO_HEIGHT = 300.dp

/** Keeps the billboard's copy off the interesting half of the backdrop. */
private const val HERO_TEXT_WIDTH_FRACTION = 0.55f

/** True for the four D-pad arrows, which is all we need to detect deliberate navigation. */
private fun Key.isDirectional(): Boolean =
  this == Key.DirectionUp || this == Key.DirectionDown ||
    this == Key.DirectionLeft || this == Key.DirectionRight

/**
 * Home's billboard: the week's top trending title, at the size a TV expects it.
 *
 * The whole banner is the focusable node rather than a button inside it. Two reasons: the focus
 * line the LazyColumn scrolls to is measured from the focused node's top, so a button sitting 250dp
 * down the banner would scroll the artwork off the screen the moment it took focus; and a banner
 * whose only focusable is a small button gives the D-pad somewhere to get stuck on the way past.
 * The pill below is therefore an affordance, not a control.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroBillboard(
  featured: MediaItem,
  onClick: () -> Unit,
  focusTarget: InitialFocusTarget?,
) {
  val metadata = DetailsMetadata.ofItem(featured)
  Box(
    modifier = Modifier
      .padding(horizontal = 48.dp)
      .initialFocusTarget(focusTarget),
  ) {
    Card(
      onClick = onClick,
      // No focus scale: a banner this wide would grow past the screen edges, and TV overscan
      // would clip the border that says it is focused.
      scale = CardDefaults.scale(focusedScale = 1f),
      // One focusable banner, so it announces as one sentence rather than as an image followed by
      // four loose lines of text. The synopsis is left out on purpose: it is read in full one
      // screen later, and here it would stand between the viewer and every rail below.
      modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT)
        .semantics(mergeDescendants = true) {
          contentDescription = A11yLabels.hero(featured.title, metadata)
        },
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop, falling back to the poster: a banner with no artwork at all is a grey slab,
        // and a stretched poster still reads as the title.
        ArtworkImage(
          url = featured.backdropUrl ?: featured.posterUrl,
          // Decorative: the banner itself is labelled.
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
        )
        // TMDB backdrops are full-frame stills with no safe area for text, so the left side is
        // darkened rather than trusting the image.
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.horizontalGradient(
                0.0f to MaterialTheme.colorScheme.surface,
                0.45f to MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                1.0f to Color.Transparent,
              ),
            ),
        )
        Column(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(HERO_TEXT_WIDTH_FRACTION)
            .padding(start = 32.dp, end = 24.dp, bottom = 28.dp),
        ) {
          Text(
            text = featured.title,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (metadata.isNotBlank()) {
            Text(
              text = metadata,
              style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
          if (featured.overview.isNotBlank()) {
            Text(
              text = featured.overview,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 10.dp),
            )
          }
          Text(
            text = "View details  ›",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
              .padding(top = 18.dp)
              .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
              .padding(horizontal = 22.dp, vertical = 9.dp),
          )
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
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
    )
    LazyRow(
      state = listState,
      modifier = Modifier.restoreRowFocus(),
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(items.size, key = { items[it].key }) { index ->
        val item = items[index]
        MediaCard(
          item = item,
          onClick = { onItemClick(item) },
          modifier = Modifier.initialFocusTarget(if (index == 0) firstCardFocus else null),
        )
      }
      if (loadingMore) {
        item(key = "loading-more") { LoadingMoreCard() }
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
    modifier = Modifier
      .width(140.dp)
      .height(200.dp)
      .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(28.dp),
    )
  }
}

/**
 * "My List", drawn entirely from the stored snapshots.
 *
 * No TMDB call and no key needed, which is the point: a saved title has to be reachable on a
 * TV that has just woken up with no network, exactly like Continue Watching.
 */
@Composable
private fun WatchlistRow(
  entries: List<WatchlistEntry>,
  onItemClick: (WatchlistEntry) -> Unit,
  onOptions: (WatchlistEntry) -> Unit,
  firstCardFocus: InitialFocusTarget?,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = "My List",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
    )
    LazyRow(
      modifier = Modifier.restoreRowFocus(),
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(entries.size, key = { entries[it].key }) { index ->
        val entry = entries[index]
        MediaCard(
          item = remember(entry) { entry.toMediaItem() },
          onClick = { onItemClick(entry) },
          modifier = Modifier.initialFocusTarget(if (index == 0) firstCardFocus else null),
          onLongClick = { onOptions(entry) },
        )
      }
    }
  }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContinueWatchingRow(
  entries: List<WatchEntry>,
  onResumeClick: (WatchEntry) -> Unit,
  onOptions: (WatchEntry) -> Unit,
  firstCardFocus: InitialFocusTarget?,
) {
  Column {
    Text(
      text = "Continue Watching",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
    )
    LazyRow(
      modifier = Modifier.restoreRowFocus(),
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(entries.size, key = { entries[it].key }) { index ->
        val entry = entries[index]
        Column(modifier = Modifier.width(140.dp)) {
          Card(
            onClick = { onResumeClick(entry) },
            onLongClick = { onOptions(entry) },
            scale = CardDefaults.scale(focusedScale = 1.08f),
            // The bar across the bottom of the card is the only thing that says how far in this
            // is, and a bar has nothing to announce, so the position rides in the description.
            modifier = Modifier.width(140.dp).height(200.dp)
              .initialFocusTarget(if (index == 0) firstCardFocus else null)
              .semantics(mergeDescendants = true) {
                contentDescription = A11yLabels.continueWatching(
                  entry.title,
                  entry.season,
                  entry.episode,
                  entry.progress,
                )
              },
          ) {
            Box(modifier = Modifier.fillMaxSize()) {
              ArtworkImage(
                url = entry.posterUrl,
                // Decorative: the card carries the title, the episode and the progress.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
              ) {
                Text(entry.title, maxLines = 3, modifier = Modifier.padding(8.dp))
              }
              // Watched-progress bar pinned to the card bottom.
              Box(
                modifier = Modifier
                  .align(Alignment.BottomStart)
                  .fillMaxWidth()
                  .height(5.dp)
                  .background(MaterialTheme.colorScheme.surfaceVariant),
              ) {
                Box(
                  modifier = Modifier
                    .fillMaxWidth(entry.progress)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.primary),
                )
              }
            }
          }
          val suffix = if (entry.season != null) " S${entry.season}E${entry.episode}" else ""
          Text(
            text = entry.title + suffix,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            // Visual echo of the card's description; left readable it is announced twice.
            modifier = Modifier.padding(top = 14.dp).clearAndSetSemantics {},
          )
        }
      }
    }
  }
}
