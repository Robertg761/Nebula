package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesClientTest {
  @Test
  fun `movie subtitles are asked for by imdb id`() = runBlocking {
    var requestedUrl = ""
    val client = client { url ->
      requestedUrl = url
      """{"subtitles":[{"id":"1","url":"https://subs.example/a.srt","lang":"eng"}]}"""
    }

    val subtitles = client.movieSubtitles("tt0111161")

    assertEquals(
      "https://opensubtitles-v3.strem.io/subtitles/movie/tt0111161.json",
      requestedUrl,
    )
    assertEquals(1, subtitles.size)
    assertEquals("https://subs.example/a.srt", subtitles.first().url)
    assertEquals("eng", subtitles.first().lang)
  }

  @Test
  fun `episode subtitles use the imdb season episode id`() = runBlocking {
    var requestedUrl = ""
    val client = client { url ->
      requestedUrl = url
      """{"subtitles":[]}"""
    }

    client.episodeSubtitles("tt0111161", 1, 5)

    assertEquals(
      "https://opensubtitles-v3.strem.io/subtitles/series/tt0111161:1:5.json",
      requestedUrl,
    )
  }

  @Test
  fun `a trailing slash or manifest suffix on the base url is dropped`() {
    val expected = "https://opensubtitles-v3.strem.io/subtitles/movie/tt1.json"

    assertEquals(
      expected,
      SubtitlesClient.subtitlesUrl("https://opensubtitles-v3.strem.io/", "movie", "tt1"),
    )
    assertEquals(
      expected,
      SubtitlesClient.subtitlesUrl(
        " https://opensubtitles-v3.strem.io/manifest.json ",
        "movie",
        "tt1",
      ),
    )
  }

  @Test
  fun `configured query survives route construction and a fragment does not`() {
    assertEquals(
      "https://subs.example/cfg/subtitles/movie/tt1.json?token=secret",
      SubtitlesClient.subtitlesUrl(
        "https://subs.example/cfg/MANIFEST.JSON?token=secret#install",
        "movie",
        "tt1",
      ),
    )
  }

  @Test
  fun `cleartext subtitle addon is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      SubtitlesClient.subtitlesUrl("http://subs.example", "movie", "tt1")
    }
  }

  @Test
  fun `extra arguments are percent-encoded into their own path segment`() {
    val url = SubtitlesClient.subtitlesUrl(
      "https://opensubtitles-v3.strem.io",
      "movie",
      "tt1",
      mapOf("filename" to "The Film (2019) [1080p]+extras.mkv", "videoSize" to "123"),
    )

    assertEquals(
      "https://opensubtitles-v3.strem.io/subtitles/movie/tt1/" +
        "filename=The%20Film%20%282019%29%20%5B1080p%5D%2Bextras.mkv&videoSize=123.json",
      url,
    )
  }

  @Test
  fun `a space never becomes a plus, which a path segment reads literally`() {
    val url = SubtitlesClient.subtitlesUrl(
      "https://subs.example",
      "series",
      "tt1:2:3",
      mapOf("filename" to "a b"),
    )

    assertTrue(url.endsWith("/tt1:2:3/filename=a%20b.json"))
  }

  @Test
  fun `unknown fields and a numeric id do not fail the whole response`() = runBlocking {
    val client = client {
      """{"subtitles":[
        {"id":1955750821,"url":"https://subs.example/a.srt","lang":"eng","m":"hash","SubRating":9},
        {"id":"x2","url":"https://subs.example/b.srt","lang":"spa"}],
        "cacheMaxAge":1000}"""
    }

    val subtitles = client.movieSubtitles("tt1")

    assertEquals(listOf("eng", "spa"), subtitles.map { it.lang })
    assertEquals("1955750821", subtitles.first().id)
  }

  @Test
  fun `entries without a url are dropped`() = runBlocking {
    val client = client {
      """{"subtitles":[
        {"id":"1","lang":"eng"},
        {"id":"2","url":"","lang":"eng"},
        {"id":"3","url":"https://subs.example/c.srt","lang":"fra"}]}"""
    }

    assertEquals(
      listOf("https://subs.example/c.srt"),
      client.movieSubtitles("tt1").map { it.url },
    )
  }

  @Test
  fun `an untagged entry is kept, because a viewer can still try it`() = runBlocking {
    val client = client { """{"subtitles":[{"id":"1","url":"https://subs.example/a.srt"}]}""" }

    val subtitles = client.movieSubtitles("tt1")

    assertEquals(1, subtitles.size)
    assertEquals("", subtitles.first().lang)
  }

  @Test
  fun `a malformed response throws rather than being read as an empty list`() {
    // The caller shows "couldn't load" for a failure and "none found" for an empty
    // list, and those are two different things to a viewer.
    val client = client { "<html>gateway timeout</html>" }

    assertThrows(Exception::class.java) { runBlocking { client.movieSubtitles("tt1") } }
  }

  @Test
  fun `subtitle lists may be served from the cache, unlike stream urls`() = runBlocking {
    // A subtitle URL is a static file, not a signed debrid link with an hour to live.
    var stale = 0
    var plain = 0
    val client = SubtitlesClient(fetcher = object : HttpFetcher {
      override suspend fun get(url: String): String {
        plain++
        return """{"subtitles":[]}"""
      }

      override suspend fun getAllowingStale(url: String): String {
        stale++
        return """{"subtitles":[]}"""
      }
    })

    client.movieSubtitles("tt1")

    assertEquals(1, stale)
    assertEquals(0, plain)
  }

  @Test
  fun `the configured subtitles addon is asked instead of the default`() = runBlocking {
    var requested = ""
    val client = SubtitlesClient(
      fetcher = fetcher { url ->
        requested = url
        """{"subtitles":[]}"""
      },
      baseUrl = { "https://subs.example" },
    )

    client.movieSubtitles("tt1")

    assertEquals("https://subs.example/subtitles/movie/tt1.json", requested)
  }

  @Test
  fun `the base url is resolved per request, so a Settings change applies mid-session`() = runBlocking {
    // The player builds one client for the whole film.
    val bases = ArrayDeque(listOf("https://first.example", "https://second.example"))
    var requested = ""
    val client = SubtitlesClient(
      fetcher = fetcher { url ->
        requested = url
        """{"subtitles":[]}"""
      },
      baseUrl = { bases.removeFirst() },
    )

    client.movieSubtitles("tt1")
    client.movieSubtitles("tt1")

    assertEquals("https://second.example/subtitles/movie/tt1.json", requested)
  }

  @Test
  fun `a blank configured url falls back to the built-in addon`() = runBlocking {
    var requested = ""
    val client = SubtitlesClient(
      fetcher = fetcher { url ->
        requested = url
        """{"subtitles":[]}"""
      },
      baseUrl = { "  " },
    )

    client.movieSubtitles("tt1")

    assertEquals("${SubtitlesClient.OPENSUBTITLES_V3_BASE}/subtitles/movie/tt1.json", requested)
  }

  private fun fetcher(body: (String) -> String) = object : HttpFetcher {
    override suspend fun get(url: String): String = body(url)
    override suspend fun getAllowingStale(url: String): String = body(url)
  }

  private fun client(body: (String) -> String) = SubtitlesClient(
    fetcher = object : HttpFetcher {
      override suspend fun get(url: String): String = body(url)
      override suspend fun getAllowingStale(url: String): String = body(url)
    },
  )
}
