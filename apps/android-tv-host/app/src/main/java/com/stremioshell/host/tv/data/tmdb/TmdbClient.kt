package com.stremioshell.host.tv.data.tmdb

import com.stremioshell.host.tv.data.HttpFetcher
import com.stremioshell.host.tv.data.OkHttpFetcher
import java.net.URLEncoder
import kotlinx.serialization.json.Json

/**
 * TMDB read-only client.
 *
 * Every GET goes through [HttpFetcher.getAllowingStale]: catalogs and metadata stay useful when
 * they are a little old, so a cold start with no network paints the last known Home instead of an
 * error screen.
 */
class TmdbClient(
  private val apiKey: String,
  private val fetcher: HttpFetcher = OkHttpFetcher,
  private val baseUrl: String = "https://api.themoviedb.org/3",
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun trending(type: MediaType): List<MediaItem> {
    val path = if (type == MediaType.Movie) "trending/movie/week" else "trending/tv/week"
    return pagedItems(path, type)
  }

  suspend fun popular(type: MediaType): List<MediaItem> {
    val path = if (type == MediaType.Movie) "movie/popular" else "tv/popular"
    return pagedItems(path, type)
  }

  suspend fun search(query: String): List<MediaItem> {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    val body = fetcher.getAllowingStale(url("search/multi", "query=$encoded&include_adult=false"))
    return json.decodeFromString<TmdbPagedResults>(body).results.mapNotNull { entry ->
      when (entry.mediaType) {
        "movie" -> entry.toItem(MediaType.Movie)
        "tv" -> entry.toItem(MediaType.Show)
        else -> null
      }
    }
  }

  /**
   * One request for everything the details screen shows.
   *
   * The extras ride along on `append_to_response` rather than as separate calls: five sequential
   * round trips would put the cast row seconds behind the title on a TV's Wi-Fi, and each one would
   * be its own chance to fail.
   */
  suspend fun details(type: MediaType, tmdbId: Int): MediaDetails {
    val isMovie = type == MediaType.Movie
    val path = if (isMovie) "movie/$tmdbId" else "tv/$tmdbId"
    val appends = if (isMovie) MOVIE_APPENDS else SHOW_APPENDS
    val body = fetcher.getAllowingStale(url(path, "append_to_response=$appends"))
    val details = json.decodeFromString<TmdbDetailsResponse>(body)
    // Certifications live under a different key per media type, and in a different shape: shows
    // carry one rating per country, movies carry a list of releases per country.
    val certifications = if (isMovie) {
      details.releaseDates?.results.orEmpty().flatMap { country ->
        country.releaseDates.map { country.country to it.certification }
      }
    } else {
      details.contentRatings?.results.orEmpty().map { it.country to it.rating }
    }
    return MediaDetails(
      item = MediaItem(
        tmdbId = details.id,
        type = type,
        title = details.title ?: details.name ?: "Untitled",
        posterUrl = details.posterPath?.let { IMAGE_BASE_POSTER + it },
        backdropUrl = details.backdropPath?.let { IMAGE_BASE_BACKDROP + it },
        overview = details.overview,
        year = (details.releaseDate ?: details.firstAirDate)?.take(4)?.ifBlank { null },
        rating = details.voteAverage,
      ),
      imdbId = details.externalIds?.imdbId?.ifBlank { null },
      runtimeMinutes = details.runtime ?: details.episodeRunTime.firstOrNull(),
      genres = details.genres.map { it.name },
      seasons = SeasonList.order(
        details.seasons.map { SeasonSummary(it.seasonNumber, it.name, it.episodeCount) },
      ),
      endYear = AirDate.year(details.lastAirDate),
      ongoing = details.inProduction,
      contentRating = ContentRating.pick(certifications),
      cast = details.credits?.cast.orEmpty()
        .sortedBy { it.order }
        .take(MAX_CAST)
        .map { member ->
          CastMember(
            id = member.id,
            name = member.name,
            character = member.character,
            profileUrl = member.profilePath?.let { IMAGE_BASE_PROFILE + it },
          )
        },
      // `similar` has no media_type of its own, so it inherits this title's - which is right,
      // because TMDB only ever recommends within the endpoint that was asked.
      similar = details.similar?.results.orEmpty()
        .filter { it.id != details.id }
        .distinctBy { it.id }
        .take(MAX_SIMILAR)
        .map { it.toItem(type) },
      trailerYoutubeKey = TrailerPick.bestYoutubeKey(
        details.videos?.results.orEmpty().map {
          VideoRef(key = it.key, site = it.site, type = it.type, official = it.official)
        },
      ),
    )
  }

  suspend fun season(tmdbId: Int, seasonNumber: Int): List<EpisodeItem> {
    val body = fetcher.getAllowingStale(url("tv/$tmdbId/season/$seasonNumber"))
    return json.decodeFromString<TmdbSeasonResponse>(body).episodes.map { episode ->
      EpisodeItem(
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        name = episode.name,
        overview = episode.overview,
        stillUrl = episode.stillPath?.let { IMAGE_BASE_BACKDROP + it },
        airDate = episode.airDate,
      )
    }
  }

  private suspend fun pagedItems(path: String, type: MediaType): List<MediaItem> {
    val body = fetcher.getAllowingStale(url(path, null))
    return json.decodeFromString<TmdbPagedResults>(body).results.map { it.toItem(type) }
  }

  private fun url(path: String, query: String? = null): String {
    val extra = if (query.isNullOrBlank()) "" else "&$query"
    return "$baseUrl/$path?api_key=$apiKey&language=en-US$extra"
  }

  private fun TmdbEntry.toItem(type: MediaType): MediaItem {
    return MediaItem(
      tmdbId = id,
      type = type,
      title = title ?: name ?: "Untitled",
      posterUrl = posterPath?.let { IMAGE_BASE_POSTER + it },
      backdropUrl = backdropPath?.let { IMAGE_BASE_BACKDROP + it },
      overview = overview,
      year = (releaseDate ?: firstAirDate)?.take(4)?.ifBlank { null },
      rating = voteAverage,
    )
  }

  companion object {
    private const val IMAGE_BASE_POSTER = "https://image.tmdb.org/t/p/w342"
    private const val IMAGE_BASE_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    /** Headshots are card-sized; w342 would be four times the bytes for no visible gain. */
    private const val IMAGE_BASE_PROFILE = "https://image.tmdb.org/t/p/w185"

    private const val MOVIE_APPENDS = "external_ids,credits,videos,similar,release_dates"
    private const val SHOW_APPENDS = "external_ids,credits,videos,similar,content_ratings"

    /** Full TMDB casts run to hundreds of one-line parts; nobody scrolls a row that far. */
    private const val MAX_CAST = 20
    private const val MAX_SIMILAR = 20
  }
}
