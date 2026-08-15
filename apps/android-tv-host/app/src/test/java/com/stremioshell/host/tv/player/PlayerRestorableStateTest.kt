package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.addon.AddonStreamSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRestorableStateTest {
  @Test
  fun `runtime episode descriptor retains every process-death field`() {
    val headers = linkedMapOf(
      "Authorization" to "Bearer current-episode",
      "User-Agent" to "Nebula TV",
    )
    val subtitles = listOf(
      AddonStreamSubtitle("english", "https://cdn.example/en.srt", "eng"),
      AddonStreamSubtitle("japanese", "https://cdn.example/ja.srt", "jpn"),
    )

    val state = PlayerRestorableState.create(
      url = "https://video.example/episode-b?fresh=1",
      title = "Example Show",
      watchKey = "episode:42:3:8",
      tmdbId = 42,
      mediaType = "show",
      posterUrl = "https://image.example/poster.jpg",
      season = 3,
      episode = 8,
      imdbId = "tt1234567",
      bingeGroup = "release-b",
      requestHeaders = headers,
      embeddedSubtitles = subtitles,
      streamVideoHash = "video-hash-b",
      streamFilename = "Example.Show.S03E08.mkv",
      streamVideoSize = 9_876_543_210L,
      positionMs = 1_234_000L,
      resumeResetRequested = true,
      pauseRequested = true,
    )

    assertEquals("https://video.example/episode-b?fresh=1", state.url)
    assertEquals("Example Show", state.title)
    assertEquals("episode:42:3:8", state.watchKey)
    assertEquals(42, state.tmdbId)
    assertEquals("show", state.mediaType)
    assertEquals("https://image.example/poster.jpg", state.posterUrl)
    assertEquals(3, state.season)
    assertEquals(8, state.episode)
    assertEquals("tt1234567", state.imdbId)
    assertEquals("release-b", state.bingeGroup)
    assertEquals(headers, state.requestHeaders())
    assertEquals(subtitles, state.embeddedSubtitles())
    assertEquals("video-hash-b", state.streamVideoHash)
    assertEquals("Example.Show.S03E08.mkv", state.streamFilename)
    assertEquals(9_876_543_210L, state.streamVideoSize)
    assertEquals(1_234_000L, state.positionMs)
    assertTrue(state.resumeResetRequested)
    assertTrue(state.pauseRequested)
  }

  @Test
  fun `damaged parallel metadata lists restore only complete pairs`() {
    val state = PlayerRestorableState(
      url = "https://video.example/file",
      title = "Title",
      watchKey = "movie:1",
      tmdbId = 1,
      mediaType = "movie",
      posterUrl = null,
      season = null,
      episode = null,
      imdbId = null,
      bingeGroup = null,
      requestHeaderNames = listOf("Accept", "Missing"),
      requestHeaderValues = listOf("video/*"),
      subtitleUrls = listOf("https://cdn.example/sub.srt"),
      subtitleLanguages = emptyList(),
      subtitleIds = emptyList(),
      streamVideoHash = null,
      streamFilename = null,
      streamVideoSize = null,
      positionMs = 0,
      resumeResetRequested = false,
      pauseRequested = false,
    )

    assertEquals(mapOf("Accept" to "video/*"), state.requestHeaders())
    assertEquals(
      listOf(AddonStreamSubtitle(null, "https://cdn.example/sub.srt", null)),
      state.embeddedSubtitles(),
    )
  }

  @Test
  fun `oversized addon metadata is bounded before saved state is parcelled`() {
    val headers = (0 until 32).associate { index ->
      "X-Token-$index" to "h".repeat(8 * 1024)
    }
    val subtitles = (0 until 60).map { index ->
      AddonStreamSubtitle(
        id = "id-$index-${"i".repeat(120)}",
        url = "https://subs.example/$index/${"u".repeat(4_000)}.srt",
        lang = "language-${"l".repeat(64)}",
      )
    }

    val state = PlayerRestorableState.create(
      url = "https://video.example/file",
      title = "t".repeat(4_000),
      watchKey = "w".repeat(4_000),
      tmdbId = 1,
      mediaType = "m".repeat(100),
      posterUrl = "https://images.example/${"p".repeat(4_000)}",
      season = 1,
      episode = 2,
      imdbId = "i".repeat(1_000),
      bingeGroup = "b".repeat(10_000),
      requestHeaders = headers,
      embeddedSubtitles = subtitles,
      streamVideoHash = "v".repeat(1_000),
      streamFilename = "f".repeat(10_000),
      streamVideoSize = 1L,
      positionMs = 0L,
      resumeResetRequested = false,
      pauseRequested = false,
    )

    assertEquals(PlayerPayloadBounds.MAX_TITLE_CHARS, state.title.length)
    assertEquals(PlayerPayloadBounds.MAX_WATCH_KEY_CHARS, state.watchKey.length)
    assertNull(state.posterUrl)
    assertNull(state.imdbId)
    assertNull(state.bingeGroup)
    assertNull(state.streamVideoHash)
    assertEquals(PlayerPayloadBounds.MAX_FILENAME_CHARS, state.streamFilename?.length)
    val totalTextChars = listOf(
      state.url,
      state.title,
      state.watchKey,
      state.mediaType,
      state.posterUrl.orEmpty(),
      state.imdbId.orEmpty(),
      state.bingeGroup.orEmpty(),
      state.streamVideoHash.orEmpty(),
      state.streamFilename.orEmpty(),
    ).sumOf(String::length) +
      state.requestHeaderNames.sumOf(String::length) +
      state.requestHeaderValues.sumOf(String::length) +
      state.subtitleUrls.sumOf(String::length) +
      state.subtitleLanguages.sumOf(String::length) +
      state.subtitleIds.sumOf(String::length)
    assertTrue(totalTextChars <= PlayerPayloadBounds.MAX_TOTAL_TEXT_CHARS)
  }

  @Test
  fun `text truncation never leaves half an emoji at the boundary`() {
    val raw = "a".repeat(PlayerPayloadBounds.MAX_TITLE_CHARS - 1) + "\uD83D\uDE80" + "tail"

    val bounded = PlayerPayloadBounds.required(raw, PlayerPayloadBounds.MAX_TITLE_CHARS)

    assertEquals(PlayerPayloadBounds.MAX_TITLE_CHARS - 1, bounded.length)
    assertFalse(bounded.last().isHighSurrogate())
  }
}
