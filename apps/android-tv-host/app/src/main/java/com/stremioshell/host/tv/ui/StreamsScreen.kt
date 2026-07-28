package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamAutoPick
import com.stremioshell.host.tv.data.addon.StreamQuality
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.nebulaCardBorder
import com.stremioshell.host.tv.ui.theme.nebulaCardGlow

/** How wide a stream row runs. Short of the full width so a long detail line still ends in the eye's
 *  path rather than at the far edge of a 55-inch panel. */
private const val ROW_WIDTH_FRACTION = 0.85f

@Composable
fun StreamsScreen(
  viewModel: TvAppViewModel,
  screen: Screen.Streams,
  onStreamClick: (AddonStream) -> Unit,
) {
  val streams by viewModel.streams.collectAsState()
  val notice by viewModel.streamsNotice.collectAsState()
  val addons by viewModel.addonManifestUrls.collectAsState()
  val remembered by viewModel.rememberedPicks.collectAsState()
  val addonCount = addons?.size ?: 1
  val firstStreamFocus = rememberInitialFocusTarget()
  val goBack = rememberBackAction()
  val listState = rememberLazyListState()

  // The shared streams flow still holds the *previous* title's Ready list while this screen first
  // composes - loadStreams only resets it to Loading from the effect below. Rendering that list
  // (and auto-focusing it) under the new header lets a fast OK press play the wrong stream and
  // record its position against the new title's watch key, so nothing but Loading is shown until
  // this screen instance has issued its own load.
  var loadIssued by remember(screen) { mutableStateOf(false) }

  LaunchedEffect(screen) {
    viewModel.loadStreams(screen.imdbId, screen.season, screen.episode)
    loadIssued = true
  }

  val state: LoadState<List<AddonStream>> = if (loadIssued) streams else LoadState.Loading

  // The row matching what was last picked for this series, which focus starts on instead
  // of the top of the list. Deliberately only preselected, never auto-played: the addon's
  // best row for *this* episode may well be better than a memory two episodes old, and a
  // list that played itself would take that choice away.
  val list = (state as? LoadState.Ready)?.value.orEmpty()
  val memory = if (screen.season != null) remembered[screen.imdbId] else null
  val preselected = remember(list, memory) {
    val match = memory?.let { StreamAutoPick.pick(list, bingeGroup = null, remembered = it) }
    match?.let { list.indexOf(it) }?.takeIf { it > 0 } ?: 0
  }

  // A LazyColumn only composes what is on screen, so a preselected row further down the
  // list has no node for the focus request to reach until it has been scrolled to.
  LaunchedEffect(preselected) {
    if (preselected > 0) listState.scrollToItem(preselected)
  }

  RequestInitialFocus(
    target = firstStreamFocus,
    key = state,
    label = "Streams first row",
    enabled = state is LoadState.Ready,
  )

  // Edge padding is carried by the children rather than by this column, so the list can pad its own
  // contents instead: a LazyColumn clips to its bounds, and a focused row's ring sits outside it.
  Column(modifier = Modifier.fillMaxSize().padding(vertical = 28.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge),
    ) {
      if (screen.posterUrl != null) {
        ArtworkImage(
          url = screen.posterUrl,
          // Decorative: the title beside it is the same fact in words.
          contentDescription = null,
          modifier = Modifier.size(width = 80.dp, height = 120.dp).clip(NebulaDimens.PosterShape),
        )
      }
      ScreenHeader(
        title = screen.title,
        subtitle = if (screen.season != null) "S${screen.season}E${screen.episode}" else null,
        modifier = Modifier.padding(start = if (screen.posterUrl != null) 22.dp else 0.dp),
      )
    }

    // Above the rows and outside LoadStateContent: it qualifies the list rather than
    // replacing it, and an addon that went down is not a reason to hide the ones that
    // answered. Only shown while a list is actually up; an all-addons failure is the
    // Failed state's message, not a footnote on an empty screen.
    val partialFailure = notice?.takeIf { loadIssued && state is LoadState.Ready }
    if (partialFailure != null) {
      NoticeStrip(
        partialFailure,
        modifier = Modifier
          .padding(horizontal = NebulaDimens.ScreenEdge)
          .padding(top = 20.dp)
          .fillMaxWidth(ROW_WIDTH_FRACTION),
      )
    }

    LoadStateContent(
      state,
      loadingText = if (addonCount > 1) {
        "Asking $addonCount addons for streams..."
      } else {
        "Asking the addon for streams..."
      },
      onRetry = { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) },
    ) { list ->
      if (list.isEmpty()) {
        // An empty result used to render a plain message with nothing focusable, which left
        // the D-pad dead on this route.
        Column(
          verticalArrangement = Arrangement.spacedBy(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.fillMaxWidth().padding(horizontal = NebulaDimens.ScreenEdge, vertical = 56.dp),
        ) {
          EmptyState(
            title = if (addonCount > 1) {
              "No addon returned a playable stream for this title."
            } else {
              "The addon returned no playable streams for this title."
            },
            icon = Icons.Filled.Search,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
            NebulaButton(
              text = "Retry",
              onClick = { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) },
              style = NebulaButtonStyle.Primary,
              modifier = Modifier.initialFocusTarget(firstStreamFocus),
            )
            NebulaButton(text = "Back", onClick = goBack, style = NebulaButtonStyle.Ghost)
          }
        }
      } else {
        LazyColumn(
          state = listState,
          verticalArrangement = Arrangement.spacedBy(12.dp),
          contentPadding = PaddingValues(
            start = NebulaDimens.ScreenEdge,
            end = NebulaDimens.ScreenEdge,
            top = 22.dp,
            bottom = 40.dp,
          ),
        ) {
          // Debrid addons hand back the same resolved URL under several quality labels, and the
          // addon client only drops blank URLs - so a url-only key throws "Key was already used"
          // and takes the screen down. The position prefix keeps keys unique there while staying
          // stable for recompositions of the same list.
          itemsIndexed(list, key = { index, s -> "$index:${s.url ?: s.label}" }) { index, stream ->
            StreamRow(
              stream = stream,
              lastUsed = index == preselected && preselected > 0,
              onClick = {
                // Recorded before the launch, and only for a series: this is the choice the
                // next episode's autoplay resolves against.
                if (screen.season != null) viewModel.rememberStreamPick(screen.imdbId, stream)
                onStreamClick(stream)
              },
              modifier = Modifier.fillMaxWidth(ROW_WIDTH_FRACTION)
                .initialFocusTarget(if (index == preselected) firstStreamFocus else null),
            )
          }
        }
      }
    }
  }
}

/**
 * One release, as the viewer is asked to choose between fifteen of them.
 *
 * The label an addon writes is mostly noise - its own branding, the release group, a run of dots -
 * so the badges are what the row is actually read by, and they get the colour. The card itself
 * barely moves on focus: it is nearly a screen wide, and a poster's 7% would carry its far edge out
 * past the overscan.
 */
@Composable
private fun StreamRow(
  stream: AddonStream,
  lastUsed: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Parsed once per stream rather than on every recomposition: this is a handful of regexes over
  // four lines of text, and a focus move down the list recomposes every row it passes.
  val quality = remember(stream) { StreamQuality.parse(stream) }
  // Set only when more than one addon is configured, so a single-addon list keeps the
  // rows it has always had.
  val source = stream.source

  Card(
    onClick = onClick,
    colors = CardDefaults.colors(
      containerColor = NebulaPalette.Surface,
      contentColor = NebulaPalette.TextHigh,
      focusedContainerColor = NebulaPalette.SurfaceVariant,
      focusedContentColor = NebulaPalette.TextHigh,
    ),
    shape = CardDefaults.shape(shape = NebulaShapes.medium),
    border = nebulaCardBorder(NebulaShapes.medium),
    glow = nebulaCardGlow(),
    scale = CardDefaults.scale(focusedScale = NebulaDimens.FocusScaleWide),
    modifier = modifier,
  ) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      Text(
        stream.label,
        style = MaterialTheme.typography.titleMedium,
        color = NebulaPalette.TextHigh,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      val resolution = quality.resolutionLabel()
      val size = quality.formattedSize()
      if (lastUsed || resolution != null || quality.dolbyVision || quality.hdr ||
        size != null || source != null
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(top = 10.dp),
        ) {
          // First, because on a series it is the reason focus is already sitting on this row.
          if (lastUsed) NebulaBadge("last used", BadgeTone.Accent)
          // What the release is worth watching for leads; the size and the addon that offered it
          // are facts about the row rather than reasons to pick it, so they stay grey.
          if (resolution != null) NebulaBadge(resolution, BadgeTone.Accent)
          if (quality.dolbyVision) NebulaBadge("DV", BadgeTone.Accent)
          if (quality.hdr) NebulaBadge("HDR", BadgeTone.Accent)
          if (size != null) NebulaBadge(size, BadgeTone.Neutral)
          if (source != null) NebulaBadge(source, BadgeTone.Neutral)
        }
      }
      if (stream.detail.isNotBlank()) {
        Text(
          stream.detail,
          style = MaterialTheme.typography.bodySmall,
          color = NebulaPalette.TextMuted,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
  }
}

/**
 * A qualification on the list below, not a failure of it.
 *
 * Given its own surface rather than left as a red line of text: as loose copy it read as the
 * screen's error message, which is exactly what it is not - the rows underneath are fine.
 */
@Composable
private fun NoticeStrip(message: String, modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .background(NebulaPalette.Surface, NebulaShapes.medium)
      .padding(horizontal = 18.dp, vertical = 14.dp),
  ) {
    Icon(
      Icons.Filled.Warning,
      // Decorative: the message beside it is the content.
      contentDescription = null,
      tint = NebulaPalette.Danger,
      modifier = Modifier.size(20.dp),
    )
    Text(
      message,
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextMuted,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = 14.dp),
    )
  }
}
