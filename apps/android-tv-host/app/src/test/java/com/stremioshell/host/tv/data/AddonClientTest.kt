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
  fun `stream url canonicalization preserves query and ignores manifest case and fragment`() {
    assertEquals(
      "https://comet.example/cfg/stream/series/tt1:2:3.json?token=secret",
      AddonClient.streamUrl(
        "https://comet.example/cfg/MANIFEST.JSON?token=secret#install",
        "series",
        "tt1:2:3",
      ),
    )
  }

  @Test
  fun `a base route with a query is completed before deriving the stream route`() {
    assertEquals(
      "https://comet.example/cfg/stream/movie/tt1.json?token=secret",
      AddonClient.streamUrl("https://comet.example/cfg?token=secret", "movie", "tt1"),
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
  fun `stream playback metadata survives protocol decoding intact`() = runBlocking {
    val client = AddonClient(fetcher = {
      """
        {"streams":[{
          "name":"Protected 4K",
          "url":"https://cdn.example/video.mkv",
          "subtitles":[
            {"id":"forced","url":"https://cdn.example/forced.srt","lang":"eng"},
            {"url":"https://cdn.example/signs.ass"}
          ],
          "behaviorHints":{
            "filename":"Movie.2160p.mkv",
            "videoSize":123456789,
            "videoHash":"abcdef012345",
            "proxyHeaders":{
              "request":{"Authorization":"Bearer secret","Referer":"https://addon.example/"},
              "response":{"Content-Type":"video/x-matroska"}
            },
            "notWebReady":true,
            "countryWhitelist":["CA","US"]
          }
        }]}
      """.trimIndent()
    })

    val stream = client.movieStreams("https://comet.example/manifest.json", "tt1").single()
    val hints = requireNotNull(stream.behaviorHints)

    assertEquals("forced", stream.subtitles.first().id)
    assertEquals("eng", stream.subtitles.first().lang)
    assertEquals(null, stream.subtitles.last().lang)
    assertEquals("Movie.2160p.mkv", hints.filename)
    assertEquals(123456789L, hints.videoSize)
    assertEquals("abcdef012345", hints.videoHash)
    assertEquals("Bearer secret", hints.proxyHeaders?.request?.get("Authorization"))
    assertEquals("video/x-matroska", hints.proxyHeaders?.response?.get("Content-Type"))
    assertEquals(true, hints.notWebReady)
    assertEquals(listOf("CA", "US"), hints.countryWhitelist)
  }

  @Test
  fun `an addon cannot name itself as the source of a row`() = runBlocking {
    val client = AddonClient(fetcher = {
      """{"streams":[{"name":"Comet","url":"https://rd.example/v.mkv","source":"Torrentio"}]}"""
    })

    assertEquals(null, client.movieStreams("https://comet.example/cfg/manifest.json", "tt1").single().source)
  }

  @Test
  fun `an explicit null costs its own field, not the whole response`() = runBlocking {
    // Addons send nulls where the protocol implies a default. Without coerceInputValues kotlinx
    // throws on the first one, and a single `"subtitles": null` cost every stream that addon
    // returned - the viewer sees "no streams found" for a title the addon answered for.
    val client = AddonClient(fetcher = {
      """
      {"streams":[
        {"name":"Comet 1080p","title":null,"description":null,
         "url":"https://rd.example/v.mkv","subtitles":null,
         "behaviorHints":{"filename":null,"countryWhitelist":null,"bingeGroup":"comet|1080p"}},
        {"name":"Comet 720p","url":"https://rd.example/w.mkv",
         "subtitles":[{"id":null,"url":"https://subs.example/a.srt","lang":null}]}
      ]}
      """.trimIndent()
    })

    val streams = client.movieStreams("https://comet.example/cfg/manifest.json", "tt1")

    assertEquals(2, streams.size)
    assertEquals(emptyList<Any>(), streams.first().subtitles)
    assertEquals(emptyList<String>(), streams.first().behaviorHints?.countryWhitelist)
    assertEquals("comet|1080p", streams.first().bingeGroup)
    // Already-nullable fields keep taking the null; coercion is only for the defaulted ones.
    assertEquals(null, streams.last().subtitles.single().id)
    assertEquals("https://subs.example/a.srt", streams.last().subtitles.single().url)
  }

  @Test
  fun `a null streams array is an empty list of streams, not a failed load`() = runBlocking {
    val client = AddonClient(fetcher = { """{"streams":null}""" })

    assertEquals(
      emptyList<Any>(),
      client.movieStreams("https://comet.example/cfg/manifest.json", "tt1"),
    )
  }

  @Test
  fun `cleartext manifest url is rejected before a request path is built`() {
    assertThrows(IllegalArgumentException::class.java) {
      AddonClient.streamUrl("http://comet.example/abc123/manifest.json", "movie", "tt1")
    }
  }
}
