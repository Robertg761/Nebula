package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.player.NextEpisodeFinder.EpisodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEpisodeFinderTest {
  private val seasonOne = (1..6).map { EpisodeRef(1, it) }

  @Test
  fun `the next episode of the same season follows in order`() {
    assertEquals(EpisodeRef(1, 3), NextEpisodeFinder.next(EpisodeRef(1, 2), seasonOne))
  }

  @Test
  fun `a gap in the numbering does not end the series`() {
    val withGap = listOf(EpisodeRef(1, 1), EpisodeRef(1, 2), EpisodeRef(1, 5))

    assertEquals(EpisodeRef(1, 5), NextEpisodeFinder.next(EpisodeRef(1, 2), withGap))
  }

  @Test
  fun `list order does not decide the answer`() {
    val shuffled = listOf(EpisodeRef(1, 4), EpisodeRef(1, 1), EpisodeRef(1, 3), EpisodeRef(1, 2))

    assertEquals(EpisodeRef(1, 3), NextEpisodeFinder.next(EpisodeRef(1, 2), shuffled))
  }

  @Test
  fun `the last episode of a season rolls into the next one`() {
    val episodes = seasonOne + listOf(EpisodeRef(2, 1), EpisodeRef(2, 2))

    assertEquals(EpisodeRef(2, 1), NextEpisodeFinder.next(EpisodeRef(1, 6), episodes))
  }

  @Test
  fun `the last episode of the series has no next`() {
    assertNull(NextEpisodeFinder.next(EpisodeRef(1, 6), seasonOne))
  }

  @Test
  fun `specials are never the next episode`() {
    val episodes = seasonOne + listOf(EpisodeRef(0, 1), EpisodeRef(0, 2))

    assertEquals(EpisodeRef(1, 3), NextEpisodeFinder.next(EpisodeRef(1, 2), episodes))
    assertNull(NextEpisodeFinder.next(EpisodeRef(1, 6), episodes))
  }

  @Test
  fun `finishing a special does not drop the viewer into the series`() {
    val episodes = seasonOne + listOf(EpisodeRef(0, 1))

    assertNull(NextEpisodeFinder.next(EpisodeRef(0, 1), episodes))
  }

  @Test
  fun `the next season is the lowest one above the current`() {
    assertEquals(3, NextEpisodeFinder.nextSeason(currentSeason = 2, seasons = listOf(1, 2, 3, 4)))
    assertNull(NextEpisodeFinder.nextSeason(currentSeason = 4, seasons = listOf(1, 2, 3, 4)))
  }

  @Test
  fun `a missing season number is skipped rather than guessed`() {
    assertEquals(5, NextEpisodeFinder.nextSeason(currentSeason = 3, seasons = listOf(1, 3, 5)))
  }

  @Test
  fun `specials are not a season to move on to`() {
    assertNull(NextEpisodeFinder.nextSeason(currentSeason = 1, seasons = listOf(0, 1)))
  }

  @Test
  fun `a season starts at its lowest episode, not necessarily one`() {
    val episodes = listOf(EpisodeRef(2, 3), EpisodeRef(2, 2))

    assertEquals(EpisodeRef(2, 2), NextEpisodeFinder.firstOfSeason(episodes, season = 2))
    assertNull(NextEpisodeFinder.firstOfSeason(episodes, season = 3))
  }
}
