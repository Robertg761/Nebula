package com.stremioshell.host.tv.data.tmdb

import com.stremioshell.host.tv.data.HttpFetcher
import com.stremioshell.host.tv.data.OkHttpFetcher
import com.stremioshell.host.tv.data.decodeJsonOffMain
import com.stremioshell.host.tv.diagnostics.PerformanceTrace
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json

/**
 * TMDB read-only client.
 *
 * Catalog and metadata GETs go through [HttpFetcher.getAllowingStale]: they stay useful when they
 * are a little old, so a cold start with no network paints the last known Home instead of an error
 * screen. [searchPage] and [probeCredentials] are the two deliberate exceptions, each for its own
 * reason.
 *
 * The `cached*` catalog reads take that one step further and skip the network entirely, so Home can
 * paint from disk while the same catalogs are being fetched for real.
 */
class TmdbClient(
  private val apiKey: String,
  private val fetcher: HttpFetcher = OkHttpFetcher,
  private val baseUrl: String = "https://api.themoviedb.org/3",
  private val locale: Locale = Locale.getDefault(),
) {
  /**
   * Requires a live authenticated response for Settings and phone-pairing validation.
   *
   * Catalog reads intentionally accept stale cache entries, but a connection check must not tell
   * the viewer a credential works merely because that key populated the cache on an earlier day.
   */
  suspend fun probeCredentials() {
    fetcher.getFresh(url("configuration"))
  }

  suspend fun trending(type: MediaType, page: Int = 1): MediaPage =
    pagedItems(trendingEndpoint(type), type, page)

  suspend fun popular(type: MediaType, page: Int = 1): MediaPage =
    pagedItems(popularEndpoint(type), type, page)

  /**
   * A genre rail.
   *
   * `vote_count.gte` is the difference between a browsable row and a junk drawer: discover sorted
   * by popularity alone leads with whatever was uploaded this week, including titles with a handful
   * of votes and no artwork.
   */
  suspend fun discover(type: MediaType, genreId: Int, page: Int = 1): MediaPage =
    pagedItems(discoverEndpoint(type, genreId), type, page)

  /**
   * The cache-only counterparts of the three catalog reads above: the rail's first page as the
   * shared disk cache already holds it, or null when it holds nothing for it.
   *
   * Home's cold open uses these to paint before TMDB has answered (see TvAppViewModel). They are
   * first pages only because that is what a load publishes and what paging counts from; a deeper
   * page has nothing on screen to attach to.
   */
  suspend fun cachedTrending(type: MediaType): MediaPage? =
    cachedFirstPage(trendingEndpoint(type), type)

  suspend fun cachedPopular(type: MediaType): MediaPage? =
    cachedFirstPage(popularEndpoint(type), type)

  suspend fun cachedDiscover(type: MediaType, genreId: Int): MediaPage? =
    cachedFirstPage(discoverEndpoint(type, genreId), type)

  /** First-page compatibility API used by existing callers. */
  suspend fun search(query: String): List<MediaItem> = searchPage(query).items

  /**
   * One page of mixed movie/show search results, with TMDB's paging counters preserved.
   *
   * The one read that deliberately does not go through [HttpFetcher.getAllowingStale]. A query is
   * typed once and never asked for again, so caching it only spends the shared 20MB disk budget on
   * bodies nothing will re-read - evicting the details and catalog payloads that a viewer does come
   * back to. Search is also the one screen where an old answer is worth least: it is answering what
   * the viewer is typing right now.
   */
  suspend fun searchPage(query: String, page: Int = 1): MediaPage {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    val requestedPage = page.coerceAtLeast(1)
    val body = fetcher.get(
      url("search/multi", "query=$encoded&include_adult=false&page=$requestedPage"),
    )
    val decoded = decode<TmdbPagedResults>(body)
    val items = decoded.results.mapNotNull { entry ->
      when (entry.mediaType) {
        "movie" -> entry.toItem(MediaType.Movie)
        "tv" -> entry.toItem(MediaType.Show)
        else -> null
      }
    }
    return MediaPage(
      items = items,
      page = decoded.page,
      totalPages = decoded.totalPages,
    )
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
    // `include_image_language` is what makes the appended images block useful: without it TMDB
    // filters artwork to `language`, i.e. en-US only, and drops the textless files it stores under
    // no language at all - which for logos is a large share of them.
    val body = PerformanceTrace.suspendSection("tmdb.details.fetch") {
      fetcher.getAllowingStale(
        url(
          path,
          "append_to_response=$appends&include_image_language=" +
            TmdbLocale.imageLanguages(locale).joinToString(",") { it ?: "null" },
        ),
      )
    }
    val details = PerformanceTrace.suspendSection("tmdb.details.decode") {
      decode<TmdbDetailsResponse>(body)
    }
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
      logoUrl = LogoPick.best(
        details.images?.logos.orEmpty().map {
          LogoRef(
            filePath = it.filePath,
            language = it.language,
            voteAverage = it.voteAverage,
            width = it.width,
          )
        },
        preferredLanguage = TmdbLocale.language(locale),
      )?.let { IMAGE_BASE_LOGO + it },
    )
  }

  suspend fun season(tmdbId: Int, seasonNumber: Int): List<EpisodeItem> {
    val body = fetcher.getAllowingStale(url("tv/$tmdbId/season/$seasonNumber"))
    return decode<TmdbSeasonResponse>(body).episodes.map { episode ->
      EpisodeItem(
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        name = episode.name,
        overview = episode.overview,
        stillUrl = episode.stillPath?.let { IMAGE_BASE_STILL + it },
        airDate = episode.airDate,
      )
    }
  }

  private fun trendingEndpoint(type: MediaType) =
    CatalogEndpoint(if (type == MediaType.Movie) "trending/movie/week" else "trending/tv/week")

  private fun popularEndpoint(type: MediaType) =
    CatalogEndpoint(if (type == MediaType.Movie) "movie/popular" else "tv/popular")

  private fun discoverEndpoint(type: MediaType, genreId: Int): CatalogEndpoint {
    val isMovie = type == MediaType.Movie
    return CatalogEndpoint(
      path = if (isMovie) "discover/movie" else "discover/tv",
      query = buildString {
        append("sort_by=popularity.desc")
        append("&with_genres=$genreId")
        append("&vote_count.gte=$MIN_DISCOVER_VOTES")
        if (isMovie) append("&include_adult=false")
      },
    )
  }

  private suspend fun pagedItems(
    endpoint: CatalogEndpoint,
    type: MediaType,
    page: Int,
  ): MediaPage = decodePage(fetcher.getAllowingStale(catalogUrl(endpoint, page)), type)

  private suspend fun cachedFirstPage(endpoint: CatalogEndpoint, type: MediaType): MediaPage? {
    val body = fetcher.getCachedOnly(catalogUrl(endpoint, page = 1)) ?: return null
    return decodePage(body, type)
  }

  /**
   * The one place a catalog URL is built, so a cache-only read cannot ask for a byte-different URL
   * from the load that stored the body - which is the whole cache key.
   */
  private fun catalogUrl(endpoint: CatalogEndpoint, page: Int): String {
    val paged = listOfNotNull(endpoint.query?.ifBlank { null }, "page=${page.coerceAtLeast(1)}")
      .joinToString("&")
    return url(endpoint.path, paged)
  }

  private suspend fun decodePage(body: String, type: MediaType): MediaPage {
    val decoded = decode<TmdbPagedResults>(body)
    return MediaPage(
      items = decoded.results.map { it.toItem(type) },
      page = decoded.page,
      totalPages = decoded.totalPages,
    )
  }

  private fun url(path: String, query: String? = null): String {
    val extra = if (query.isNullOrBlank()) "" else "&$query"
    val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
    return "$baseUrl/$path?api_key=$encodedKey&language=${TmdbLocale.languageTag(locale)}$extra"
  }

  private suspend inline fun <reified T> decode(body: String): T =
    decodeJsonOffMain { JSON.decodeFromString<T>(body) }

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

  /** A catalog endpoint minus its page number: everything the live and cached reads must share. */
  private data class CatalogEndpoint(val path: String, val query: String? = null)

  companion object {
    /**
     * Shared rather than per-instance: a [TmdbClient] is built per request, and a [Json] carries a
     * serializer cache worth keeping instead of rebuilding nine times on a cold Home. The
     * configuration is immutable and [Json] is safe to use from several threads.
     */
    private val JSON = Json { ignoreUnknownKeys = true }

    private const val IMAGE_BASE_POSTER = "https://image.tmdb.org/t/p/w342"
    private const val IMAGE_BASE_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    /** Headshots are card-sized; w342 would be four times the bytes for no visible gain. */
    private const val IMAGE_BASE_PROFILE = "https://image.tmdb.org/t/p/w185"

    /**
     * Episode stills, on the same reasoning as the headshots above but with more at stake: they
     * arrive a whole season at a time, into a 268x151dp slot. At backdrop width a 24-episode season
     * was 4-6MB down the wire and two dozen 1280px JPEGs to decode, which on a 4GB box is felt as a
     * stutter while scrolling the episode list. w300 is the largest size TMDB documents for a
     * still.
     */
    private const val IMAGE_BASE_STILL = "https://image.tmdb.org/t/p/w300"

    /**
     * Logos render at roughly 300dp wide on the two surfaces that use them, i.e. ~600px on this
     * panel. w500 is the nearest step that does not upscale a transparent PNG, where softness
     * shows far more than it does on a photographic poster.
     */
    private const val IMAGE_BASE_LOGO = "https://image.tmdb.org/t/p/w500"

    private const val MOVIE_APPENDS = "external_ids,credits,videos,similar,release_dates,images"
    private const val SHOW_APPENDS = "external_ids,credits,videos,similar,content_ratings,images"

    /** Full TMDB casts run to hundreds of one-line parts; nobody scrolls a row that far. */
    private const val MAX_CAST = 20
    private const val MAX_SIMILAR = 20

    /** Low enough to keep genre rails deep, high enough to keep unreleased noise out of them. */
    private const val MIN_DISCOVER_VOTES = 100
  }
}

/** Locale values in the shapes TMDB's API and image filters accept. */
object TmdbLocale {
  fun language(locale: Locale): String =
    locale.language.lowercase(Locale.ROOT)
      .takeIf { it.length in 2..3 && it.all(Char::isLetter) && it != "und" }
      ?: "en"

  fun languageTag(locale: Locale): String {
    val language = language(locale)
    val country = locale.country.uppercase(Locale.ROOT)
      .takeIf { it.length == 2 && it.all(Char::isLetter) }
    return when {
      country != null -> "$language-$country"
      language == "en" -> "en-US"
      else -> language
    }
  }

  /**
   * Prefer artwork matching the TV, then English, then TMDB's language-neutral assets. Distinct
   * keeps an English TV from asking for `en` twice.
   */
  fun imageLanguages(locale: Locale): List<String?> =
    listOf(language(locale), "en", null).distinct()
}
