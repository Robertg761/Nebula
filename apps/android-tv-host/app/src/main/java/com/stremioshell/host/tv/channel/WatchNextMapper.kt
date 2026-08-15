package com.stremioshell.host.tv.channel

import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.watchEntryNewestFirst

/** Mirrors TvContractCompat.WatchNextPrograms.TYPE_*, kept out of the mapping so it stays JVM-pure. */
enum class WatchNextProgramType { Movie, TvEpisode }

/** Mirrors TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_*. */
enum class WatchNextKind { Continue, Next }

/**
 * One Watch Next row, as fields rather than as ContentValues, so the decision of
 * what a row should say is testable without a device.
 */
data class WatchNextProgramData(
  /**
   * The [WatchEntry.key]. Stored on the row as its internal provider id, which is
   * what lets the next publish recognise a row it already owns instead of
   * inserting a duplicate.
   */
  val internalProviderId: String,
  val title: String,
  val type: WatchNextProgramType,
  val kind: WatchNextKind,
  val posterArtUri: String?,
  val lastEngagementTimeUtcMillis: Long,
  /** Null unless [kind] is [WatchNextKind.Continue]; CONTINUE is what draws the progress bar. */
  val lastPlaybackPositionMillis: Int?,
  val durationMillis: Int?,
  val seasonNumber: Int?,
  val episodeNumber: Int?,
  val deepLinkUri: String,
)

/**
 * Projects the stored watch state onto the TV home's Watch Next row.
 *
 * The filter is the Continue Watching rail's rule - everything not finished -
 * so the row and the rail can never disagree about what is still in progress.
 * Anything that could only render as a broken card (no title to show, no tmdb id
 * to deep-link back to) is dropped on top of that.
 */
object WatchNextMapper {
  /**
   * The TV home only ever surfaces a handful of rows per app and the provider is
   * rewritten on every save, so publishing a viewer's entire history would be
   * paid for on every pause for rows nobody sees.
   */
  const val MAX_PROGRAMS = 20

  fun resumable(entries: List<WatchEntry>): List<WatchEntry> = entries
    .filterNot { it.watched }
    .filter { it.tmdbId > 0 && it.title.isNotBlank() }
    .sortedWith(watchEntryNewestFirst)
    .take(MAX_PROGRAMS)

  fun programsFor(entries: List<WatchEntry>): List<WatchNextProgramData> =
    resumable(entries).map(::map)

  fun map(entry: WatchEntry): WatchNextProgramData {
    // A seeded next episode has a record but no position yet. CONTINUE on it would
    // claim a resume point that does not exist and draw an empty progress bar, so
    // it goes out as NEXT - which is what the row's "next up" slot is for.
    val hasProgress = entry.positionMs > 0L
    val durationMs = entry.durationMs.takeIf { it > 0L }?.toClampedInt()
    return WatchNextProgramData(
      internalProviderId = entry.key,
      title = entry.title,
      type = if (entry.mediaType == WatchNextDeepLink.TYPE_SHOW) {
        WatchNextProgramType.TvEpisode
      } else {
        WatchNextProgramType.Movie
      },
      kind = if (hasProgress) WatchNextKind.Continue else WatchNextKind.Next,
      posterArtUri = entry.posterUrl?.takeIf { it.isNotBlank() },
      lastEngagementTimeUtcMillis = entry.updatedAtMs,
      lastPlaybackPositionMillis = if (hasProgress) {
        // A position past the end would render as an over-full bar; the entry is
        // still unfinished as far as the app is concerned, so cap rather than drop.
        entry.positionMs.toClampedInt().let { position ->
          if (durationMs != null) minOf(position, durationMs) else position
        }
      } else {
        null
      },
      durationMillis = durationMs,
      seasonNumber = entry.season,
      episodeNumber = entry.episode,
      deepLinkUri = WatchNextDeepLink.build(WatchNextDeepLink.targetFor(entry)),
    )
  }

  /** The provider's position/duration columns are 32-bit; 24 days of video is not a real case. */
  private fun Long.toClampedInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
