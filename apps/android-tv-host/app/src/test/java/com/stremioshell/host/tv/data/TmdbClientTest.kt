package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.TmdbClient
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbClientTest {
  private val requested = mutableListOf<String>()

  private fun client(response: String): TmdbClient {
    return TmdbClient(
      apiKey = "test-key",
      fetcher = { url ->
        requested += url
        response
      },
      locale = Locale.US,
    )
  }

  @Test
  fun `trending parses entries and builds image urls`() = runBlocking {
    val page = client(
      """
      {"page":1,"total_pages":12,
       "results":[{"id":42,"title":"Obsession","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
        "overview":"A film.","release_date":"2026-03-01","vote_average":7.5}]}
      """.trimIndent()
    ).trending(MediaType.Movie)

    assertEquals(1, page.items.size)
    assertEquals(1, page.page)
    assertEquals(12, page.totalPages)
    val item = page.items.first()
    assertEquals(42, item.tmdbId)
    assertEquals("Obsession", item.title)
    assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", item.posterUrl)
    assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", item.backdropUrl)
    assertEquals("2026", item.year)
    assertTrue(requested.single().contains("trending/movie/week?api_key=test-key"))
  }

  @Test
  fun `catalog page counters default so an endpoint that omits them still parses`() = runBlocking {
    val page = client("""{"results":[{"id":1,"title":"A"}]}""").popular(MediaType.Movie)

    assertEquals(1, page.page)
    assertEquals(1, page.totalPages)
  }

  @Test
  fun `rails ask for the page they are paging to`() = runBlocking {
    client("""{"results":[]}""").trending(MediaType.Show, page = 3)
    assertTrue(requested.single().contains("&page=3"))

    requested.clear()
    // A nonsense page is clamped rather than sent: TMDB answers page=0 with an error.
    client("""{"results":[]}""").popular(MediaType.Movie, page = 0)
    assertTrue(requested.single().contains("&page=1"))
  }

  @Test
  fun `discover builds a genre rail sorted by popularity`() = runBlocking {
    val page = client(
      """{"page":2,"total_pages":500,"results":[{"id":7,"name":"Show","first_air_date":"2020-01-01"}]}"""
    ).discover(MediaType.Show, genreId = 10765, page = 2)

    assertEquals(MediaType.Show, page.items.single().type)
    assertEquals(500, page.totalPages)
    val url = requested.single()
    assertTrue(url.contains("discover/tv"))
    assertTrue(url.contains("sort_by=popularity.desc"))
    assertTrue(url.contains("with_genres=10765"))
    // Without a vote floor, popularity.desc leads with unrated junk that has no artwork.
    assertTrue(url.contains("vote_count.gte="))
    assertTrue(url.contains("&page=2"))
  }

  @Test
  fun `movie discover excludes adult titles`() = runBlocking {
    client("""{"results":[]}""").discover(MediaType.Movie, genreId = 28)
    val url = requested.single()
    assertTrue(url.contains("discover/movie"))
    assertTrue(url.contains("include_adult=false"))
  }

  @Test
  fun `search keeps only movies and shows`() = runBlocking {
    val items = client(
      """
      {"results":[
        {"id":1,"media_type":"movie","title":"A"},
        {"id":2,"media_type":"tv","name":"B"},
        {"id":3,"media_type":"person","name":"C"}]}
      """.trimIndent()
    ).search("ab")

    assertEquals(listOf("A", "B"), items.map { it.title })
    assertEquals(MediaType.Show, items[1].type)
  }

  @Test
  fun `search page preserves counters and asks for the requested page`() = runBlocking {
    val page = client(
      """{"page":3,"total_pages":9,"results":[{"id":1,"media_type":"movie","title":"A"}]}"""
    ).searchPage("ab", page = 3)

    assertEquals(3, page.page)
    assertEquals(9, page.totalPages)
    assertEquals(listOf("A"), page.items.map { it.title })
    assertTrue(requested.single().contains("&page=3"))
  }

  @Test
  fun `details exposes imdb id, seasons, and runtime fallbacks`() = runBlocking {
    val details = client(
      """
      {"id":9,"name":"Silo","overview":"Underground.","episode_run_time":[50],
       "genres":[{"id":1,"name":"Drama"}],
       "seasons":[
         {"season_number":0,"name":"Specials","episode_count":2},
         {"season_number":1,"name":"Season 1","episode_count":10}],
       "external_ids":{"imdb_id":"tt14688458"}}
      """.trimIndent()
    ).details(MediaType.Show, 9)

    assertEquals("tt14688458", details.imdbId)
    assertEquals(50, details.runtimeMinutes)
    assertEquals(listOf("Drama"), details.genres)
    // Specials are offered, but after the real seasons so the screen still opens on season 1.
    assertEquals(listOf(1, 0), details.seasons.map { it.seasonNumber })
    assertEquals(listOf("Season 1", "Specials"), details.seasons.map { it.label })
    assertEquals(10, details.seasons.first().episodeCount)
  }

  @Test
  fun `details asks for every extra in one request`() = runBlocking {
    client("""{"id":9,"name":"Silo"}""").details(MediaType.Show, 9)
    val showUrl = requested.single()
    assertTrue(showUrl.contains("append_to_response=external_ids,credits,videos,similar,content_ratings"))

    requested.clear()
    client("""{"id":9,"title":"Heat"}""").details(MediaType.Movie, 9)
    val movieUrl = requested.single()
    // Certifications live under a different key for movies.
    assertTrue(movieUrl.contains("append_to_response=external_ids,credits,videos,similar,release_dates"))
  }

  @Test
  fun `metadata and artwork requests follow the device locale with safe fallbacks`() = runBlocking {
    TmdbClient(
      apiKey = "test-key",
      fetcher = { url ->
        requested += url
        """{"id":9,"title":"Titre"}"""
      },
      locale = Locale.CANADA_FRENCH,
    ).details(MediaType.Movie, 9)

    val url = requested.single()
    assertTrue(url.contains("language=fr-CA"))
    assertTrue(url.contains("include_image_language=fr,en,null"))
  }

  @Test
  fun `the api key cannot inject another query parameter`() = runBlocking {
    TmdbClient(
      apiKey = "key&include_adult=true",
      fetcher = { url ->
        requested += url
        """{"results":[]}"""
      },
      locale = Locale.US,
    ).trending(MediaType.Movie)

    assertTrue(requested.single().contains("api_key=key%26include_adult%3Dtrue"))
  }

  @Test
  fun `details parses cast, similar titles, trailer and a show certification`() = runBlocking {
    val details = client(
      """
      {"id":9,"name":"Silo","first_air_date":"2023-05-05","last_air_date":"2025-01-17",
       "in_production":true,
       "credits":{"cast":[
         {"id":7,"name":"Second","character":"Sims","order":1},
         {"id":5,"name":"First","character":"Juliette","profile_path":"/j.jpg","order":0}]},
       "videos":{"results":[{"key":"abc","site":"YouTube","type":"Trailer","official":true}]},
       "similar":{"results":[
         {"id":9,"name":"Silo"},
         {"id":11,"name":"Severance","poster_path":"/s.jpg"}]},
       "content_ratings":{"results":[{"iso_3166_1":"DE","rating":"16"},
         {"iso_3166_1":"US","rating":"TV-MA"}]}}
      """.trimIndent()
    ).details(MediaType.Show, 9)

    assertEquals(listOf("First", "Second"), details.cast.map { it.name })
    assertEquals("Juliette", details.cast.first().character)
    assertEquals("https://image.tmdb.org/t/p/w185/j.jpg", details.cast.first().profileUrl)
    assertNull(details.cast[1].profileUrl)
    // The title itself is dropped from its own recommendations, and the type is inherited.
    assertEquals(listOf(11), details.similar.map { it.tmdbId })
    assertEquals(MediaType.Show, details.similar.first().type)
    assertEquals("abc", details.trailerYoutubeKey)
    assertEquals("TV-MA", details.contentRating)
    assertEquals("2025", details.endYear)
    assertTrue(details.ongoing)
  }

  @Test
  fun `details reads a movie certification out of release dates`() = runBlocking {
    val details = client(
      """
      {"id":9,"title":"Heat","release_date":"1995-12-15",
       "release_dates":{"results":[
         {"iso_3166_1":"DE","release_dates":[{"certification":"16"}]},
         {"iso_3166_1":"US","release_dates":[{"certification":""},{"certification":"R"}]}]}}
      """.trimIndent()
    ).details(MediaType.Movie, 9)

    assertEquals("R", details.contentRating)
    assertNull(details.endYear)
    assertEquals(false, details.ongoing)
  }

  @Test
  fun `details tolerates every extra being absent`() = runBlocking {
    val details = client("""{"id":9,"title":"X"}""").details(MediaType.Movie, 9)

    assertTrue(details.cast.isEmpty())
    assertTrue(details.similar.isEmpty())
    assertNull(details.trailerYoutubeKey)
    assertNull(details.contentRating)
  }

  @Test
  fun `details tolerates missing imdb id`() = runBlocking {
    val details = client("""{"id":9,"title":"X"}""").details(MediaType.Movie, 9)
    assertNull(details.imdbId)
  }

  @Test
  fun `catalog gets take the cache-tolerant path, and search deliberately does not`() = runBlocking {
    // Metadata stays useful when it is a little old, and that is what lets a cold start with no
    // network paint the last known Home instead of an error screen. A typed-once search query is
    // the opposite: caching it spends the shared disk budget on a body nothing will ever re-read,
    // evicting the details payloads that a viewer does come back to.
    var stale = 0
    var plain = 0
    val fetcher = object : HttpFetcher {
      override suspend fun get(url: String): String {
        plain++
        return """{"results":[]}"""
      }

      override suspend fun getAllowingStale(url: String): String {
        stale++
        return """{"results":[]}"""
      }
    }

    val client = TmdbClient(apiKey = "test-key", fetcher = fetcher)
    client.trending(MediaType.Movie)
    client.popular(MediaType.Show)
    client.discover(MediaType.Movie, genreId = 28)

    assertEquals(3, stale)
    assertEquals(0, plain)

    client.search("q")
    client.searchPage("q", page = 2)

    assertEquals(3, stale)
    assertEquals(2, plain)
  }

  @Test
  fun `a cached catalog read asks for exactly the url its load stored`() = runBlocking {
    // The URL is the cache key, so any drift between how a load builds one and how the cold-open
    // read-ahead builds one turns every read-ahead into a miss - silently, and only on a device.
    val live = mutableListOf<String>()
    val cached = mutableListOf<String>()
    val fetcher = object : HttpFetcher {
      override suspend fun get(url: String): String = """{"results":[]}"""

      override suspend fun getAllowingStale(url: String): String {
        live += url
        return """{"results":[]}"""
      }

      override suspend fun getCachedOnly(url: String): String? {
        cached += url
        return null
      }
    }
    val client = TmdbClient(apiKey = "test-key", fetcher = fetcher, locale = Locale.US)

    client.trending(MediaType.Show)
    client.popular(MediaType.Movie)
    client.discover(MediaType.Show, genreId = 10765)
    // A rail is only ever primed from its first page, which is the page a load stores.
    client.cachedTrending(MediaType.Show)
    client.cachedPopular(MediaType.Movie)
    client.cachedDiscover(MediaType.Show, genreId = 10765)

    assertEquals(live, cached)
    assertTrue(live.first().contains("&page=1"))
  }

  @Test
  fun `cached catalog reads never fall through to the network`() = runBlocking {
    // The point of the read-ahead is that a miss costs a disk lookup rather than a round trip; a
    // fallback here would put Home's first paint back behind TMDB.
    var live = 0
    val fetcher = object : HttpFetcher {
      override suspend fun get(url: String): String {
        live++
        return """{"results":[]}"""
      }

      override suspend fun getAllowingStale(url: String): String {
        live++
        return """{"results":[]}"""
      }

      override suspend fun getCachedOnly(url: String): String? =
        if (url.contains("trending")) {
          """{"page":1,"total_pages":9,"results":[{"id":1,"title":"A"}]}"""
        } else {
          null
        }
    }
    val client = TmdbClient(apiKey = "test-key", fetcher = fetcher, locale = Locale.US)

    val hit = requireNotNull(client.cachedTrending(MediaType.Movie))
    assertEquals(listOf("A"), hit.items.map { it.title })
    // Counters come from the cached body, so a rail served from disk can be paged immediately.
    assertEquals(9, hit.totalPages)

    assertNull(client.cachedPopular(MediaType.Show))
    assertNull(client.cachedDiscover(MediaType.Movie, genreId = 28))
    assertEquals(0, live)
  }

  @Test
  fun `credential probe requires a live non-stale response`() = runBlocking {
    var stale = 0
    var plain = 0
    var fresh = 0
    val fetcher = object : HttpFetcher {
      override suspend fun get(url: String): String {
        plain++
        return "{}"
      }

      override suspend fun getFresh(url: String): String {
        fresh++
        assertTrue(url.contains("/configuration?api_key=test-key"))
        return "{}"
      }

      override suspend fun getAllowingStale(url: String): String {
        stale++
        return "{}"
      }
    }

    TmdbClient(apiKey = "test-key", fetcher = fetcher).probeCredentials()

    assertEquals(0, plain)
    assertEquals(1, fresh)
    assertEquals(0, stale)
  }

  @Test
  fun `season parses episodes with stills sized for the slot they render in`() = runBlocking {
    val episodes = client(
      """
      {"episodes":[{"season_number":1,"episode_number":2,"name":"Ep2",
        "overview":"...","still_path":"/s.jpg","air_date":"2026-01-02"}]}
      """.trimIndent()
    ).season(9, 1)

    assertEquals(1, episodes.size)
    assertEquals(2, episodes.first().episodeNumber)
    // Backdrop width here meant a 24-episode season downloaded several megabytes of artwork for a
    // 268x151dp thumbnail, and decoded two dozen 1280px JPEGs to draw it.
    assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", episodes.first().stillUrl)
  }
}
