package com.stremioshell.host.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.ui.ArtworkImage
import com.stremioshell.host.tv.ui.BadgeTone
import com.stremioshell.host.tv.ui.NebulaBadge
import com.stremioshell.host.tv.ui.NebulaProgressBar
import com.stremioshell.host.tv.ui.PosterFallback
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace

/** The episode an up-next card is offering. */
data class UpNextTarget(
  val season: Int,
  val episode: Int,
  /** TMDB's episode name, which is missing often enough to be allowed to be blank. */
  val name: String,
  /**
   * TMDB's episode still. Carried this far because the card is the one moment the app asks a
   * viewer to commit to another forty-five minutes, and every other surface in Nebula leads with
   * an image while this one used to be five stacked text runs. Nullable: TMDB is missing stills
   * for a real share of episodes.
   */
  val stillUrl: String? = null,
)

/**
 * A next-episode lookup or stream resolution that did not complete.
 *
 * Kept as a type instead of a second Boolean so the card cannot be in an
 * unexplained "failed" state. [message] is app-authored copy; network diagnostics
 * and stream URLs do not belong on the living-room surface.
 */
data class UpNextFailure(
  val message: String = "Couldn't start the next episode.",
)

/**
 * What the card is showing.
 *
 * [secondsLeft] null is the asked-not-told form: nothing starts on its own, the
 * card waits for OK. [resolving] covers the gap between "play it" and a first
 * frame, which is a couple of seconds of addon and debrid latency the viewer
 * would otherwise spend looking at a card that ignored their press.
 *
 * [failure] keeps a failed resolution on the same card so the viewer can retry
 * rather than being thrown into a different screen. Failure takes precedence
 * over [resolving] and [secondsLeft], which also makes a late callback unable to
 * leave a spinner or countdown visible over the error.
 *
 * [progress] is the countdown as a real fraction rather than as
 * `secondsLeft / total`: the tick runs four times a second precisely so the card
 * is never a whole second stale, and deriving the bar from the whole-second
 * number threw three of every four of those ticks away and made the bar lurch a
 * thirtieth of its width once a second instead of draining.
 */
data class UpNextCardState(
  val seriesTitle: String,
  val target: UpNextTarget,
  val secondsLeft: Int?,
  val resolving: Boolean,
  val progress: Float = 1f,
  val failure: UpNextFailure? = null,
)

/** Actions exposed through accessibility without creating a D-pad focus target. */
enum class UpNextCardAction {
  Play,
  Retry,
  Cancel,
}

/** The card's text, out here so the wording is testable without a composition. */
object UpNextText {
  /** "S2E4  Hide and Seek" - for the OSD and the logs, where there is no badge. */
  fun episodeLine(target: UpNextTarget): String {
    val number = "S${target.season}E${target.episode}"
    val name = target.name.trim()
    return if (name.isEmpty()) number else "$number  $name"
  }

  /**
   * The card's headline, which is the name alone.
   *
   * The card already carries the number in an accent badge, and this used to be
   * [episodeLine] - so the number was in the chip *and* buried at the head of the
   * title, which is the exact thing the badge exists to prevent. Falls back to the
   * number when TMDB has no name, so the line is never blank.
   */
  fun titleLine(target: UpNextTarget): String =
    target.name.trim().ifEmpty { episodeLine(target) }

  fun statusLine(state: UpNextCardState): String = when {
    state.failure != null -> state.failure.message.trim().ifEmpty { UpNextFailure().message }
    state.resolving -> "Finding a stream..."
    state.secondsLeft != null -> "Playing in ${state.secondsLeft}s"
    else -> "Press OK to play"
  }

  fun hintLine(state: UpNextCardState): String = when {
    state.failure != null -> "OK retry   |   BACK stop"
    state.resolving -> "BACK to stop"
    else -> "OK play now   |   BACK stop"
  }

  /**
   * The operations an accessibility service should offer for this state.
   *
   * Resolving deliberately has no Play action: accepting it twice is not useful,
   * and the activity already guards against parallel lookups. Cancel remains
   * available in every state.
   */
  fun availableActions(state: UpNextCardState): List<UpNextCardAction> = when {
    state.failure != null -> listOf(UpNextCardAction.Retry, UpNextCardAction.Cancel)
    state.resolving -> listOf(UpNextCardAction.Cancel)
    else -> listOf(UpNextCardAction.Play, UpNextCardAction.Cancel)
  }

  fun actionLabel(action: UpNextCardAction, target: UpNextTarget): String = when (action) {
    UpNextCardAction.Play -> "Play ${episodeLine(target)} now"
    UpNextCardAction.Retry -> "Retry ${episodeLine(target)}"
    UpNextCardAction.Cancel -> "Cancel up next"
  }

  /**
   * A bounded announcement stream for a card whose progress itself updates four
   * times a second. A live region on the whole merged card made every progress
   * update eligible to interrupt TalkBack; these milestones announce the offer
   * and urgency without drowning out the episode title or the available actions.
   */
  fun accessibilityAnnouncement(state: UpNextCardState): String? {
    val prefix = "Up next, ${episodeLine(state.target)}."
    return when {
      state.failure != null ->
        "$prefix ${statusLine(state)} Press OK to retry, or BACK to stop."
      state.resolving -> "$prefix ${statusLine(state)} Press BACK to stop."
      state.secondsLeft == null -> "$prefix ${statusLine(state)}"
      state.secondsLeft in ANNOUNCED_COUNTDOWN_SECONDS -> "$prefix ${statusLine(state)}"
      else -> null
    }
  }

  private val ANNOUNCED_COUNTDOWN_SECONDS = setOf(15, 10, 5, 3, 2, 1)
}

/**
 * The end-of-episode offer, over the paused last frame.
 *
 * Deliberately holds no focusable node: the keys that drive it are handled in the
 * activity's [MpvPlayerActivity.onKeyDown] alongside the rest of the player's
 * transport, and a Compose button here would put a focus trap over a surface that
 * has never had focus to hand it. Accessibility services receive equivalent
 * custom actions from the card's semantics node; semantics do not participate in
 * Compose's D-pad focus traversal.
 */
@Composable
fun BoxScope.UpNextCard(
  state: UpNextCardState,
  onPlay: (() -> Unit)? = null,
  onCancel: (() -> Unit)? = null,
  onRetry: (() -> Unit)? = onPlay,
) {
  val view = LocalView.current
  val accessibilityAnnouncement = UpNextText.accessibilityAnnouncement(state)
  LaunchedEffect(accessibilityAnnouncement) {
    accessibilityAnnouncement?.let(view::announceForAccessibility)
  }
  val accessibilityActions = UpNextText.availableActions(state).mapNotNull { action ->
    val callback = when (action) {
      UpNextCardAction.Play -> onPlay
      UpNextCardAction.Retry -> onRetry
      UpNextCardAction.Cancel -> onCancel
    } ?: return@mapNotNull null
    CustomAccessibilityAction(
      label = UpNextText.actionLabel(action, state.target),
      action = {
        callback()
        true
      },
    )
  }
  Row(
    modifier = Modifier
      .align(Alignment.BottomEnd)
      .padding(NebulaDimens.ScreenEdge)
      .width(CARD_WIDTH)
      .background(NebulaPalette.Surface, NebulaShapes.large)
      .border(1.dp, NebulaPalette.Outline, NebulaShapes.large)
      .padding(NebulaSpace.lg)
      // One accessibility node instead of six separate text stops. This node is
      // intentionally not focusable: ordinary D-pad routing stays in the
      // activity while TalkBack/Switch Access can invoke the custom actions.
      // Announcements are explicit milestones above; a live region here would
      // be invalidated by every 250ms progress update.
      .semantics(mergeDescendants = true) {
        customActions = accessibilityActions
      },
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.md),
  ) {
    // The cheapest image in the app: one 16:9 RGB_565 decode through the tuned
    // loader, at the one moment nothing is scrolling and the video has stopped.
    ArtworkImage(
      url = state.target.stillUrl,
      contentDescription = null,
      modifier = Modifier
        .size(width = STILL_WIDTH, height = STILL_HEIGHT)
        .clip(NebulaDimens.PosterShape),
      // Not the broken-image glyph: a missing still is routine on TMDB, and the
      // episode's name in the slot is information rather than an apology.
      fallback = { PosterFallback(UpNextText.titleLine(state.target)) },
    )
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "Up next",
          style = MaterialTheme.typography.labelMedium,
          color = NebulaPalette.TextFaint,
          modifier = Modifier.weight(1f),
        )
        // The number is the fastest thing to read from the sofa, so it gets the
        // accent chip rather than being buried at the head of the title below.
        NebulaBadge("S${state.target.season}E${state.target.episode}", tone = BadgeTone.Accent)
      }
      Text(
        UpNextText.titleLine(state.target),
        style = MaterialTheme.typography.titleLarge,
        color = NebulaPalette.TextHigh,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        state.seriesTitle,
        style = MaterialTheme.typography.bodySmall,
        color = NebulaPalette.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        modifier = Modifier.padding(top = NebulaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
      ) {
        // A resolve is an addon fetch plus a debrid round trip - routinely two to
        // five seconds during which the countdown bar has gone and mpv is not yet
        // buffering, so without something moving here the card is indistinguishable
        // from a press that was dropped.
        if (state.resolving && state.failure == null) {
          androidx.compose.material3.CircularProgressIndicator(
            color = NebulaPalette.VioletBright,
            trackColor = NebulaPalette.Outline,
            strokeWidth = 3.dp,
            modifier = Modifier.size(18.dp),
          )
        }
        Text(
          UpNextText.statusLine(state),
          style = MaterialTheme.typography.titleMedium,
          color = if (state.failure == null) NebulaPalette.VioletBright else NebulaPalette.Danger,
        )
      }
      // The bar is the countdown made watchable: "Playing in 9s" tells a viewer
      // reading it how long they have, a draining bar tells one who is not. Height
      // is reserved either way so the card does not resize when it goes.
      Box(modifier = Modifier.fillMaxWidth().height(COUNTDOWN_SLOT).padding(top = NebulaSpace.xxs)) {
        if (state.secondsLeft != null && !state.resolving && state.failure == null) {
          NebulaProgressBar(
            progress = state.progress,
            height = 6.dp,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      Text(
        UpNextText.hintLine(state),
        style = MaterialTheme.typography.labelMedium,
        color = NebulaPalette.TextFaint,
      )
    }
  }
}

/** Wide enough for a 16:9 still beside two lines of episode title at [MaterialTheme]'s titleLarge. */
private val CARD_WIDTH = 620.dp
private val STILL_WIDTH = 200.dp
private val STILL_HEIGHT = 113.dp

/** 6dp bar plus its 4dp lead-in, held whether or not the bar is in it. */
private val COUNTDOWN_SLOT = 10.dp
