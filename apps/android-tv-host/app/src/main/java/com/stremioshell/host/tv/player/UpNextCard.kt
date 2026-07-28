package com.stremioshell.host.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.ui.BadgeTone
import com.stremioshell.host.tv.ui.NebulaBadge
import com.stremioshell.host.tv.ui.NebulaProgressBar
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes

/** The episode an up-next card is offering. */
data class UpNextTarget(
  val season: Int,
  val episode: Int,
  /** TMDB's episode name, which is missing often enough to be allowed to be blank. */
  val name: String,
)

/**
 * What the card is showing.
 *
 * [secondsLeft] null is the asked-not-told form: nothing starts on its own, the
 * card waits for OK. [resolving] covers the gap between "play it" and a first
 * frame, which is a couple of seconds of addon and debrid latency the viewer
 * would otherwise spend looking at a card that ignored their press.
 */
data class UpNextCardState(
  val seriesTitle: String,
  val target: UpNextTarget,
  val secondsLeft: Int?,
  val resolving: Boolean,
)

/** The card's text, out here so the wording is testable without a composition. */
object UpNextText {
  fun episodeLine(target: UpNextTarget): String {
    val number = "S${target.season}E${target.episode}"
    val name = target.name.trim()
    return if (name.isEmpty()) number else "$number  $name"
  }

  fun statusLine(state: UpNextCardState): String = when {
    state.resolving -> "Finding a stream..."
    state.secondsLeft != null -> "Playing in ${state.secondsLeft}s"
    else -> "Press OK to play"
  }

  fun hintLine(state: UpNextCardState): String =
    if (state.resolving) "BACK to stop" else "OK play now   |   BACK stop"
}

/**
 * The end-of-episode offer, over the paused last frame.
 *
 * Deliberately holds no focusable node: the keys that drive it are handled in the
 * activity's [MpvPlayerActivity.onKeyDown] alongside the rest of the player's
 * transport, and a Compose button here would put a focus trap over a surface that
 * has never had focus to hand it.
 */
@Composable
fun BoxScope.UpNextCard(state: UpNextCardState) {
  Column(
    modifier = Modifier
      .align(Alignment.BottomEnd)
      .padding(NebulaDimens.ScreenEdge)
      .width(460.dp)
      .background(NebulaPalette.Surface, NebulaShapes.large)
      .border(1.dp, NebulaPalette.Outline, NebulaShapes.large)
      .padding(horizontal = 28.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
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
      NebulaBadge("S${state.target.season}E${state.target.episode}", BadgeTone.Accent)
    }
    Text(
      UpNextText.episodeLine(state.target),
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
    Text(
      UpNextText.statusLine(state),
      style = MaterialTheme.typography.titleMedium,
      color = NebulaPalette.VioletBright,
      modifier = Modifier.padding(top = 6.dp),
    )
    // The bar is the countdown made watchable: "Playing in 9s" tells a viewer
    // reading it how long they have, a draining bar tells one who is not.
    val seconds = state.secondsLeft
    if (seconds != null) {
      NebulaProgressBar(
        progress = seconds * 1000f / UpNextPolicy.COUNTDOWN_MS,
        height = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      )
    }
    Text(
      UpNextText.hintLine(state),
      style = MaterialTheme.typography.labelMedium,
      color = NebulaPalette.TextFaint,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}
