package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.tmdb.EpisodeItem
import com.stremioshell.host.tv.data.tmdb.MediaDetails
import com.stremioshell.host.tv.data.tmdb.MediaType

@Composable
fun DetailsScreen(
  viewModel: TvAppViewModel,
  screen: Screen.Details,
  onPlay: (details: MediaDetails, season: Int?, episode: Int?) -> Unit,
) {
  val type = screen.type
  val tmdbId = screen.tmdbId
  val detailsState by viewModel.details.collectAsState()
  val episodesState by viewModel.episodes.collectAsState()
  val watching by viewModel.continueWatching.collectAsState()

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
        it.tmdbId == media.item.tmdbId && it.season != null && it.episode != null
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
    LaunchedEffect(canAimAtEpisode) { if (canAimAtEpisode) handedToEpisode = true }

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
        AsyncImage(
          model = media.item.backdropUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().alpha(0.25f),
        )
      }

      LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        item(key = "header") {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(media.item.title, style = MaterialTheme.typography.displaySmall)
            val meta = listOfNotNull(
              media.item.year,
              media.runtimeMinutes?.let { "${it} min" },
              media.item.rating?.let { "%.1f".format(it) + " / 10" },
              media.genres.take(3).joinToString(", ").ifBlank { null },
            ).joinToString("   ")
            Text(meta, style = MaterialTheme.typography.bodyMedium)
            Text(
              media.item.overview,
              style = MaterialTheme.typography.bodyLarge,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.fillMaxWidth(0.7f),
            )
            if (media.imdbId == null) {
              Text(
                "No IMDb id found for this title; streams are unavailable.",
                style = MaterialTheme.typography.bodySmall,
              )
            } else if (media.item.type == MediaType.Movie) {
              val entry = watching.firstOrNull { it.key == "movie:${media.item.tmdbId}" }
              Button(
                onClick = { onPlay(media, null, null) },
                modifier = Modifier.initialFocusTarget(primaryFocus),
              ) {
                Text(if (entry != null) "Resume" else "Find Streams")
              }
              if (entry != null) ResumeHint(entry)
            } else if (showResume != null) {
              Button(
                onClick = { onPlay(media, showResume.season, showResume.episode) },
                modifier = Modifier.initialFocusTarget(primaryFocus),
              ) {
                Text("Resume S${showResume.season}E${showResume.episode}")
              }
              ResumeHint(showResume)
            }
            if (needsFallbackAction) {
              if (media.item.type == MediaType.Show) {
                Text(
                  "No seasons were returned for this title.",
                  style = MaterialTheme.typography.bodySmall,
                )
              }
              // Keeps the screen navigable when there is nothing to play: without these the
              // whole route has no focusable node and only BACK works.
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                  onClick = goBack,
                  modifier = Modifier.initialFocusTarget(primaryFocus),
                ) {
                  Text("Back")
                }
                Button(onClick = { viewModel.loadDetails(type, tmdbId) }) {
                  Text("Retry")
                }
              }
            }
          }
        }

        if (hasSeasonRow) {
          item(key = "seasons") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              items(media.seasons, key = { it.seasonNumber }) { season ->
                Button(
                  onClick = {
                    pickedSeason = season.seasonNumber
                    resumeAimArmed = false
                  },
                  modifier = (if (season.seasonNumber == selectedSeason) Modifier else Modifier.alpha(0.6f))
                    // The *selected* season, not the first: a resume arrival opens on S4 and
                    // focus has to follow it. Yields to a header Resume button when there is one.
                    .initialFocusTarget(
                      if (!hasHeaderAction && season.seasonNumber == selectedSeason) primaryFocus else null,
                    ),
                ) {
                  Text("Season ${season.seasonNumber}")
                }
              }
            }
          }

          item(key = "episodes") {
            LoadStateContentInline(
              episodes,
              onRetry = { viewModel.loadSeason(media.item.tmdbId, selectedSeason) },
            ) { list ->
              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { episode ->
                  val entry = watching.firstOrNull {
                    it.key == "episode:${media.item.tmdbId}:${episode.seasonNumber}:${episode.episodeNumber}"
                  }
                  val isResumeTarget = episode.episodeNumber == resumeEpisode
                  Card(
                    onClick = { onPlay(media, episode.seasonNumber, episode.episodeNumber) },
                    modifier = Modifier.fillMaxWidth(0.8f)
                      .initialFocusTarget(if (isResumeTarget) resumeFocus else null),
                  ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                      // Accent bar rather than a Card border: tv-material3 swaps the border out for
                      // focusedBorder, so a border marker would vanish the moment focus lands here.
                      if (isResumeTarget) {
                        Box(
                          modifier = Modifier
                            .width(4.dp)
                            .height(90.dp)
                            .background(MaterialTheme.colorScheme.primary),
                        )
                      }
                      if (episode.stillUrl != null) {
                        AsyncImage(
                          model = episode.stillUrl,
                          contentDescription = null,
                          contentScale = ContentScale.Crop,
                          modifier = Modifier.width(160.dp).height(90.dp),
                        )
                      }
                      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                          "E${episode.episodeNumber}  ${episode.name}",
                          style = MaterialTheme.typography.titleMedium,
                          color = if (isResumeTarget) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                        Text(
                          episode.overview,
                          style = MaterialTheme.typography.bodySmall,
                          maxLines = 2,
                          overflow = TextOverflow.Ellipsis,
                        )
                        // Watched progress, so a part-way episode reads the same here as it does
                        // on its Continue Watching card.
                        if (entry != null && entry.durationMs > 0) {
                          Box(
                            modifier = Modifier
                              .fillMaxWidth(0.6f)
                              .height(4.dp)
                              .background(MaterialTheme.colorScheme.surfaceVariant),
                          ) {
                            Box(
                              modifier = Modifier
                                .fillMaxWidth(entry.progress)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary),
                            )
                          }
                        }
                        val marker = listOfNotNull(
                          "Resume here".takeIf { isResumeTarget },
                          entry?.minutesLeft()?.let { "$it min left" },
                        ).joinToString("  -  ")
                        if (marker.isNotEmpty()) {
                          Text(
                            marker,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isResumeTarget) MaterialTheme.colorScheme.primary else Color.Unspecified,
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/** Whole minutes left in a saved position, or null when the duration was never learned. */
private fun WatchEntry.minutesLeft(): Long? =
  if (durationMs > 0) ((durationMs - positionMs) / 60_000).coerceAtLeast(1) else null

@Composable
private fun ResumeHint(entry: WatchEntry) {
  val minsLeft = entry.minutesLeft() ?: return
  Text(
    "$minsLeft min left - picks up where you stopped",
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun <T> LoadStateContentInline(
  state: LoadState<T>,
  onRetry: (() -> Unit)? = null,
  content: @Composable (T) -> Unit,
) {
  when (state) {
    is LoadState.Loading -> Text("Loading episodes...")
    // A bare error message would be unreachable by the D-pad, so failures always offer Retry.
    is LoadState.Failed -> Column(
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(state.message)
      if (onRetry != null) {
        Button(onClick = onRetry) { Text("Retry") }
      }
    }
    is LoadState.Ready -> content(state.value)
  }
}
