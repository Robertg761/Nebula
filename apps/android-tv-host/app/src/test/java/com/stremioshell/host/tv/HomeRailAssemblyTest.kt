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
    val assembled = HomeRailAssembly.visible(
      order = order,
      loaded = listOf(rail("Popular Shows", 4), rail("Trending Movies", 1), rail("Popular Movies", 3), rail("Trending Shows", 2)),
    )

    assertEquals(order, assembled.rails.map { it.title })
    assertTrue(assembled.missingTitles.isEmpty())
  }

  @Test
  fun `rails that loaded survive one that failed`() {
    val assembled = HomeRailAssembly.visible(
      order = order,
      loaded = listOf(rail("Trending Movies", 1), rail("Popular Movies", 3)),
      failed = setOf("Trending Shows", "Popular Shows"),
    )

    assertEquals(listOf("Trending Movies", "Popular Movies"), assembled.rails.map { it.title })
    assertEquals(listOf("Trending Shows", "Popular Shows"), assembled.missingTitles)
  }

  @Test
  fun `a failed rail falls back to what was already on screen`() {
    // The point: a refresh where one endpoint fails must not make a row disappear mid-browse.
    val assembled = HomeRailAssembly.visible(
      order = order,
      loaded = listOf(rail("Trending Movies", 11)),
      failed = order.drop(1).toSet(),
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
    val assembled = HomeRailAssembly.visible(order, loaded = emptyList(), failed = order.toSet())

    assertTrue(assembled.rails.isEmpty())
    assertEquals(order, assembled.missingTitles)
  }

  @Test
  fun `unknown loaded titles are ignored`() {
    // Only rails the caller asked for can reach Home, so a renamed rail cannot sneak in twice.
    val assembled = HomeRailAssembly.visible(
      order = listOf("Trending Movies"),
      loaded = listOf(rail("Trending Movies", 1), rail("Retired Rail", 9)),
    )

    assertEquals(listOf("Trending Movies"), assembled.rails.map { it.title })
  }

  // --- streaming a load in progress ---------------------------------------

  @Test
  fun `rails appear in declared order as they land, never out of it`() {
    // A rail that finished early is held back until the ones above it resolve, so nothing already
    // on screen is ever pushed down and the focused row cannot move.
    val assembled = HomeRailAssembly.visible(
      order = order,
      loaded = listOf(rail("Trending Movies", 1), rail("Popular Shows", 4)),
    )

    assertEquals(listOf("Trending Movies"), assembled.rails.map { it.title })
    assertTrue(assembled.missingTitles.isEmpty())
  }

  @Test
  fun `a rail still in flight is not reported as a gap`() {
    val assembled = HomeRailAssembly.visible(order, loaded = emptyList())

    assertTrue(assembled.rails.isEmpty())
    assertTrue(assembled.missingTitles.isEmpty())
  }

  @Test
  fun `a failed rail does not hold back the rails below it`() {
    val assembled = HomeRailAssembly.visible(
      order = order,
      loaded = listOf(rail("Trending Shows", 2)),
      failed = setOf("Trending Movies"),
    )

    assertEquals(listOf("Trending Shows"), assembled.rails.map { it.title })
    assertEquals(listOf("Trending Movies"), assembled.missingTitles)
  }

  @Test
  fun `a refresh keeps every row up while the new ones are still in flight`() {
    // Nothing has come back yet, so Home must look exactly as it did before the refresh started.
    val previous = order.mapIndexed { index, title -> rail(title, index) }
    val assembled = HomeRailAssembly.visible(order, loaded = emptyList(), previous = previous)

    assertEquals(order, assembled.rails.map { it.title })
    assertEquals(previous, assembled.rails)
  }
}
