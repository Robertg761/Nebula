package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.SearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationTest {
  private fun item(id: Int, title: String = "item $id", type: MediaType = MediaType.Movie) = MediaItem(
    tmdbId = id,
    type = type,
    title = title,
    posterUrl = "/p$id.jpg",
    backdropUrl = null,
    overview = "",
    year = "2020",
    rating = null,
  )

  private fun resolve(
    typed: String,
    requested: String,
    state: LoadState<List<MediaItem>>,
    filter: SearchFilter = SearchFilter.All,
  ) = SearchPresentation.resolve(typed, requested, state, filter)

  @Test
  fun `an empty field asks for a query`() {
    assertEquals(SearchUi.Idle, resolve("", "", LoadState.Ready(emptyList())))
    assertEquals(SearchUi.Idle, resolve("   ", "", LoadState.Ready(emptyList())))
    // Even mid-flight: whatever is loading is for a query that is no longer on screen.
    assertEquals(SearchUi.Idle, resolve("", "dune", LoadState.Loading))
  }

  @Test
  fun `a request in flight is reported as searching`() {
    assertEquals(SearchUi.Searching, resolve("dune", "dune", LoadState.Loading))
  }

  // --- the debounce window -------------------------------------------------

  @Test
  fun `results for an earlier query stay up while a newer one is pending`() {
    // Typing "dune " while "dune"'s results are on screen: blanking the grid on every keystroke
    // would be unreadable, and these results are for a prefix of what is being typed.
    val ui = resolve("dune p", "dune", LoadState.Ready(listOf(item(1))))

    assertTrue(ui is SearchUi.Results)
    assertEquals(listOf(1), (ui as SearchUi.Results).items.map { it.tmdbId })
    assertTrue(ui.refreshing)
  }

  @Test
  fun `a pending query never inherits the previous one's emptiness`() {
    // The bug this exists to stop: "No results for 'd'" flashing up as "dune" is typed.
    assertEquals(SearchUi.Searching, resolve("dune", "d", LoadState.Ready(emptyList())))
  }

  @Test
  fun `a pending query never inherits the previous one's failure`() {
    assertEquals(SearchUi.Searching, resolve("dune", "d", LoadState.Failed("Network unreachable")))
  }

  @Test
  fun `a settled query is not marked as refreshing`() {
    val ui = resolve("dune", "dune", LoadState.Ready(listOf(item(1))))

    assertFalse((ui as SearchUi.Results).refreshing)
  }

  @Test
  fun `only leading and trailing spaces are forgiven when matching field to request`() {
    val ui = resolve(" dune ", "dune", LoadState.Ready(listOf(item(1))))

    assertFalse((ui as SearchUi.Results).refreshing)
  }

  // --- settled states ------------------------------------------------------

  @Test
  fun `a settled empty answer echoes the query and hints at fewer words`() {
    val ui = resolve("a very long title nobody has", "a very long title nobody has", LoadState.Ready(emptyList()))

    assertEquals(
      SearchUi.Empty(
        title = "No results for \"a very long title nobody has\"",
        hint = "Try fewer words, or check the spelling.",
      ),
      ui,
    )
  }

  @Test
  fun `an empty tab says so is the filter's doing when other types matched`() {
    val ui = resolve("fargo", "fargo", LoadState.Ready(listOf(item(1, type = MediaType.Show))), SearchFilter.Movies)

    assertEquals(
      SearchUi.Empty(
        title = "No movies match \"fargo\"",
        hint = "There are matches of another type - try All.",
      ),
      ui,
    )
  }

  @Test
  fun `a settled failure is shown so the screen can offer a retry`() {
    assertEquals(
      SearchUi.Failed("Network unreachable"),
      resolve("dune", "dune", LoadState.Failed("Network unreachable")),
    )
  }

  @Test
  fun `the filter is applied to results already in hand`() {
    val items = listOf(item(1, type = MediaType.Movie), item(2, type = MediaType.Show))

    val movies = resolve("f", "f", LoadState.Ready(items), SearchFilter.Movies)
    val shows = resolve("f", "f", LoadState.Ready(items), SearchFilter.Shows)

    assertEquals(listOf(1), (movies as SearchUi.Results).items.map { it.tmdbId })
    assertEquals(listOf(2), (shows as SearchUi.Results).items.map { it.tmdbId })
  }

  @Test
  fun `ordering is decided by the query the results came back for`() {
    // The field may already be one keystroke ahead; ranking against it would promote nothing.
    val items = listOf(item(1, "Dune Part Two"), item(2, "Dune"))

    val ui = resolve("dune p", "dune", LoadState.Ready(items))

    assertEquals(listOf(2, 1), (ui as SearchUi.Results).items.map { it.tmdbId })
  }
}
