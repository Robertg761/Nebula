package com.stremioshell.host.tv.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MediaType { Movie, Show }

/** A decoded TMDB value and whether disk cache stood in for a failed live/cache-fresh request. */
data class TmdbLoad<out T>(
  val value: T,
  val staleFallback: Boolean = false,
)

/** A row entry on browse/search surfaces. */
data class MediaItem(
  val tmdbId: Int,
  val type: MediaType,
  val title: String,
  val posterUrl: String?,
  val backdropUrl: String?,
  val overview: String,
  val year: String?,
  val rating: Double?,
) {
  /**
   * Identity across TMDB pages and across endpoints. TMDB numbers movies and shows in separate
   * spaces, so the id alone collides between a film and a series that share one.
   */
  val key: String get() = "$type:$tmdbId"
}

/**
 * One page of a TMDB catalog endpoint.
 *
 * The page counters ride along because a rail pages itself as the user drives right: without
 * [totalPages] the only way to learn a catalog has ended is to request past it.
 */
data class MediaPage(
  val items: List<MediaItem>,
  val page: Int,
  val totalPages: Int,
)

data class SeasonSummary(
  val seasonNumber: Int,
  val name: String,
  val episodeCount: Int,
) {
  /** What the season button says; see [SeasonList.label] for why TMDB's own name is not used. */
  val label: String get() = SeasonList.label(seasonNumber)
}

/** One credited performer, as the cast row shows them. */
data class CastMember(
  val id: Int,
  val name: String,
  val character: String,
  val profileUrl: String?,
)

/**
 * @param endYear the last year the title produced anything, for a show that has finished. Null for
 *   movies and for a show whose run is a single year.
 * @param ongoing the show is still in production, so its year range stays open-ended.
 * @param contentRating the age certification to print, already narrowed to one country by
 *   [ContentRating.pick]; null when TMDB has none worth showing.
 * @param trailerYoutubeKey the title's trailer on YouTube. Carried here because it arrives free
 *   with the details request; no screen plays it yet.
 * @param logoUrl the title's own logotype, transparent PNG. Null when TMDB has none worth using,
 *   which is common enough that every surface showing it must still read correctly without one.
 */
data class MediaDetails(
  val item: MediaItem,
  val imdbId: String?,
  val runtimeMinutes: Int?,
  val genres: List<String>,
  val seasons: List<SeasonSummary>,
  val endYear: String? = null,
  val ongoing: Boolean = false,
  val contentRating: String? = null,
  val cast: List<CastMember> = emptyList(),
  val similar: List<MediaItem> = emptyList(),
  val trailerYoutubeKey: String? = null,
  val logoUrl: String? = null,
)

data class EpisodeItem(
  val seasonNumber: Int,
  val episodeNumber: Int,
  val name: String,
  val overview: String,
  val stillUrl: String?,
  val airDate: String?,
)

// --- Wire models -----------------------------------------------------------
//
// Decoded with `coerceInputValues` (see TmdbClient.JSON), which turns an explicit `null` into the
// property's declared default. That only works for properties that have one, so the rule below is
// deliberate rather than incidental:
//
//  - anything that is displayed defaults, because a null in it must cost that field and not the
//    whole page. TMDB genuinely writes `"overview": null`, `"name": null` and friends.
//  - the numeric ids and the season/episode numbers stay required. They are identity, not display:
//    a record coerced to id 0 or episode 0 would be indistinguishable from a real one and would go
//    on to key a watch-state row, a Details navigation or a stream request. TMDB has never omitted
//    them, and failing loudly is the right answer if it ever does.

@Serializable
internal data class TmdbPagedResults(
  val results: List<TmdbEntry> = emptyList(),
  val page: Int = 1,
  // Absent on the appended `similar` block, which is why both counters default rather than being
  // required: a details response must not fail to parse over pagination it never uses.
  @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
internal data class TmdbEntry(
  val id: Int,
  @SerialName("media_type") val mediaType: String? = null,
  val title: String? = null,
  val name: String? = null,
  @SerialName("poster_path") val posterPath: String? = null,
  @SerialName("backdrop_path") val backdropPath: String? = null,
  val overview: String = "",
  @SerialName("release_date") val releaseDate: String? = null,
  @SerialName("first_air_date") val firstAirDate: String? = null,
  @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
internal data class TmdbExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
internal data class TmdbGenre(val name: String = "")

@Serializable
internal data class TmdbSeason(
  @SerialName("season_number") val seasonNumber: Int,
  val name: String = "",
  @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
internal data class TmdbCastMember(
  val id: Int,
  val name: String = "",
  val character: String = "",
  @SerialName("profile_path") val profilePath: String? = null,
  val order: Int = 0,
)

@Serializable
internal data class TmdbCredits(val cast: List<TmdbCastMember> = emptyList())

@Serializable
internal data class TmdbVideo(
  val key: String = "",
  val site: String = "",
  val type: String = "",
  val official: Boolean = false,
)

@Serializable
internal data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

/**
 * One artwork file TMDB holds for a title.
 *
 * Only the fields the logo pick reads. `iso_639_1` is nullable and genuinely absent on the
 * textless artwork TMDB files under a null language, which is the one case where "no language" is
 * a useful answer rather than missing data.
 */
@Serializable
internal data class TmdbImage(
  @SerialName("file_path") val filePath: String = "",
  @SerialName("iso_639_1") val language: String? = null,
  @SerialName("vote_average") val voteAverage: Double = 0.0,
  val width: Int = 0,
)

@Serializable
internal data class TmdbImages(val logos: List<TmdbImage> = emptyList())

@Serializable
internal data class TmdbContentRating(
  @SerialName("iso_3166_1") val country: String = "",
  val rating: String = "",
)

@Serializable
internal data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

@Serializable
internal data class TmdbCertification(val certification: String = "")

@Serializable
internal data class TmdbCountryReleaseDates(
  @SerialName("iso_3166_1") val country: String = "",
  @SerialName("release_dates") val releaseDates: List<TmdbCertification> = emptyList(),
)

@Serializable
internal data class TmdbReleaseDates(val results: List<TmdbCountryReleaseDates> = emptyList())

@Serializable
internal data class TmdbDetailsResponse(
  val id: Int,
  val title: String? = null,
  val name: String? = null,
  @SerialName("poster_path") val posterPath: String? = null,
  @SerialName("backdrop_path") val backdropPath: String? = null,
  val overview: String = "",
  @SerialName("release_date") val releaseDate: String? = null,
  @SerialName("first_air_date") val firstAirDate: String? = null,
  @SerialName("last_air_date") val lastAirDate: String? = null,
  @SerialName("in_production") val inProduction: Boolean = false,
  @SerialName("vote_average") val voteAverage: Double? = null,
  val runtime: Int? = null,
  @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
  val genres: List<TmdbGenre> = emptyList(),
  val seasons: List<TmdbSeason> = emptyList(),
  @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
  val credits: TmdbCredits? = null,
  val videos: TmdbVideos? = null,
  val similar: TmdbPagedResults? = null,
  @SerialName("content_ratings") val contentRatings: TmdbContentRatings? = null,
  @SerialName("release_dates") val releaseDates: TmdbReleaseDates? = null,
  val images: TmdbImages? = null,
)

@Serializable
internal data class TmdbSeasonResponse(val episodes: List<TmdbEpisode> = emptyList())

@Serializable
internal data class TmdbEpisode(
  @SerialName("season_number") val seasonNumber: Int,
  @SerialName("episode_number") val episodeNumber: Int,
  val name: String = "",
  val overview: String = "",
  @SerialName("still_path") val stillPath: String? = null,
  @SerialName("air_date") val airDate: String? = null,
)
