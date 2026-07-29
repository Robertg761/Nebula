package com.stremioshell.host.tv.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrushVertical
import com.stremioshell.host.tv.ui.theme.NebulaBackdropScrim
import com.stremioshell.host.tv.ui.theme.NebulaBottomScrim
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaMotion
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.tv.ui.theme.nebulaButtonBorder
import com.stremioshell.host.tv.ui.theme.nebulaButtonGlow
import com.stremioshell.host.tv.ui.theme.nebulaCardBorder
import com.stremioshell.host.tv.ui.theme.nebulaCardGlow
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

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

/**
 * Extra air above a rail, so "Cast" starts a new section rather than reading as one more row.
 *
 * Expressed as what it is - the list's own item spacing completed up to the gap Home puts between
 * rails - rather than as the bare 18dp it used to be, which happened to add up to the same number
 * and would have silently drifted the first time [NebulaDimens.RailGap] was retuned.
 */
private val SECTION_GAP = NebulaDimens.RailGap - NebulaDimens.CardGap

/** The title may run wider than the copy, because it is the one thing set at 40sp. */
private const val HEADER_TITLE_WIDTH = 0.72f

/** One right edge for the metadata chips, the synopsis and the notices under them. */
private const val HEADER_COPY_WIDTH = 0.62f

/** An episode row is a wide element, not a full-bleed one: it stops well short of the right edge. */
private const val EPISODE_ROW_WIDTH = 0.8f

/** The resume marker's gutter, reserved on every row so only its colour changes. */
private val EPISODE_GUTTER = 4.dp

/** How much of the still is left when the episode has not aired, and when it has been finished. */
private const val UNAIRED_STILL_ALPHA = 0.45f
private const val WATCHED_STILL_ALPHA = 0.75f

/**
 * How far the page scrolls before the backdrop has fully resolved into the page colour.
 *
 * About two thirds of a viewport: long enough that the fade reads as the artwork receding rather
 * than as a light being switched off, short enough that the cast row never sits on a bright sky.
 */
private val BACKDROP_SETTLE = 320.dp

/** How much of the row above a resumed episode is left showing when the screen scrolls to it. */
private val RESUME_PEEK = 96.dp

/** Where a newly focused row settles: a line 18% down the viewport, as on Home. */
private const val FOCUS_LINE_FRACTION = 0.18f

/** Placeholder rows drawn while a season is in flight. */
private const val EPISODE_SKELETON_ROWS = 3

/**
 * What the header's play control starts, and what it says.
 *
 * A show only ever got one of these when it had an *unfinished* watch record, so a series nobody
 * had started rendered a header whose only control was "Add to My List" - and the initial focus
 * request fell through to the selected season chip, whose bring-into-view then dragged the title,
 * the artwork and the metadata off the top of the screen. Now every playable title has one, and
 * the label is the only thing that changes when the episode list lands: the node that owns focus
 * is there from the first frame and never migrates.
 *
 * @param resume the saved position this picks up from, or null when it starts from the top. Also
 *   what the progress readout above the button is drawn from.
 * @param spoken what TalkBack says instead of "S4E2", which it reads a letter and a digit at a time.
 */
private data class HeaderPlay(
  val season: Int?,
  val episode: Int?,
  val label: String,
  val resume: WatchEntry?,
  val spoken: String?,
)

@OptIn(ExperimentalFoundationApi::class)
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
  // Recomputed only when the calendar can actually change. Refreshing on resume covers a TV that
  // slept across midnight; the timer covers one left open on Details overnight.
  var today by remember { mutableStateOf(LocalDate.now()) }
  LifecycleResumeEffect(Unit) {
    today = LocalDate.now()
    onPauseOrDispose { }
  }
  LaunchedEffect(today) {
    val now = ZonedDateTime.now()
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
    delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
    today = LocalDate.now()
  }

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
    // The shape of the page that is arriving, not a spinner in the middle of a black screen: the
    // viewer just pressed OK on a card carrying a title and a poster, and the app used to throw
    // all of it away and then snap from centre-of-screen to a left-aligned header.
    loading = { DetailsSkeleton() },
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
    var pickedSeason by rememberSaveable(type, tmdbId) { mutableIntStateOf(defaultSeason) }
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

    val hasSeasonRow = media.item.type == MediaType.Show && media.seasons.isNotEmpty()

    // What the header plays when there is no saved position: the first episode of the selected
    // season nobody has finished, skipping anything that has not aired, and falling back to the
    // season's first episode once the whole thing is watched. One scan of a list that is already
    // in memory, and only when it or the watch records change.
    val firstUnplayed = remember(episodes, watching, media.item.tmdbId, today) {
      val list = when (val state = episodes) {
        is LoadState.Ready -> state.value
        else -> emptyList()
      }
      val aired = list.filterNot { AirDate.isUpcoming(it.airDate, today) }
      aired.firstOrNull { episode ->
        !AirDate.isUpcoming(episode.airDate, today) &&
          watching.none {
            it.key == "episode:${media.item.tmdbId}:${episode.seasonNumber}:${episode.episodeNumber}" &&
              it.watched
          }
      } ?: aired.firstOrNull()
    }

    // Derived in one place so that the *node* is the same in every state and only its label moves.
    val headerPlay: HeaderPlay? = when {
      media.imdbId == null -> null
      media.item.type == MediaType.Movie -> {
        val entry = watching.firstOrNull { it.key == "movie:${media.item.tmdbId}" }
        // Only a real resume point has something to start over from; a watched record already
        // plays from 0:00, so it needs no second button.
        val resume = entry?.takeIf { !it.watched && it.positionMs > 0 }
        HeaderPlay(
          season = null,
          episode = null,
          label = when {
            resume != null -> "Resume"
            entry?.watched == true -> "Watch again"
            // Was "Find Streams": plumbing language on the only violet button on the page, beside
            // a play glyph that flatly disagreed with it. Picking a stream is how this app plays a
            // film, not what the viewer came here to do.
            else -> "Play"
          },
          resume = resume,
          spoken = null,
        )
      }
      // A saved position wins, wherever it came from - including a next episode the player seeded,
      // which has never been played and so is offered as "Play" rather than as a resume of
      // something sitting at 0:00.
      showResume != null -> {
        val resumable = showResume.positionMs > 0
        val verb = if (resumable) "Resume" else "Play"
        HeaderPlay(
          season = showResume.season,
          episode = showResume.episode,
          label = "$verb S${showResume.season}E${showResume.episode}",
          resume = showResume.takeIf { resumable },
          spoken = A11yLabels.episodeCode(showResume.season, showResume.episode)?.let { "$verb $it" },
        )
      }
      hasSeasonRow && firstUnplayed != null -> HeaderPlay(
        season = firstUnplayed.seasonNumber,
        episode = firstUnplayed.episodeNumber,
        label = "Play S${firstUnplayed.seasonNumber}E${firstUnplayed.episodeNumber}",
        resume = null,
        spoken = A11yLabels.episodeCode(firstUnplayed.seasonNumber, firstUnplayed.episodeNumber)
          ?.let { "Play $it" },
      )
      else -> null
    }

    // Exactly one control owns the initial focus, and every state must have one: a title with
    // no IMDb id or a show with no seasons used to render pure text, leaving the D-pad dead.
    // Whichever branch of the header renders, that control now lives *in the header*, so the
    // request can never scroll the title out of view to satisfy itself.
    val needsFallbackAction = headerPlay == null
    val episodesPending = hasSeasonRow && episodes is LoadState.Loading
    val allEpisodesUpcoming = episodes is LoadState.Ready &&
      episodes.value.isNotEmpty() &&
      episodes.value.all { AirDate.isUpcoming(it.airDate, today) }
    val retryHeader: (() -> Unit)? = when {
      headerPlay != null ||
        media.imdbId == null ||
        !hasSeasonRow ||
        episodesPending ||
        allEpisodesUpcoming -> null
      else -> {
        { viewModel.loadSeason(media.item.tmdbId, selectedSeason) }
      }
    }

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
    // Captured out here because the effect below cannot read composition locals.
    val resumePeekPx = with(LocalDensity.current) { RESUME_PEEK.roundToPx() }
    LaunchedEffect(canAimAtEpisode) {
      if (!canAimAtEpisode) return@LaunchedEffect
      handedToEpisode = true
      // The episode list is lazy, so the row focus is being aimed at is very likely not composed
      // yet - a resume ten episodes into a season is off screen. Put it in view first: a focus
      // request at a node that does not exist just times out and leaves focus in the header.
      val index = (episodes as? LoadState.Ready)?.value
        ?.indexOfFirst { it.episodeNumber == resumeEpisode }
        ?: -1
      // Negative offset, so the row above peeks in under the top edge. Landed flush, the one
      // arrival meant to feel like the app remembered you was also the one with nothing on screen
      // to say the list continued upward.
      if (index >= 0) listState.scrollToItem(EPISODE_ITEM_OFFSET + index, scrollOffset = -resumePeekPx)
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

    val railSpec = rememberDetailsRailSpec()

    Box(modifier = Modifier.fillMaxSize()) {
      if (media.item.backdropUrl != null) {
        val context = LocalContext.current
        // One alpha on one node, once per screen - the cheapest animation there is, and a
        // full-bleed image that cuts in at full opacity is the most jarring frame in the whole
        // browsing flow. Held as a State and read inside the layer block so the fade invalidates
        // the draw phase only; read with `by` it would recompose this subtree ~25 times.
        var backdropLoaded by remember(media.item.backdropUrl) { mutableStateOf(false) }
        val backdropAlpha = animateFloatAsState(
          targetValue = if (backdropLoaded) 1f else 0f,
          animationSpec = NebulaMotion.backdrop(),
          label = "backdropFade",
        )
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
          onSuccess = { backdropLoaded = true },
          modifier = Modifier.fillMaxSize().graphicsLayer { alpha = backdropAlpha.value },
        )
        // A wash rather than a flat dim: an evenly dimmed backdrop is equally in the way of the
        // title at the top and the episode list at the bottom, so it ends up dimmed until it is
        // no longer worth showing. This one is nearly clear where the artwork is the point and
        // resolves into the page colour behind everything that scrolls over it.
        Box(modifier = Modifier.fillMaxSize().background(NebulaBackdropScrim))
        // The scrim is fixed to the viewport, so on its own it only made that claim true for the
        // bottom of the screen: however far the page scrolled, the top 45% stayed the title's
        // backdrop, and the cast headshots and "More like this" posters ended up sitting on an
        // unrelated still with a bright sky behind them. Reading the scroll inside the layer block
        // is a draw-phase read - no recomposition, no relayout, one extra solid quad on a GPU that
        // is already compositing two.
        Box(
          modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
              alpha = if (listState.firstVisibleItemIndex > 0) {
                1f
              } else {
                (listState.firstVisibleItemScrollOffset / BACKDROP_SETTLE.toPx()).coerceIn(0f, 1f)
              }
            }
            .background(NebulaPalette.Void),
        )
      }

      CompositionLocalProvider(LocalBringIntoViewSpec provides DetailsFocusLineSpec) {
        LazyColumn(
          state = listState,
          // Horizontal padding lives on the items, not here: the rails below full-bleed to the
          // screen edge and RailHeading brings its own overscan margin. The vertical values are
          // Home's, so the first line of content does not jump as you navigate between them.
          contentPadding = PaddingValues(top = NebulaDimens.ScreenEdgeVertical, bottom = 56.dp),
          verticalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
          modifier = Modifier
            .fillMaxSize()
            // Each row already remembers its card; this is what makes the column remember the row,
            // so coming back from the nav rail or a stream returns to the episode the viewer was
            // on rather than re-entering the page by geometry. The initial-focus requests above
            // still win on a cold open: they name a node directly rather than entering through
            // this group, and there is nothing saved here to restore.
            .restoreColumnFocus(),
        ) {
          item(key = "header") {
            // No uniform `spacedBy` on this Column. One gap for all four kinds of block gave the
            // header no vertical hierarchy at all - the distance from a 40sp title to its metadata
            // was the distance from the synopsis to the Play button - so each block states its own
            // top padding instead.
            Column(modifier = Modifier.padding(horizontal = NebulaDimens.ScreenEdge)) {
              TitleTreatment(
                title = media.item.title,
                logoUrl = media.logoUrl,
                style = MaterialTheme.typography.displaySmall,
                isHeading = true,
                // Two lines of displaySmall (40sp on a 50sp line), so a title with a logo and one
                // without put the metadata chips in exactly the same place.
                logoHeight = 100.dp,
                modifier = Modifier.fillMaxWidth(HEADER_TITLE_WIDTH),
              )
              MetadataBadges(
                media,
                modifier = Modifier
                  .padding(top = NebulaSpace.sm)
                  .fillMaxWidth(HEADER_COPY_WIDTH),
              )
              ExpandableOverview(
                text = media.item.overview,
                // Keyed on the title so opening another one starts collapsed, and so the flag
                // survives the season switches and watch-state updates that recompose this header.
                stateKey = "$type:$tmdbId",
              )
              val inWatchlist = WatchlistEntry.keyOf(media.item.type, media.item.tmdbId) in watchlistKeys
              val toggleWatchlist = { viewModel.toggleWatchlist(media.item) }
              if (media.imdbId == null) {
                // On a surface rather than as loose copy, and in the viewer's terms rather than
                // the addon lookup's: "IMDb id" is a fact about how streams are found, not about
                // the film. The same class of message shipped on Streams as a bare line and read
                // as the screen's error message.
                HeaderNotice("We can't look up streams for this title yet - TMDB has no IMDb id for it.")
              } else if (needsFallbackAction && media.item.type == MediaType.Show) {
                HeaderNotice(
                  when {
                    episodesPending -> "Checking this season for playable episodes…"
                    allEpisodesUpcoming -> "The listed episodes have not aired yet."
                    media.seasons.isEmpty() -> "No seasons have been listed for this title yet."
                    else -> "No playable episodes are available for this season."
                  },
                )
              }
              val resumeMinutes = headerPlay?.resume?.minutesLeft()
              // Context before the action. This used to be the sentence "45 min left - picks up
              // where you stopped" below the button. The first action is now also unconditional:
              // while episodes load it is a safe Back control, then that same composition node
              // becomes Play once TMDB has supplied an actual episode number.
              if (resumeMinutes != null) ResumeProgress(headerPlay.resume, resumeMinutes)
              HeaderActions(
                play = headerPlay,
                focusTarget = primaryFocus,
                onPlay = { startOver ->
                  headerPlay?.let { playable ->
                    onPlay(media, playable.season, playable.episode, startOver)
                  }
                },
                onBack = goBack,
                onRetry = retryHeader,
                title = media.item.title,
                inWatchlist = inWatchlist,
                onToggleWatchlist = toggleWatchlist,
                modifier = Modifier.padding(
                  top = if (resumeMinutes != null) NebulaSpace.sm else NebulaSpace.xl,
                ),
              )
            }
          }

          if (hasSeasonRow) {
            item(key = "seasons") {
              // The bottom padding is what stops the selector reading as the first item of the
              // list rather than as the control for it: at the list's own 16dp it was closer to
              // the first episode than the episodes are to each other.
              Column(modifier = Modifier.padding(top = SECTION_GAP, bottom = NebulaSpace.sm)) {
                RailHeading("Episodes")
                CompositionLocalProvider(LocalBringIntoViewSpec provides railSpec) {
                  LazyRow(
                    modifier = Modifier.restoreRowFocus(),
                    // No vertical padding for the focused chip's glow: foundation clips the scroll
                    // axis only and inflates the cross axis by 30dp of elevation headroom, which
                    // was confirmed on a device. This carried 8dp on the opposite belief.
                    contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge),
                    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
                  ) {
                    items(media.seasons, key = { it.seasonNumber }) { season ->
                      SeasonChip(
                        label = season.label,
                        selected = season.seasonNumber == selectedSeason,
                        onClick = {
                          pickedSeason = season.seasonNumber
                          resumeAimArmed = false
                        },
                      )
                    }
                  }
                }
              }
            }

            // One lazy item per episode rather than one item holding a Column of all of them:
            // a 24-episode season used to compose every row up front, and a season switch paid for
            // all 24 before the first one appeared.
            when (val loaded = episodes) {
              // Not a line of text: loadSeason sets Loading on every uncached switch, so pressing
              // a season chip collapsed a 24-row list to one grey line and then repainted it,
              // taking the cast row and "More like this" hundreds of dp up the page and back.
              is LoadState.Loading -> items(
                EPISODE_SKELETON_ROWS,
                key = { "episode-skeleton-$it" },
              ) { EpisodeSkeleton() }
              // A bare error message would be unreachable by the D-pad, so failures offer Retry.
              is LoadState.Failed -> item(key = "episodes-status") {
                Column(
                  verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
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
            item(key = "cast") { CastRow(media.cast, railSpec) }
          }
          if (media.similar.isNotEmpty()) {
            // The Home rail as-is, so recommendations are browsed with exactly the card, spacing
            // and focus restoration the viewer already learned one screen back. It brings its own
            // bring-into-view spec, so the column's vertical rule does not reach it.
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
}

/**
 * Where a newly focused node settles on this page.
 *
 * Home pins every focused row to a line 18% down the viewport, and two screens with the same shape
 * scrolling two different ways is something a viewer feels without being able to name it. Details
 * cannot use Home's rule verbatim, though: there every focusable is a whole row whose top *is* the
 * item's top, while here the header is one ~450dp item whose focusable is the Play button near its
 * foot. Applied unconditionally, the line would scroll the title, the artwork and the metadata off
 * the top the instant the screen opened - which is the defect this screen shipped with, by another
 * route.
 *
 * So the line only claims a node that is not already fully in view: arriving in the header moves
 * nothing, and driving down the episode list still settles each row on the line instead of jamming
 * it against the bottom edge one row at a time.
 */
@OptIn(ExperimentalFoundationApi::class)
private val DetailsFocusLineSpec = object : BringIntoViewSpec {
  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    val line = containerSize * FOCUS_LINE_FRACTION
    return when {
      // Above the viewport: bring it back down onto the line.
      offset < 0f -> offset - line
      // Already fully visible: leave the page where the viewer put it.
      offset + size <= containerSize -> 0f
      // Below the fold and short enough to sit under the line once it gets there.
      size <= containerSize - line -> offset - line
      // Taller than the space under the line, which would cut its foot off: reveal the bottom edge
      // and nothing more.
      else -> offset + size - containerSize
    }
  }
}

/**
 * How the two hand-rolled rails on this page scroll when focus moves along them.
 *
 * A LazyRow nested inside the column above inherits [DetailsFocusLineSpec] through the same
 * composition local, which would apply a rule written for the vertical axis to a horizontal scroll
 * and pin the focused chip or headshot 18% in from the left, leaving a sliced card in the margin.
 * MediaRow owns the identical rail rule, but privately inside Components.kt, so the season row and
 * the cast row re-provide their own copy: scroll the minimum needed to keep the focused item, its
 * ring and its glow clear of both edges, and nothing at all when it is already comfortably in view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberDetailsRailSpec(): BringIntoViewSpec {
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
 * The shape of a title page while it is loading.
 *
 * The whole screen used to be handed to a 40dp spinner dead centre of an empty page, and when the
 * response landed the layout snapped from the middle of the screen to a left-aligned header with a
 * full-bleed backdrop. This is where the header is going to be, so nothing jumps. Static - no
 * shimmer, nothing invalidating a frame - and [DelayedBusy] means a cached title never shows it.
 */
@Composable
private fun DetailsSkeleton() {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(top = NebulaDimens.ScreenEdgeVertical, start = NebulaDimens.ScreenEdge)
      // Announced, this is a page of grey rectangles.
      .clearAndSetSemantics {},
  ) {
    // The title's own line, at the height displaySmall occupies.
    Box(
      modifier = Modifier
        .fillMaxWidth(0.42f)
        .height(44.dp)
        .background(NebulaPalette.SurfaceVariant, NebulaShapes.extraSmall),
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
      modifier = Modifier.padding(top = NebulaSpace.sm),
    ) {
      listOf(58.dp, 92.dp, 74.dp).forEach { width ->
        Box(
          modifier = Modifier
            .width(width)
            .height(24.dp)
            .background(NebulaPalette.SurfaceVariant, NebulaShapes.extraSmall),
        )
      }
    }
    Column(
      verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
      modifier = Modifier.padding(top = NebulaSpace.lg).fillMaxWidth(HEADER_COPY_WIDTH),
    ) {
      listOf(1f, 0.96f, 0.72f).forEach { fraction ->
        Box(
          modifier = Modifier
            .fillMaxWidth(fraction)
            .height(14.dp)
            .background(NebulaPalette.Surface, NebulaShapes.extraSmall),
        )
      }
    }
    Box(
      modifier = Modifier
        .padding(top = NebulaSpace.xl)
        .width(168.dp)
        .height(44.dp)
        .background(NebulaPalette.Surface, NebulaShapes.large),
    )
  }
}

/**
 * A statement about this title that the viewer cannot act on and that is not a failure.
 *
 * On a surface rather than as loose copy: the identical class of message shipped on the stream list
 * as a bare line of body text and read as the screen's error message. StreamsScreen keeps its own
 * copy of this privately; this one stays local rather than reaching into another screen's internals,
 * and both want lifting into the shared controls.
 */
@Composable
private fun HeaderNotice(text: String, modifier: Modifier = Modifier) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .padding(top = NebulaSpace.lg)
      .fillMaxWidth(HEADER_COPY_WIDTH)
      .background(NebulaPalette.Surface, NebulaShapes.medium)
      .padding(horizontal = NebulaSpace.md, vertical = NebulaSpace.sm),
  ) {
    Icon(
      Icons.Filled.Warning,
      // Decorative: the sentence beside it is the content.
      contentDescription = null,
      // Amber, not the failure colour: nothing has gone wrong, the catalog is simply incomplete.
      tint = NebulaPalette.Caution,
      modifier = Modifier.size(NebulaIcon.sm),
    )
    Text(
      text,
      style = MaterialTheme.typography.bodySmall,
      color = NebulaPalette.TextMuted,
    )
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
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
    verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
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
 *
 * No initial-focus target any more. The selected chip used to take it whenever the header had no
 * play control, and its bring-into-view then scrolled the title, the artwork and the metadata off
 * the top of the screen the moment a show opened. The header now always owns a focusable control.
 */
@Composable
private fun SeasonChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val shape = NebulaShapes.large
  val colors = if (selected) {
    ButtonDefaults.colors(
      containerColor = NebulaPalette.Violet,
      // Dark ink at rest as well as focused, exactly as the primary button does it: TextHigh on
      // Violet measures 3.12:1 at the 15sp SemiBold this chip carries, on the one control whose
      // whole job is to say which season you are looking at from three metres. This is 5.13:1.
      contentColor = NebulaPalette.OnAccent,
      focusedContainerColor = NebulaPalette.VioletBright,
      focusedContentColor = NebulaPalette.OnAccent,
    )
  } else {
    ButtonDefaults.colors(
      containerColor = NebulaPalette.SurfaceVariant,
      contentColor = NebulaPalette.TextMuted,
      // Focus brightens without going violet, which would read as a second selected season.
      focusedContainerColor = NebulaPalette.SurfaceRaised,
      focusedContentColor = NebulaPalette.TextHigh,
    )
  }
  Button(
    onClick = onClick,
    modifier = Modifier.semantics { this.selected = selected },
    colors = colors,
    shape = ButtonDefaults.shape(shape = shape),
    border = nebulaButtonBorder(shape),
    glow = nebulaButtonGlow(),
    scale = ButtonDefaults.scale(focusedScale = NebulaDimens.FocusScaleButton),
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
  val minutesLeft = entry?.minutesLeft()
  // Still built, and still joined with the separator A11yLabels turns into spoken pauses: this
  // string is what TalkBack reads. What the eye gets is the chip row at the foot of the text
  // column, because as one faint hyphen-joined sentence it was the least readable thing in the row
  // and also its only statement of state.
  val marker = listOfNotNull(
    // "Airs <date>" rather than a bare date, because that is the whole explanation for why
    // pressing OK on this row does nothing.
    airLabel?.let { if (upcoming) "Airs $it" else it },
    "Resume here".takeIf { isResumeTarget },
    "Watched".takeIf { entry?.watched == true },
    minutesLeft?.let { "$it min left" },
  ).joinToString("  -  ")
  val watched = entry?.watched == true
  val titleColor = when {
    isResumeTarget -> NebulaPalette.VioletBright
    // Dimmed content rather than a dimmed card. The alpha used to sit on the whole node, ahead of
    // the focus modifiers, so an unaired row rendered its own focus ring and glow at 60% - the one
    // affordance that says where the remote is pointing, dimmed on exactly the rows that already
    // look inert - and its Surface went translucent enough to let the backdrop through.
    upcoming || watched -> NebulaPalette.TextMuted
    else -> NebulaPalette.TextHigh
  }
  val stillAlpha = when {
    upcoming -> UNAIRED_STILL_ALPHA
    watched -> WATCHED_STILL_ALPHA
    else -> 1f
  }
  // A finished episode already says so with its tick and its dimmed still; a full accent bar as
  // well made a watched season a wall of violet pulling the eye toward the episodes the viewer
  // least needs. The bar is now what it says it is: how far in am I.
  val partWatched = entry != null && entry.durationMs > 0 && !entry.watched && entry.positionMs > 0
  val description = A11yLabels.episodeRow(episode.episodeNumber, episode.name, marker)
  val rowModifier = Modifier
    .padding(horizontal = NebulaDimens.ScreenEdge)
    .fillMaxWidth(EPISODE_ROW_WIDTH)
    .initialFocusTarget(focusTarget)
  val rowContent: @Composable () -> Unit = {
    Row(
      modifier = Modifier.padding(NebulaSpace.md),
      horizontalArrangement = Arrangement.spacedBy(NebulaSpace.md),
    ) {
      // Accent bar rather than a Card border: tv-material3 swaps the border out for focusedBorder,
      // so a border marker would vanish the moment focus lands here. The gutter is reserved on
      // every row - emitted only when it was needed, it shifted the one row the eye is being sent
      // to 20dp right of every other row in the list.
      Box(
        modifier = Modifier
          .width(EPISODE_GUTTER)
          .height(NebulaDimens.StillHeight)
          .background(
            if (isResumeTarget) NebulaAccentBrushVertical else SolidColor(Color.Transparent),
            RoundedCornerShape(2.dp),
          ),
      )
      // The slot is drawn whether or not there is a still. Skipping it cost the row 151dp of
      // height and moved its text 286dp left, so a season where TMDB has stills for some episodes
      // and not others had two different layouts and no rhythm - and a *failed* still held the
      // slot while a missing one did not, which is two answers to one question.
      Box(
        modifier = Modifier
          .width(NebulaDimens.StillWidth)
          .height(NebulaDimens.StillHeight)
          .clip(NebulaDimens.PosterShape),
      ) {
        ArtworkImage(
          url = episode.stillUrl,
          // Decorative: the row's own description carries the number and the name.
          contentDescription = null,
          modifier = Modifier
            .fillMaxSize()
            .then(if (stillAlpha < 1f) Modifier.alpha(stillAlpha) else Modifier),
        ) {
          // Mirrors MediaCard's artless poster: the number is the one thing about this episode
          // that is certainly known.
          Text(
            "E${episode.episodeNumber}",
            style = MaterialTheme.typography.headlineSmall,
            color = NebulaPalette.TextFaint,
          )
        }
        if (partWatched && entry != null) {
          // On the artwork, over a scrim, exactly as the Continue Watching card does it. Under the
          // text it read as a divider rule between the synopsis and the marker line rather than as
          // progress, so the one shared component looked like two different things.
          Box(
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .height(40.dp)
              .background(NebulaBottomScrim),
          )
          NebulaProgressBar(
            progress = entry.progress,
            height = 4.dp,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .padding(horizontal = NebulaSpace.xs)
              .padding(bottom = NebulaSpace.xs),
          )
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(NebulaSpace.xs)) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // The tick is the whole point of keeping a finished episode's record: a season list
          // that never says what you have seen is the reason "which one was I on" is a question
          // at all. It rides beside the title rather than on the still, because an episode with
          // no artwork still has to be able to say it has been watched.
          if (watched) {
            Icon(
              Icons.Filled.CheckCircle,
              // Decorative: the row's own description already carries "Watched".
              contentDescription = null,
              tint = NebulaPalette.Success,
              modifier = Modifier.size(NebulaIcon.sm),
            )
          }
          // The number set as a quiet figure in its own gutter, not glued to the name with a
          // double space: as one string it could not be styled, the gap was font-dependent and
          // matched none of the spacing in the row, and on a resume row the number turned violet
          // along with the name.
          Text(
            "${episode.episodeNumber}",
            style = MaterialTheme.typography.titleLarge,
            color = NebulaPalette.TextFaint,
            modifier = Modifier.widthIn(min = 30.dp),
          )
          Text(
            episode.name,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          episode.overview,
          style = MaterialTheme.typography.bodySmall,
          color = if (upcoming) NebulaPalette.TextFaint else NebulaPalette.TextMuted,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (isResumeTarget || minutesLeft != null || airLabel != null) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = NebulaSpace.xxs),
          ) {
            if (isResumeTarget) NebulaBadge("Resume here", tone = BadgeTone.Accent)
            if (minutesLeft != null) NebulaBadge("$minutesLeft min left", tone = BadgeTone.Neutral)
            // Amber, not red: an episode that has not aired yet is a caveat, not a failure, and
            // this chip is the whole explanation for why OK does nothing on this row.
            if (upcoming && airLabel != null) NebulaBadge("Airs $airLabel", tone = BadgeTone.Warn)
            // An aired date is a plain fact, so it stays plain type - but at TextMuted (7.9:1)
            // rather than the TextFaint it used to share with everything else on this line.
            if (!upcoming && airLabel != null) {
              Text(
                airLabel,
                style = MaterialTheme.typography.labelMedium,
                color = NebulaPalette.TextMuted,
              )
            }
          }
        }
      }
    }
  }
  if (upcoming) {
    // A future episode is information, not a disabled or fake button. It remains reachable so a
    // remote user can inspect its date and season position, but OK has no click action to consume.
    FocusableInfoSurface(
      description = description,
      shape = NebulaShapes.medium,
      focusedScale = NebulaDimens.FocusScaleWide,
      modifier = rowModifier,
      content = { rowContent() },
    )
  } else {
    Card(
      onClick = onPlay,
      shape = CardDefaults.shape(shape = NebulaShapes.medium),
      colors = CardDefaults.colors(
        containerColor = NebulaPalette.Surface,
        contentColor = NebulaPalette.TextHigh,
        focusedContainerColor = NebulaPalette.SurfaceRaised,
        focusedContentColor = NebulaPalette.TextHigh,
      ),
      border = nebulaCardBorder(NebulaShapes.medium),
      glow = nebulaCardGlow(),
      scale = CardDefaults.scale(focusedScale = NebulaDimens.FocusScaleWide),
      modifier = rowModifier.semantics(mergeDescendants = true) {
        contentDescription = description
      },
      content = { rowContent() },
    )
  }
}

/**
 * An episode row before its season has arrived.
 *
 * Static - no shimmer, nothing invalidating a frame - and not focusable: the season chips keep the
 * D-pad alive while the list is in flight, exactly as they did when this was one line of grey text.
 */
@Composable
private fun EpisodeSkeleton() {
  Row(
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.md),
    modifier = Modifier
      .padding(horizontal = NebulaDimens.ScreenEdge)
      .fillMaxWidth(EPISODE_ROW_WIDTH)
      .background(NebulaPalette.Surface, NebulaShapes.medium)
      .padding(NebulaSpace.md)
      // A row of grey rectangles has nothing to announce.
      .clearAndSetSemantics {},
  ) {
    // Same gutter, still and gaps as the real row, so nothing moves sideways when the season lands.
    Box(modifier = Modifier.width(EPISODE_GUTTER).height(NebulaDimens.StillHeight))
    Box(
      modifier = Modifier
        .width(NebulaDimens.StillWidth)
        .height(NebulaDimens.StillHeight)
        .background(NebulaPalette.SurfaceVariant, NebulaDimens.PosterShape),
    )
    Column(verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm)) {
      Box(
        modifier = Modifier
          .fillMaxWidth(0.6f)
          .height(18.dp)
          .background(NebulaPalette.SurfaceVariant, NebulaShapes.extraSmall),
      )
      Box(
        modifier = Modifier
          .fillMaxWidth(0.9f)
          .height(12.dp)
          .background(NebulaPalette.SurfaceVariant, NebulaShapes.extraSmall),
      )
      Box(
        modifier = Modifier
          .fillMaxWidth(0.75f)
          .height(12.dp)
          .background(NebulaPalette.SurfaceVariant, NebulaShapes.extraSmall),
      )
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
 * Brings its own top padding, because the header has no uniform gap to inherit: the paragraph
 * separates from the metadata above it more than its own "More" button separates from it.
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
    modifier = Modifier.padding(top = NebulaSpace.lg).fillMaxWidth(HEADER_COPY_WIDTH),
  )
  if (overflowed) {
    // Ghost: reading more of the synopsis is an aside, and this button sits directly above Play.
    NebulaButton(
      text = if (expanded) "Less" else "More",
      onClick = { expanded = !expanded },
      style = NebulaButtonStyle.Ghost,
      modifier = Modifier.padding(top = NebulaSpace.xxs),
    )
  }
}

/**
 * A remote-focusable surface that presents information without pretending it can be opened.
 *
 * TV Material's Card requires an onClick and therefore exposes a click action to accessibility
 * even when that callback is empty. Upcoming episodes and cast portraits need traversal and a
 * strong focus affordance, but no fake action.
 */
@Composable
private fun FocusableInfoSurface(
  description: String,
  shape: Shape,
  focusedScale: Float,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  Box(
    modifier = modifier
      .graphicsLayer {
        val scale = if (focused) focusedScale else 1f
        scaleX = scale
        scaleY = scale
        shadowElevation = if (focused) 12.dp.toPx() else 0f
        this.shape = shape
      }
      .background(
        color = if (focused) NebulaPalette.SurfaceRaised else NebulaPalette.Surface,
        shape = shape,
      )
      .border(
        width = if (focused) 3.dp else 1.dp,
        color = if (focused) NebulaPalette.VioletBright else NebulaPalette.Outline,
        shape = shape,
      )
      .onFocusChanged { focused = it.isFocused }
      .focusable()
      .semantics(mergeDescendants = true) { contentDescription = description },
    content = content,
  )
}

/**
 * The billed cast, headshot first.
 *
 * Every portrait is a focusable information surface: a row a remote cannot enter is a row that
 * does not exist on TV, but there is no person screen to open yet, so it deliberately exposes no
 * click action. Focus passes straight through left/right and out again vertically.
 *
 * @param railSpec how the row scrolls; see [rememberDetailsRailSpec] for why it cannot inherit the
 *   page's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CastRow(cast: List<CastMember>, railSpec: BringIntoViewSpec) {
  Column(modifier = Modifier.padding(top = SECTION_GAP)) {
    RailHeading("Cast")
    CompositionLocalProvider(LocalBringIntoViewSpec provides railSpec) {
      LazyRow(
        modifier = Modifier.restoreRowFocus(),
        contentPadding = PaddingValues(horizontal = NebulaDimens.ScreenEdge),
        horizontalArrangement = Arrangement.spacedBy(NebulaDimens.CardGap),
      ) {
        // Index in the key: TMDB credits the same person twice on plenty of titles (an actor who
        // also voices something), and a duplicate key crashes a lazy list outright.
        items(cast.size, key = { "${cast[it].id}:$it" }) { index ->
          val member = cast[index]
          Column(modifier = Modifier.width(NebulaDimens.PortraitWidth)) {
            FocusableInfoSurface(
              description = A11yLabels.castMember(member.name, member.character),
              shape = NebulaDimens.PosterShape,
              focusedScale = NebulaDimens.FocusScale,
              modifier = Modifier
                .width(NebulaDimens.PortraitWidth)
                .height(NebulaDimens.PortraitHeight),
            ) {
              ArtworkImage(
                url = member.profileUrl,
                // Decorative: the headshot's card is labelled with the same name.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
              ) {
                // The shared artless treatment rather than a wrapped three-line name on a grey
                // slab, so a missing headshot still reads as a card in the row.
                PosterFallback(member.name)
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
              modifier = Modifier.padding(top = NebulaDimens.CardCaptionGap).clearAndSetSemantics {},
            )
            if (member.character.isNotBlank()) {
              Text(
                member.character,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = NebulaPalette.TextMuted,
                modifier = Modifier.padding(top = NebulaSpace.xxs).clearAndSetSemantics {},
              )
            }
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
 * How far into this title the viewer already is, stated the way every other surface states it: a
 * bar and one fact, above the control it is context for.
 */
@Composable
private fun ResumeProgress(entry: WatchEntry?, minutesLeft: Long) {
  if (entry == null) return
  Column(modifier = Modifier.padding(top = NebulaSpace.xl)) {
    NebulaProgressBar(entry.progress, modifier = Modifier.fillMaxWidth(0.42f))
    Text(
      "$minutesLeft min left",
      style = MaterialTheme.typography.labelMedium,
      color = NebulaPalette.TextMuted,
      modifier = Modifier.padding(top = NebulaSpace.xs),
    )
  }
}

/**
 * The header's primary control and its companions. The primary node is unconditional: it is Back
 * while a show has no confirmed playable episode, and becomes Play without moving focus when the
 * episode list arrives. "Start over" is separate because a TV remote has no modifier press.
 *
 * The My List toggle rides in the same row rather than on a line of its own: it is the
 * one thing a viewer reaches for when they are not going to press Play, so it should be
 * one press right of it rather than one press down and past the season buttons.
 */
@Composable
private fun HeaderActions(
  play: HeaderPlay?,
  focusTarget: InitialFocusTarget,
  onPlay: (startOver: Boolean) -> Unit,
  onBack: () -> Unit,
  onRetry: (() -> Unit)?,
  title: String,
  inWatchlist: Boolean,
  onToggleWatchlist: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Read out of the model first: a smart cast that has to survive into the semantics lambda is
  // easier to lose than a local.
  val currentPlay = play
  val spoken = currentPlay?.spoken
  Row(
    horizontalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
    modifier = modifier,
  ) {
    NebulaButton(
      text = currentPlay?.label ?: "Back",
      onClick = {
        if (currentPlay == null) onBack() else onPlay(false)
      },
      // The one thing the viewer came here to press, and the only violet on the page that is not
      // a focus ring.
      style = NebulaButtonStyle.Primary,
      icon = if (currentPlay == null) {
        Icons.AutoMirrored.Filled.ArrowBack
      } else {
        Icons.Filled.PlayArrow
      },
      modifier = Modifier.initialFocusTarget(focusTarget)
        .then(
          if (spoken == null) {
            Modifier
          } else {
            Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }
          },
        ),
    )
    // Only a real saved position has something to start over from; a watched record already plays
    // from 0:00, and a first episode has never been played at all.
    if (currentPlay?.resume != null) {
      NebulaButton(
        text = "Start over",
        onClick = { onPlay(true) },
        icon = Icons.Filled.Refresh,
      )
    }
    if (currentPlay == null && onRetry != null) {
      NebulaButton(
        text = "Retry",
        onClick = onRetry,
        icon = Icons.Filled.Refresh,
      )
    }
    WatchlistButton(title, inWatchlist, onToggleWatchlist)
  }
}

/**
 * One button that both reports membership and changes it. Two buttons - one to add, one
 * to remove - would mean the row's width changed under the D-pad on every press.
 *
 * @param focusTarget set only where this is the header's sole control, which is the one state that
 *   has no play button and no fallback row to hand the D-pad to.
 */
@Composable
private fun WatchlistButton(
  title: String,
  inWatchlist: Boolean,
  onToggle: () -> Unit,
  focusTarget: InitialFocusTarget? = null,
) {
  NebulaButton(
    text = if (inWatchlist) "In My List" else "Add to My List",
    onClick = onToggle,
    icon = if (inWatchlist) Icons.Filled.Check else Icons.Filled.Add,
    // The visible label is a tick and a state - what pressing it does is left to the icon, which
    // reads at a glance and not at all out loud. Spoken, it has to say what will happen, and to
    // what.
    modifier = Modifier.initialFocusTarget(focusTarget)
      .semantics(mergeDescendants = true) {
        contentDescription = A11yLabels.watchlistButton(title, inWatchlist)
      },
  )
}
