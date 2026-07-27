package com.stremioshell.host.tv.channel

import com.stremioshell.host.tv.data.WatchEntry

/**
 * What a Watch Next row points back at: enough to reopen the title a viewer
 * stopped part-way through, on the episode they stopped on.
 */
data class WatchNextTarget(
  /** "movie" or "show", the same two values [WatchEntry.mediaType] stores. */
  val mediaType: String,
  val tmdbId: Int,
  val season: Int? = null,
  val episode: Int? = null,
  /** 0 when the row is an unstarted next episode rather than a resume point. */
  val resumePositionMs: Long = 0L,
)

/**
 * The URI a Watch Next program carries, and that TvAppActivity parses on the way
 * back in.
 *
 * Hand-rolled string handling instead of android.net.Uri so the round trip is a
 * plain JVM test: every value here is digits or one of two fixed words, so there
 * is nothing that would need percent-encoding to survive.
 */
object WatchNextDeepLink {
  const val SCHEME = "stremio-tv"
  const val HOST = "watch-next"

  const val TYPE_MOVIE = "movie"
  const val TYPE_SHOW = "show"

  private const val PREFIX = "$SCHEME://$HOST"
  private const val PARAM_TYPE = "type"
  private const val PARAM_TMDB = "tmdb"
  private const val PARAM_SEASON = "season"
  private const val PARAM_EPISODE = "episode"
  private const val PARAM_POSITION = "position"

  fun targetFor(entry: WatchEntry): WatchNextTarget = WatchNextTarget(
    mediaType = if (entry.mediaType == TYPE_SHOW) TYPE_SHOW else TYPE_MOVIE,
    tmdbId = entry.tmdbId,
    season = entry.season,
    episode = entry.episode,
    resumePositionMs = entry.positionMs.coerceAtLeast(0L),
  )

  fun build(target: WatchNextTarget): String = buildString {
    append(PREFIX).append('?')
    append(PARAM_TYPE).append('=').append(target.mediaType)
    append('&').append(PARAM_TMDB).append('=').append(target.tmdbId)
    target.season?.let { append('&').append(PARAM_SEASON).append('=').append(it) }
    target.episode?.let { append('&').append(PARAM_EPISODE).append('=').append(it) }
    if (target.resumePositionMs > 0L) {
      append('&').append(PARAM_POSITION).append('=').append(target.resumePositionMs)
    }
  }

  /**
   * Null for anything that is not one of our rows. The launcher hands back
   * whatever URI the row carried, and the activity this lands in is exported, so
   * a malformed or foreign URI has to read as "no deep link" rather than as a
   * half-filled target that would open some unrelated title.
   */
  fun parse(uri: String?): WatchNextTarget? {
    if (uri == null || !uri.startsWith("$PREFIX?")) return null
    val params = HashMap<String, String>()
    for (pair in uri.substring(PREFIX.length + 1).split('&')) {
      val eq = pair.indexOf('=')
      if (eq <= 0) continue
      params[pair.substring(0, eq)] = pair.substring(eq + 1)
    }
    val mediaType = params[PARAM_TYPE]
    if (mediaType != TYPE_MOVIE && mediaType != TYPE_SHOW) return null
    val tmdbId = params[PARAM_TMDB]?.toIntOrNull() ?: return null
    if (tmdbId <= 0) return null
    return WatchNextTarget(
      mediaType = mediaType,
      tmdbId = tmdbId,
      season = params[PARAM_SEASON]?.toIntOrNull(),
      episode = params[PARAM_EPISODE]?.toIntOrNull(),
      resumePositionMs = params[PARAM_POSITION]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
    )
  }
}
