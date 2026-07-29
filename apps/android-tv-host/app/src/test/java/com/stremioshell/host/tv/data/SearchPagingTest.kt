package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaPage
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.SearchPageState
import com.stremioshell.host.tv.data.tmdb.SearchPaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPagingTest {
  @Test
  fun `first page exposes another page and prefetches near grid end`() {
    val state = SearchPaging.afterFirstPage(page(1, 3, 1..20))

    assertEquals(2, state.nextPage)
    assertFalse(state.endReached)
    assertFalse(SearchPaging.shouldPrefetch(state, itemCount = 20, lastVisibleIndex = 11))
    assertTrue(SearchPaging.shouldPrefetch(state, itemCount = 20, lastVisibleIndex = 12))
  }

  @Test
  fun `append keeps existing indices and removes duplicate titles`() {
    val existing = (1..20).map(::item)
    val merged = SearchPaging.merge(existing, (18..30).map(::item))

    assertEquals((1..30).toList(), merged.map { it.tmdbId })
    assertEquals(existing, merged.take(existing.size))
  }

  @Test
  fun `all duplicate page reuses list and still advances paging`() {
    val existing = (1..20).map(::item)
    val merged = SearchPaging.merge(existing, (1..20).map(::item))
    val state = SearchPaging.afterPage(
      SearchPageState(nextPage = 2, totalPages = 3, loading = true, endReached = false),
      page(2, 3, 1..20),
      merged.size,
    )

    assertSame(existing, merged)
    assertEquals(3, state.nextPage)
    assertFalse(state.endReached)
  }

  @Test
  fun `failure waits for explicit retry`() {
    val failed = SearchPaging.failed(
      SearchPageState(totalPages = 3, endReached = false),
      "offline",
    )

    assertFalse(SearchPaging.canLoad(failed))
    assertTrue(SearchPaging.canLoad(SearchPaging.retry(failed)))
  }

  @Test
  fun `an empty mixed-search page still advances while the server has more pages`() {
    val first = SearchPaging.afterFirstPage(MediaPage(emptyList(), page = 1, totalPages = 3))
    assertTrue(SearchPaging.canLoad(first))

    val second = SearchPaging.afterPage(
      SearchPaging.begin(first),
      MediaPage(emptyList(), page = 2, totalPages = 3),
      mergedCount = 0,
    )
    assertEquals(3, second.nextPage)
    assertFalse(second.endReached)
  }

  private fun page(number: Int, total: Int, ids: IntRange) =
    MediaPage(ids.map(::item), page = number, totalPages = total)

  private fun item(id: Int) = MediaItem(
    tmdbId = id,
    type = MediaType.Movie,
    title = "Title $id",
    posterUrl = null,
    backdropUrl = null,
    overview = "",
    year = null,
    rating = null,
  )
}
