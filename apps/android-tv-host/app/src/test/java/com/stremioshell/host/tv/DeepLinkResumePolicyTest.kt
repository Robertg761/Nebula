package com.stremioshell.host.tv

import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.ui.Screen
import com.stremioshell.host.tv.ui.deepLinkFallbackMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkResumePolicyTest {
  @Test
  fun `local position including zero is authoritative over the deep link fallback`() {
    assertEquals(
      0L,
      resolveResumePositionMs(startOver = false, storedPositionMs = 0L, fallbackPositionMs = 60_000L),
    )
    assertEquals(
      30_000L,
      resolveResumePositionMs(
        startOver = false,
        storedPositionMs = 30_000L,
        fallbackPositionMs = 60_000L,
      ),
    )
  }

  @Test
  fun `deep link position is the fallback for an absent or unreadable local record`() {
    assertEquals(
      60_000L,
      resolveResumePositionMs(
        startOver = false,
        storedPositionMs = null,
        fallbackPositionMs = 60_000L,
      ),
    )
  }

  @Test
  fun `start over wins over both stored and deep link positions`() {
    assertEquals(
      0L,
      resolveResumePositionMs(
        startOver = true,
        storedPositionMs = 30_000L,
        fallbackPositionMs = 60_000L,
      ),
    )
  }

  @Test
  fun `unsafe stored and fallback positions resolve to the beginning`() {
    assertEquals(
      0L,
      resolveResumePositionMs(
        startOver = false,
        storedPositionMs = Long.MAX_VALUE,
        fallbackPositionMs = 60_000L,
      ),
    )
    assertEquals(
      0L,
      resolveResumePositionMs(
        startOver = false,
        storedPositionMs = null,
        fallbackPositionMs = Long.MAX_VALUE,
      ),
    )
  }

  @Test
  fun `show fallback follows only the episode named by the deep link`() {
    val screen = Screen.Details(
      MediaType.Show,
      tmdbId = 1,
      initialSeason = 2,
      initialEpisode = 3,
      resumePositionFallbackMs = 60_000L,
    )

    assertTrue(deepLinkFallbackMatches(screen, season = 2, episode = 3))
    assertFalse(deepLinkFallbackMatches(screen, season = 2, episode = 4))
    assertFalse(deepLinkFallbackMatches(screen, season = 1, episode = 3))
  }

  @Test
  fun `movie fallback applies only to the movie stream target`() {
    val screen = Screen.Details(MediaType.Movie, tmdbId = 1, resumePositionFallbackMs = 60_000L)

    assertTrue(deepLinkFallbackMatches(screen, season = null, episode = null))
    assertFalse(deepLinkFallbackMatches(screen, season = 1, episode = 1))
  }
}
