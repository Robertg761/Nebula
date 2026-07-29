package com.stremioshell.host.tv.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.ui.EmptyState
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaMotion
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace

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
  val audioOutput: AudioOutputMode,
  val audioDelaySec: Double,
  val subtitleDelaySec: Double,
  /** What a subtitles addon has for this file; see [ExternalSubtitlesState]. */
  val externalSubtitles: ExternalSubtitlesState = ExternalSubtitlesState.Unavailable,
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
  val onAudioOutputStep: (Int) -> Unit,
  val onAudioDelayStep: (Int) -> Unit,
  val onSubtitleDelayStep: (Int) -> Unit,
  /** Ask the subtitles addon what it has, or ask again after a failure. */
  val onFetchExternalSubtitles: () -> Unit = {},
  val onSelectExternalSubtitle: (ExternalSubtitleOption) -> Unit = {},
  /** Any key handled inside Compose still counts as recent viewer interaction. */
  val onInteraction: () -> Unit = {},
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

  Row(
    modifier = Modifier
      .align(Alignment.CenterEnd)
      .fillMaxHeight()
      .fillMaxWidth(PANEL_WIDTH_FRACTION)
      .background(NebulaPalette.Surface)
      .onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown) actions.onInteraction()
        false
      },
  ) {
    // The panel is opaque and the film behind it is not, so without an edge its
    // left side reads as a dark band in the picture rather than as a boundary.
    Box(
      modifier = Modifier
        .width(1.dp)
        .fillMaxHeight()
        .background(NebulaPalette.Outline),
    )
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxHeight()
        // Asymmetric on purpose. The left side's boundary is the 1dp divider above,
        // not the screen, so 24dp is right there. The right side *is* the physical
        // panel edge, and at 24dp a set with overscan on ate the "+" buttons of
        // every Options row - the first thing off the screen was the only way to
        // change any of them. Top and bottom get the same protection the rest of
        // the app's screens do.
        .padding(
          start = NebulaSpace.lg,
          end = NebulaDimens.ScreenEdge,
          top = NebulaDimens.ScreenEdgeVertical,
          bottom = NebulaDimens.ScreenEdgeVertical,
        ),
    ) {
      // One track, so the three sections read as one control with a position in
      // it rather than as three buttons that happen to sit together.
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .fillMaxWidth()
          .background(NebulaPalette.SurfaceVariant, NebulaShapes.medium)
          .padding(5.dp),
      ) {
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
      Spacer(modifier = Modifier.height(NebulaSpace.md))
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when (state.tab) {
          PlayerMenuTab.Audio -> TrackSection(
            rows = state.audioRows,
            emptyTitle = "No audio tracks",
            emptyHint = "This release has no selectable audio.",
            focusRequester = contentFocus,
            onSelect = { row -> row.trackId?.let(actions.onSelectAudio) },
          )
          PlayerMenuTab.Subtitles -> SubtitleSection(
            rows = state.subtitleRows,
            external = state.externalSubtitles,
            focusRequester = contentFocus,
            onSelect = { row -> actions.onSelectSubtitle(row.trackId) },
            onFetch = actions.onFetchExternalSubtitles,
            onSelectExternal = actions.onSelectExternalSubtitle,
          )
          PlayerMenuTab.Options -> OptionsSection(
            state = state,
            actions = actions,
            focusRequester = contentFocus,
          )
        }
      }
      // Only what is not obvious, and only what is true of the section on screen.
      // This printed "OK selects" under all three tabs, and on Options OK selects
      // nothing at all - the interaction there is LEFT/RIGHT on the step buttons.
      // A line of instructions that is wrong on a third of the panel is worse than
      // no line, and BACK is the one mapping a viewer cannot guess.
      Text(
        if (state.tab == PlayerMenuTab.Options) {
          "LEFT/RIGHT chooses −/+   |   OK adjusts   |   BACK closes"
        } else {
          "BACK closes"
        },
        modifier = Modifier.padding(top = NebulaSpace.sm),
        color = NebulaPalette.TextFaint,
        style = MaterialTheme.typography.labelMedium,
      )
    }
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
        runCatching { contentFocus.requestFocus() }
        withFrameNanos {}
      }
      return@LaunchedEffect
    }
    // Nothing focusable in the section (an empty track list): the tabs are still
    // focusable, and are also the way out of the empty section.
    repeat(FOCUS_ATTEMPTS) {
      runCatching { tabFocus.requestFocus() }
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
  val shape = NebulaShapes.small
  // Three states, three fills: the active section has to be obvious in a
  // photograph of the screen, including when focus has been walked away from it.
  val background = when {
    focused -> NebulaPalette.VioletBright
    active -> NebulaPalette.Violet.copy(alpha = 0.30f)
    else -> Color.Transparent
  }
  Box(
    modifier = modifier
      // Focus, not OK, switches section: on a TV the section a viewer has walked
      // to is the section they are looking at, and making them confirm it first
      // is a press that does nothing they did not already ask for.
      .onFocusChanged { if (it.isFocused) { focused = true; onFocused() } else focused = false }
      .clickable(onClick = onFocused)
      .semantics {
        selected = active
        role = Role.Tab
      }
      .background(background, shape)
      .border(
        width = if (focused || active) 2.dp else 0.dp,
        color = when {
          focused -> NebulaPalette.VioletBright
          active -> NebulaPalette.Violet
          else -> Color.Transparent
        },
        shape = shape,
      )
      .padding(vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      tab.label,
      color = when {
        focused -> NebulaPalette.OnAccent
        active -> NebulaPalette.TextHigh
        else -> NebulaPalette.TextMuted
      },
      style = MaterialTheme.typography.titleSmall,
      maxLines = 1,
    )
  }
}

@Composable
private fun TrackSection(
  rows: List<TrackRow>,
  emptyTitle: String,
  emptyHint: String,
  focusRequester: FocusRequester,
  onSelect: (TrackRow) -> Unit,
) {
  if (rows.isEmpty()) {
    // Was one line of grey text in the top-left corner of an otherwise empty
    // 400dp panel. The app already owns the treatment for "nothing here"; focus is
    // unaffected because the caller's `contentFocusable` is already false for this
    // case and the panel's focus request falls back to the tabs.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      EmptyState(title = emptyTitle, hint = emptyHint, icon = Icons.Filled.Warning)
    }
    return
  }
  val focusIndex = MpvTracks.initialFocusIndex(rows)
  // The list opens scrolled to the selected row. Not cosmetic: a lazy list only
  // composes what is visible, so on a fifteen-track remux the row the focus
  // request below aims at would not exist yet, and focus would fall back to the
  // tabs instead of landing on the track that is playing.
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusIndex)
  LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
    itemsIndexed(rows, key = { _, row -> row.trackId ?: OFF_ROW_KEY }) { index, row ->
      LabelledRow(
        label = row.label,
        detail = row.detail,
        selected = row.selected,
        onClick = { onSelect(row) },
        modifier = if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier,
      )
    }
  }
}

/**
 * The Subtitles section: the file's own tracks, then what a subtitles addon has
 * for it.
 *
 * One list rather than two panels because the two halves are the same decision —
 * many releases carry no subtitle track at all, and a viewer who wants subtitles
 * should not have to know whether they came muxed in or over the network to find
 * them. An added external file appears in the upper half like any other track,
 * which is also where it is switched back off.
 */
@Composable
private fun SubtitleSection(
  rows: List<TrackRow>,
  external: ExternalSubtitlesState,
  focusRequester: FocusRequester,
  onSelect: (TrackRow) -> Unit,
  onFetch: () -> Unit,
  onSelectExternal: (ExternalSubtitleOption) -> Unit,
) {
  val focusIndex = MpvTracks.initialFocusIndex(rows)
  // As in [TrackSection]: the row the focus request aims at has to have been
  // composed, and a lazy list only composes what is visible.
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusIndex)
  LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
    itemsIndexed(rows, key = { _, row -> "track:${row.trackId ?: OFF_ROW_KEY}" }) { index, row ->
      LabelledRow(
        label = row.label,
        detail = row.detail,
        selected = row.selected,
        onClick = { onSelect(row) },
        modifier = if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier,
      )
    }
    // Null means there is no id to ask an addon with, and offering a search that
    // cannot run is worse than not offering one.
    val action = ExternalSubtitles.action(external) ?: return@LazyColumn
    item(key = "external:header") { SectionHeader("Get subtitles") }
    // One item, one key, whatever the state: the row focus is on when the search
    // starts is the row it is still on when the results arrive below it.
    item(key = "external:action") {
      LabelledRow(
        label = action.label,
        detail = action.detail,
        selected = false,
        onClick = { if (action.enabled) onFetch() },
      )
    }
    val options = (external as? ExternalSubtitlesState.Ready)?.options ?: return@LazyColumn
    items(options, key = { "external:${it.url}" }) { option ->
      LabelledRow(
        label = option.label,
        detail = option.detail,
        selected = false,
        onClick = { onSelectExternal(option) },
      )
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text,
    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    color = NebulaPalette.TextFaint,
    style = MaterialTheme.typography.labelMedium,
  )
}

/** The two-line focusable row both halves of the track lists are made of. */
@Composable
private fun LabelledRow(
  label: String,
  detail: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  MenuRow(onClick = onClick, selected = selected, modifier = modifier) { focused ->
    SelectionMarker(selected = selected, focused = focused)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        label,
        color = if (focused) NebulaPalette.OnAccent else NebulaPalette.TextHigh,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (detail.isNotBlank()) {
        Text(
          detail,
          color = if (focused) NebulaPalette.OnAccentMuted else NebulaPalette.TextMuted,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * The five adjustable settings, and one line of help about whichever one the
 * highlight is on.
 *
 * That line replaced two permanent body paragraphs - a sentence about delay scope
 * and a three-line explanation of passthrough - which between them held the bottom
 * third of the panel for information relevant only while you were on one specific
 * row. (The passthrough one was also already said at the moment it matters, by
 * [AudioOutputMode.osdMessage], on the switch itself.) Same node count, and the
 * sentence is now about the thing under the highlight.
 *
 * Scrollable because Android TV exposes a system text-size setting: at Large these
 * rows wrap and the bottom ones used to be clipped with no way for the D-pad to
 * bring them into view. Compose's focus system scrolls a `verticalScroll`
 * container to its focused child on its own, so this needs no extra plumbing.
 */
@Composable
private fun OptionsSection(
  state: PlayerMenuState,
  actions: PlayerMenuActions,
  focusRequester: FocusRequester,
) {
  var help by remember { mutableStateOf(SPEED_HELP) }
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    AdjusterRow(
      label = "Playback speed",
      value = PlaybackSpeeds.label(state.speed),
      help = SPEED_HELP,
      onStep = actions.onSpeedStep,
      onFocusedHelp = { help = it },
      decreaseFocus = focusRequester,
    )
    AdjusterRow(
      label = "Subtitle size",
      value = state.subtitleSize.label,
      help = "Changes text subtitles. Bitmap PGS/DVD subtitles keep their encoded size.",
      onStep = actions.onSubtitleSizeStep,
      onFocusedHelp = { help = it },
    )
    AdjusterRow(
      label = "Audio output",
      value = state.audioOutput.label,
      help = "Passthrough is session-only and appears only when a receiver reports support.",
      onStep = actions.onAudioOutputStep,
      onFocusedHelp = { help = it },
    )
    AdjusterRow(
      label = "Audio delay",
      value = DelaySteps.label(state.audioDelaySec),
      help = DELAY_HELP,
      onStep = actions.onAudioDelayStep,
      onFocusedHelp = { help = it },
    )
    AdjusterRow(
      label = "Subtitle delay",
      value = DelaySteps.label(state.subtitleDelaySec),
      help = DELAY_HELP,
      onStep = actions.onSubtitleDelayStep,
      onFocusedHelp = { help = it },
    )
    Text(
      help,
      // The height is reserved so that walking the rows swaps a sentence rather
      // than reflowing the column under the highlight.
      modifier = Modifier.padding(top = 6.dp).heightIn(min = 40.dp),
      color = NebulaPalette.TextMuted,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/**
 * A "-  value  +" row. Two focusables per setting, so LEFT/RIGHT adjusts and
 * UP/DOWN walks between settings — which is what makes every option reachable
 * without the player having to intercept LEFT/RIGHT and route it at whichever
 * row happens to be focused.
 *
 * [onFocusedHelp] fires for the whole row rather than per button, so the sentence
 * under the list does not flicker as the highlight crosses from "-" to "+".
 */
@Composable
private fun AdjusterRow(
  label: String,
  value: String,
  help: String,
  onStep: (Int) -> Unit,
  onFocusedHelp: (String) -> Unit,
  decreaseFocus: FocusRequester? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .onFocusChanged { if (it.hasFocus) onFocusedHelp(help) },
  ) {
    Text(
      label,
      modifier = Modifier.weight(1f),
      color = NebulaPalette.TextHigh,
      style = MaterialTheme.typography.titleSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    StepButton(
      // U+2212 MINUS SIGN, not the hyphen this used to carry: a hyphen sits short
      // and low on its own axis, so beside a full-height "+" in an identical circle
      // the pair read as two different weights at two different heights.
      text = "−",
      label = "Decrease $label",
      onClick = { onStep(-1) },
      modifier = if (decreaseFocus != null) Modifier.focusRequester(decreaseFocus) else Modifier,
    )
    Text(
      value,
      // Wide enough for "Passthrough", which is the longest value any of these
      // rows shows; the label beside it ellipsizes rather than this, because the
      // value is the part that changes under the press. Accent-tinted so the eye
      // goes to the number between the two buttons that move it.
      modifier = Modifier
        .width(VALUE_WIDTH)
        .padding(horizontal = 8.dp)
        .background(NebulaPalette.Violet.copy(alpha = 0.18f), NebulaShapes.extraSmall)
        .padding(vertical = 6.dp),
      color = NebulaPalette.VioletBright,
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    StepButton(text = "+", label = "Increase $label", onClick = { onStep(1) })
  }
}

/**
 * Wears the same focus treatment as every other button in the app - the 3dp bright
 * ring and the same scale step - rather than the 2dp border and nothing else it
 * used to roll for itself, which made a focused "+" the dimmest focused thing on
 * screen. The scale is a `graphicsLayer`, which is draw-only and so cannot reflow
 * the row it sits in.
 */
@Composable
private fun StepButton(
  text: String,
  /** What this button steps, spoken. Shadowing `contentDescription` inside the semantics
   *  lambda is what made the parameter unreachable there, so it is named for its job. */
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var focused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(
    targetValue = if (focused) NebulaDimens.FocusScaleButton else 1f,
    animationSpec = NebulaMotion.standard(),
    label = "stepButtonScale",
  )
  Box(
    modifier = modifier
      .size(42.dp)
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .onFocusChanged { focused = it.isFocused }
      .clickable(onClick = onClick)
      .background(
        if (focused) NebulaPalette.VioletBright else NebulaPalette.SurfaceVariant,
        CircleShape,
      )
      .border(
        width = if (focused) 3.dp else 1.dp,
        color = if (focused) NebulaPalette.VioletBright else NebulaPalette.Outline,
        shape = CircleShape,
      )
      // The glyph is the whole button, so the name of what it steps has to reach a
      // screen reader some other way; "plus" alone says nothing.
      .semantics { this.contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      color = if (focused) NebulaPalette.OnAccent else NebulaPalette.TextHigh,
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

/**
 * The focusable row shape shared by the track lists.
 *
 * [selected] is styled to survive losing focus: the row that is playing keeps an
 * accent tint and its tick whatever the D-pad is pointing at, because "which
 * track am I on" is the question the viewer opened this menu to answer.
 */
@Composable
private fun MenuRow(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val shape = NebulaShapes.small
  Row(
    modifier = modifier
      .fillMaxWidth()
      // Before the modifier that makes the row focusable, which is the only
      // order in which this observes that row's focus.
      .onFocusChanged { focused = it.isFocused }
      .clickable(onClick = onClick)
      .background(
        when {
          focused -> NebulaPalette.VioletBright
          selected -> NebulaPalette.Violet.copy(alpha = 0.20f)
          else -> NebulaPalette.SurfaceVariant
        },
        shape,
      )
      .border(
        width = if (focused) 2.dp else 1.dp,
        color = when {
          focused -> NebulaPalette.VioletBright
          selected -> NebulaPalette.Violet
          else -> Color.Transparent
        },
        shape = shape,
      )
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    content(focused)
  }
}

/** Which track is playing right now, in a form that survives a photograph. */
@Composable
private fun SelectionMarker(selected: Boolean, focused: Boolean) {
  Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
    if (selected) {
      Icon(
        Icons.Filled.Check,
        contentDescription = "Selected",
        tint = if (focused) NebulaPalette.OnAccent else NebulaPalette.VioletBright,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

// The ink on a focused accent fill used to be declared here as a literal, with a
// comment asserting it matched the app's buttons. It is now NebulaPalette.OnAccent,
// so the assertion is a definition.

/** Two sentences that are true of more than one row; the rest are written inline. */
private const val SPEED_HELP = "Lasts until you leave the player."
private const val DELAY_HELP = "Per file; reset when you leave this stream."

private val VALUE_WIDTH = 124.dp
private val ROW_GAP = 8.dp

/**
 * How much of the screen the panel takes.
 *
 * Not private: the OSD underneath narrows itself by exactly this while the menu is
 * open, so that the scrub bar, the remaining time and the end-of-film clock the
 * panel exists to sit *beside* are not behind it. The two cannot be allowed to
 * drift. Widened from 0.44 to pay for the overscan-safe right padding above, so
 * the usable content width is unchanged.
 */
internal const val PANEL_WIDTH_FRACTION = 0.46f
private const val OFF_ROW_KEY = Int.MIN_VALUE
private const val FOCUS_ATTEMPTS = 5
