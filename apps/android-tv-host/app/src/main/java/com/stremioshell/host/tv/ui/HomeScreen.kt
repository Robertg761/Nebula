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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
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
  val continueWatching by viewModel.continueWatching.collectAsState()
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
  // until something asks for focus again.
  var options by remember { mutableStateOf<WatchEntry?>(null) }
  var rowEditTick by remember { mutableStateOf(0) }

  LaunchedEffect(apiKey) { viewModel.loadHomeRails() }

  val needsSetup = apiKey != null && apiKey!!.isBlank()
  // Continue Watching resolves asynchronously and takes over the first-card slot, so the
  // target is only known once both it and the rails have settled; re-aim when it appears.
  val hasContinueWatching = continueWatching.isNotEmpty()

  // Only the rails need the one-shot treatment; the setup screen has no scroll position to
  // protect, so it stays free to re-aim at its button on every visit.
  LaunchedEffect(firstContentFocus.focused, needsSetup) {
    if (firstContentFocus.focused && !needsSetup) landedFocus = true
  }

  // Land focus on content (not the nav rail) once something focusable exists. Continue Watching
  // counts: it renders from local storage, so offline it is the only focusable content there is.
  RequestInitialFocus(
    target = firstContentFocus,
    key = if (needsSetup) "setup" else "content:$hasContinueWatching",
    label = "Home first content card",
    enabled = !userNavigated &&
      (needsSetup || (!landedFocus && (rails is LoadState.Ready || hasContinueWatching))),
  )

  // Separate request, because the one above is deliberately dead once the user has driven
  // focus themselves - which they always have by the time they long-press a card.
  RequestInitialFocus(
    target = firstContentFocus,
    key = "row-edit:$rowEditTick",
    label = "Home first content card after row edit",
    enabled = rowEditTick > 0 && options == null,
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

  // Continue Watching comes from local storage, so a TMDB outage must not take it down with the
  // rails. Only when there is nothing local to show does the rails' state own the whole screen;
  // otherwise it is reported inline, under the row the user can still use.
  if (!hasContinueWatching && rails !is LoadState.Ready) {
    LoadStateContent(
      rails,
      loadingText = "Loading catalogs...",
      onRetry = { viewModel.loadHomeRails(force = true) },
    ) {}
    return
  }

  val railList = (rails as? LoadState.Ready)?.value.orEmpty()
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
        // Notes that the user is driving focus themselves; observed only, never consumed.
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key.isDirectional()) {
            userNavigated = true
          }
          false
        },
    ) {
      if (hasContinueWatching) {
        item(key = "continue") {
          ContinueWatchingRow(
            entries = continueWatching,
            onResumeClick = onResumeClick,
            onOptions = { options = it },
            firstCardFocus = firstContentFocus,
          )
        }
      }
      items(railList.size, key = { railList[it].title }) { index ->
        val rail = railList[index]
        MediaRowFocusable(
          title = rail.title,
          items = rail.items,
          firstCardFocus = if (index == 0 && !hasContinueWatching) firstContentFocus else null,
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
    ContinueWatchingOptions(
      entry = entry,
      onDismiss = { options = null },
      onMarkWatched = {
        viewModel.markWatched(entry)
        options = null
        rowEditTick++
      },
      onRemove = {
        viewModel.forgetWatchEntry(entry)
        options = null
        rowEditTick++
      },
    )
  }
}

/**
 * What a long press on a Continue Watching card offers.
 *
 * Long press rather than a visible menu button: the row is posters, and every TV app the
 * user already owns puts row management behind a held OK.
 */
@Composable
private fun ContinueWatchingOptions(
  entry: WatchEntry,
  onDismiss: () -> Unit,
  onMarkWatched: () -> Unit,
  onRemove: () -> Unit,
) {
  val firstOption = rememberInitialFocusTarget()
  val suffix = if (entry.season != null) " S${entry.season}E${entry.episode}" else ""

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
  ) {
    RequestInitialFocus(target = firstOption, key = entry.key, label = "Continue Watching options")

    Surface(modifier = Modifier.width(520.dp)) {
      Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text(entry.title + suffix, style = MaterialTheme.typography.headlineSmall)
        Text(
          "Manage this title in Continue Watching.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Button(
          onClick = onRemove,
          modifier = Modifier.fillMaxWidth().initialFocusTarget(firstOption),
        ) {
          Text("Remove from row")
        }
        Button(onClick = onMarkWatched, modifier = Modifier.fillMaxWidth()) {
          Text("Mark watched")
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
          Text("Cancel")
        }
      }
    }
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

/** True for the four D-pad arrows, which is all we need to detect deliberate navigation. */
private fun Key.isDirectional(): Boolean =
  this == Key.DirectionUp || this == Key.DirectionDown ||
    this == Key.DirectionLeft || this == Key.DirectionRight

@Composable
private fun MediaRowFocusable(
  title: String,
  items: List<com.stremioshell.host.tv.data.tmdb.MediaItem>,
  firstCardFocus: InitialFocusTarget?,
  onItemClick: (com.stremioshell.host.tv.data.tmdb.MediaItem) -> Unit,
) {
  if (items.isEmpty()) return
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
    )
    LazyRow(
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(items.size, key = { "${items[it].type}:${items[it].tmdbId}" }) { index ->
        val item = items[index]
        MediaCard(
          item = item,
          onClick = { onItemClick(item) },
          modifier = Modifier.initialFocusTarget(if (index == 0) firstCardFocus else null),
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
  firstCardFocus: InitialFocusTarget,
) {
  Column {
    Text(
      text = "Continue Watching",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
    )
    LazyRow(
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
            modifier = Modifier.width(140.dp).height(200.dp)
              .initialFocusTarget(if (index == 0) firstCardFocus else null),
          ) {
            Box(modifier = Modifier.fillMaxSize()) {
              ArtworkImage(
                url = entry.posterUrl,
                contentDescription = entry.title,
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
            modifier = Modifier.padding(top = 14.dp),
          )
        }
      }
    }
  }
}
