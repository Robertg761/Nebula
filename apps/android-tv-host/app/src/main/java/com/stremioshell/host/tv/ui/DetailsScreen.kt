package com.stremioshell.host.tv.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.tmdb.AirDate
import com.stremioshell.host.tv.data.tmdb.CastMember
import com.stremioshell.host.tv.data.tmdb.DetailsMetadata
import com.stremioshell.host.tv.data.tmdb.EpisodeItem
import com.stremioshell.host.tv.data.tmdb.MediaDetails
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import java.time.LocalDate

/** Lines of synopsis shown before the "More" affordance takes over. */
private const val OVERVIEW_COLLAPSED_LINES = 4

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
        val context = LocalContext.current
        AsyncImage(
          // The backdrop is one large, dimmed panel, where the app-wide RGB_565 decode shows as
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
            // Remembered because this header recomposes on every watch-state update, and the
            // metadata line only changes when the title does.
            val meta = remember(media) { DetailsMetadata.of(media) }
            if (meta.isNotEmpty()) {
              Text(meta, style = MaterialTheme.typography.bodyMedium)
            }
            ExpandableOverview(
              text = media.item.overview,
              // Keyed on the title so opening another one starts collapsed, and so the flag
              // survives the season switches and watch-state updates that recompose this header.
              stateKey = "$type:$tmdbId",
            )
            if (media.imdbId == null) {
              Text(
                "No IMDb id found for this title; streams are unavailable.",
                style = MaterialTheme.typography.bodySmall,
              )
            } else if (media.item.type == MediaType.Movie) {
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
              )
              if (resumable != null) ResumeHint(resumable)
            } else if (showResume != null) {
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
              )
              if (resumable) ResumeHint(showResume)
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
                  Text(season.label)
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
                  // Nothing has been released for an episode that has not aired, so its streams
                  // would come back empty. It stays focusable with a no-op OK rather than being
                  // made unfocusable: a whole unaired season would otherwise render as a block the
                  // D-pad cannot enter or read.
                  val upcoming = AirDate.isUpcoming(episode.airDate, today)
                  Card(
                    onClick = {
                      // Clicking a watched episode replays it: its stored position is 0, so
                      // startOver would change nothing and is left false.
                      if (!upcoming) {
                        onPlay(media, episode.seasonNumber, episode.episodeNumber, false)
                      }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f)
                      .then(if (upcoming) Modifier.alpha(0.6f) else Modifier)
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
                      // Null keeps its no-op: an absent still should not reserve an empty slot
                      // next to the episode text, while a failed one holds its tonal block.
                      if (episode.stillUrl != null) {
                        ArtworkImage(
                          url = episode.stillUrl,
                          contentDescription = null,
                          modifier = Modifier.width(160.dp).height(90.dp),
                        )
                      }
                      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                          // The tick is the whole point of keeping a finished episode's record:
                          // a season list that never says what you have seen is the reason
                          // "which one was I on" is a question at all.
                          buildString {
                            if (entry?.watched == true) append("✓  ")
                            append("E${episode.episodeNumber}  ${episode.name}")
                          },
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
                        val airLabel = AirDate.label(episode.airDate)
                        val marker = listOfNotNull(
                          // "Airs <date>" rather than a bare date, because that is the whole
                          // explanation for why pressing OK on this row does nothing.
                          airLabel?.let { if (upcoming) "Airs $it" else it },
                          "Resume here".takeIf { isResumeTarget },
                          "Watched".takeIf { entry?.watched == true },
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

        // Below the episode list, in the order a viewer asks the questions: who is in this, then
        // what else is like it.
        if (media.cast.isNotEmpty()) {
          item(key = "cast") { CastRow(media.cast) }
        }
        if (media.similar.isNotEmpty()) {
          item(key = "similar") { SimilarRow(media.similar, onItemClick) }
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
    maxLines = if (expanded) Int.MAX_VALUE else OVERVIEW_COLLAPSED_LINES,
    overflow = TextOverflow.Ellipsis,
    onTextLayout = { if (it.hasVisualOverflow) overflowed = true },
    modifier = Modifier.fillMaxWidth(0.7f),
  )
  if (overflowed) {
    Button(onClick = { expanded = !expanded }) {
      Text(if (expanded) "Less" else "More")
    }
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
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Cast", style = MaterialTheme.typography.titleLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      // Index in the key: TMDB credits the same person twice on plenty of titles (an actor who
      // also voices something), and a duplicate key crashes a lazy list outright.
      items(cast.size, key = { "${cast[it].id}:$it" }) { index ->
        val member = cast[index]
        Column(modifier = Modifier.width(CAST_CARD_WIDTH)) {
          Card(
            onClick = {},
            scale = CardDefaults.scale(focusedScale = 1.08f),
            modifier = Modifier.width(CAST_CARD_WIDTH).height(160.dp),
          ) {
            ArtworkImage(
              url = member.profileUrl,
              contentDescription = member.name,
              modifier = Modifier.fillMaxSize(),
            ) {
              Text(
                member.name,
                maxLines = 3,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(6.dp),
              )
            }
          }
          Text(
            member.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            // Clears the focused card's scaled-up bottom edge, as on the poster rows.
            modifier = Modifier.padding(top = 12.dp),
          )
          if (member.character.isNotBlank()) {
            Text(
              member.character,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.alpha(0.7f),
            )
          }
        }
      }
    }
  }
}

/** Recommendations, on the same poster card the Home rails use. */
@Composable
private fun SimilarRow(items: List<MediaItem>, onItemClick: (MediaType, Int) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("More like this", style = MaterialTheme.typography.titleLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      items(items.size, key = { "${items[it].type}:${items[it].tmdbId}" }) { index ->
        val item = items[index]
        MediaCard(item = item, onClick = { onItemClick(item.type, item.tmdbId) })
      }
    }
  }
}

/** Narrower than a poster card: a headshot is portrait, but not 2:3. */
private val CAST_CARD_WIDTH = 120.dp

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
 */
@Composable
private fun PlayActions(
  playLabel: String,
  offerStartOver: Boolean,
  focusTarget: InitialFocusTarget,
  onPlay: (startOver: Boolean) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Button(
      onClick = { onPlay(false) },
      modifier = Modifier.initialFocusTarget(focusTarget),
    ) {
      Text(playLabel)
    }
    if (offerStartOver) {
      Button(onClick = { onPlay(true) }) { Text("Start over") }
    }
  }
}

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
