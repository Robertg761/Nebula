package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistRetentionTest {
  @Test
  fun `a saved title goes to the front of the row`() {
    val list = WatchlistRetention.add(listOf(entry(1, addedAtMs = 10)), entry(2, addedAtMs = 20))

    assertEquals(listOf(2, 1), list.map { it.tmdbId })
  }

  @Test
  fun `saving a title already in the list replaces it rather than duplicating it`() {
    val stored = listOf(entry(2, addedAtMs = 20), entry(1, addedAtMs = 10))

    val list = WatchlistRetention.add(stored, entry(1, title = "renamed", addedAtMs = 30))

    assertEquals(2, list.size)
    assertEquals(listOf(1, 2), list.map { it.tmdbId })
    assertEquals("renamed", list.first().title)
  }

  @Test
  fun `a re-save in the same millisecond still lands in front`() {
    val stored = listOf(entry(2, addedAtMs = 20))

    val list = WatchlistRetention.add(stored, entry(1, addedAtMs = 20))

    assertEquals(listOf(1, 2), list.map { it.tmdbId })
  }

  @Test
  fun `the list is capped and it is the oldest saves that go`() {
    val stored = (1..WatchlistRetention.MAX_ENTRIES).map { entry(it, addedAtMs = it.toLong()) }

    val list = WatchlistRetention.add(stored, entry(9_000, addedAtMs = 100_000))

    assertEquals(WatchlistRetention.MAX_ENTRIES, list.size)
    assertEquals(9_000, list.first().tmdbId)
    assertFalse(list.any { it.tmdbId == 1 })
  }

  @Test
  fun `removing takes only the title asked for`() {
    val stored = listOf(entry(2, addedAtMs = 20), entry(1, addedAtMs = 10))

    val list = WatchlistRetention.remove(stored, entry(2).key)

    assertEquals(listOf(1), list.map { it.tmdbId })
  }

  @Test
  fun `removing a title that was never saved changes nothing`() {
    val stored = listOf(entry(1, addedAtMs = 10))

    assertEquals(stored, WatchlistRetention.remove(stored, entry(7).key))
  }

  @Test
  fun `the toggle saves an absent title and unsaves a stored one`() {
    val added = WatchlistRetention.toggled(emptyList(), entry(1, addedAtMs = 10))
    assertEquals(listOf(1), added.map { it.tmdbId })

    val removed = WatchlistRetention.toggled(added, entry(1, addedAtMs = 99))
    assertTrue(removed.isEmpty())
  }

  @Test
  fun `a movie and a show sharing a tmdb id are different titles`() {
    val movie = entry(42, addedAtMs = 10)
    val show = entry(42, type = MediaType.Show, addedAtMs = 20)

    assertNotEquals(movie.key, show.key)

    val list = WatchlistRetention.add(listOf(movie), show)

    assertEquals(2, list.size)
    assertTrue(WatchlistRetention.contains(list, movie.key))
    assertTrue(WatchlistRetention.contains(list, show.key))
  }

  @Test
  fun `a stored list with duplicates keeps the newest copy of each title`() {
    val stored = listOf(
      entry(1, title = "old", addedAtMs = 10),
      entry(1, title = "new", addedAtMs = 30),
      entry(2, addedAtMs = 20),
    )

    val list = WatchlistRetention.ordered(stored)

    assertEquals(listOf("new", "film 2"), list.map { it.title })
  }

  @Test
  fun `a stored list longer than the cap is trimmed on read`() {
    val stored = (1..WatchlistRetention.MAX_ENTRIES + 25).map { entry(it, addedAtMs = it.toLong()) }

    assertEquals(WatchlistRetention.MAX_ENTRIES, WatchlistRetention.ordered(stored).size)
  }

  @Test
  fun `the key a screen builds from a type and an id matches the stored entry`() {
    assertEquals(entry(5).key, WatchlistEntry.keyOf(MediaType.Movie, 5))
    assertEquals(entry(5, type = MediaType.Show).key, WatchlistEntry.keyOf(MediaType.Show, 5))
  }

  @Test
  fun `saving keeps the whole card, so the row draws with no network`() {
    val item = MediaItem(
      tmdbId = 7,
      type = MediaType.Show,
      title = "Severance",
      posterUrl = "https://image.tmdb.org/poster.jpg",
      backdropUrl = "https://image.tmdb.org/backdrop.jpg",
      overview = "Work-life balance, enforced.",
      year = "2022",
      rating = 8.4,
    )

    val restored = WatchlistEntry.of(item, addedAtMs = 1_700_000_000_000).toMediaItem()

    assertEquals(item, restored)
  }

  private fun entry(
    tmdbId: Int,
    type: MediaType = MediaType.Movie,
    title: String = "film $tmdbId",
    addedAtMs: Long = 0,
  ) = WatchlistEntry.of(
    MediaItem(
      tmdbId = tmdbId,
      type = type,
      title = title,
      posterUrl = null,
      backdropUrl = null,
      overview = "",
      year = null,
      rating = null,
    ),
    addedAtMs = addedAtMs,
  )
}
