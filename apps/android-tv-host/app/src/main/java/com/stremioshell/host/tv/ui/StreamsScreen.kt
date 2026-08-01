package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.R
import com.stremioshell.host.tv.StreamsRequestKey
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamAutoPick
import com.stremioshell.host.tv.data.addon.StreamQuality
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.tv.ui.theme.nebulaCardBorder
import com.stremioshell.host.tv.ui.theme.nebulaCardGlow
import java.util.Locale

/** How wide a stream row runs. Short of the full width so a long detail line still ends in the eye's
 *  path rather than at the far edge of a 55-inch panel. */
private const val ROW_WIDTH_FRACTION = 0.85f

/**
 * Fixed lead column for the resolution badge.
 *
 * The point of the fixed width is the *column*: with the tier badge first and always the same size,
 * "4K", "1080p" and "720p" line up down the left of the list and the run is scannable in one
 * vertical sweep, which eight badges in a variable-order row never are. Wide enough for a badged
 * "1080p" at labelSmall plus the badge's own padding.
 */
private val RESOLUTION_GUTTER = 64.dp

/** The poster beside the header. Small: this screen is a list, not an artwork surface. */
private val HEADER_POSTER_WIDTH = 80.dp
private val HEADER_POSTER_HEIGHT = 120.dp

@Composable
fun StreamsScreen(
  viewModel: TvAppViewModel,
  screen: Screen.Streams,
  onOpenSettings: () -> Unit = {},
  onStreamClick: (AddonStream) -> Unit,
) {
  val streams by viewModel.streams.collectAsStateWithLifecycle()
  val streamsRequest by viewModel.streamsRequest.collectAsStateWithLifecycle()
  val notice by viewModel.streamsNotice.collectAsStateWithLifecycle()
  val addons by viewModel.addonManifestUrls.collectAsStateWithLifecycle()
  val remembered by viewModel.rememberedPicks.collectAsStateWithLifecycle()
  val addonCount = addons?.size ?: 0
  val firstStreamFocus = rememberInitialFocusTarget()
  val goBack = rememberBackAction()
  val listState = rememberLazyListState()

  // The shared streams flow still holds the *previous* title's Ready list while this screen first
  // composes - loadStreams only resets it to Loading from the effect below. Rendering that list
  // (and auto-focusing it) under the new header lets a fast OK press play the wrong stream and
  // record its position against the new title's watch key, so nothing but Loading is shown until
  // this screen instance has issued its own load.
  var loadIssued by remember(screen) { mutableStateOf(false) }

  val request = StreamsRequestKey(screen.imdbId, screen.season, screen.episode)

  // Issues this screen's load, and re-issues it if the list is ever dropped underneath it: memory
  // pressure clears the shared streams state (see TvAppViewModel.onTrimMemory), and this screen is
  // usually the one waiting behind the player when that happens. A request the ViewModel no longer
  // holds is the only sign of it, and without the re-issue a return from the player would land on
  // a spinner that never resolves.
  //
  // Resume-scoped rather than a plain LaunchedEffect so a drop while the player is starting is not
  // answered by refetching from every addon there and then, which is the moment the memory and the
  // bandwidth were being reclaimed for. Keyed on the collected request as well, so a drop while
  // the picker is actually on screen is picked up without waiting for the next resume - and read
  // off the flow rather than off that key, because a resume can outrun the collector restarting.
  LifecycleResumeEffect(screen, streamsRequest) {
    if (!loadIssued || viewModel.streamsRequest.value != request) {
      viewModel.loadStreams(screen.imdbId, screen.season, screen.episode)
      loadIssued = true
    }
    onPauseOrDispose { }
  }

  val state: LoadState<List<AddonStream>> = if (
    loadIssued && streamsRequest == request
  ) {
    streams
  } else {
    LoadState.Loading
  }

  // The row matching what was last picked for this series, which focus starts on instead
  // of the top of the list. Deliberately only preselected, never auto-played: the addon's
  // best row for *this* episode may well be better than a memory two episodes old, and a
  // list that played itself would take that choice away.
  val rawList = (state as? LoadState.Ready)?.value.orEmpty()
  var filters by remember(screen) { mutableStateOf(StreamFilters()) }
  var streamListFocusTick by remember(screen) { mutableIntStateOf(0) }
  val sourceOptions = remember(rawList) { StreamFilterPolicy.sources(rawList) }
  // Read once per load, not once per press. Every regex on this screen lives behind this call, and
  // filtering, tier headings and each row's badges all read the result rather than the free text
  // again - so cycling a filter chip is now list comparisons and nothing else.
  val rated = remember(rawList) { StreamFilterPolicy.rate(rawList) }
  val visible = remember(rated, filters) { StreamFilterPolicy.applyRated(rated, filters) }
  val list = remember(visible) { visible.map(RatedStream::stream) }
  val memory = if (screen.season != null) remembered[screen.imdbId] else null
  val matched = remember(list, memory) {
    memory?.let { StreamAutoPick.pick(list, bingeGroup = null, remembered = it) }
  }
  // Kept apart from the match on purpose. Scrolling to index 0 is pointless, but the *badge* at
  // index 0 is not: the common case is precisely that one, because the viewer picked 4K last
  // episode and 4K is what StreamOrder puts at the top. Folding the two together meant the marker
  // explaining why focus was parked on a row appeared only when the remembered release happened not
  // to be the best one, so the same stream was badged on one episode and bare on the next.
  val preselected = matched?.let { list.indexOf(it) }?.coerceAtLeast(0) ?: 0

  // Tier headings, built once per list rather than per row: a focus move down the list recomposes
  // every row it passes.
  val rows = remember(visible) { StreamPresentation.rows(visible) }
  val preselectedRow = remember(rows, preselected) {
    rows.indexOfFirst { it is StreamListItem.Release && it.index == preselected }
  }

  // A LazyColumn only composes what is on screen, so a preselected row further down the
  // list has no node for the focus request to reach until it has been scrolled to.
  LaunchedEffect(rows, preselectedRow) {
    // Stops one row short so something ranked *above* the remembered one stays visible. Flush
    // against the top the picker looked like a list that begins at the viewer's old choice, with
    // every better release StreamOrder had put above it off screen and nothing saying so.
    if (preselectedRow > 1) listState.scrollToItem(preselectedRow - 1)
  }

  RequestInitialFocus(
    target = firstStreamFocus,
    key = state to streamListFocusTick,
    label = "Streams first row",
    enabled = state is LoadState.Ready,
  )

  // Edge padding is carried by the children rather than by this column, so the list can pad its own
  // contents instead: a LazyColumn clips to its bounds, and a focused row's ring sits outside it.
  Column(modifier = Modifier.fillMaxSize().padding(top = NebulaDimens.ScreenEdgeVertical)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      // No start padding: the poster carries its own, and ScreenHeader pads itself so that its
      // *text* lands on the content line. Adding it here padded both a second time.
      modifier = Modifier.padding(end = NebulaDimens.ScreenEdge),
    ) {
      if (screen.posterUrl != null) {
        ArtworkImage(
          url = screen.posterUrl,
          // Decorative: the title beside it is the same fact in words.
          contentDescription = null,
          // A dead poster URL used to leave a bare grey slab beside the title, indistinguishable
          // from an unfinished layout.
          fallback = {
            Icon(
              Icons.Filled.PlayArrow,
              contentDescription = null,
              tint = NebulaPalette.TextFaint,
              modifier = Modifier.size(NebulaIcon.md),
            )
          },
          modifier = Modifier
            .padding(start = NebulaDimens.ScreenEdge)
            .size(width = HEADER_POSTER_WIDTH, height = HEADER_POSTER_HEIGHT)
            .clip(NebulaDimens.PosterShape),
        )
      }
      ScreenHeader(
        title = screen.title,
        subtitle = if (screen.season != null) "S${screen.season}E${screen.episode}" else null,
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
          .padding(top = NebulaSpace.lg)
          .fillMaxWidth(ROW_WIDTH_FRACTION),
      )
    }

    val missingAddonFailure = (state as? LoadState.Failed)?.let { failed ->
      addons?.isEmpty() == true || failed.message.contains("No addon", ignoreCase = true)
    } == true
    LoadStateContent(
      state,
      loadingText = if (addonCount > 0) {
        pluralStringResource(
          R.plurals.streams_loading_addons,
          addonCount,
          addonCount,
        )
      } else {
        stringResource(R.string.streams_loading_no_addons)
      },
      onRetry = if (missingAddonFailure) {
        null
      } else {
        { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) }
      },
      failureActionLabel = stringResource(R.string.action_open_settings)
        .takeIf { missingAddonFailure },
      onFailureAction = onOpenSettings.takeIf { missingAddonFailure },
    ) { ready ->
      if (ready.isEmpty()) {
        // An empty result used to render a plain message with nothing focusable, which left
        // the D-pad dead on this route. Centred rather than top-aligned so that the two outcomes
        // of the same load - nothing found and it failed - land in the same optical position.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(
            verticalArrangement = Arrangement.spacedBy(NebulaSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            EmptyState(
              title = stringResource(R.string.streams_empty_title),
              hint = if (addonCount > 1) {
                stringResource(R.string.streams_empty_many_addons)
              } else {
                stringResource(R.string.streams_empty_one_addon)
              },
              // Not a magnifier: the viewer did not search for this, they pressed Play on Details.
              icon = Icons.Filled.Warning,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
              NebulaButton(
                text = stringResource(R.string.action_retry),
                onClick = { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) },
                style = NebulaButtonStyle.Primary,
                modifier = Modifier.initialFocusTarget(firstStreamFocus),
              )
              NebulaButton(
                text = stringResource(R.string.action_manage_addons),
                onClick = onOpenSettings,
                style = NebulaButtonStyle.Secondary,
              )
              NebulaButton(
                text = stringResource(R.string.action_back),
                onClick = goBack,
                style = NebulaButtonStyle.Ghost,
              )
            }
          }
        }
      } else {
        // How many there are, what subset is visible and what order they are in. The raw merged
        // list remains one "Show all" press away even when the conservative preset is active.
        Text(
          text = if (list.size == ready.size) {
            pluralStringResource(
              R.plurals.streams_release_count,
              ready.size,
              ready.size,
            )
          } else {
            pluralStringResource(
              R.plurals.streams_filtered_release_count,
              ready.size,
              list.size,
              ready.size,
            )
          },
          style = MaterialTheme.typography.labelMedium,
          color = NebulaPalette.TextMuted,
          modifier = Modifier.padding(start = NebulaDimens.ScreenEdge, top = NebulaSpace.lg),
        )
        StreamFilterControls(
          filters = filters,
          sources = sourceOptions,
          onFiltersChanged = { next ->
            filters = next
          },
          modifier = Modifier
            .padding(horizontal = NebulaDimens.ScreenEdge, vertical = NebulaSpace.sm)
            .fillMaxWidth(ROW_WIDTH_FRACTION),
        )
        if (list.isEmpty()) {
          Column(
            verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
            modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge, vertical = NebulaSpace.lg),
          ) {
            Text(
              stringResource(R.string.streams_no_filter_matches),
              style = MaterialTheme.typography.bodyMedium,
              color = NebulaPalette.TextMuted,
            )
            NebulaButton(
              text = stringResource(R.string.streams_show_all_releases),
              onClick = {
                filters = StreamFilters.SHOW_ALL
                // The button leaves composition as soon as releases return. Re-aim at the
                // remembered release (or first row) instead of stranding focus on the old node.
                streamListFocusTick++
              },
              style = NebulaButtonStyle.Primary,
            )
          }
        } else {
          LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
            contentPadding = PaddingValues(
              start = NebulaDimens.ScreenEdge,
              end = NebulaDimens.ScreenEdge,
              top = NebulaSpace.sm,
              bottom = 40.dp,
            ),
            modifier = Modifier.weight(1f),
          ) {
            // The key is the row's own identity, computed with the list; see [StreamPresentation.rows]
            // for why it cannot simply be the URL, and why it must not be the position.
            items(
              rows,
              key = { row -> row.key },
              // A tier heading is a line of text and a release is a two-line card with a badge
              // run: without this Compose is free to recycle one into the other every time a
              // filter changes the shape of the list.
              contentType = { row ->
                when (row) {
                  is StreamListItem.Tier -> "tier"
                  is StreamListItem.Release -> "release"
                }
              },
            ) { row ->
              when (row) {
                // Carries no focusable, so the D-pad steps straight past it and the screen keeps
                // every focus target it had.
                is StreamListItem.Tier -> TierHeading(row)
                is StreamListItem.Release -> StreamRow(
                  stream = row.stream,
                  quality = row.quality,
                  lastUsed = matched != null && row.index == preselected,
                  onClick = {
                    // Recorded before the launch, and only for a series: this is the choice the
                    // next episode's autoplay resolves against.
                    if (screen.season != null) viewModel.rememberStreamPick(screen.imdbId, row.stream)
                    onStreamClick(row.stream)
                  },
                  modifier = Modifier.fillMaxWidth(ROW_WIDTH_FRACTION)
                    .initialFocusTarget(if (row.index == preselected) firstStreamFocus else null),
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Six compact cycling controls instead of six rows of radio buttons. A TV remote can inspect and
 * change every dimension with OK alone, while the release list still keeps most of the viewport.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamFilterControls(
  filters: StreamFilters,
  sources: List<String>,
  onFiltersChanged: (StreamFilters) -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    modifier = modifier,
  ) {
    StreamFilterButton(
      label = stringResource(
        R.string.streams_filter_view,
        filters.viewMode.localizedLabel(),
      ),
      active = filters.viewMode == StreamViewMode.Recommended,
      onClick = {
        onFiltersChanged(
          filters.copy(viewMode = nextValue(filters.viewMode, StreamViewMode.entries)),
        )
      },
    )
    StreamFilterButton(
      label = stringResource(
        R.string.streams_filter_availability,
        filters.availability.localizedLabel(),
      ),
      active = filters.availability != StreamAvailability.Any,
      onClick = {
        onFiltersChanged(
          filters.copy(
            availability = nextValue(filters.availability, StreamAvailability.entries),
          ),
        )
      },
    )
    StreamFilterButton(
      label = stringResource(
        R.string.streams_filter_range,
        filters.dynamicRange.localizedLabel(),
      ),
      active = filters.dynamicRange != StreamDynamicRange.Any,
      onClick = {
        onFiltersChanged(
          filters.copy(
            dynamicRange = nextValue(filters.dynamicRange, StreamDynamicRange.entries),
          ),
        )
      },
    )
    StreamFilterButton(
      label = stringResource(
        R.string.streams_filter_resolution,
        filters.resolution.localizedLabel(),
      ),
      active = filters.resolution != StreamResolution.Any,
      onClick = {
        onFiltersChanged(
          filters.copy(resolution = nextValue(filters.resolution, StreamResolution.entries)),
        )
      },
    )
    if (sources.isNotEmpty()) {
      val sourceChoices = listOf<String?>(null) + sources
      StreamFilterButton(
        label = stringResource(
          R.string.streams_filter_source,
          filters.source ?: stringResource(R.string.streams_filter_any),
        ),
        active = filters.source != null,
        onClick = {
          onFiltersChanged(filters.copy(source = nextValue(filters.source, sourceChoices)))
        },
      )
    }
    StreamFilterButton(
      label = stringResource(
        R.string.streams_filter_size,
        filters.sizeLimit.localizedLabel(),
      ),
      active = filters.sizeLimit != StreamSizeLimit.Any,
      onClick = {
        onFiltersChanged(
          filters.copy(sizeLimit = nextValue(filters.sizeLimit, StreamSizeLimit.entries)),
        )
      },
    )
    StreamFilterButton(
      label = stringResource(R.string.streams_filter_show_all),
      active = filters == StreamFilters.SHOW_ALL,
      onClick = { onFiltersChanged(StreamFilters.SHOW_ALL) },
    )
  }
}

@Composable
private fun StreamFilterButton(
  label: String,
  active: Boolean,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    colors = CardDefaults.colors(
      containerColor = if (active) NebulaPalette.AccentPlate else NebulaPalette.SurfaceVariant,
      contentColor = if (active) NebulaPalette.VioletBright else NebulaPalette.TextHigh,
      focusedContainerColor =
      if (active) NebulaPalette.AccentPlateStrong else NebulaPalette.SurfaceRaised,
      focusedContentColor = NebulaPalette.TextHigh,
    ),
    shape = CardDefaults.shape(shape = NebulaShapes.small),
    border = nebulaCardBorder(NebulaShapes.small),
    glow = nebulaCardGlow(),
    scale = CardDefaults.scale(focusedScale = NebulaDimens.FocusScaleWide),
    modifier = Modifier.semantics { selected = active },
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = NebulaSpace.sm, vertical = NebulaSpace.xs),
    )
  }
}

private fun <T> nextValue(current: T, choices: List<T>): T {
  if (choices.isEmpty()) return current
  val index = choices.indexOf(current)
  return choices[(index + 1).mod(choices.size)]
}

@Composable
private fun StreamViewMode.localizedLabel(): String = when (this) {
  StreamViewMode.Recommended -> stringResource(R.string.streams_filter_recommended)
  StreamViewMode.All -> stringResource(R.string.streams_filter_all)
}

@Composable
private fun StreamAvailability.localizedLabel(): String = when (this) {
  StreamAvailability.Any -> stringResource(R.string.streams_filter_any)
  StreamAvailability.Instant -> stringResource(R.string.streams_filter_instant)
}

@Composable
private fun StreamDynamicRange.localizedLabel(): String = when (this) {
  StreamDynamicRange.Any -> stringResource(R.string.streams_filter_any)
  // Free-text addon metadata cannot prove SDR; it can only prove that no HDR/DV tag was found.
  StreamDynamicRange.Sdr -> stringResource(R.string.streams_filter_unmarked_range)
  StreamDynamicRange.Hdr -> "HDR"
  StreamDynamicRange.DolbyVision -> "DV"
}

@Composable
private fun StreamResolution.localizedLabel(): String = when (this) {
  StreamResolution.Any -> stringResource(R.string.streams_filter_any)
  StreamResolution.Uhd -> "4K"
  StreamResolution.FullHd -> "1080p"
  StreamResolution.Hd -> "720p"
  StreamResolution.Sd -> "SD"
  StreamResolution.Unknown -> stringResource(R.string.streams_filter_other)
}

@Composable
private fun StreamSizeLimit.localizedLabel(): String = when (this) {
  StreamSizeLimit.Any -> stringResource(R.string.streams_filter_any)
  else -> label
}

/** Where one resolution tier starts, and how many releases are in it. */
@Composable
private fun TierHeading(tier: StreamListItem.Tier) {
  Text(
    text = pluralStringResource(
      R.plurals.streams_tier_count,
      tier.count,
      tier.label,
      tier.count,
    ),
    style = MaterialTheme.typography.labelLarge,
    color = NebulaPalette.TextMuted,
    modifier = Modifier.padding(top = NebulaSpace.xs, start = NebulaSpace.xxs),
  )
}

/**
 * One release, as the viewer is asked to choose between forty of them.
 *
 * Two lines and change rather than the four it used to be. The old row led with [AddonStream.name]
 * at 19sp, which for every debrid addon anyone runs is the addon's own branding plus a resolution
 * the badges already carry - so forty releases were forty rows whose loudest word was "Comet", and
 * at ~140dp each only two and a half of them fitted on the panel. Now the release itself leads, the
 * resolution sits in a fixed gutter so the tiers line up in a column, the size sits at the trailing
 * edge where it was previously dead space, and everything that differentiates two 4K rows - REMUX
 * vs WEB-DL, Atmos vs DDP, cached vs not - is a badge instead of being buried in a truncated
 * filename.
 *
 * The card itself barely moves on focus: it is nearly a screen wide, and a poster's 7% would carry
 * its far edge out past the overscan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamRow(
  stream: AddonStream,
  quality: StreamQuality,
  lastUsed: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // The parse itself already happened once for the whole list; this is only the string work, and it
  // is remembered for the same reason: a focus move down the list recomposes every row it passes.
  val text = remember(stream) { StreamPresentation.rowText(stream) }
  // Set only when more than one addon is configured, so a single-addon list keeps the
  // rows it has always had.
  val source = stream.source
  val resolution = quality.resolutionLabel()
  val size = quality.formattedSize()
  val hasBadges = text.cached || quality.dolbyVision || quality.hdr ||
    text.releaseType != null || text.audio != null || source != null || lastUsed

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
    Box {
      if (lastUsed) {
        // Marks the remembered row without displacing a single badge, which is what putting it
        // first in the badge run did - it shoved the resolution ~70dp right on exactly the one row
        // the eye is being asked to compare against its neighbours. matchParentSize rather than
        // fillMaxHeight on a Box child: the latter resolves against the *viewport*, not the row.
        Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
          Box(
            modifier = Modifier
              .width(3.dp)
              .fillMaxHeight()
              .background(NebulaPalette.Violet),
          )
        }
      }
      Column(modifier = Modifier.padding(horizontal = NebulaSpace.md, vertical = NebulaSpace.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(modifier = Modifier.width(RESOLUTION_GUTTER)) {
            // What the release is worth watching for leads, and it is also the sort key, so it
            // keeps the accent. The gutter stays even when a row never said its resolution: an
            // empty slot is what keeps the column straight.
            if (resolution != null) NebulaBadge(resolution, tone = BadgeTone.Accent)
          }
          Text(
            text.title,
            style = MaterialTheme.typography.titleMedium,
            color = NebulaPalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = NebulaSpace.sm),
          )
          if (size != null) {
            // A fact about the row rather than a reason to pick it, so it stays grey - but it is
            // half the decision between two 4K rows, so it gets the trailing edge rather than a
            // place in the badge run.
            Text(
              size,
              style = MaterialTheme.typography.labelMedium,
              color = NebulaPalette.TextMuted,
              maxLines = 1,
            )
          }
        }
        if (hasBadges) {
          // FlowRow, not Row: eight badges plus a verbose addon label overrun the card's inner
          // width, and a plain Row drops the trailing ones with no ellipsis and no sign anything
          // is missing. Indented to the gutter so the badges hang under the title, not under the
          // resolution column.
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
            maxItemsInEachRow = 6,
            modifier = Modifier.padding(top = NebulaSpace.xs, start = RESOLUTION_GUTTER),
          ) {
            // "Already on the debrid server", i.e. it starts now instead of downloading first -
            // the single strongest reason to pick one row over another, and until now it was
            // thrown away with the addon branding it was hidden inside.
            if (text.cached) {
              NebulaBadge(stringResource(R.string.streams_badge_instant), tone = BadgeTone.Good)
            }
            if (quality.dolbyVision) NebulaBadge("DV", tone = BadgeTone.Accent)
            if (quality.hdr) NebulaBadge("HDR", tone = BadgeTone.Accent)
            text.releaseType?.let { NebulaBadge(it, tone = BadgeTone.Neutral) }
            text.audio?.let { NebulaBadge(it, tone = BadgeTone.Neutral) }
            // A server-supplied name of unbounded length; capped so one verbose addon cannot eat
            // the run.
            if (source != null) {
              NebulaBadge(source, modifier = Modifier.widthIn(max = 120.dp), tone = BadgeTone.Neutral)
            }
            // Sentence case, and last: it was the only lowercase, multi-word pill in a run of
            // uppercase acronyms set in a face tuned for short caps-style strings.
            if (lastUsed) {
              NebulaBadge(stringResource(R.string.streams_badge_last_used), tone = BadgeTone.Accent)
            }
          }
        }
        if (text.detail.isNotEmpty()) {
          Text(
            text.detail,
            style = MaterialTheme.typography.bodySmall,
            color = NebulaPalette.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NebulaSpace.xs, start = RESOLUTION_GUTTER),
          )
        }
      }
    }
  }
}

/**
 * A qualification on the list below, not a failure of it.
 *
 * Given its own surface rather than left as a red line of text: as loose copy it read as the
 * screen's error message, which is exactly what it is not - the rows underneath are fine. For the
 * same reason the glyph is amber rather than [NebulaPalette.Danger], which is the colour
 * FailureMessage uses and made the loudest element of a deliberately non-alarming strip the alarm
 * colour, while the sentence it was qualifying was the quietest thing on it. The emphasis is now
 * the other way round, and a hairline is what makes the strip its own object.
 */
@Composable
private fun NoticeStrip(message: String, modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .background(NebulaPalette.Surface, NebulaShapes.medium)
      .border(1.dp, NebulaPalette.Outline, NebulaShapes.medium)
      .padding(horizontal = NebulaSpace.md, vertical = NebulaSpace.sm),
  ) {
    Icon(
      Icons.Filled.Warning,
      // Decorative: the message beside it is the content.
      contentDescription = null,
      tint = NebulaPalette.Caution,
      modifier = Modifier.size(NebulaIcon.sm),
    )
    Text(
      message,
      style = MaterialTheme.typography.bodyMedium,
      color = NebulaPalette.TextHigh,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = NebulaSpace.sm),
    )
  }
}

/** One entry in the picker: a tier heading, or a release. */
sealed interface StreamListItem {
  /**
   * What this row is, independent of where it currently sits.
   *
   * Deliberately not derived from the position. The key used to be `"row:$position:$url"`, so every
   * press of a filter chip renamed every row: the lazy list could reuse nothing, each surviving row
   * was torn down and rebuilt, and the node holding focus went with it.
   */
  val key: String

  /** @param count how many releases are in this tier, so the heading is also a map. */
  data class Tier(override val key: String, val label: String, val count: Int) : StreamListItem

  /** @param index the release's position in the *unflattened* list, which is what focus and the
   *   remembered pick are addressed by. */
  data class Release(
    override val key: String,
    val index: Int,
    val stream: AddonStream,
    val quality: StreamQuality,
  ) : StreamListItem
}

/** The strings one row shows, parsed once. */
data class StreamRowText(
  val title: String,
  val detail: String,
  val releaseType: String?,
  val audio: String?,
  val cached: Boolean,
)

/**
 * What a stream row says, out of the free text an addon writes.
 *
 * Pure, and beside the screen rather than inside it for the same reason [SearchPresentation] is:
 * the interesting part is strings no device can be made to produce on demand - a Torrentio row's
 * name is its own branding, a Comet row's description is five newline-separated fields with emoji
 * in them - and the row has to read the same whichever addon produced it.
 *
 * The release-type and audio passes belong with [StreamQuality], which already lowercases and
 * tokenises the same text for resolution, HDR and size; they live here only because that file was
 * not ours to change. If it is ever reopened, move them and delete this note.
 */
object StreamPresentation {
  /** Containers an addon leaves on the end of a filename. Noise in a title. */
  private val EXTENSION = Regex("""\.(mkv|mp4|avi|m2ts|ts|mov|wmv|webm)$""", RegexOption.IGNORE_CASE)
  private val WHITESPACE = Regex("""\s+""")

  /**
   * A scene filename's word separators.
   *
   * Dots only where they are *not* between two digits, so "Dune.Part.Two" reads as words while
   * "DD5.1", "7.1" and "2160p.2024"-style year runs keep their punctuation. At three metres a line
   * of dot-joined words is genuinely harder to read than the same words spaced, and this line is
   * the one the whole screen is being scanned by.
   */
  private val SEPARATORS = Regex("""(?<!\d)\.|\.(?!\d)|_""")

  /**
   * The debrid "already on the server" marker, which addons bury in their own branding: "[RD+]",
   * "[PM+]", a lightning bolt, the word cached. Read from `name` only - that is the field the
   * marker lives in, and "cached" appearing in a filename means nothing.
   */
  private val CACHED_MARKER = Regex("""\[[a-z]{2}\+\]|⚡|\bcached\b|\binstant\b""")

  /** "DD5.1" but not "DDP" and not the "dd" inside a word. */
  private val DOLBY_DIGITAL = Regex("""(?<![a-z0-9])dd(?![a-z])""")

  /** How many releases the picker is offering, and what order they are in. */
  fun summary(count: Int): String =
    if (count == 1) "1 release" else "$count releases • best quality first"

  /**
   * The row's headline: the release, not the addon.
   *
   * The filename is the one field that differs between forty rows of the same title. Falls back to
   * the first line of the detail, then to the addon's label - which is what the row used to lead
   * with, and which for a single configured addon is the same string forty times over.
   */
  fun releaseTitle(stream: AddonStream): String {
    val filename = stream.behaviorHints?.filename?.trim()?.ifBlank { null }
    val firstDetailLine = stream.detail.lineSequence()
      .map(String::trim)
      .firstOrNull { it.isNotEmpty() }
    val raw = filename ?: firstDetailLine ?: stream.label
    return clean(raw).ifBlank { stream.label }
  }

  /**
   * Everything left in the detail once the title and the addon's line breaks are out of it.
   *
   * The raw field was rendered at two lines, so which facts the viewer saw was decided by the order
   * the addon happened to write them in - typically the filename again, then whichever one of
   * seeders/size/tracker came next, ellipsised, with the size repeated from a badge directly above.
   * Once size, source, resolution and release type are all badges, what is genuinely left is one
   * line: seeders and where it came from.
   */
  fun detailLine(stream: AddonStream, title: String): String =
    stream.detail.lineSequence()
      .map { it.trim().replace(WHITESPACE, " ") }
      // Compared through [clean] rather than raw: the title has usually been read out of the same
      // line and then de-dotted, so a literal comparison would print the filename twice.
      .filter { it.isNotEmpty() && clean(it) != title }
      .joinToString(" • ")

  /**
   * How the release was made, which together with the resolution is most of the choice between two
   * 4K rows. Ordered best-first: a filename saying both "bluray" and "remux" is a remux.
   */
  fun releaseType(text: String): String? {
    val lower = text.lowercase(Locale.ROOT)
    return when {
      lower.contains("remux") -> "REMUX"
      lower.containsAny("bluray", "blu-ray", "bdrip", "brrip") -> "BluRay"
      lower.containsAny("web-dl", "webdl", "web dl") -> "WEB-DL"
      lower.containsAny("webrip", "web-rip") -> "WEBRip"
      lower.containsToken("web") -> "WEB"
      lower.contains("hdtv") -> "HDTV"
      lower.containsAny("dvdrip", "dvd-rip") -> "DVDRip"
      // Deliberately no "ts"/"tc" tokens: both are common enough as ordinary letter pairs that
      // they would label good releases as camrips.
      lower.containsAny("telesync", "hdcam") || lower.containsToken("cam") -> "CAM"
      else -> null
    }
  }

  /**
   * The audio format, which is the other half of that same choice - and the one fact a 62 GB row
   * carries over a 12 GB one that the size alone does not explain.
   */
  fun audio(text: String): String? {
    val lower = text.lowercase(Locale.ROOT)
    return when {
      lower.contains("atmos") -> "Atmos"
      lower.containsAny("dts-x", "dts x", "dtsx") -> "DTS:X"
      lower.containsAny("dts-hd", "dtshd", "dts hd", "dtsma") -> "DTS-HD"
      lower.containsAny("truehd", "true-hd") -> "TrueHD"
      lower.containsAny("ddp", "eac3", "e-ac3", "dd+") -> "DDP"
      lower.containsToken("dts") -> "DTS"
      lower.contains("ac3") || DOLBY_DIGITAL.containsMatchIn(lower) -> "DD"
      else -> null
    }
  }

  fun isCached(stream: AddonStream): Boolean =
    CACHED_MARKER.containsMatchIn(stream.name.orEmpty().lowercase(Locale.ROOT))

  /** Everything one row shows, parsed in one pass over the same text [StreamQuality] reads. */
  fun rowText(stream: AddonStream): StreamRowText {
    val title = releaseTitle(stream)
    val haystack = listOfNotNull(
      stream.name,
      stream.title,
      stream.description,
      stream.behaviorHints?.filename,
    ).joinToString(" ")
    return StreamRowText(
      title = title,
      detail = detailLine(stream, title),
      releaseType = releaseType(haystack),
      audio = audio(haystack),
      cached = isCached(stream),
    )
  }

  /** Which block of the list a row belongs to. Never "SD" for a row that simply did not say. */
  fun tierLabel(quality: StreamQuality): String = quality.resolutionLabel() ?: "Other"

  /**
   * The list as the picker draws it: a heading at each change of resolution tier, then its rows.
   *
   * Safe to group in one pass because the list arrives sorted by `StreamOrder`, which is descending
   * by resolution - so a tier is always contiguous. A single tier gets no heading at all: it would
   * only repeat the summary line above the list.
   *
   * Each entry is also given the identity the lazy list keys on. A release is named by its URL,
   * which is what actually distinguishes two rows - but debrid addons hand back the *same* resolved
   * URL under several quality labels and the addon client only drops blank ones, so a bare URL key
   * throws "Key was already used" and takes the screen down. [RatedStream.occurrence] - counted
   * over the unfiltered list in [StreamFilterPolicy.rate] - is what makes it unique without making
   * it positional: a row keeps its name when a filter removes the duplicates above it, which is
   * what lets the list reuse its nodes and keeps focus on the release the viewer was standing on.
   * The newline separator is deliberate: a URL cannot contain one, so no URL ending in `#1` can
   * forge another row's suffixed key.
   */
  fun rows(streams: List<RatedStream>): List<StreamListItem> {
    val labels = streams.map { tierLabel(it.quality) }
    val tiers = labels.distinct().size
    val out = ArrayList<StreamListItem>(streams.size + tiers)
    val seenTiers = mutableMapOf<String, Int>()
    var index = 0
    while (index < streams.size) {
      var end = index
      while (end < streams.size && labels[end] == labels[index]) end++
      if (tiers > 1) {
        val label = labels[index]
        out += StreamListItem.Tier(
          key = occurrenceKey("tier", label, seenTiers),
          label = label,
          count = end - index,
        )
      }
      for (i in index until end) {
        val rated = streams[i]
        out += StreamListItem.Release(
          key = "row:${rated.occurrence}\n${rated.stream.url ?: rated.stream.label}",
          index = i,
          stream = rated.stream,
          quality = rated.quality,
        )
      }
      index = end
    }
    return out
  }

  /** `"<kind>:<name>"`, suffixed only where the same name has already been handed out. */
  private fun occurrenceKey(kind: String, name: String, seen: MutableMap<String, Int>): String {
    val occurrence = seen[name] ?: 0
    seen[name] = occurrence + 1
    return if (occurrence == 0) "$kind:$name" else "$kind:$name#$occurrence"
  }

  /** A filename as a line of words: no container, no scene punctuation, no double spaces. */
  private fun clean(text: String): String = EXTENSION.replace(text.trim(), "")
    .replace(SEPARATORS, " ")
    .replace(WHITESPACE, " ")
    .trim()

  private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

  /**
   * Markers that only count as a word of their own - the same discipline [StreamQuality] applies,
   * and for the same reason: "web" is inside "webrip", "cam" is inside "camera", "dts" is inside
   * "dtshd". Compiled once rather than per call: this runs for every one of forty rows.
   */
  private val TOKENS = listOf("web", "cam", "dts")
    .associateWith { Regex("(?<![a-z0-9])$it(?![a-z0-9])") }

  private fun String.containsToken(token: String): Boolean =
    TOKENS.getValue(token).containsMatchIn(this)
}
