package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.TmdbClient
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
    )
  }

  @Test
  fun `trending parses entries and builds image urls`() = runBlocking {
    val items = client(
      """
      {"results":[{"id":42,"title":"Obsession","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
        "overview":"A film.","release_date":"2026-03-01","vote_average":7.5}]}
      """.trimIndent()
    ).trending(MediaType.Movie)

    assertEquals(1, items.size)
    val item = items.first()
    assertEquals(42, item.tmdbId)
    assertEquals("Obsession", item.title)
    assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", item.posterUrl)
    assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", item.backdropUrl)
    assertEquals("2026", item.year)
    assertTrue(requested.single().contains("trending/movie/week?api_key=test-key"))
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
    assertEquals(1, details.seasons.size)
    assertEquals(10, details.seasons.first().episodeCount)
  }

  @Test
  fun `details tolerates missing imdb id`() = runBlocking {
    val details = client("""{"id":9,"title":"X"}""").details(MediaType.Movie, 9)
    assertNull(details.imdbId)
  }

  @Test
  fun `catalog gets take the cache-tolerant path`() = runBlocking {
    // Metadata stays useful when it is a little old, and that is what lets a cold start with no
    // network paint the last known Home instead of an error screen.
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
    client.search("q")

    assertEquals(3, stale)
    assertEquals(0, plain)
  }

  @Test
  fun `season parses episodes with stills`() = runBlocking {
    val episodes = client(
      """
      {"episodes":[{"season_number":1,"episode_number":2,"name":"Ep2",
        "overview":"...","still_path":"/s.jpg","air_date":"2026-01-02"}]}
      """.trimIndent()
    ).season(9, 1)

    assertEquals(1, episodes.size)
    assertEquals(2, episodes.first().episodeNumber)
    assertEquals("https://image.tmdb.org/t/p/w1280/s.jpg", episodes.first().stillUrl)
  }
}
