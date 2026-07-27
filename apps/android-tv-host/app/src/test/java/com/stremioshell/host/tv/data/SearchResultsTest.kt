package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.SearchFilter
import com.stremioshell.host.tv.data.tmdb.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsTest {
  private fun item(
    id: Int,
    title: String = "item $id",
    type: MediaType = MediaType.Movie,
    poster: String? = "/p$id.jpg",
    backdrop: String? = null,
    year: String? = "2020",
  ) = MediaItem(
    tmdbId = id,
    type = type,
    title = title,
    posterUrl = poster,
    backdropUrl = backdrop,
    overview = "",
    year = year,
    rating = null,
  )

  // --- the type filter -----------------------------------------------------

  @Test
  fun `filter narrows to one media type without touching the rest`() {
    val items = listOf(
      item(1, "A", MediaType.Movie),
      item(2, "B", MediaType.Show),
      item(3, "C", MediaType.Movie),
    )

    assertEquals(listOf(1, 2, 3), SearchResults.present("", items, SearchFilter.All).map { it.tmdbId })
    assertEquals(listOf(1, 3), SearchResults.present("", items, SearchFilter.Movies).map { it.tmdbId })
    assertEquals(listOf(2), SearchResults.present("", items, SearchFilter.Shows).map { it.tmdbId })
  }

  @Test
  fun `a filter restores from its name and survives an unknown one`() {
    assertEquals(SearchFilter.Shows, SearchFilter.of("Shows"))
    assertEquals(SearchFilter.All, SearchFilter.of(null))
    assertEquals(SearchFilter.All, SearchFilter.of("Podcasts"))
    // Every filter round-trips through the name the screen saves.
    for (filter in SearchFilter.values()) assertEquals(filter, SearchFilter.of(filter.name))
  }

  @Test
  fun `accepts answers for one item at a time`() {
    assertTrue(SearchFilter.All.accepts(item(1, type = MediaType.Show)))
    assertTrue(SearchFilter.Shows.accepts(item(1, type = MediaType.Show)))
    assertFalse(SearchFilter.Movies.accepts(item(1, type = MediaType.Show)))
  }

  // --- ordering ------------------------------------------------------------

  @Test
  fun `an exact title match leads, whatever TMDB thought`() {
    // TMDB sorts search by popularity, which buries the film actually asked for under its sequels.
    val items = listOf(item(1, "Alien vs Predator"), item(2, "Aliens"), item(3, "Alien"))

    val presented = SearchResults.present("Alien", items, SearchFilter.All)

    assertEquals(listOf(3, 1, 2), presented.map { it.tmdbId })
  }

  @Test
  fun `exact means exact apart from case and spacing`() {
    val items = listOf(item(1, "Blade Runner 2049"), item(2, "  blade   runner  "))

    val presented = SearchResults.present("Blade Runner", items, SearchFilter.All)

    assertEquals(listOf(2, 1), presented.map { it.tmdbId })
  }

  @Test
  fun `several exact matches keep TMDB's order among themselves`() {
    val items = listOf(item(1, "Dune"), item(2, "Dune Part Two"), item(3, "dune"))

    val presented = SearchResults.present("Dune", items, SearchFilter.All)

    assertEquals(listOf(1, 3, 2), presented.map { it.tmdbId })
  }

  @Test
  fun `nothing moves when nothing matches exactly`() {
    val items = listOf(item(1, "Alien"), item(2, "Aliens"))

    assertEquals(listOf(1, 2), SearchResults.present("xenomorph", items, SearchFilter.All).map { it.tmdbId })
  }

  @Test
  fun `the query the results came back for decides the order, not the type filter`() {
    val items = listOf(item(1, "Fargo Season", MediaType.Show), item(2, "Fargo", MediaType.Show))

    val presented = SearchResults.present("fargo", items, SearchFilter.Shows)

    assertEquals(listOf(2, 1), presented.map { it.tmdbId })
  }

  // --- what gets dropped ---------------------------------------------------

  @Test
  fun `entries with no artwork at all are dropped`() {
    val items = listOf(
      item(1, poster = null, backdrop = null),
      item(2, poster = "/p.jpg"),
      item(3, poster = null, backdrop = "/b.jpg"),
    )

    assertEquals(listOf(2, 3), SearchResults.present("", items, SearchFilter.All).map { it.tmdbId })
  }

  @Test
  fun `artless entries stay when they are all there is`() {
    // An empty grid answers the viewer worse than a row of placeholder cards.
    val items = listOf(item(1, poster = null, backdrop = null), item(2, poster = null, backdrop = null))

    assertEquals(listOf(1, 2), SearchResults.present("", items, SearchFilter.All).map { it.tmdbId })
  }

  @Test
  fun `the artwork rule is applied per filter, so a tab is never needlessly empty`() {
    val items = listOf(
      item(1, "A", MediaType.Movie, poster = "/p.jpg"),
      item(2, "B", MediaType.Show, poster = null, backdrop = null),
    )

    assertEquals(listOf(2), SearchResults.present("", items, SearchFilter.Shows).map { it.tmdbId })
  }

  @Test
  fun `a title carried twice appears once`() {
    val duplicate = item(7, "Fargo", MediaType.Show)

    val presented = SearchResults.present("", listOf(duplicate, duplicate), SearchFilter.All)

    assertEquals(1, presented.size)
  }

  @Test
  fun `an empty answer stays empty`() {
    assertTrue(SearchResults.present("dune", emptyList(), SearchFilter.All).isEmpty())
  }

  // --- card and state copy -------------------------------------------------

  @Test
  fun `a card caption carries the year and the kind of title`() {
    assertEquals("2020 • Movie", SearchResults.caption(item(1)))
    assertEquals("2020 • Series", SearchResults.caption(item(1, type = MediaType.Show)))
    // TMDB has no date for plenty of catalog entries; the separator must not survive alone.
    assertEquals("Movie", SearchResults.caption(item(1, year = null)))
    assertEquals("Series", SearchResults.caption(item(1, type = MediaType.Show, year = "  ")))
  }

  @Test
  fun `the count line reads as English`() {
    assertEquals("0 results", SearchResults.countLabel(0))
    assertEquals("1 result", SearchResults.countLabel(1))
    assertEquals("12 results", SearchResults.countLabel(12))
  }

  @Test
  fun `an empty result echoes the query and names the filter`() {
    assertEquals("No results for \"dune\"", SearchResults.emptyTitle(" dune ", SearchFilter.All))
    assertEquals("No movies match \"dune\"", SearchResults.emptyTitle("dune", SearchFilter.Movies))
    assertEquals("No shows match \"dune\"", SearchResults.emptyTitle("dune", SearchFilter.Shows))
  }

  @Test
  fun `the hint tells the viewer which of the two problems they have`() {
    assertEquals(
      "There are matches of another type - try All.",
      SearchResults.emptyHint(SearchFilter.Movies, otherTypesMatch = true),
    )
    assertEquals(
      "Try fewer words, or check the spelling.",
      SearchResults.emptyHint(SearchFilter.Movies, otherTypesMatch = false),
    )
    // Under All there is no other type to try, so the advice is about the query.
    assertEquals(
      "Try fewer words, or check the spelling.",
      SearchResults.emptyHint(SearchFilter.All, otherTypesMatch = true),
    )
  }
}
