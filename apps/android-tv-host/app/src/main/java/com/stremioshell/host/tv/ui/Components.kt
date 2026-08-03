package com.stremioshell.host.tv.ui

import android.util.Log
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.stremioshell.host.R
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrushVertical
import com.stremioshell.host.tv.ui.theme.NebulaBottomScrim
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaMotion
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.tv.ui.theme.nebulaCardBorder
import com.stremioshell.host.tv.ui.theme.nebulaCardGlow
import com.stremioshell.host.tv.diagnostics.PerformanceTrace
import kotlinx.coroutines.delay

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
    var firstRequest = true
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
        runCatching {
          if (firstRequest) {
            firstRequest = false
            PerformanceTrace.section("focus.$label") { target.requester.requestFocus() }
          } else {
            target.requester.requestFocus()
          }
        }.onFailure { lastFailure = it }
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
 *
 * The default [fallback] is a broken-image glyph rather than nothing. Every caller except the
 * poster rows passed no fallback at all, so a dead URL left a bare grey slab - a cast row with two
 * missing headshots was two grey holes, indistinguishable from two that were still decoding.
 */
@Composable
fun ArtworkImage(
  url: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  fallback: @Composable BoxScope.() -> Unit = { MissingArtworkGlyph() },
) {
  var failed by remember(url) { mutableStateOf(false) }
  if (url == null || failed) {
    Box(
      modifier = modifier.background(NebulaPalette.SurfaceVariant),
      contentAlignment = Alignment.Center,
      content = fallback,
    )
  } else {
    AsyncImage(
      model = url,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      // Solid tonal fill instead of the bare theme background, so a card does not pop from an
      // empty rectangle to a full poster mid-scroll. Quieter than the failed fill above, so
      // "still decoding" and "gave up" are not the same rectangle.
      placeholder = remember { ColorPainter(NebulaPalette.Surface) },
      onError = { failed = true },
      modifier = modifier,
    )
  }
}

/** What a slot with no artwork shows, when the caller has nothing better to put there. */
@Composable
private fun MissingArtworkGlyph() {
  Icon(
    Icons.Filled.Warning,
    contentDescription = null,
    tint = NebulaPalette.TextDisabled,
    modifier = Modifier.size(NebulaIcon.md),
  )
}

/**
 * A poster-shaped slot for a title with no artwork.
 *
 * TMDB is missing art for a real share of obscure titles, so this is not a rare state - and it
 * existed twice with different values and no text alignment, so a wrapped title was ragged inside
 * a centred block on a 144dp card. A gradient rather than a flat fill so an artless card still
 * reads as a card rather than as a hole in the row.
 */
@Composable
fun PosterFallback(title: String, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(listOf(NebulaPalette.SurfaceVariant, NebulaPalette.Surface)),
      ),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = title,
      maxLines = 4,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.titleSmall,
      color = NebulaPalette.TextHigh,
      modifier = Modifier.padding(horizontal = 10.dp),
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
  /** 0f..1f watch position, drawn across the foot of the poster. Null when there is none. */
  progress: Float? = null,
) {
  // Hoisted so the caption can answer to it. Twenty cards in a rail all captioned at full
  // brightness is what makes a poster wall fight the UI; dimming the unfocused ones is the
  // cheapest thing that makes a row read as "one selected thing among many". A colour-only
  // change recomposes two Text nodes on the card that gained focus and the one that lost it -
  // no measure, no layout, no effect on the lazy list. Deliberately not animated: every other
  // focus treatment in the app is instant.
  var focused by remember { mutableStateOf(false) }
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
        .onFocusChanged { focused = it.isFocused }
        .semantics(mergeDescendants = true) {
          contentDescription = A11yLabels.card(item.title, subtitle, manageable = onLongClick != null)
        },
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        ArtworkImage(
          url = item.posterUrl,
          // Decorative: the card above already says the title, and a second description here would
          // make every poster announce its title twice.
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
        ) {
          // Artless titles get the title set as a poster rather than as a caption on a grey slab,
          // so a rail with a few missing images still reads as a rail.
          PosterFallback(item.title)
        }
        if (progress != null && progress > 0f) {
          // The bar needs something to sit on: over a pale poster foot a thin violet line simply
          // disappears.
          Box(
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .height(40.dp)
              .background(NebulaBottomScrim),
          )
          NebulaProgressBar(
            progress = progress,
            height = 4.dp,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .padding(horizontal = NebulaSpace.xs)
              .padding(bottom = NebulaSpace.xs),
          )
        }
        // Long press manages the row this card is in, and nothing about a poster says so - it was
        // announced to TalkBack and to nobody else, so a viewer had no way to discover that a My
        // List card could be removed at all. Composed only while this one card holds focus.
        if (focused && onLongClick != null) {
          Box(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(6.dp)
              .size(22.dp)
              .background(NebulaPalette.Void.copy(alpha = 0.72f), CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              Icons.Filled.MoreVert,
              contentDescription = null,
              tint = NebulaPalette.TextHigh,
              modifier = Modifier.size(14.dp),
            )
          }
        }
      }
    }
    Text(
      text = item.title,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
      color = if (focused) NebulaPalette.TextHigh else NebulaPalette.TextMuted,
      // Clears the focused card's scaled-up bottom edge: 7.56dp of overhang plus 3.75dp of scaled
      // focus ring is 11.3dp, which the old 12dp cleared by 0.7dp.
      modifier = Modifier.padding(top = NebulaDimens.CardCaptionGap).width(NebulaDimens.PosterWidth)
        // Visual echo of the card's own description; left in the tree it is read a second time.
        .clearAndSetSemantics {},
    )
    if (subtitle != null) {
      Text(
        text = subtitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = if (focused) NebulaPalette.TextMuted else NebulaPalette.TextFaint,
        modifier = Modifier.padding(top = NebulaSpace.xxs).width(NebulaDimens.PosterWidth)
          .clearAndSetSemantics {},
      )
    }
  }
}

/**
 * How a rail scrolls when focus moves along it.
 *
 * Rails used to inherit whatever [LocalBringIntoViewSpec] was ambient, which meant the same widget
 * behaved two ways: on Home it picked up the vertical focus-line spec the LazyColumn provides -
 * applied to the horizontal axis too, pinning the focused card 18% in from the left and leaving a
 * sliced poster in the margin - while on Details and Search it used the default, which scrolls
 * only until the card's trailing edge is flush with the container, i.e. flush with the physical
 * screen edge, putting the focus ring inside the overscan margin.
 *
 * This scrolls the minimum needed to keep the focused card, its ring and its glow clear of both
 * edges by the screen margin, and does nothing at all when the card is already comfortably in
 * view. Consulted only on a focus move, so it costs nothing per frame.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberRailBringIntoViewSpec(): BringIntoViewSpec {
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

/**
 * A list handed to a row as one value Compose can compare.
 *
 * Strong skipping is off on this compiler, so a bare `List<T>` parameter is unstable and a row that
 * takes one is never skippable: it recomposes every time the screen around it does, which on Home
 * is once per rail for every watch-state update, paging append and artwork arrival. Wrapping the
 * list is what lets the row skip.
 *
 * The promise is the caller's to keep - nothing may mutate [items] once it is wrapped. Every list
 * this holds is a snapshot the ViewModel replaces wholesale rather than edits in place, which is
 * also why callers remember the wrapper against that snapshot: one allocation per data change
 * rather than one per frame.
 */
@Immutable
data class StableList<T>(val items: List<T>)

/**
 * The rail every browse surface is built from.
 *
 * No vertical `contentPadding`. Every rail used to carry 8dp of it under a comment saying a
 * LazyRow clips its children - it does not: foundation clips the scroll axis only and inflates the
 * cross axis by 30dp of elevation headroom, which is why a focused poster's ring and its 16dp glow
 * both render complete. Confirmed on a device. Removing it gives each rail 16dp of height back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(title: String, items: StableList<MediaItem>, onItemClick: (MediaItem) -> Unit) {
  val cards = items.items
  if (cards.isEmpty()) return
  Column(modifier = Modifier.fillMaxWidth()) {
    RailHeading(title)
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRailBringIntoViewSpec()) {
      LazyRow(
        modifier = Modifier.restoreRowFocus(),
        contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge),
        horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
      ) {
        // contentType, as on every other lazy list in the app: without it Compose is free to reuse
        // a card's subcomposition slot for something of another shape entirely.
        items(cards, key = { it.key }, contentType = { "card" }) { item ->
          MediaCard(item = item, onClick = { onItemClick(item) })
        }
      }
    }
  }
}

/**
 * A rail as it looks before its data arrives.
 *
 * Static: no shimmer, no animation, nothing invalidating a frame. That is deliberate on this
 * hardware - the indeterminate spinner this replaces re-drew every 16ms for the whole load, while
 * this composes once and never again. It is also strictly more informative, because it says how
 * much is coming and where it will be, so the page does not jump when it lands.
 */
@Composable
fun RailSkeleton(modifier: Modifier = Modifier, cards: Int = 6) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(
        start = NebulaDimens.ScreenEdge - NebulaDimens.TickInset,
        bottom = NebulaDimens.RailHeadingGap,
      ),
    ) {
      Box(
        modifier = Modifier
          .size(width = 4.dp, height = 18.dp)
          .background(NebulaAccentBrushVertical, RoundedCornerShape(2.dp)),
      )
      Box(
        modifier = Modifier
          .padding(start = NebulaSpace.sm)
          .size(width = 180.dp, height = 20.dp)
          .background(NebulaPalette.SurfaceVariant, NebulaShapes.extraSmall),
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
      modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge),
    ) {
      repeat(cards) {
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
}

/** Home's whole loading state: the shape of the page that is about to arrive. */
@Composable
fun HomeSkeleton() {
  Column(
    verticalArrangement = Arrangement.spacedBy(NebulaDimens.RailGap),
    modifier = Modifier
      .fillMaxSize()
      .padding(top = NebulaDimens.ScreenEdgeVertical)
      .clearAndSetSemantics {},
  ) {
    Box(
      modifier = Modifier
        .padding(horizontal = NebulaDimens.ScreenEdge)
        .fillMaxWidth()
        .height(NebulaDimens.HeroHeight)
        .background(NebulaPalette.Surface, NebulaShapes.large)
        .border(1.dp, NebulaPalette.Outline, NebulaShapes.large),
    )
    RailSkeleton()
    RailSkeleton()
  }
}

/** An [EmptyState] centred in whatever space it is given. */
@Composable
fun CenteredEmptyState(title: String, hint: String? = null, icon: ImageVector? = null) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    EmptyState(title = title, hint = hint, icon = icon)
  }
}

/**
 * A failure and, where the caller can offer one, a focusable way to try again.
 *
 * The Retry button is the only focusable thing on a failed screen, so it is also what stops the
 * D-pad from being dead there - which is why it is shared rather than re-styled per screen.
 */
@Composable
fun FailureMessage(
  message: String,
  onRetry: (() -> Unit)?,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val firstActionFocus = rememberInitialFocusTarget()
  val hasAction = actionLabel != null && onAction != null
  val primaryActionLabel = actionLabel.orEmpty()
  val retryLabel = stringResource(R.string.action_retry)
  RequestInitialFocus(
    target = firstActionFocus,
    key = message to actionLabel,
    label = "Failure recovery action",
    enabled = hasAction || onRetry != null,
  )
  Box(
    modifier = Modifier.fillMaxSize().padding(horizontal = NebulaDimens.ScreenEdge),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
        Icons.Filled.Warning,
        // Decorative: the message under it is the actual content.
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        // Padding outside size. The other way round - which is how this shipped - draws the glyph
        // into a box the padding has already shortened, so a "44dp" icon rendered at 30dp: a
        // smudge, on the one screen where the icon is the only non-text element.
        modifier = Modifier.padding(bottom = NebulaSpace.md).size(NebulaIcon.lg),
      )
      Text(
        text = message,
        style = MaterialTheme.typography.titleMedium,
        color = NebulaPalette.TextHigh,
        // These strings come straight off an HTTP or addon failure and are of unbounded length;
        // without a measure one runs past the overscan margin and is physically clipped.
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 640.dp).semantics {
          liveRegion = LiveRegionMode.Assertive
        },
      )
      if (hasAction || onRetry != null) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
          modifier = Modifier.padding(top = NebulaSpace.lg),
        ) {
          if (hasAction) {
            NebulaButton(
              text = primaryActionLabel,
              onClick = onAction!!,
              style = NebulaButtonStyle.Primary,
              modifier = Modifier
                .initialFocusTarget(firstActionFocus)
                .semantics {
                  contentDescription = A11yLabels.failureRecovery(message, primaryActionLabel)
                },
            )
          }
          if (onRetry != null) {
            NebulaButton(
              text = retryLabel,
              onClick = onRetry,
              style = if (hasAction) NebulaButtonStyle.Secondary else NebulaButtonStyle.Primary,
              icon = Icons.Filled.Refresh,
              modifier = Modifier
                .initialFocusTarget(firstActionFocus.takeIf { !hasAction })
                .semantics {
                  contentDescription = A11yLabels.failureRecovery(message, retryLabel)
                },
            )
          }
        }
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
        trackColor = NebulaPalette.TrackInactive,
        strokeWidth = 3.dp,
        // Round, so the arc's ends match a shape language that is fully rounded everywhere else.
        strokeCap = StrokeCap.Round,
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

/**
 * Holds [content] back until a load has been running long enough to be worth admitting to.
 *
 * Details, episodes and search results are frequently served from the in-memory cache in well
 * under a frame, and a spinner that appears and vanishes inside three of them reads as a flicker -
 * the screen looks broken rather than fast.
 *
 * Deliberately only a delay, with no minimum display time on the other side. A floor would have to
 * outlive this composable - the caller stops rendering it the instant the value is Ready - so it
 * would mean holding a finished screen back behind a skeleton, which trades a rare flash for a
 * guaranteed stall on every fast load. The delay alone removes the case that actually happens.
 */
@Composable
fun DelayedBusy(content: @Composable () -> Unit) {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(NebulaMotion.BusyDelayMs.toLong())
    visible = true
  }
  if (visible) content()
}

/**
 * One button in a [CardOptionsDialog].
 *
 * A data class for its equality: as a plain class every rebuild of the actions list was a new
 * identity, so [CardOptionsDialog] recomposed its whole sheet whenever the screen behind it did.
 *
 * @param destructive it removes something. Styled as [NebulaButtonStyle.Danger] rather than as the
 *   dialog's Primary - "Remove from My List" used to be a full violet button identical to Play.
 */
data class CardAction(
  val label: String,
  val destructive: Boolean = false,
  val onClick: () -> Unit,
)

/**
 * The sheet both of the app's dialogs are built from.
 *
 * Was copy-pasted between them, down to a byte-identical comment, and 20dp apart in width for no
 * reason. The glow is a real drop shadow rather than decoration: a 1dp hairline is not enough to
 * separate a near-black sheet from a near-black page at three metres, which is exactly what both
 * copies of that comment were complaining about.
 */
@Composable
fun NebulaDialogSurface(
  paneLabel: String = "Dialog",
  content: @Composable ColumnScope.() -> Unit,
) {
  // One layer on one surface for eight frames, draw-phase only, with nothing else on screen
  // animating - the one place in the app where an entrance is affordable.
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { shown = true }
  val appear by animateFloatAsState(
    targetValue = if (shown) 1f else 0f,
    animationSpec = NebulaMotion.enter(),
    label = "dialogAppear",
  )

  Surface(
    shape = NebulaShapes.extraLarge,
    colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Surface),
    border = Border(
      border = BorderStroke(2.dp, NebulaPalette.OutlineStrong),
      shape = NebulaShapes.extraLarge,
    ),
    glow = Glow(elevationColor = Color.Black, elevation = 24.dp),
    modifier = Modifier
      .width(NebulaDimens.DialogWidth)
      .semantics { paneTitle = paneLabel }
      .graphicsLayer {
        alpha = appear
        scaleX = 0.96f + 0.04f * appear
        scaleY = scaleX
      },
  ) {
    Column(
      modifier = Modifier.padding(NebulaDimens.DialogPadding),
      verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
      content = content,
    )
  }
}

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
  // An opening OK press must never also arm the only destructive action in the sheet. Prefer the
  // first action that cannot delete data; when every action is destructive, Cancel is the safe
  // landing place. The visual order stays unchanged, so the primary choice remains easy to scan.
  val initialActionIndex = actions.indexOfFirst { !it.destructive }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
  ) {
    RequestInitialFocus(target = firstOption, key = focusKey, label = focusLabel)

    NebulaDialogSurface(paneLabel = title) {
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
          style = when {
            // Removal never wears the Primary fill, whatever its position: on My List and
            // Continue Watching the first option deletes something, and it used to be styled
            // identically to Play.
            action.destructive -> NebulaButtonStyle.Danger
            // Otherwise the first option is the one the dialog exists for, so it carries the
            // weight.
            index == 0 -> NebulaButtonStyle.Primary
            else -> NebulaButtonStyle.Secondary
          },
          modifier = Modifier.fillMaxWidth()
            .initialFocusTarget(if (index == initialActionIndex) firstOption else null),
        )
      }
      NebulaButton(
        text = stringResource(R.string.action_cancel),
        onClick = onDismiss,
        style = NebulaButtonStyle.Ghost,
        modifier = Modifier.fillMaxWidth()
          .initialFocusTarget(firstOption.takeIf { initialActionIndex < 0 }),
      )
    }
  }
}

/**
 * @param loading what to show while the value is in flight. Defaults to the spinner, which is
 *   right for a small in-place wait; screens whose whole page is loading pass a skeleton instead,
 *   so the viewer sees the shape of what is arriving rather than a black rectangle with a ring in
 *   the middle of it.
 */
@Composable
fun <T> LoadStateContent(
  state: LoadState<T>,
  loadingText: String = "Loading…",
  onRetry: (() -> Unit)? = null,
  failureActionLabel: String? = null,
  onFailureAction: (() -> Unit)? = null,
  loading: @Composable () -> Unit = { CenteredLoading(loadingText) },
  content: @Composable (T) -> Unit,
) {
  when (state) {
    is LoadState.Loading -> {
      val loadingFocus = rememberInitialFocusTarget()
      RequestInitialFocus(
        target = loadingFocus,
        key = loadingText,
        label = loadingText,
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .initialFocusTarget(loadingFocus)
          .focusable()
          .semantics { contentDescription = loadingText },
      ) {
        DelayedBusy { loading() }
      }
    }
    is LoadState.Failed -> FailureMessage(
      message = state.message,
      onRetry = onRetry,
      actionLabel = failureActionLabel,
      onAction = onFailureAction,
    )
    is LoadState.Ready -> content(state.value)
  }
}

/**
 * A title, set as its own logotype where TMDB has one.
 *
 * A typeset title is the clearest tell that a browse UI is generic: every premium service leads
 * with the artwork the title was actually branded with, and TMDB holds one for the large majority
 * of things. The type is not a placeholder to be tolerated - it is the correct answer for
 * everything with no logo - so both forms occupy the same band and neither shifts what is under it.
 *
 * Height rather than width is what is constrained. Logos come in wildly different aspect ratios: a
 * long horizontal wordmark and a stacked emblem are both common, and pinning the width would render
 * one of them enormous. ContentScale.Fit inside a fixed-height box, aligned bottom-start, puts every
 * logotype on the same baseline the typeset title would have used.
 *
 * Falls back on a decode failure and not merely on a missing URL: these are transparent PNGs from a
 * CDN, and a title that renders as an empty gap is far worse than one merely set in Outfit.
 *
 * @param logoHeight the band the title occupies either way. Callers match it to the height their
 *   typeset fallback would take, so switching between the two never moves what is below.
 */
@Composable
fun TitleTreatment(
  title: String,
  logoUrl: String?,
  style: androidx.compose.ui.text.TextStyle,
  logoHeight: androidx.compose.ui.unit.Dp,
  modifier: Modifier = Modifier,
  maxLines: Int = 2,
  isHeading: Boolean = false,
) {
  var failed by remember(logoUrl) { mutableStateOf(false) }
  var loaded by remember(logoUrl) { mutableStateOf(false) }
  val titleModifier = if (isHeading) {
    modifier.semantics { heading() }
  } else {
    modifier
  }
  if (logoUrl == null || failed) {
    Text(
      title,
      style = style,
      color = NebulaPalette.TextHigh,
      // Uncapped, a three-line title silently pushes everything under it down the page.
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
      modifier = titleModifier,
    )
    return
  }
  Box(modifier = titleModifier.height(logoHeight), contentAlignment = Alignment.BottomStart) {
    // Keep a real title on screen while the transparent logo is still decoding. A cold cache used
    // to render this whole band empty for several frames, which looked like missing metadata
    // rather than loading artwork.
    if (!loaded) {
      Text(
        title,
        style = style,
        color = NebulaPalette.TextHigh,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
      )
    }
    AsyncImage(
      model = logoUrl,
      // The logo *is* the title, so it carries it rather than being decorative - the one piece of
      // artwork in the app whose contentDescription is not null.
      contentDescription = title,
      contentScale = ContentScale.Fit,
      alignment = Alignment.BottomStart,
      onSuccess = { loaded = true },
      onError = { failed = true },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
