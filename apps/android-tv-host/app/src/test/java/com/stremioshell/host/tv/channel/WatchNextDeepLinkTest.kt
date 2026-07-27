package com.stremioshell.host.tv.channel

import com.stremioshell.host.tv.data.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextDeepLinkTest {
  @Test
  fun `movie target round trips`() {
    val target = WatchNextTarget(mediaType = "movie", tmdbId = 550, resumePositionMs = 1_234_000L)
    assertEquals(target, WatchNextDeepLink.parse(WatchNextDeepLink.build(target)))
  }

  @Test
  fun `episode target round trips with season and episode`() {
    val target = WatchNextTarget(
      mediaType = "show",
      tmdbId = 1399,
      season = 4,
      episode = 9,
      resumePositionMs = 3_600_500L,
    )
    assertEquals(target, WatchNextDeepLink.parse(WatchNextDeepLink.build(target)))
  }

  @Test
  fun `zero position round trips as zero rather than as a missing field`() {
    val target = WatchNextTarget(mediaType = "show", tmdbId = 82856, season = 1, episode = 1)
    val uri = WatchNextDeepLink.build(target)
    assertEquals(false, uri.contains("position"))
    assertEquals(target, WatchNextDeepLink.parse(uri))
  }

  @Test
  fun `built uri uses the app scheme and host`() {
    val uri = WatchNextDeepLink.build(WatchNextTarget(mediaType = "movie", tmdbId = 27205))
    assertEquals("stremio-tv://watch-next?type=movie&tmdb=27205", uri)
  }

  @Test
  fun `target is derived from a watch entry`() {
    val entry = entry(
      key = "episode:1399:2:3",
      tmdbId = 1399,
      mediaType = "show",
      season = 2,
      episode = 3,
      positionMs = 500_000L,
    )
    assertEquals(
      WatchNextTarget("show", 1399, season = 2, episode = 3, resumePositionMs = 500_000L),
      WatchNextDeepLink.targetFor(entry),
    )
  }

  @Test
  fun `unknown media type on an entry falls back to movie`() {
    val entry = entry(key = "movie:1", tmdbId = 1, mediaType = "something-else")
    assertEquals("movie", WatchNextDeepLink.targetFor(entry).mediaType)
  }

  @Test
  fun `foreign and malformed uris are not targets`() {
    assertNull(WatchNextDeepLink.parse(null))
    assertNull(WatchNextDeepLink.parse(""))
    assertNull(WatchNextDeepLink.parse("https://example.com/watch-next?type=movie&tmdb=1"))
    assertNull(WatchNextDeepLink.parse("stremio-tv://other?type=movie&tmdb=1"))
    // No type, no tmdb id, an unusable type, and an id that cannot address anything.
    assertNull(WatchNextDeepLink.parse("stremio-tv://watch-next?tmdb=1"))
    assertNull(WatchNextDeepLink.parse("stremio-tv://watch-next?type=movie"))
    assertNull(WatchNextDeepLink.parse("stremio-tv://watch-next?type=channel&tmdb=1"))
    assertNull(WatchNextDeepLink.parse("stremio-tv://watch-next?type=movie&tmdb=abc"))
    assertNull(WatchNextDeepLink.parse("stremio-tv://watch-next?type=movie&tmdb=0"))
    assertNull(WatchNextDeepLink.parse("stremio-tv://watch-next?type=movie&tmdb=-7"))
  }

  @Test
  fun `unparseable optional fields are dropped rather than failing the whole link`() {
    val target = WatchNextDeepLink.parse(
      "stremio-tv://watch-next?type=show&tmdb=1399&season=x&episode=&position=nope",
    )
    assertEquals(WatchNextTarget("show", 1399), target)
  }

  private fun entry(
    key: String,
    tmdbId: Int,
    mediaType: String,
    season: Int? = null,
    episode: Int? = null,
    positionMs: Long = 0L,
  ) = WatchEntry(
    key = key,
    tmdbId = tmdbId,
    mediaType = mediaType,
    title = "Title",
    season = season,
    episode = episode,
    positionMs = positionMs,
    updatedAtMs = 1L,
  )
}
