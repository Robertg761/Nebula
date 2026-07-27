package com.stremioshell.host.tv

import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRailAssemblyTest {
  private val order = listOf("Trending Movies", "Trending Shows", "Popular Movies", "Popular Shows")

  private fun rail(title: String, itemId: Int) = HomeRail(
    title,
    listOf(
      MediaItem(
        tmdbId = itemId,
        type = MediaType.Movie,
        title = "item $itemId",
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        year = null,
        rating = null,
      ),
    ),
  )

  @Test
  fun `a full load keeps the declared order regardless of completion order`() {
    val assembled = HomeRailAssembly.merge(
      order = order,
      fresh = listOf(rail("Popular Shows", 4), rail("Trending Movies", 1), rail("Popular Movies", 3), rail("Trending Shows", 2)),
      previous = emptyList(),
    )

    assertEquals(order, assembled.rails.map { it.title })
    assertTrue(assembled.missingTitles.isEmpty())
  }

  @Test
  fun `rails that loaded survive one that failed`() {
    val assembled = HomeRailAssembly.merge(
      order = order,
      fresh = listOf(rail("Trending Movies", 1), rail("Popular Movies", 3)),
      previous = emptyList(),
    )

    assertEquals(listOf("Trending Movies", "Popular Movies"), assembled.rails.map { it.title })
    assertEquals(listOf("Trending Shows", "Popular Shows"), assembled.missingTitles)
  }

  @Test
  fun `a failed rail falls back to what was already on screen`() {
    // The point: a refresh where one endpoint fails must not make a row disappear mid-browse.
    val assembled = HomeRailAssembly.merge(
      order = order,
      fresh = listOf(rail("Trending Movies", 11)),
      previous = order.mapIndexed { index, title -> rail(title, index) },
    )

    assertEquals(order, assembled.rails.map { it.title })
    assertTrue(assembled.missingTitles.isEmpty())
    // The rail that did load shows the new items, not the stale ones.
    assertEquals(11, assembled.rails.first().items.single().tmdbId)
    assertEquals(1, assembled.rails[1].items.single().tmdbId)
  }

  @Test
  fun `an empty result with nothing to fall back on leaves nothing to show`() {
    val assembled = HomeRailAssembly.merge(order, fresh = emptyList(), previous = emptyList())

    assertTrue(assembled.rails.isEmpty())
    assertEquals(order, assembled.missingTitles)
  }

  @Test
  fun `unknown fresh titles are ignored`() {
    // Only rails the caller asked for can reach Home, so a renamed rail cannot sneak in twice.
    val assembled = HomeRailAssembly.merge(
      order = listOf("Trending Movies"),
      fresh = listOf(rail("Trending Movies", 1), rail("Retired Rail", 9)),
      previous = emptyList(),
    )

    assertEquals(listOf("Trending Movies"), assembled.rails.map { it.title })
  }
}
