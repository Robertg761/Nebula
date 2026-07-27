package com.stremioshell.host.tv.channel

import com.stremioshell.host.tv.data.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextMapperTest {
  @Test
  fun `a part watched movie maps to a continue row`() {
    val entry = entry(
      key = "movie:550",
      tmdbId = 550,
      mediaType = "movie",
      title = "Fight Club",
      posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
      positionMs = 1_800_000L,
      durationMs = 8_340_000L,
      updatedAtMs = 1_700_000_000_000L,
    )

    val program = WatchNextMapper.map(entry)

    assertEquals("movie:550", program.internalProviderId)
    assertEquals("Fight Club", program.title)
    assertEquals(WatchNextProgramType.Movie, program.type)
    assertEquals(WatchNextKind.Continue, program.kind)
    assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", program.posterArtUri)
    assertEquals(1_700_000_000_000L, program.lastEngagementTimeUtcMillis)
    assertEquals(1_800_000, program.lastPlaybackPositionMillis)
    assertEquals(8_340_000, program.durationMillis)
    assertNull(program.seasonNumber)
    assertNull(program.episodeNumber)
    assertEquals(
      "stremio-tv://watch-next?type=movie&tmdb=550&position=1800000",
      program.deepLinkUri,
    )
  }

  @Test
  fun `a part watched episode maps to a tv episode row carrying season and episode`() {
    val program = WatchNextMapper.map(
      entry(
        key = "episode:1399:4:9",
        tmdbId = 1399,
        mediaType = "show",
        title = "Game of Thrones",
        season = 4,
        episode = 9,
        positionMs = 600_000L,
        durationMs = 3_600_000L,
      ),
    )

    assertEquals(WatchNextProgramType.TvEpisode, program.type)
    assertEquals(WatchNextKind.Continue, program.kind)
    assertEquals(4, program.seasonNumber)
    assertEquals(9, program.episodeNumber)
    assertEquals(
      "stremio-tv://watch-next?type=show&tmdb=1399&season=4&episode=9&position=600000",
      program.deepLinkUri,
    )
  }

  @Test
  fun `an episode seeded with no progress goes out as next rather than continue`() {
    val program = WatchNextMapper.map(
      entry(
        key = "episode:1399:4:10",
        tmdbId = 1399,
        mediaType = "show",
        title = "Game of Thrones",
        season = 4,
        episode = 10,
        positionMs = 0L,
        durationMs = 0L,
      ),
    )

    assertEquals(WatchNextKind.Next, program.kind)
    // CONTINUE without a position renders as an empty progress bar; NEXT has none.
    assertNull(program.lastPlaybackPositionMillis)
    assertNull(program.durationMillis)
  }

  @Test
  fun `a position past the stored duration is capped instead of overflowing the bar`() {
    val program = WatchNextMapper.map(
      entry(
        key = "movie:1",
        tmdbId = 1,
        mediaType = "movie",
        title = "Odd",
        positionMs = 9_000_000L,
        durationMs = 8_000_000L,
      ),
    )

    assertEquals(8_000_000, program.lastPlaybackPositionMillis)
  }

  @Test
  fun `a blank poster is dropped rather than published as an empty art uri`() {
    val program = WatchNextMapper.map(
      entry(key = "movie:2", tmdbId = 2, mediaType = "movie", title = "T", posterUrl = "  "),
    )
    assertNull(program.posterArtUri)
  }

  @Test
  fun `finished entries are not resumable`() {
    val entries = listOf(
      entry(key = "movie:1", tmdbId = 1, mediaType = "movie", title = "Watched", positionMs = 0L)
        .copy(watchedAtMs = 5L),
      entry(key = "movie:2", tmdbId = 2, mediaType = "movie", title = "In progress", positionMs = 10L),
    )

    assertEquals(listOf("movie:2"), WatchNextMapper.resumable(entries).map { it.key })
  }

  @Test
  fun `entries that could only render as a broken card are dropped`() {
    val entries = listOf(
      entry(key = "movie:0", tmdbId = 0, mediaType = "movie", title = "No id", positionMs = 10L),
      entry(key = "movie:3", tmdbId = 3, mediaType = "movie", title = "   ", positionMs = 10L),
      entry(key = "movie:4", tmdbId = 4, mediaType = "movie", title = "Fine", positionMs = 10L),
    )

    assertEquals(listOf("movie:4"), WatchNextMapper.resumable(entries).map { it.key })
  }

  @Test
  fun `rows come out newest engagement first`() {
    val entries = listOf(
      entry(key = "movie:1", tmdbId = 1, mediaType = "movie", title = "Old", updatedAtMs = 100L),
      entry(key = "movie:2", tmdbId = 2, mediaType = "movie", title = "New", updatedAtMs = 300L),
      entry(key = "movie:3", tmdbId = 3, mediaType = "movie", title = "Mid", updatedAtMs = 200L),
    )

    assertEquals(
      listOf("movie:2", "movie:3", "movie:1"),
      WatchNextMapper.programsFor(entries).map { it.internalProviderId },
    )
  }

  @Test
  fun `the published set is capped`() {
    val entries = (1..(WatchNextMapper.MAX_PROGRAMS + 5)).map { index ->
      entry(
        key = "movie:$index",
        tmdbId = index,
        mediaType = "movie",
        title = "Title $index",
        updatedAtMs = index.toLong(),
      )
    }

    val programs = WatchNextMapper.programsFor(entries)

    assertEquals(WatchNextMapper.MAX_PROGRAMS, programs.size)
    // Kept the newest, not the first the store happened to hand over.
    assertTrue(programs.first().internalProviderId == "movie:${WatchNextMapper.MAX_PROGRAMS + 5}")
  }

  @Test
  fun `every published row deep links back to the entry it came from`() {
    val entry = entry(
      key = "episode:66732:1:2",
      tmdbId = 66732,
      mediaType = "show",
      title = "Stranger Things",
      season = 1,
      episode = 2,
      positionMs = 42_000L,
    )

    val program = WatchNextMapper.programsFor(listOf(entry)).single()

    assertEquals(
      WatchNextTarget("show", 66732, season = 1, episode = 2, resumePositionMs = 42_000L),
      WatchNextDeepLink.parse(program.deepLinkUri),
    )
  }

  private fun entry(
    key: String,
    tmdbId: Int,
    mediaType: String,
    title: String,
    posterUrl: String? = null,
    season: Int? = null,
    episode: Int? = null,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    updatedAtMs: Long = 1L,
  ) = WatchEntry(
    key = key,
    tmdbId = tmdbId,
    mediaType = mediaType,
    title = title,
    posterUrl = posterUrl,
    season = season,
    episode = episode,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAtMs = updatedAtMs,
  )
}
