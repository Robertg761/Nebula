package com.stremioshell.host.tv.ui

import android.util.Log
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.data.tmdb.MediaItem

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
  Column(modifier = modifier.width(140.dp)) {
    Card(
      onClick = onClick,
      onLongClick = onLongClick,
      // Modest focus scale so the poster does not grow over its own title.
      scale = CardDefaults.scale(focusedScale = 1.08f),
      modifier = Modifier.width(140.dp).height(200.dp),
    ) {
      ArtworkImage(
        url = item.posterUrl,
        contentDescription = item.title,
        modifier = Modifier.fillMaxSize(),
      ) {
        Text(item.title, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
      }
    }
    Text(
      text = item.title,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodySmall,
      // Clears the focused card's scaled-up bottom edge.
      modifier = Modifier.padding(top = 14.dp).width(140.dp),
    )
    if (subtitle != null) {
      Text(
        text = subtitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp).width(140.dp),
      )
    }
  }
}

@Composable
fun MediaRow(title: String, items: List<MediaItem>, onItemClick: (MediaItem) -> Unit) {
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(text, style = MaterialTheme.typography.titleMedium)
      if (hint != null) {
        Text(
          text = hint,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
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
      Text(message, style = MaterialTheme.typography.titleMedium)
      if (onRetry != null) {
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
          Text("Retry")
        }
      }
    }
  }
}

@Composable
fun CenteredLoading(text: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
      Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp))
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
