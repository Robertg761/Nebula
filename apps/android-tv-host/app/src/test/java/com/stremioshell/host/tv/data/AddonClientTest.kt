package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.addon.AddonClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddonClientTest {
  @Test
  fun `stream url is derived from manifest url`() {
    assertEquals(
      "https://comet.example/abc123/stream/movie/tt0111161.json",
      AddonClient.streamUrl("https://comet.example/abc123/manifest.json", "movie", "tt0111161"),
    )
  }

  @Test
  fun `episode ids use imdb season episode format`() = runBlocking {
    var requestedUrl = ""
    val client = AddonClient(fetcher = { url ->
      requestedUrl = url
      """{"streams":[{"name":"[RD+] Comet","title":"Show S01E02","url":"https://rd.example/v.mkv"}]}"""
    })

    val streams = client.episodeStreams("https://comet.example/cfg/manifest.json", "tt14688458", 1, 2)

    assertEquals("https://comet.example/cfg/stream/series/tt14688458:1:2.json", requestedUrl)
    assertEquals(1, streams.size)
    assertEquals("[RD+] Comet", streams.first().label)
    assertEquals("https://rd.example/v.mkv", streams.first().url)
  }

  @Test
  fun `streams without a url are dropped`() = runBlocking {
    val client = AddonClient(fetcher = {
      """{"streams":[
        {"name":"No url stream","infoHash":"abc"},
        {"name":"Good","url":"https://rd.example/v.mp4"}]}"""
    })

    val streams = client.movieStreams("https://comet.example/cfg/manifest.json", "tt1")
    assertEquals(listOf("Good"), streams.map { it.label })
  }

  @Test
  fun `stream requests never accept a stale cached response`() = runBlocking {
    // Debrid stream URLs expire; replaying a cached one hands the player a dead link.
    var stale = 0
    var plain = 0
    val client = AddonClient(fetcher = object : HttpFetcher {
      override suspend fun get(url: String): String {
        plain++
        return """{"streams":[{"name":"Good","url":"https://rd.example/v.mp4"}]}"""
      }

      override suspend fun getAllowingStale(url: String): String {
        stale++
        return """{"streams":[]}"""
      }
    })

    client.movieStreams("https://comet.example/cfg/manifest.json", "tt1")

    assertEquals(1, plain)
    assertEquals(0, stale)
  }

  @Test
  fun `infoHash and fileIdx are read, so one torrent can be spotted across two addons`() = runBlocking {
    val client = AddonClient(fetcher = {
      """{"streams":[{"name":"Comet","url":"https://rd.example/v.mkv",
        "infoHash":"ABC123","fileIdx":2}]}"""
    })

    val stream = client.movieStreams("https://comet.example/cfg/manifest.json", "tt1").single()

    assertEquals("ABC123", stream.infoHash)
    assertEquals(2, stream.fileIdx)
    // Ours to set, never the addon's: a response cannot claim to come from elsewhere.
    assertEquals(null, stream.source)
  }

  @Test
  fun `an addon cannot name itself as the source of a row`() = runBlocking {
    val client = AddonClient(fetcher = {
      """{"streams":[{"name":"Comet","url":"https://rd.example/v.mkv","source":"Torrentio"}]}"""
    })

    assertEquals(null, client.movieStreams("https://comet.example/cfg/manifest.json", "tt1").single().source)
  }

  @Test
  fun `manifest url without manifest json is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      AddonClient.streamUrl("https://comet.example/abc123", "movie", "tt1")
    }
  }
}
