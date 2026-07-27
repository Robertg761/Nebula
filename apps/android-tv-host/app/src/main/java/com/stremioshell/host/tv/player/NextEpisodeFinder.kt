package com.stremioshell.host.tv.player

/**
 * Which episode follows the one that just finished.
 *
 * Ordering is season-then-episode rather than list order: TMDB returns episodes
 * in order today, but a gap (an unaired episode filed out of sequence, a
 * two-parter numbered 6 and 6.5) must not turn into "no next episode".
 *
 * Specials are never binged into or out of. TMDB files them as season 0, so they
 * sort ahead of the whole series and would otherwise make S0E2 the answer for
 * every finished special, and the finder is asked for the *next* episode of the
 * story - not the extras.
 *
 * Split from the fetching so the awkward cases are testable: the player only has
 * one season's episode list in hand when a season ends, which is why the
 * per-season and cross-season steps are separately callable.
 */
object NextEpisodeFinder {
  data class EpisodeRef(val season: Int, val episode: Int)

  private const val SPECIALS_SEASON = 0

  /** The next episode in [episodes], which may span seasons. */
  fun next(current: EpisodeRef, episodes: List<EpisodeRef>): EpisodeRef? {
    if (current.season == SPECIALS_SEASON) return null
    return episodes
      .filter { it.season != SPECIALS_SEASON && it.isAfter(current) }
      .minWithOrNull(compareBy({ it.season }, { it.episode }))
  }

  /**
   * The lowest season above [currentSeason] that the series actually has, for
   * planning the one extra fetch a season ending costs.
   */
  fun nextSeason(currentSeason: Int, seasons: List<Int>): Int? =
    seasons.filter { it != SPECIALS_SEASON && it > currentSeason }.minOrNull()

  /** Where a newly opened season starts, which is not always episode 1. */
  fun firstOfSeason(episodes: List<EpisodeRef>, season: Int): EpisodeRef? =
    episodes.filter { it.season == season }.minByOrNull { it.episode }

  private fun EpisodeRef.isAfter(other: EpisodeRef): Boolean =
    season > other.season || (season == other.season && episode > other.episode)
}
