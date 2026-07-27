package com.stremioshell.host.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yLabelsTest {
  @Test
  fun `a card announces its title and caption`() {
    assertEquals("Dune", A11yLabels.card("Dune"))
    assertEquals("Dune, 2021, Movie", A11yLabels.card("Dune", "2021 • Movie"))
  }

  @Test
  fun `a blank caption does not leave a trailing comma`() {
    assertEquals("Dune", A11yLabels.card("Dune", ""))
    assertEquals("Dune", A11yLabels.card("Dune", "   "))
  }

  @Test
  fun `a manageable card says how to manage it`() {
    // The long press is the only way to remove a title, and nothing on screen says so.
    assertEquals(
      "Dune, 2021, Movie. Press and hold for options",
      A11yLabels.card("Dune", "2021 • Movie", manageable = true),
    )
  }

  @Test
  fun `detail lines separated by wide dashes are read as separate facts`() {
    assertEquals("2019, 4 seasons, ~52m", A11yLabels.spoken("2019  •  4 seasons  •  ~52m"))
    assertEquals("12 Mar 2026, Watched", A11yLabels.spoken("12 Mar 2026  -  Watched"))
  }

  @Test
  fun `a year range keeps its dash`() {
    // Only the wide separator is a separator; "2019 - 2023" is one fact.
    assertEquals("2019 - 2023, Series", A11yLabels.spoken("2019 - 2023  •  Series"))
  }

  @Test
  fun `continue watching says the episode and how far in it is`() {
    assertEquals(
      "Severance, season 2, episode 5, 40% watched. Press and hold for options",
      A11yLabels.continueWatching("Severance", 2, 5, 0.4f),
    )
  }

  @Test
  fun `a movie in continue watching has no episode to announce`() {
    assertEquals(
      "Dune, 63% watched. Press and hold for options",
      A11yLabels.continueWatching("Dune", null, null, 0.625f),
    )
  }

  @Test
  fun `an untouched position is not announced as progress`() {
    assertNull(A11yLabels.progressLabel(0f))
    // Rounds to 0%, which would announce as if nothing had been watched at all.
    assertNull(A11yLabels.progressLabel(0.002f))
    assertEquals("1% watched", A11yLabels.progressLabel(0.01f))
    assertEquals("finished", A11yLabels.progressLabel(1f))
    assertEquals("finished", A11yLabels.progressLabel(2f))
  }

  @Test
  fun `an episode code is spelled out rather than left as a code`() {
    assertEquals("season 4, episode 2", A11yLabels.episodeCode(4, 2))
    assertNull(A11yLabels.episodeCode(null, 2))
    assertNull(A11yLabels.episodeCode(4, null))
  }

  @Test
  fun `an episode row announces its number, name and state`() {
    assertEquals(
      "Episode 3, The Great Game, 12 Mar 2026, Watched",
      A11yLabels.episodeRow(3, "The Great Game", "12 Mar 2026  -  Watched"),
    )
    assertEquals("Episode 1, Pilot", A11yLabels.episodeRow(1, "Pilot", ""))
  }

  @Test
  fun `an unaired episode says when it airs, which is why OK does nothing`() {
    val label = A11yLabels.episodeRow(9, "Finale", "Airs 3 May 2026")
    assertTrue(label, label.endsWith("Airs 3 May 2026"))
  }

  @Test
  fun `a cast card names the part`() {
    assertEquals("Zendaya as Chani", A11yLabels.castMember("Zendaya", "Chani"))
    assertEquals("Zendaya", A11yLabels.castMember("Zendaya", "  "))
  }

  @Test
  fun `the billboard announces as one sentence`() {
    assertEquals(
      "Featured: Dune, 2021, Film, 8.1 / 10, View details",
      A11yLabels.hero("Dune", "2021  •  Film  •  8.1 / 10"),
    )
    assertEquals("Featured: Dune, View details", A11yLabels.hero("Dune", ""))
  }

  @Test
  fun `the watchlist button says what pressing it does`() {
    // The visible label is "✓ In My List - Remove", which spoken says neither.
    assertEquals("Remove Dune from My List", A11yLabels.watchlistButton("Dune", inList = true))
    assertEquals("Add Dune to My List", A11yLabels.watchlistButton("Dune", inList = false))
  }
}
