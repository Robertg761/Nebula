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

  suspend fun details(type: MediaType, tmdbId: Int): MediaDetails {
    val path = if (type == MediaType.Movie) "movie/$tmdbId" else "tv/$tmdbId"
    val body = fetcher.getAllowingStale(url(path, "append_to_response=external_ids"))
    val details = json.decodeFromString<TmdbDetailsResponse>(body)
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
      seasons = details.seasons
        .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
        .map { SeasonSummary(it.seasonNumber, it.name, it.episodeCount) },
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
  }
}
