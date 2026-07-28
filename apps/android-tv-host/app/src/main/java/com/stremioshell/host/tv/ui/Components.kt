package com.stremioshell.host.tv.ui

import android.util.Log
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.nebulaCardBorder
import com.stremioshell.host.tv.ui.theme.nebulaCardGlow

/** Tag for the focus-recovery logs the QA matrix expects in a diagnostics capture. */
private const val FOCUS_TAG = "TvFocus"

/** Frames to keep retrying an initial focus request before giving up (~0.5s at 60fps). */
private const val FOCUS_MAX_FRAMES = 30

/**
 * The node a screen wants focused when it opens. Tracks placement and focus so
 * [RequestInitialFocus] can wait for a real, laid-out node instead of firing a request at
 * nothing.
 */
@Stable
class InitialFocusTarget {
  /** Exposed so screens that already route D-pad keys by requester can reuse this one. */
  val requester = FocusRequester()
  internal var placed by mutableStateOf(false)
  internal var focused by mutableStateOf(false)
}

@Composable
fun rememberInitialFocusTarget(): InitialFocusTarget = remember { InitialFocusTarget() }

/**
 * Marks this node as the screen's initial focus target. Pass `null` to leave the node alone,
 * so a caller can move the target between siblings without branching on the modifier.
 */
fun Modifier.initialFocusTarget(target: InitialFocusTarget?): Modifier {
  if (target == null) return this
  return this.composed {
    DisposableEffect(target) {
      onDispose {
        // The target migrates between nodes (Continue Watching taking over the first card,
        // a season row replacing a Back button). Stale flags would make the next request
        // look already satisfied.
        target.placed = false
        target.focused = false
      }
    }
    Modifier
      .onFocusChanged { target.focused = it.isFocused || it.hasFocus }
      .onGloballyPositioned { target.placed = true }
      .focusRequester(target.requester)
  }
}

/**
 * Makes a scrolling row (or grid) hand focus back to the card it was left on.
 *
 * Compose enters a lazy row with a plain geometric focus search, which lands on whichever card is
 * nearest the entry edge - so a rail the viewer has scrolled ten cards into quietly rewinds to its
 * first card every time they step down to the row below and back. [focusRestorer] saves the focused
 * child on the way out and pins it, so the choice also survives the parent list recycling the row
 * while it is off screen.
 *
 * Goes on the row's own modifier, in front of the scrollable container that is the actual focus
 * group. Initial focus is unaffected: [RequestInitialFocus] asks a specific card for focus directly
 * rather than entering through the group, and on a first visit there is nothing saved to restore.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.restoreRowFocus(): Modifier = focusRestorer()

/**
 * The same restoration one axis out: a screen's vertical list hands focus back to the row it was
 * left on.
 *
 * Without it the rows remember their card and the column forgets the row, so coming back to Home
 * from Details or the nav rail re-enters by geometry - the topmost row nearest the entry edge -
 * and a viewer eight rails down lands on the billboard again. Distinct from [restoreRowFocus] only
 * in what it documents; kept separate so the call sites still say which axis they mean.
 *
 * Safe next to [RequestInitialFocus]: that asks a named node for focus directly rather than
 * entering through this group, and on a cold open there is nothing saved here to restore.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.restoreColumnFocus(): Modifier = focusRestorer()

/**
 * Requests focus for [target] once its node has actually been placed, retrying for a bounded
 * number of frames and logging when focus never lands.
 *
 * A bare `requestFocus()` inside a `LaunchedEffect` runs in the same frame the node first
 * composes - before placement - so on slower devices the request is silently dropped, the
 * D-pad is dead and nothing in logcat says why.
 *
 * @param key re-aims the request when it changes; keep it stable while the user is browsing
 *   so focus is never stolen back.
 * @param enabled set false to suppress the request entirely (e.g. the user already navigated).
 */
@Composable
fun RequestInitialFocus(
  target: InitialFocusTarget,
  key: Any?,
  label: String,
  enabled: Boolean = true,
) {
  LaunchedEffect(target, key, enabled) {
    if (!enabled) return@LaunchedEffect
    var frames = 0
    var lastFailure: Throwable? = null
    while (frames < FOCUS_MAX_FRAMES) {
      // Resumes at the start of the next frame, by which point the pending composition has
      // been laid out and the target node exists.
      withFrameNanos { }
      frames++
      if (target.focused) {
        if (frames > 2) {
          Log.i(FOCUS_TAG, "focus recovery: $label took $frames frames to accept focus")
        }
        return@LaunchedEffect
      }
      if (target.placed) {
        runCatching { target.requester.requestFocus() }.onFailure { lastFailure = it }
      }
    }
    Log.w(
      FOCUS_TAG,
      "focus recovery failed: $label never took focus after $frames frames " +
        "(placed=${target.placed}); D-pad may need a manual nudge",
      lastFailure,
    )
  }
}

/**
 * Pops the current screen through the activity back dispatcher, so a screen can offer a
 * focusable Back affordance without threading a nav callback through every call site.
 */
@Composable
fun rememberBackAction(): () -> Unit {
  val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
  return remember(dispatcher) {
    {
      if (dispatcher == null) {
        Log.w(FOCUS_TAG, "back action invoked with no OnBackPressedDispatcher")
      } else {
        dispatcher.onBackPressed()
      }
    }
  }
}

/**
 * Network artwork that fills [modifier]'s bounds, with a tonal placeholder while it decodes and
 * [fallback] whenever there is nothing to show - no URL, or a load that failed. Without the
 * failure branch a dead poster URL leaves a permanently blank card.
 *
 * Failure arrives through Coil's `onError` callback rather than SubcomposeAsyncImage: a row holds
 * a dozen of these at once and per-image subcomposition is too expensive on low-RAM TVs.
 */
@Composable
fun ArtworkImage(
  url: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  fallback: @Composable BoxScope.() -> Unit = {},
) {
  val tone = MaterialTheme.colorScheme.surfaceVariant
  var failed by remember(url) { mutableStateOf(false) }
  if (url == null || failed) {
    Box(modifier = modifier.background(tone), contentAlignment = Alignment.Center, content = fallback)
  } else {
    AsyncImage(
      model = url,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      // Solid tonal fill instead of the bare theme background, so a card does not pop from an
      // empty rectangle to a full poster mid-scroll.
      placeholder = remember(tone) { ColorPainter(tone) },
      onError = { failed = true },
      modifier = modifier,
    )
  }
}

/**
 * @param subtitle a second, quieter line under the title - the year and kind of title on surfaces
 *   where a viewer is choosing between similarly named results. Omitted on the rails, where the
 *   row heading already says what kind of thing is in it and the extra line would cost a card's
 *   worth of height on every row.
 * @param onLongClick row management for the rows that have any (My List). Null everywhere else, so
 *   a held OK on a catalog card stays the plain press it has always been.
 */
@Composable
fun MediaCard(
  item: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  onLongClick: (() -> Unit)? = null,
) {
  Column(modifier = modifier.width(NebulaDimens.PosterWidth)) {
    Card(
      onClick = onClick,
      onLongClick = onLongClick,
      // Modest focus scale so the poster does not grow over its own title.
      scale = CardDefaults.scale(focusedScale = NebulaDimens.FocusScale),
      shape = CardDefaults.shape(shape = NebulaDimens.PosterShape),
      border = nebulaCardBorder(),
      glow = nebulaCardGlow(),
      // The card is the node the remote - and therefore TalkBack - lands on; the title under it
      // is a sibling it never visits, so without this a rail announces as a row of "unlabeled".
      // Built in the lambda rather than in composition: it only runs when something is actually
      // reading the screen.
      modifier = Modifier.width(NebulaDimens.PosterWidth).height(NebulaDimens.PosterHeight)
        .semantics(mergeDescendants = true) {
          contentDescription = A11yLabels.card(item.title, subtitle, manageable = onLongClick != null)
        },
    ) {
      ArtworkImage(
        url = item.posterUrl,
        // Decorative: the card above already says the title, and a second description here would
        // make every poster announce its title twice.
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
      ) {
        // Artless titles get the title set as a poster rather than as a caption on a grey slab,
        // so a rail with a few missing images still reads as a rail.
        Text(
          text = item.title,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.titleSmall,
          color = NebulaPalette.TextMuted,
          modifier = Modifier.padding(horizontal = 10.dp),
        )
      }
    }
    Text(
      text = item.title,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodySmall,
      color = NebulaPalette.TextHigh,
      // Clears the focused card's scaled-up bottom edge.
      modifier = Modifier.padding(top = 12.dp).width(NebulaDimens.PosterWidth)
        // Visual echo of the card's own description; left in the tree it is read a second time.
        .clearAndSetSemantics {},
    )
    if (subtitle != null) {
      Text(
        text = subtitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = NebulaPalette.TextMuted,
        modifier = Modifier.padding(top = 3.dp).width(NebulaDimens.PosterWidth)
          .clearAndSetSemantics {},
      )
    }
  }
}

@Composable
fun MediaRow(title: String, items: List<MediaItem>, onItemClick: (MediaItem) -> Unit) {
  if (items.isEmpty()) return
  Column(modifier = Modifier.fillMaxWidth()) {
    RailHeading(title)
    LazyRow(
      modifier = Modifier.restoreRowFocus(),
      // Vertical slack in the padding, not the arrangement: a focused card scales up and its glow
      // spills past its bounds, and a LazyRow clips to its own edges.
      contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
    ) {
      items(items, key = { it.key }) { item ->
        MediaCard(item = item, onClick = { onItemClick(item) })
      }
    }
  }
}

/** @param hint the smaller second line: what to do about [text], when there is something to do. */
@Composable
fun CenteredMessage(text: String, hint: String? = null) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    EmptyState(title = text, hint = hint)
  }
}

/**
 * A failure and, where the caller can offer one, a focusable way to try again.
 *
 * The Retry button is the only focusable thing on a failed screen, so it is also what stops the
 * D-pad from being dead there - which is why it is shared rather than re-styled per screen.
 */
@Composable
fun FailureMessage(message: String, onRetry: (() -> Unit)?) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
        Icons.Filled.Warning,
        // Decorative: the message under it is the actual content.
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(44.dp).padding(bottom = 14.dp),
      )
      Text(
        text = message,
        style = MaterialTheme.typography.titleMedium,
        color = NebulaPalette.TextHigh,
      )
      if (onRetry != null) {
        NebulaButton(
          text = "Retry",
          onClick = onRetry,
          style = NebulaButtonStyle.Primary,
          modifier = Modifier.padding(top = 20.dp),
        )
      }
    }
  }
}

@Composable
fun CenteredLoading(text: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        trackColor = NebulaPalette.Outline,
        strokeWidth = 3.dp,
        modifier = Modifier.size(40.dp),
      )
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
        modifier = Modifier.padding(top = 18.dp),
      )
    }
  }
}

/** One button in a [CardOptionsDialog]. */
class CardAction(val label: String, val onClick: () -> Unit)

/**
 * What a long press on a managed card offers.
 *
 * Long press rather than a visible menu button: these surfaces are posters, and every TV app the
 * viewer already owns puts card management behind a held OK. Cancel is appended here rather than
 * passed in, so no caller can ship a dialog with no way out of it but BACK.
 *
 * Shared rather than per-screen because the affordance has to be identical everywhere it exists:
 * a held OK that opens a differently shaped sheet on Search than on Home is a different gesture as
 * far as the viewer is concerned.
 *
 * @param focusKey re-aims the first-option focus request when the dialog switches cards.
 */
@Composable
fun CardOptionsDialog(
  title: String,
  message: String,
  focusKey: Any,
  focusLabel: String,
  actions: List<CardAction>,
  onDismiss: () -> Unit,
) {
  val firstOption = rememberInitialFocusTarget()

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
  ) {
    RequestInitialFocus(target = firstOption, key = focusKey, label = focusLabel)

    Surface(
      shape = NebulaShapes.extraLarge,
      colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Surface),
      // A hairline is the only thing separating a dialog from the page behind it once both are
      // near-black; without it the sheet has no edge at all on a dark scene.
      border = Border(
        border = BorderStroke(1.dp, NebulaPalette.Outline),
        shape = NebulaShapes.extraLarge,
      ),
      modifier = Modifier.width(540.dp),
    ) {
      Column(
        modifier = Modifier.padding(34.dp),
        verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = NebulaPalette.TextMuted,
          modifier = Modifier.padding(bottom = 6.dp),
        )
        actions.forEachIndexed { index, action ->
          NebulaButton(
            text = action.label,
            onClick = action.onClick,
            // The first option is the one the dialog exists for, so it carries the weight.
            style = if (index == 0) NebulaButtonStyle.Primary else NebulaButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth()
              .initialFocusTarget(if (index == 0) firstOption else null),
          )
        }
        NebulaButton(
          text = "Cancel",
          onClick = onDismiss,
          style = NebulaButtonStyle.Ghost,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
fun <T> LoadStateContent(
  state: LoadState<T>,
  loadingText: String = "Loading...",
  onRetry: (() -> Unit)? = null,
  content: @Composable (T) -> Unit,
) {
  when (state) {
    is LoadState.Loading -> CenteredLoading(loadingText)
    is LoadState.Failed -> FailureMessage(state.message, onRetry)
    is LoadState.Ready -> content(state.value)
  }
}
