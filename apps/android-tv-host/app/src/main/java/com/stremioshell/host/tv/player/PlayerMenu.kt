package com.stremioshell.host.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The menu's three sections. */
enum class PlayerMenuTab(val label: String) {
  Audio("Audio"),
  Subtitles("Subtitles"),
  Options("Options"),
}

/** Everything the menu renders, so the composable itself holds no playback state. */
data class PlayerMenuState(
  val tab: PlayerMenuTab,
  val audioRows: List<TrackRow>,
  val subtitleRows: List<TrackRow>,
  val speed: Double,
  val subtitleSize: SubtitleSize,
  val audioDelaySec: Double,
  val subtitleDelaySec: Double,
)

/**
 * What the menu can ask the player to do. The step callbacks take a signed
 * number of steps rather than a value, so the ladders and their limits live in
 * [PlaybackSpeeds], [SubtitleSize] and [DelaySteps] and are testable there.
 */
class PlayerMenuActions(
  val onTab: (PlayerMenuTab) -> Unit,
  /** The chosen audio track's mpv id. */
  val onSelectAudio: (Int) -> Unit,
  /** The chosen subtitle track's mpv id, or null for "Off". */
  val onSelectSubtitle: (Int?) -> Unit,
  val onSpeedStep: (Int) -> Unit,
  val onSubtitleSizeStep: (Int) -> Unit,
  val onAudioDelayStep: (Int) -> Unit,
  val onSubtitleDelayStep: (Int) -> Unit,
)

/**
 * The in-player track and options menu: a side panel over the video, focusable
 * with the D-pad alone.
 *
 * Replaces what MENU used to do, which was `cycle sub` — on a remux with fifteen
 * subtitle tracks that meant up to sixteen blind presses to get back to where you
 * started, and audio tracks were only reachable from a key most TV remotes do not
 * have at all.
 *
 * A panel rather than a full-screen sheet so the picture and the OSD's position
 * stay visible while a track is chosen: picking the right audio track is mostly a
 * matter of listening to the result.
 */
@Composable
fun BoxScope.PlayerMenu(state: PlayerMenuState, actions: PlayerMenuActions) {
  // Where focus goes when the menu opens or the section changes. Keyed on the tab
  // so switching sections lands on that section's current value, and so a track
  // list refreshing under the viewer (mpv re-reports the list after a selection)
  // does not yank focus back out from under them.
  val contentFocus = remember(state.tab) { FocusRequester() }
  val tabFocus = remember { FocusRequester() }
  // Whether the section has anything to focus at all. A file with no subtitle
  // tracks renders a message and nothing focusable, and the request below has to
  // fall back to the tabs rather than spin.
  val contentFocusable = when (state.tab) {
    PlayerMenuTab.Audio -> state.audioRows.isNotEmpty()
    PlayerMenuTab.Subtitles -> state.subtitleRows.isNotEmpty()
    PlayerMenuTab.Options -> true
  }

  Column(
    modifier = Modifier
      .align(Alignment.CenterEnd)
      .fillMaxHeight()
      .fillMaxWidth(PANEL_WIDTH_FRACTION)
      .background(Color(0xE6000000))
      .padding(horizontal = 24.dp, vertical = 20.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      PlayerMenuTab.entries.forEach { tab ->
        TabButton(
          tab = tab,
          active = tab == state.tab,
          onFocused = { actions.onTab(tab) },
          modifier = Modifier
            .weight(1f)
            .then(if (tab == state.tab) Modifier.focusRequester(tabFocus) else Modifier),
        )
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when (state.tab) {
        PlayerMenuTab.Audio -> TrackSection(
          rows = state.audioRows,
          emptyMessage = "This file has no audio tracks.",
          focusRequester = contentFocus,
          onSelect = { row -> row.trackId?.let(actions.onSelectAudio) },
        )
        PlayerMenuTab.Subtitles -> TrackSection(
          rows = state.subtitleRows,
          emptyMessage = "This file has no subtitle tracks.",
          focusRequester = contentFocus,
          onSelect = { row -> actions.onSelectSubtitle(row.trackId) },
        )
        PlayerMenuTab.Options -> OptionsSection(
          state = state,
          actions = actions,
          focusRequester = contentFocus,
        )
      }
    }
    Text(
      "OK selects   |   UP/DOWN moves   |   BACK closes",
      modifier = Modifier.padding(top = 12.dp),
      color = Color(0x99FFFFFF),
      style = MaterialTheme.typography.bodySmall,
    )
  }

  // Focus has to land inside the panel: while the menu is open the player
  // swallows the D-pad keys Compose does not consume, so a menu nothing in has
  // focus would be a menu the remote cannot drive. The retry across frames is
  // because a requester is only usable once its node is attached, which is not
  // guaranteed to have happened by the time this effect first runs.
  //
  // Deliberately not keyed on the section: switching sections is done by walking
  // focus onto a tab, and pulling focus down into that section's content would
  // take the viewer straight back off the tab they just moved to. Re-running when
  // the section gains its first focusable row is what puts focus in the list for
  // a menu opened before mpv had read the track list.
  LaunchedEffect(contentFocusable) {
    if (contentFocusable) {
      repeat(FOCUS_ATTEMPTS) {
        if (runCatching { contentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        withFrameNanos {}
      }
    }
    // Nothing focusable in the section (an empty track list): the tabs are still
    // focusable, and are also the way out of the empty section.
    repeat(FOCUS_ATTEMPTS) {
      if (runCatching { tabFocus.requestFocus() }.isSuccess) return@LaunchedEffect
      withFrameNanos {}
    }
  }
}

@Composable
private fun TabButton(
  tab: PlayerMenuTab,
  active: Boolean,
  onFocused: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var focused by remember { mutableStateOf(false) }
  val background = when {
    focused -> MaterialTheme.colorScheme.primary
    active -> Color(0x33FFFFFF)
    else -> Color(0x1AFFFFFF)
  }
  Box(
    modifier = modifier
      // Focus, not OK, switches section: on a TV the section a viewer has walked
      // to is the section they are looking at, and making them confirm it first
      // is a press that does nothing they did not already ask for.
      .onFocusChanged { if (it.isFocused) onFocused() }
      .clickable(onClick = onFocused)
      .background(background)
      .padding(vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      tab.label,
      color = if (active || focused) Color.White else Color(0xB3FFFFFF),
      style = MaterialTheme.typography.titleSmall,
      maxLines = 1,
    )
  }
}

@Composable
private fun TrackSection(
  rows: List<TrackRow>,
  emptyMessage: String,
  focusRequester: FocusRequester,
  onSelect: (TrackRow) -> Unit,
) {
  if (rows.isEmpty()) {
    Text(
      emptyMessage,
      color = Color(0xCCFFFFFF),
      style = MaterialTheme.typography.bodyMedium,
    )
    return
  }
  val focusIndex = MpvTracks.initialFocusIndex(rows)
  // The list opens scrolled to the selected row. Not cosmetic: a lazy list only
  // composes what is visible, so on a fifteen-track remux the row the focus
  // request below aims at would not exist yet, and focus would fall back to the
  // tabs instead of landing on the track that is playing.
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusIndex)
  LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    itemsIndexed(rows, key = { _, row -> row.trackId ?: OFF_ROW_KEY }) { index, row ->
      MenuRow(
        onClick = { onSelect(row) },
        modifier = if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier,
      ) { focused ->
        SelectionMarker(row.selected)
        Column(modifier = Modifier.weight(1f)) {
          Text(
            row.label,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (row.detail.isNotBlank()) {
            Text(
              row.detail,
              color = if (focused) Color(0xE6FFFFFF) else Color(0x99FFFFFF),
              style = MaterialTheme.typography.bodySmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun OptionsSection(
  state: PlayerMenuState,
  actions: PlayerMenuActions,
  focusRequester: FocusRequester,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    AdjusterRow(
      label = "Playback speed",
      value = PlaybackSpeeds.label(state.speed),
      onStep = actions.onSpeedStep,
      decreaseFocus = focusRequester,
    )
    AdjusterRow(
      label = "Subtitle size",
      value = state.subtitleSize.label,
      onStep = actions.onSubtitleSizeStep,
    )
    AdjusterRow(
      label = "Audio delay",
      value = DelaySteps.label(state.audioDelaySec),
      onStep = actions.onAudioDelayStep,
    )
    AdjusterRow(
      label = "Subtitle delay",
      value = DelaySteps.label(state.subtitleDelaySec),
      onStep = actions.onSubtitleDelayStep,
    )
    Text(
      "Delays are per file; the speed lasts until you leave the player.",
      color = Color(0x99FFFFFF),
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/**
 * A "-  value  +" row. Two focusables per setting, so LEFT/RIGHT adjusts and
 * UP/DOWN walks between settings — which is what makes every option reachable
 * without the player having to intercept LEFT/RIGHT and route it at whichever
 * row happens to be focused.
 */
@Composable
private fun AdjusterRow(
  label: String,
  value: String,
  onStep: (Int) -> Unit,
  decreaseFocus: FocusRequester? = null,
) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(
      label,
      modifier = Modifier.weight(1f),
      color = Color.White,
      style = MaterialTheme.typography.titleSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    StepButton(
      text = "-",
      onClick = { onStep(-1) },
      modifier = if (decreaseFocus != null) Modifier.focusRequester(decreaseFocus) else Modifier,
    )
    Text(
      value,
      modifier = Modifier.width(96.dp).padding(horizontal = 8.dp),
      color = Color.White,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
    )
    StepButton(text = "+", onClick = { onStep(1) })
  }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  var focused by remember { mutableStateOf(false) }
  Box(
    modifier = modifier
      .width(44.dp)
      .onFocusChanged { focused = it.isFocused }
      .clickable(onClick = onClick)
      .background(if (focused) MaterialTheme.colorScheme.primary else Color(0x26FFFFFF))
      .padding(vertical = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
  }
}

/**
 * The focusable row shape shared by the track lists. Plain foundation rather than
 * a tv-material Card: the row has to sit legibly on top of video, and the OSD's
 * flat translucent panels are what the rest of the player already looks like.
 */
@Composable
private fun MenuRow(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  Row(
    modifier = modifier
      .fillMaxWidth()
      // Before the modifier that makes the row focusable, which is the only
      // order in which this observes that row's focus.
      .onFocusChanged { focused = it.isFocused }
      .clickable(onClick = onClick)
      .background(if (focused) MaterialTheme.colorScheme.primary else Color(0x1AFFFFFF))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    content(focused)
  }
}

/** Which track is playing right now, in a form that survives a photograph. */
@Composable
private fun SelectionMarker(selected: Boolean) {
  Text(
    if (selected) "•" else " ",
    modifier = Modifier.width(20.dp),
    color = Color.White,
    style = MaterialTheme.typography.titleMedium,
  )
}

private const val PANEL_WIDTH_FRACTION = 0.42f
private const val OFF_ROW_KEY = Int.MIN_VALUE
private const val FOCUS_ATTEMPTS = 5
