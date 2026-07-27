package com.stremioshell.host.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

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
      .padding(48.dp)
      .width(440.dp)
      .background(Color(0xE6101010))
      .padding(horizontal = 28.dp, vertical = 22.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text("Up next", style = MaterialTheme.typography.labelLarge, color = Color(0x99FFFFFF))
    Text(
      UpNextText.episodeLine(state.target),
      style = MaterialTheme.typography.titleLarge,
      color = Color.White,
    )
    Text(
      state.seriesTitle,
      style = MaterialTheme.typography.bodySmall,
      color = Color(0xCCFFFFFF),
    )
    Text(
      UpNextText.statusLine(state),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(top = 8.dp),
    )
    Text(
      UpNextText.hintLine(state),
      style = MaterialTheme.typography.bodySmall,
      color = Color(0x99FFFFFF),
    )
  }
}
