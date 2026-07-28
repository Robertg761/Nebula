package com.stremioshell.host.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchlistEntry
import com.stremioshell.host.tv.data.tmdb.AirDate
import com.stremioshell.host.tv.data.tmdb.CastMember
import com.stremioshell.host.tv.data.tmdb.DetailsMetadata
import com.stremioshell.host.tv.data.tmdb.EpisodeItem
import com.stremioshell.host.tv.data.tmdb.MediaDetails
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrush
import com.stremioshell.host.tv.ui.theme.NebulaBackdropScrim
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.nebulaButtonBorder
import com.stremioshell.host.tv.ui.theme.nebulaButtonGlow
import com.stremioshell.host.tv.ui.theme.nebulaCardBorder
import com.stremioshell.host.tv.ui.theme.nebulaCardGlow
import java.time.LocalDate

/** Lines of synopsis shown before the "More" affordance takes over. */
private const val OVERVIEW_COLLAPSED_LINES = 4

/**
 * Lazy items that always sit above the episodes: the header, and the episode section's own item -
 * the "Episodes" heading and the season row deliberately share one, so this offset stays a constant
 * two rather than moving with the heading. Only used to scroll a resumed episode into view, and the
 * season row is a precondition for having one at all.
 */
private const val EPISODE_ITEM_OFFSET = 2

/** Splits [DetailsMetadata]'s one line back into the facts it joined, for the badge row. */
private val METADATA_SEPARATOR = Regex("""\s*•\s*""")

/** Extra air above a rail, so "Cast" starts a new section rather than reading as one more row. */
private val SECTION_GAP = 18.dp

@Composable
fun DetailsScreen(
  viewModel: TvAppViewModel,
  screen: Screen.Details,
  onItemClick: (MediaType, Int) -> Unit,
  onPlay: (details: MediaDetails, season: Int?, episode: Int?, startOver: Boolean) -> Unit,
) {
  val type = screen.type
  val tmdbId = screen.tmdbId
  val detailsState by viewModel.details.collectAsState()
  val episodesState by viewModel.episodes.collectAsState()
  // Every record, watched ones included: this screen marks finished episodes as well as
  // part-watched ones, so it cannot use the Continue Watching projection.
  val watching by viewModel.watchEntries.collectAsState()
  val watchlistKeys by viewModel.watchlistKeys.collectAsState()
  // Read once: an episode list that recomputed "has this aired" on every recomposition would be
  // doing date arithmetic per frame for a boundary that moves once a day.
  val today = remember { LocalDate.now() }

  LaunchedEffect(type, tmdbId) { viewModel.loadDetails(type, tmdbId) }

  // The details flow can still hold the PREVIOUS title on this screen's first frame: loadDetails
  // only runs from the effect above, one composition later. Rendering that Ready value would flash
  // the wrong title and, worse, seed the season selection from the wrong season list - so anything
  // that is not this screen's title counts as still loading.
  val details: LoadState<MediaDetails> = when (val state = detailsState) {
    is LoadState.Ready ->
      if (state.value.item.tmdbId == tmdbId && state.value.item.type == type) state else LoadState.Loading
    else -> state
  }

  LoadStateContent(
    details,
    loadingText = "Loading details...",
    onRetry = { viewModel.loadDetails(type, tmdbId) },
  ) { media ->
    // Everything below is clamped to the seasons TMDB actually returned.
    val seasonNumbers = media.seasons.map { it.seasonNumber }
    // Where a Continue Watching arrival wants to land, when that season still exists.
    val resumeSeason = screen.initialSeason?.takeIf { it in seasonNumbers }
    val defaultSeason = resumeSeason ?: seasonNumbers.firstOrNull() ?: 1
    // Keyed on the title: an unkeyed rememberSaveable slot is reused by composition position, so
    // show B could otherwise open on the season the user last picked for show A. The remembered
    // (or restored) value is clamped below too - a saved "Season 7" means nothing for a two-season
    // show, and would leave the episode list stuck on an empty request.
    var pickedSeason by rememberSaveable(type, tmdbId) { mutableStateOf(defaultSeason) }
    val selectedSeason = if (pickedSeason in seasonNumbers) pickedSeason else defaultSeason
    // Aiming focus at the stopped-at episode is a one-shot arrival gesture; picking a season by
    // hand hands focus control back to the user.
    var resumeAimArmed by rememberSaveable(type, tmdbId) { mutableStateOf(screen.initialEpisode != null) }
    val primaryFocus = rememberInitialFocusTarget()
    val resumeFocus = rememberInitialFocusTarget()
    val goBack = rememberBackAction()
    val listState = rememberLazyListState()

    // Same staleness trap one level down: the episode list can still be the previously selected
    // season's until loadSeason's effect has run.
    val episodes: LoadState<List<EpisodeItem>> = when (val state = episodesState) {
      is LoadState.Ready ->
        if (state.value.all { it.seasonNumber == selectedSeason }) state else LoadState.Loading
      else -> state
    }

    // Newest saved position for this show, preferring the exact episode the user clicked in
    // Continue Watching. Shows get the same Resume affordance movies always had.
    val showResume = if (media.item.type == MediaType.Show && media.imdbId != null) {
      val forShow = watching.filter {
        it.tmdbId == media.item.tmdbId && it.season != null && it.episode != null && !it.watched
      }
      forShow.firstOrNull { it.season == screen.initialSeason && it.episode == screen.initialEpisode }
        ?: forShow.firstOrNull()
    } else {
      null
    }

    // Exactly one control owns the initial focus, and every state must have one: a title with
    // no IMDb id or a show with no seasons used to render pure text, leaving the D-pad dead.
    val hasSeasonRow = media.item.type == MediaType.Show && media.seasons.isNotEmpty()
    val canFindStreams = media.imdbId != null && media.item.type == MediaType.Movie
    val hasHeaderAction = canFindStreams || showResume != null
    val needsFallbackAction = !hasSeasonRow && !hasHeaderAction

    // The episode this arrival should mark and focus, once its season is the one on screen.
    val resumeEpisode = screen.initialEpisode?.takeIf { resumeAimArmed && selectedSeason == resumeSeason }
    // Focus only moves down to the episode once it is really in the loaded list. Until then - and
    // forever, if the season never loads - the header action keeps the D-pad alive, so this never
    // trades a working screen for a request aimed at a node that does not exist.
    val canAimAtEpisode = resumeEpisode != null &&
      episodes is LoadState.Ready && episodes.value.any { it.episodeNumber == resumeEpisode }
    // Latched, because RequestInitialFocus re-runs whenever `enabled` changes: letting the primary
    // request switch back on (the user picks another season, the resumed episode stops being the
    // target) would yank focus off whatever they had just moved to.
    // Deliberately not saveable: an activity recreation loses focus outright, so the primary
    // request has to be free to re-arm and put it back.
    var handedToEpisode by remember(type, tmdbId) { mutableStateOf(false) }
    LaunchedEffect(canAimAtEpisode) {
      if (!canAimAtEpisode) return@LaunchedEffect
      handedToEpisode = true
      // The episode list is lazy, so the row focus is being aimed at is very likely not composed
      // yet - a resume ten episodes into a season is off screen. Put it in view first: a focus
      // request at a node that does not exist just times out and leaves focus in the header.
      val index = (episodes as? LoadState.Ready)?.value
        ?.indexOfFirst { it.episodeNumber == resumeEpisode }
        ?: -1
      if (index >= 0) listState.scrollToItem(EPISODE_ITEM_OFFSET + index)
    }

    // Land focus on the primary action instead of leaving it in the nav rail. This wins the first
    // frames even on a resume arrival - the episode list is still loading then - and hands over
    // below once the stopped-at episode exists.
    RequestInitialFocus(
      target = primaryFocus,
      key = media.item.tmdbId,
      label = "Details primary action",
      enabled = !handedToEpisode,
    )
    RequestInitialFocus(
      target = resumeFocus,
      key = "$tmdbId:$selectedSeason:$resumeEpisode",
      label = "Resumed episode",
      enabled = canAimAtEpisode,
    )

    LaunchedEffect(media.item.tmdbId, selectedSeason) {
      if (media.item.type == MediaType.Show && media.seasons.isNotEmpty()) {
        viewModel.loadSeason(media.item.tmdbId, selectedSeason)
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      if (media.item.backdropUrl != null) {
        val context = LocalContext.current
        AsyncImage(
          // The backdrop is one large, scrimmed panel, where the app-wide RGB_565 decode shows as
          // banding steps across its gradients. allowRgb565 is the load-bearing override: the
          // decoder downgrades an ARGB_8888 request back to 565 for JPEGs while that flag is on.
          // Only this image opts out - posters stay 565, which is what keeps rows cheap.
          model = remember(context, media.item.backdropUrl) {
            ImageRequest.Builder(context)
              .data(media.item.backdropUrl)
              .bitmapConfig(Bitmap.Config.ARGB_8888)
              .allowRgb565(false)
              .build()
          },
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        // A wash rather than a flat dim: an evenly dimmed backdrop is equally in the way of the
        // title at the top and the episode list at the bottom, so it ends up dimmed until it is
        // no longer worth showing. This one is nearly clear where the artwork is the point and
        // resolves into the page colour behind everything that scrolls over it.
        Box(modifier = Modifier.fillMaxSize().background(NebulaBackdropScrim))
      }

      LazyColumn(
        state = listState,
        // Horizontal padding lives on the items, not here: the rails below full-bleed to the
        // screen edge and RailHeading brings its own overscan margin.
        contentPadding = PaddingValues(top = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
        modifier = Modifier.fillMaxSize(),
      ) {
        item(key = "header") {
          Column(
            verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
            modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge),
          ) {
            Text(
              media.item.title,
              style = MaterialTheme.typography.displaySmall,
              color = NebulaPalette.TextHigh,
              modifier = Modifier.fillMaxWidth(0.72f),
            )
            MetadataBadges(media, modifier = Modifier.fillMaxWidth(0.62f))
            ExpandableOverview(
              text = media.item.overview,
              // Keyed on the title so opening another one starts collapsed, and so the flag
              // survives the season switches and watch-state updates that recompose this header.
              stateKey = "$type:$tmdbId",
            )
            val inWatchlist = WatchlistEntry.keyOf(media.item.type, media.item.tmdbId) in watchlistKeys
            val toggleWatchlist = { viewModel.toggleWatchlist(media.item) }
            if (media.imdbId == null) {
              Text(
                "No IMDb id found for this title; streams are unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = NebulaPalette.TextMuted,
              )
            }
            if (media.imdbId != null && media.item.type == MediaType.Movie) {
              val entry = watching.firstOrNull { it.key == "movie:${media.item.tmdbId}" }
              val resumable = entry?.takeIf { !it.watched && it.positionMs > 0 }
              PlayActions(
                playLabel = when {
                  resumable != null -> "Resume"
                  entry?.watched == true -> "Watch again"
                  else -> "Find Streams"
                },
                // Only a real resume point has something to start over from; a watched
                // record already plays from 0:00, so it needs no second button.
                offerStartOver = resumable != null,
                focusTarget = primaryFocus,
                onPlay = { startOver -> onPlay(media, null, null, startOver) },
                title = media.item.title,
                inWatchlist = inWatchlist,
                onToggleWatchlist = toggleWatchlist,
              )
              if (resumable != null) ResumeHint(resumable)
            } else if (media.imdbId != null && showResume != null) {
              val episodeLabel = "S${showResume.season}E${showResume.episode}"
              val resumable = showResume.positionMs > 0
              PlayActions(
                // A next episode seeded by the player's up-next has never been played, so
                // it is offered as "Play", not as a resume of something at 0:00.
                playLabel = if (resumable) "Resume $episodeLabel" else "Play $episodeLabel",
                offerStartOver = resumable,
                focusTarget = primaryFocus,
                onPlay = { startOver ->
                  onPlay(media, showResume.season, showResume.episode, startOver)
                },
                title = media.item.title,
                inWatchlist = inWatchlist,
                onToggleWatchlist = toggleWatchlist,
                // "S4E2" is announced a letter and a digit at a time, which is not what the
                // viewer needs to hear before pressing the one button that starts playback.
                playDescription = A11yLabels.episodeCode(showResume.season, showResume.episode)
                  ?.let { "${if (resumable) "Resume" else "Play"} $it" },
              )
              if (resumable) ResumeHint(showResume)
            } else {
              // Saving for later is offered on every title, including the ones with nothing
              // to play from the header: a series with no resume point is the main reason a
              // viewer wants the button at all.
              Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
                WatchlistButton(media.item.title, inWatchlist, toggleWatchlist)
              }
            }
            if (needsFallbackAction) {
              if (media.item.type == MediaType.Show) {
                Text(
                  "No seasons were returned for this title.",
                  style = MaterialTheme.typography.bodySmall,
                  color = NebulaPalette.TextMuted,
                )
              }
              // Keeps the screen navigable when there is nothing to play: without these the
              // whole route has no focusable node and only BACK works.
              Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
                NebulaButton(
                  text = "Back",
                  onClick = goBack,
                  // Nothing else on this screen can be pressed, so leaving is the primary action.
                  style = NebulaButtonStyle.Primary,
                  icon = Icons.AutoMirrored.Filled.ArrowBack,
                  modifier = Modifier.initialFocusTarget(primaryFocus),
                )
                NebulaButton(
                  text = "Retry",
                  onClick = { viewModel.loadDetails(type, tmdbId) },
                  icon = Icons.Filled.Refresh,
                )
              }
            }
          }
        }

        if (hasSeasonRow) {
          item(key = "seasons") {
            Column(modifier = Modifier.padding(top = SECTION_GAP)) {
              RailHeading("Episodes")
              LazyRow(
                modifier = Modifier.restoreRowFocus(),
                // Slack in the padding rather than the arrangement: a focused chip scales up and
                // spills its glow, and a LazyRow clips to its own edges.
                contentPadding = PaddingValues(
                  horizontal = NebulaDimens.ScreenEdge,
                  vertical = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                items(media.seasons, key = { it.seasonNumber }) { season ->
                  SeasonChip(
                    label = season.label,
                    selected = season.seasonNumber == selectedSeason,
                    onClick = {
                      pickedSeason = season.seasonNumber
                      resumeAimArmed = false
                    },
                    // The *selected* season, not the first: a resume arrival opens on S4 and
                    // focus has to follow it. Yields to a header Resume button when there is one.
                    focusTarget = if (!hasHeaderAction && season.seasonNumber == selectedSeason) {
                      primaryFocus
                    } else {
                      null
                    },
                  )
                }
              }
            }
          }

          // One lazy item per episode rather than one item holding a Column of all of them:
          // a 24-episode season used to compose every row up front, and a season switch paid for
          // all 24 before the first one appeared.
          when (val loaded = episodes) {
            is LoadState.Loading -> item(key = "episodes-status") {
              Text(
                "Loading episodes...",
                style = MaterialTheme.typography.bodyMedium,
                color = NebulaPalette.TextMuted,
                modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge),
              )
            }
            // A bare error message would be unreachable by the D-pad, so failures offer Retry.
            is LoadState.Failed -> item(key = "episodes-status") {
              Column(
                verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
                modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge),
              ) {
                Text(
                  loaded.message,
                  style = MaterialTheme.typography.bodyMedium,
                  color = NebulaPalette.TextMuted,
                )
                NebulaButton(
                  text = "Retry",
                  onClick = { viewModel.loadSeason(media.item.tmdbId, selectedSeason) },
                  icon = Icons.Filled.Refresh,
                )
              }
            }
            is LoadState.Ready -> items(
              loaded.value.size,
              key = { "episode:${loaded.value[it].seasonNumber}:${loaded.value[it].episodeNumber}" },
            ) { index ->
              val episode = loaded.value[index]
              EpisodeRow(
                episode = episode,
                entry = watching.firstOrNull {
                  it.key == "episode:${media.item.tmdbId}:${episode.seasonNumber}:${episode.episodeNumber}"
                },
                isResumeTarget = episode.episodeNumber == resumeEpisode,
                // Nothing has been released for an episode that has not aired, so its streams
                // would come back empty. It stays focusable with a no-op OK rather than being
                // made unfocusable: a whole unaired season would otherwise render as a block the
                // D-pad cannot enter or read.
                upcoming = AirDate.isUpcoming(episode.airDate, today),
                focusTarget = if (episode.episodeNumber == resumeEpisode) resumeFocus else null,
                // Clicking a watched episode replays it: its stored position is 0, so startOver
                // would change nothing and is left false.
                onPlay = { onPlay(media, episode.seasonNumber, episode.episodeNumber, false) },
              )
            }
          }
        }

        // Below the episode list, in the order a viewer asks the questions: who is in this, then
        // what else is like it.
        if (media.cast.isNotEmpty()) {
          item(key = "cast") { CastRow(media.cast) }
        }
        if (media.similar.isNotEmpty()) {
          // The Home rail as-is, so recommendations are browsed with exactly the card, spacing and
          // focus restoration the viewer already learned one screen back.
          item(key = "similar") {
            Box(modifier = Modifier.padding(top = SECTION_GAP)) {
              MediaRow(
                title = "More like this",
                items = media.similar,
                onItemClick = { onItemClick(it.type, it.tmdbId) },
              )
            }
          }
        }
      }
    }
  }
}

/**
 * The facts under the title, one chip apiece.
 *
 * Split back out of [DetailsMetadata]'s single line rather than reassembled from its parts: which
 * fields a given title actually has, and the order they read in, is the rule that object exists to
 * own and that its tests pin down. A chip row that rebuilt it would be a second copy of that rule,
 * free to drift.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataBadges(details: MediaDetails, modifier: Modifier = Modifier) {
  // Remembered because this header recomposes on every watch-state update, and the metadata only
  // changes when the title does.
  val line = remember(details) { DetailsMetadata.of(details) }
  val chips = remember(line, details.item.rating) {
    val score = DetailsMetadata.scoreLabel(details.item.rating)
    line.split(METADATA_SEPARATOR)
      // Genres arrive as one comma-joined field, and no other fact in the line carries a comma:
      // as chips they read better one apiece than as a single wide pill.
      .flatMap { it.split(", ") }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { it to (it == score) }
  }
  if (chips.isEmpty()) return
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    // Six chips announced one at a time is six stops for a fact each; spoken, this is one line,
    // and A11yLabels turns its visual separators into the pauses TalkBack needs.
    modifier = modifier.clearAndSetSemantics { contentDescription = A11yLabels.spoken(line) },
  ) {
    chips.forEach { (text, isScore) ->
      // The score is the one fact a viewer scans for, so it is the only one that gets colour.
      NebulaBadge(text, tone = if (isScore) BadgeTone.Accent else BadgeTone.Neutral)
    }
  }
}

/**
 * One season in the selector.
 *
 * Selection is carried by the fill, not by opacity: the row used to mark the unselected seasons
 * with 0.6 alpha, which at three metres is indistinguishable from "this season's label is a bit
 * grey". Focus is the ring and the glow on top, so a focused chip still says which season is the
 * one being shown.
 */
@Composable
private fun SeasonChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  focusTarget: InitialFocusTarget?,
) {
  val shape = NebulaShapes.large
  val colors = if (selected) {
    ButtonDefaults.colors(
      containerColor = NebulaPalette.Violet,
      contentColor = NebulaPalette.TextHigh,
      focusedContainerColor = NebulaPalette.VioletBright,
      focusedContentColor = NebulaPalette.Void,
    )
  } else {
    ButtonDefaults.colors(
      containerColor = NebulaPalette.SurfaceVariant,
      contentColor = NebulaPalette.TextMuted,
      // Focus brightens without going violet, which would read as a second selected season.
      focusedContainerColor = NebulaPalette.Outline,
      focusedContentColor = NebulaPalette.TextHigh,
    )
  }
  Button(
    onClick = onClick,
    colors = colors,
    shape = ButtonDefaults.shape(shape = shape),
    border = nebulaButtonBorder(shape),
    glow = nebulaButtonGlow(),
    scale = ButtonDefaults.scale(focusedScale = 1.05f),
    modifier = Modifier.initialFocusTarget(focusTarget),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
  }
}

/**
 * One episode row: the still, then everything known about it.
 *
 * @param entry this episode's watch record, if it has one: the tick, the progress bar and the
 *   time left all come from it.
 * @param upcoming the episode has not aired, so OK does nothing and the row says why.
 */
@Composable
private fun EpisodeRow(
  episode: EpisodeItem,
  entry: WatchEntry?,
  isResumeTarget: Boolean,
  upcoming: Boolean,
  focusTarget: InitialFocusTarget?,
  onPlay: () -> Unit,
) {
  val airLabel = AirDate.label(episode.airDate)
  val marker = listOfNotNull(
    // "Airs <date>" rather than a bare date, because that is the whole explanation for why
    // pressing OK on this row does nothing.
    airLabel?.let { if (upcoming) "Airs $it" else it },
    "Resume here".takeIf { isResumeTarget },
    "Watched".takeIf { entry?.watched == true },
    entry?.minutesLeft()?.let { "$it min left" },
  ).joinToString("  -  ")
  val accent = if (isResumeTarget) NebulaPalette.VioletBright else NebulaPalette.TextHigh
  Card(
    onClick = { if (!upcoming) onPlay() },
    shape = CardDefaults.shape(shape = NebulaShapes.medium),
    colors = CardDefaults.colors(
      containerColor = NebulaPalette.Surface,
      contentColor = NebulaPalette.TextHigh,
      focusedContainerColor = NebulaPalette.SurfaceVariant,
      focusedContentColor = NebulaPalette.TextHigh,
    ),
    border = nebulaCardBorder(NebulaShapes.medium),
    glow = nebulaCardGlow(),
    // A row is most of the screen wide, so it lifts less than a poster does or it would grow
    // straight through the overscan margin.
    scale = CardDefaults.scale(focusedScale = NebulaDimens.FocusScaleWide),
    // The row is the focusable node, and its number, tick and progress bar are shapes rather than
    // anything a screen reader can reach. The synopsis is deliberately left out: two truncated
    // lines of flavour would stand between the viewer and the next episode.
    modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge)
      .fillMaxWidth(0.8f)
      .then(if (upcoming) Modifier.alpha(0.6f) else Modifier)
      .initialFocusTarget(focusTarget)
      .semantics(mergeDescendants = true) {
        contentDescription = A11yLabels.episodeRow(episode.episodeNumber, episode.name, marker)
      },
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      // Accent bar rather than a Card border: tv-material3 swaps the border out for
      // focusedBorder, so a border marker would vanish the moment focus lands here.
      if (isResumeTarget) {
        Box(
          modifier = Modifier
            .width(4.dp)
            .height(NebulaDimens.StillHeight)
            .background(NebulaAccentBrush, RoundedCornerShape(2.dp)),
        )
      }
      // Null keeps its no-op: an absent still should not reserve an empty slot next to the
      // episode text, while a failed one holds its tonal block.
      if (episode.stillUrl != null) {
        ArtworkImage(
          url = episode.stillUrl,
          contentDescription = null,
          modifier = Modifier
            .width(NebulaDimens.StillWidth)
            .height(NebulaDimens.StillHeight)
            .clip(NebulaDimens.PosterShape),
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // The tick is the whole point of keeping a finished episode's record: a season list
          // that never says what you have seen is the reason "which one was I on" is a question
          // at all. It rides beside the title rather than on the still, because an episode with
          // no artwork still has to be able to say it has been watched.
          if (entry?.watched == true) {
            Icon(
              Icons.Filled.CheckCircle,
              // Decorative: the row's own description already carries "Watched".
              contentDescription = null,
              tint = NebulaPalette.Success,
              modifier = Modifier.size(18.dp),
            )
          }
          Text(
            "E${episode.episodeNumber}  ${episode.name}",
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          episode.overview,
          style = MaterialTheme.typography.bodySmall,
          color = NebulaPalette.TextMuted,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        // Watched progress, so a part-way episode reads the same here as it does on its
        // Continue Watching card.
        if (entry != null && entry.durationMs > 0) {
          NebulaProgressBar(entry.progress, modifier = Modifier.fillMaxWidth(0.6f))
        }
        if (marker.isNotEmpty()) {
          Text(
            marker,
            style = MaterialTheme.typography.labelMedium,
            color = if (isResumeTarget) NebulaPalette.VioletBright else NebulaPalette.TextFaint,
          )
        }
      }
    }
  }
}

/**
 * Synopsis capped at [OVERVIEW_COLLAPSED_LINES] with an inline expander.
 *
 * A truncated synopsis with no way to read the rest is the complaint; a dialog would be the other
 * option, but expanding in place keeps the D-pad exactly where it was, and the button is a real
 * focusable node so a remote can reach it at all.
 *
 * @param stateKey resets the expanded flag when the screen switches to another title.
 */
@Composable
private fun ExpandableOverview(text: String, stateKey: String) {
  if (text.isBlank()) return
  var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
  // Latched, never cleared: expanding removes the overflow that revealed the button, and clearing
  // the flag would take the "Less" button away with it - stranding focus on a node that vanished.
  var overflowed by rememberSaveable(stateKey) { mutableStateOf(false) }

  Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = NebulaPalette.TextMuted,
    maxLines = if (expanded) Int.MAX_VALUE else OVERVIEW_COLLAPSED_LINES,
    overflow = TextOverflow.Ellipsis,
    onTextLayout = { if (it.hasVisualOverflow) overflowed = true },
    modifier = Modifier.fillMaxWidth(0.7f),
  )
  if (overflowed) {
    // Ghost: reading more of the synopsis is an aside, and this button sits directly above Play.
    NebulaButton(
      text = if (expanded) "Less" else "More",
      onClick = { expanded = !expanded },
      style = NebulaButtonStyle.Ghost,
    )
  }
}

/**
 * The billed cast, headshot first.
 *
 * Every card is focusable with a deliberately empty click: a row a remote cannot enter is a row
 * that does not exist on TV, and there is no person screen to open yet. Focus passes straight
 * through left/right and out again vertically, so nothing is trapped.
 */
@Composable
private fun CastRow(cast: List<CastMember>) {
  Column(modifier = Modifier.padding(top = SECTION_GAP)) {
    RailHeading("Cast")
    LazyRow(
      modifier = Modifier.restoreRowFocus(),
      contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
    ) {
      // Index in the key: TMDB credits the same person twice on plenty of titles (an actor who
      // also voices something), and a duplicate key crashes a lazy list outright.
      items(cast.size, key = { "${cast[it].id}:$it" }) { index ->
        val member = cast[index]
        Column(modifier = Modifier.width(NebulaDimens.PortraitWidth)) {
          Card(
            onClick = {},
            shape = CardDefaults.shape(shape = NebulaDimens.PosterShape),
            border = nebulaCardBorder(),
            glow = nebulaCardGlow(),
            scale = CardDefaults.scale(focusedScale = NebulaDimens.FocusScale),
            modifier = Modifier
              .width(NebulaDimens.PortraitWidth)
              .height(NebulaDimens.PortraitHeight)
              .semantics(mergeDescendants = true) {
                contentDescription = A11yLabels.castMember(member.name, member.character)
              },
          ) {
            ArtworkImage(
              url = member.profileUrl,
              // Decorative: the headshot's card is labelled with the same name.
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
            ) {
              Text(
                member.name,
                maxLines = 3,
                style = MaterialTheme.typography.labelMedium,
                color = NebulaPalette.TextMuted,
                modifier = Modifier.padding(horizontal = 8.dp),
              )
            }
          }
          Text(
            member.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = NebulaPalette.TextHigh,
            // Clears the focused card's scaled-up bottom edge, as on the poster rows. Semantics
            // cleared for the same reason as the poster captions: the card already says this.
            modifier = Modifier.padding(top = 12.dp).clearAndSetSemantics {},
          )
          if (member.character.isNotBlank()) {
            Text(
              member.character,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.labelSmall,
              color = NebulaPalette.TextMuted,
              modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {},
            )
          }
        }
      }
    }
  }
}

/**
 * Whole minutes left in a saved position, or null when there is no position to be part
 * way through: an unstarted or finished record has none, and "45 min left" under a
 * watched episode reads as if it had never been played.
 */
private fun WatchEntry.minutesLeft(): Long? =
  if (durationMs > 0 && positionMs > 0 && !watched) {
    ((durationMs - positionMs) / 60_000).coerceAtLeast(1)
  } else {
    null
  }

/**
 * The header's play control(s). "Start over" is a separate button rather than a mode on
 * the first one because a resume that cannot be overridden is the complaint this fixes,
 * and a TV remote has nowhere to put a modifier press.
 *
 * The My List toggle rides in the same row rather than on a line of its own: it is the
 * one thing a viewer reaches for when they are not going to press Play, so it should be
 * one press right of it rather than one press down and past the season buttons.
 */
@Composable
private fun PlayActions(
  playLabel: String,
  offerStartOver: Boolean,
  focusTarget: InitialFocusTarget,
  onPlay: (startOver: Boolean) -> Unit,
  title: String,
  inWatchlist: Boolean,
  onToggleWatchlist: () -> Unit,
  playDescription: String? = null,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
    NebulaButton(
      text = playLabel,
      onClick = { onPlay(false) },
      // The one thing the viewer came here to press, and the only violet on the page that is not
      // a focus ring.
      style = NebulaButtonStyle.Primary,
      icon = Icons.Filled.PlayArrow,
      modifier = Modifier.initialFocusTarget(focusTarget)
        .then(
          if (playDescription == null) {
            Modifier
          } else {
            Modifier.semantics(mergeDescendants = true) { contentDescription = playDescription }
          },
        ),
    )
    if (offerStartOver) {
      NebulaButton(
        text = "Start over",
        onClick = { onPlay(true) },
        icon = Icons.Filled.Refresh,
      )
    }
    WatchlistButton(title, inWatchlist, onToggleWatchlist)
  }
}

/**
 * One button that both reports membership and changes it. Two buttons - one to add, one
 * to remove - would mean the row's width changed under the D-pad on every press.
 */
@Composable
private fun WatchlistButton(title: String, inWatchlist: Boolean, onToggle: () -> Unit) {
  NebulaButton(
    text = if (inWatchlist) "In My List" else "Add to My List",
    onClick = onToggle,
    icon = if (inWatchlist) Icons.Filled.Check else Icons.Filled.Add,
    // The visible label is a tick and a state - what pressing it does is left to the icon, which
    // reads at a glance and not at all out loud. Spoken, it has to say what will happen, and to
    // what.
    modifier = Modifier.semantics(mergeDescendants = true) {
      contentDescription = A11yLabels.watchlistButton(title, inWatchlist)
    },
  )
}

@Composable
private fun ResumeHint(entry: WatchEntry) {
  val minsLeft = entry.minutesLeft() ?: return
  Text(
    "$minsLeft min left - picks up where you stopped",
    style = MaterialTheme.typography.bodySmall,
    color = NebulaPalette.TextMuted,
  )
}
