package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaPage
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.RailPageState
import com.stremioshell.host.tv.data.tmdb.RailPaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RailPagingTest {
  private fun item(id: Int, type: MediaType = MediaType.Movie) = MediaItem(
    tmdbId = id,
    type = type,
    title = "item $id",
    posterUrl = null,
    backdropUrl = null,
    overview = "",
    year = null,
    rating = null,
  )

  private fun items(range: IntRange) = range.map { item(it) }

  private fun page(items: List<MediaItem>, page: Int = 2, totalPages: Int = 50) =
    MediaPage(items, page, totalPages)

  // --- when to fetch -------------------------------------------------------

  @Test
  fun `fetches once the last visible card is within the prefetch window`() {
    val state = RailPageState()
    assertFalse(RailPaging.shouldFetchNext(state, itemCount = 20, lastVisibleIndex = 13))
    assertTrue(RailPaging.shouldFetchNext(state, itemCount = 20, lastVisibleIndex = 14))
    assertTrue(RailPaging.shouldFetchNext(state, itemCount = 20, lastVisibleIndex = 19))
  }

  @Test
  fun `never fetches twice at once`() {
    val loading = RailPageState(loading = true)
    assertFalse(RailPaging.shouldFetchNext(loading, itemCount = 20, lastVisibleIndex = 19))
  }

  @Test
  fun `a rail that has reached the end stays there`() {
    val done = RailPageState(endReached = true)
    assertFalse(RailPaging.shouldFetchNext(done, itemCount = 20, lastVisibleIndex = 19))
  }

  @Test
  fun `stops at the item and page caps`() {
    assertFalse(
      RailPaging.shouldFetchNext(
        RailPageState(nextPage = 3),
        itemCount = RailPaging.MAX_ITEMS,
        lastVisibleIndex = RailPaging.MAX_ITEMS - 1,
      ),
    )
    assertFalse(
      RailPaging.shouldFetchNext(
        RailPageState(nextPage = RailPaging.MAX_PAGES + 1),
        itemCount = 60,
        lastVisibleIndex = 59,
      ),
    )
  }

  @Test
  fun `an empty rail is the rails load's problem, not paging's`() {
    // A row with no items has no scroll position worth trusting, and paging it would fire a
    // second request for the page the rails load is already fetching.
    assertFalse(RailPaging.shouldFetchNext(RailPageState(), itemCount = 0, lastVisibleIndex = 0))
  }

  // --- merging -------------------------------------------------------------

  @Test
  fun `a page appends without disturbing the cards already in the row`() {
    val existing = items(1..20)
    val merged = RailPaging.merge(existing, items(21..40))

    assertEquals(40, merged.size)
    assertEquals(existing, merged.take(20))
  }

  @Test
  fun `repeats across pages are dropped`() {
    // TMDB reorders by popularity between requests, so page 2 routinely re-serves page 1 entries.
    val merged = RailPaging.merge(items(1..20), items(15..30))

    assertEquals((1..30).toList(), merged.map { it.tmdbId })
  }

  @Test
  fun `a movie and a show sharing a tmdb id are different titles`() {
    val merged = RailPaging.merge(listOf(item(7, MediaType.Movie)), listOf(item(7, MediaType.Show)))

    assertEquals(2, merged.size)
  }

  @Test
  fun `an all-duplicate page costs no recomposition`() {
    val existing = items(1..20)
    // Same list instance back, so the row's items parameter is unchanged and Compose skips it.
    assertSame(existing, RailPaging.merge(existing, items(1..20)))
    assertSame(existing, RailPaging.merge(existing, emptyList()))
  }

  @Test
  fun `the cap is enforced on the merged row`() {
    val merged = RailPaging.merge(items(1..90), items(91..130))

    assertEquals(RailPaging.MAX_ITEMS, merged.size)
    assertEquals(RailPaging.MAX_ITEMS, merged.last().tmdbId)
  }

  @Test
  fun `a refresh keeps the depth the user already paged to`() {
    // Fresh first page over the previously loaded row: the new items lead, everything the user had
    // scrolled to survives behind them.
    val previous = items(1..60)
    val refreshed = RailPaging.merge(items(101..120), previous)

    assertEquals(80, refreshed.size)
    assertEquals((101..120).toList(), refreshed.take(20).map { it.tmdbId })
    assertEquals((1..60).toList(), refreshed.drop(20).map { it.tmdbId })
  }

  // --- state transitions ---------------------------------------------------

  @Test
  fun `a first page opens paging at page two`() {
    val state = RailPaging.afterFirstPage(page(items(1..20), page = 1), itemCount = 20)

    assertEquals(2, state.nextPage)
    assertFalse(state.loading)
    assertFalse(state.endReached)
  }

  @Test
  fun `a refresh resumes where the rail had paged to`() {
    val state = RailPaging.afterFirstPage(
      page(items(1..20), page = 1),
      itemCount = 60,
      carried = RailPageState(nextPage = 4),
    )

    assertEquals(4, state.nextPage)
    assertFalse(state.endReached)
  }

  @Test
  fun `a short catalog ends immediately`() {
    val state = RailPaging.afterFirstPage(
      MediaPage(items(1..8), page = 1, totalPages = 1),
      itemCount = 8,
    )

    assertTrue(state.endReached)
  }

  @Test
  fun `each page advances the cursor`() {
    val state = RailPaging.afterNextPage(RailPageState(nextPage = 2), page(items(21..40)), 40)

    assertEquals(3, state.nextPage)
    assertFalse(state.loading)
    assertFalse(state.endReached)
  }

  @Test
  fun `the page cap ends paging`() {
    val state = RailPaging.afterNextPage(
      RailPageState(nextPage = RailPaging.MAX_PAGES),
      page(items(81..100)),
      RailPaging.MAX_ITEMS,
    )

    assertEquals(RailPaging.MAX_PAGES + 1, state.nextPage)
    assertTrue(state.endReached)
  }

  @Test
  fun `an empty page ends paging even when TMDB claims more`() {
    val state = RailPaging.afterNextPage(RailPageState(nextPage = 2), page(emptyList()), 20)

    assertTrue(state.endReached)
  }

  @Test
  fun `running past the last page ends paging`() {
    val state = RailPaging.afterNextPage(
      RailPageState(nextPage = 2),
      MediaPage(items(21..30), page = 2, totalPages = 2),
      30,
    )

    assertTrue(state.endReached)
  }

  @Test
  fun `a failed fetch stops the rail rather than retrying on every scroll`() {
    val state = RailPaging.failed(RailPageState(nextPage = 3, loading = true))

    assertEquals(3, state.nextPage)
    assertFalse(state.loading)
    assertTrue(state.endReached)
    assertFalse(RailPaging.shouldFetchNext(state, itemCount = 40, lastVisibleIndex = 39))
  }

  @Test
  fun `a failed stale page reopens the same cursor after a full rail refresh`() {
    val failed = RailPaging.failed(RailPageState(nextPage = 3, loading = true))

    val refreshed = RailPaging.afterFirstPage(
      page = page(items(1..20), page = 1),
      itemCount = 40,
      carried = failed,
    )

    assertEquals(3, refreshed.nextPage)
    assertFalse(refreshed.loading)
    assertFalse(refreshed.endReached)
    assertTrue(RailPaging.shouldFetchNext(refreshed, itemCount = 40, lastVisibleIndex = 39))
  }
}
