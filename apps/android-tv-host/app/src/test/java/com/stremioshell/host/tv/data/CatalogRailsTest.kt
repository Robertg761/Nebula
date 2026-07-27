package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.CatalogQuery
import com.stremioshell.host.tv.data.tmdb.CatalogRails
import com.stremioshell.host.tv.data.tmdb.HeroPick
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRailsTest {
  @Test
  fun `titles are unique because they are the rail identity`() {
    // Rails are matched by title across reloads and paged by title; a duplicate would make two
    // rows fight over one paging cursor.
    val titles = CatalogRails.ORDER
    assertEquals(titles.size, titles.toSet().size)
  }

  @Test
  fun `the headline rails load first and keep their historic order`() {
    // These four are what Home used to be, and the audit's first-focus behaviour is pinned to
    // Trending Movies being the top row.
    assertEquals(
      listOf("Trending Movies", "Trending Shows", "Popular Movies", "Popular Shows"),
      CatalogRails.PRIMARY.map { it.title },
    )
    assertEquals(CatalogRails.PRIMARY, CatalogRails.ALL.take(CatalogRails.PRIMARY.size))
  }

  @Test
  fun `every wave is dispatched and none is dispatched twice`() {
    assertEquals(CatalogRails.ALL, CatalogRails.WAVES.flatten())
  }

  @Test
  fun `the genre rails are discover queries and mix movies with shows`() {
    val genres = CatalogRails.SECONDARY.map { it.query }
    assertTrue(genres.all { it is CatalogQuery.Genre })
    assertTrue(genres.any { it.type == MediaType.Movie })
    assertTrue(genres.any { it.type == MediaType.Show })
    // Distinct ids per media type: two rails on the same discover query would be the same row twice.
    val keys = genres.filterIsInstance<CatalogQuery.Genre>().map { it.type to it.genreId }
    assertEquals(keys.size, keys.toSet().size)
  }

  @Test
  fun `home has real depth rather than a handful of rows`() {
    assertTrue("expected at least 8 rails, got ${CatalogRails.ALL.size}", CatalogRails.ALL.size >= 8)
  }

  @Test
  fun `specs are looked up by title and unknown titles are refused`() {
    assertEquals(CatalogRails.PRIMARY.first(), CatalogRails.specFor("Trending Movies"))
    assertNull(CatalogRails.specFor("Retired Rail"))
  }
}

class HeroPickTest {
  private fun item(id: Int, backdrop: String? = "/b$id.jpg") = MediaItem(
    tmdbId = id,
    type = MediaType.Movie,
    title = "item $id",
    posterUrl = "/p$id.jpg",
    backdropUrl = backdrop,
    overview = "",
    year = null,
    rating = null,
  )

  @Test
  fun `leads with the first entry of the first rail`() {
    val pick = HeroPick.from(listOf(listOf(item(1), item(2)), listOf(item(3))))

    assertEquals(1, pick?.tmdbId)
  }

  @Test
  fun `skips entries with no backdrop to render`() {
    val pick = HeroPick.from(listOf(listOf(item(1, backdrop = null), item(2, backdrop = "  "), item(3))))

    assertEquals(3, pick?.tmdbId)
  }

  @Test
  fun `moves to the next rail when the first has no artwork at all`() {
    val pick = HeroPick.from(
      listOf(listOf(item(1, backdrop = null)), listOf(item(2))),
    )

    assertEquals(2, pick?.tmdbId)
  }

  @Test
  fun `falls back to the very first entry rather than dropping the billboard`() {
    val first = item(1, backdrop = null)
    val pick = HeroPick.from(listOf(listOf(first), listOf(item(2, backdrop = null))))

    assertSame(first, pick)
  }

  @Test
  fun `no rails and empty rails mean no billboard`() {
    assertNull(HeroPick.from(emptyList()))
    assertNull(HeroPick.from(listOf(emptyList(), emptyList())))
  }
}
