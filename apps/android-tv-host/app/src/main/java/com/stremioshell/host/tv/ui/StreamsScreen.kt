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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
  val streams by viewModel.streams.collectAsState()
  val notice by viewModel.streamsNotice.collectAsState()
  val addons by viewModel.addonManifestUrls.collectAsState()
  val remembered by viewModel.rememberedPicks.collectAsState()
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
  val matched = remember(list, memory) {
    memory?.let { StreamAutoPick.pick(list, bingeGroup = null, remembered = it) }
  }
  // Kept apart from the match on purpose. Scrolling to index 0 is pointless, but the *badge* at
  // index 0 is not: the common case is precisely that one, because the viewer picked 4K last
  // episode and 4K is what StreamOrder puts at the top. Folding the two together meant the marker
  // explaining why focus was parked on a row appeared only when the remembered release happened not
  // to be the best one, so the same stream was badged on one episode and bare on the next.
  val preselected = matched?.let { list.indexOf(it) }?.coerceAtLeast(0) ?: 0

  // Tier headings and per-row text, built once per list rather than per row: this is where the
  // regexes live, and a focus move down the list recomposes every row it passes.
  val rows = remember(list) { StreamPresentation.rows(list) }
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
    key = state,
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
      loadingText = when {
        addonCount > 1 -> "Asking $addonCount addons for streams..."
        addonCount == 1 -> "Asking the addon for streams..."
        else -> "Looking for configured addons..."
      },
      onRetry = if (missingAddonFailure) {
        null
      } else {
        { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) }
      },
      failureActionLabel = "Open Settings".takeIf { missingAddonFailure },
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
              title = "No streams for this title",
              hint = if (addonCount > 1) {
                "None of your addons returned anything playable."
              } else {
                "The addon returned nothing playable."
              },
              // Not a magnifier: the viewer did not search for this, they pressed Play on Details.
              icon = Icons.Filled.Warning,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
              NebulaButton(
                text = "Retry",
                onClick = { viewModel.loadStreams(screen.imdbId, screen.season, screen.episode) },
                style = NebulaButtonStyle.Primary,
                modifier = Modifier.initialFocusTarget(firstStreamFocus),
              )
              NebulaButton(
                text = "Manage addons",
                onClick = onOpenSettings,
                style = NebulaButtonStyle.Secondary,
              )
              NebulaButton(text = "Back", onClick = goBack, style = NebulaButtonStyle.Ghost)
            }
          }
        }
      } else {
        // How many there are and what order they are in. Forty releases used to arrive as one
        // undifferentiated column with nothing saying either, so a viewer holding Down had no idea
        // how far through the list they were or why the top row was the top row.
        Text(
          text = StreamPresentation.summary(ready.size),
          style = MaterialTheme.typography.labelMedium,
          color = NebulaPalette.TextMuted,
          modifier = Modifier.padding(start = NebulaDimens.ScreenEdge, top = NebulaSpace.lg),
        )
        LazyColumn(
          state = listState,
          verticalArrangement = Arrangement.spacedBy(NebulaSpace.sm),
          contentPadding = PaddingValues(
            start = NebulaDimens.ScreenEdge,
            end = NebulaDimens.ScreenEdge,
            top = NebulaSpace.sm,
            bottom = 40.dp,
          ),
        ) {
          // Debrid addons hand back the same resolved URL under several quality labels, and the
          // addon client only drops blank URLs - so a url-only key throws "Key was already used"
          // and takes the screen down. The position prefix keeps keys unique there while staying
          // stable for recompositions of the same list.
          itemsIndexed(
            rows,
            key = { position, row ->
              when (row) {
                is StreamListItem.Tier -> "tier:$position:${row.label}"
                is StreamListItem.Release ->
                  "row:$position:${row.stream.url ?: row.stream.label}"
              }
            },
          ) { _, row ->
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

/** Where one resolution tier starts, and how many releases are in it. */
@Composable
private fun TierHeading(tier: StreamListItem.Tier) {
  Text(
    text = "${tier.label} • ${tier.count}",
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
            if (text.cached) NebulaBadge("Instant", tone = BadgeTone.Good)
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
            if (lastUsed) NebulaBadge("Last used", tone = BadgeTone.Accent)
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
  /** @param count how many releases are in this tier, so the heading is also a map. */
  data class Tier(val label: String, val count: Int) : StreamListItem

  /** @param index the release's position in the *unflattened* list, which is what focus and the
   *   remembered pick are addressed by. */
  data class Release(
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
   */
  fun rows(streams: List<AddonStream>): List<StreamListItem> {
    val parsed = streams.map { StreamQuality.parse(it) }
    val labels = parsed.map(::tierLabel)
    val tiers = labels.distinct().size
    val out = ArrayList<StreamListItem>(streams.size + tiers)
    var index = 0
    while (index < streams.size) {
      var end = index
      while (end < streams.size && labels[end] == labels[index]) end++
      if (tiers > 1) out += StreamListItem.Tier(labels[index], end - index)
      for (i in index until end) out += StreamListItem.Release(i, streams[i], parsed[i])
      index = end
    }
    return out
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
