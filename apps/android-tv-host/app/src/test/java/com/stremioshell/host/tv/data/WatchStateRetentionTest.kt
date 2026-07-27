package com.stremioshell.host.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStateRetentionTest {
  @Test
  fun `the newest records are the ones kept`() {
    val entries = (1..WatchStateRetention.MAX_RESUMABLE + 10).map { resumable(it) }

    val pruned = WatchStateRetention.prune(entries)

    assertEquals(WatchStateRetention.MAX_RESUMABLE, pruned.size)
    assertEquals("resume:60", pruned.first().key)
    assertEquals("resume:11", pruned.last().key)
  }

  @Test
  fun `a binge of watched episodes cannot evict continue watching`() {
    val resume = listOf(resumable(1))
    val binge = (1..WatchStateRetention.MAX_RESUMABLE + 20).map { watched(it) }

    val pruned = WatchStateRetention.prune(resume + binge)

    assertTrue(pruned.any { it.key == "resume:1" })
    assertEquals(binge.size, pruned.count { it.watched })
  }

  @Test
  fun `watched history is capped too`() {
    val entries = (1..WatchStateRetention.MAX_WATCHED + 5).map { watched(it) }

    val pruned = WatchStateRetention.prune(entries)

    assertEquals(WatchStateRetention.MAX_WATCHED, pruned.size)
    assertEquals("watched:${WatchStateRetention.MAX_WATCHED + 5}", pruned.first().key)
  }

  @Test
  fun `the result is newest first whatever order it arrived in`() {
    val pruned = WatchStateRetention.prune(listOf(resumable(1), watched(5), resumable(3)))

    assertEquals(listOf(5L, 3L, 1L), pruned.map { it.updatedAtMs })
  }

  private fun resumable(index: Int) = WatchEntry(
    key = "resume:$index",
    tmdbId = index,
    mediaType = "movie",
    title = "film $index",
    positionMs = 60_000,
    durationMs = 6_000_000,
    updatedAtMs = index.toLong(),
  )

  private fun watched(index: Int) = WatchEntry(
    key = "watched:$index",
    tmdbId = index,
    mediaType = "show",
    title = "show $index",
    season = 1,
    episode = index,
    durationMs = 2_700_000,
    updatedAtMs = index.toLong(),
    watchedAtMs = index.toLong(),
  )
}
