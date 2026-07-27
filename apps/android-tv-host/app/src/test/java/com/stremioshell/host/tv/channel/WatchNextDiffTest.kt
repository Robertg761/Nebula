package com.stremioshell.host.tv.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextDiffTest {
  @Test
  fun `a first publish inserts everything`() {
    val desired = listOf(program("movie:1"), program("movie:2"))

    val plan = WatchNextDiff.plan(emptyList(), desired)

    assertEquals(desired, plan.inserts)
    assertTrue(plan.updates.isEmpty())
    assertTrue(plan.deletes.isEmpty())
  }

  @Test
  fun `republishing the same set only updates in place`() {
    val existing = listOf(
      ExistingWatchNextRow(id = 10L, internalProviderId = "movie:1"),
      ExistingWatchNextRow(id = 11L, internalProviderId = "movie:2"),
    )
    val desired = listOf(program("movie:1"), program("movie:2"))

    val plan = WatchNextDiff.plan(existing, desired)

    assertTrue(plan.inserts.isEmpty())
    assertEquals(listOf(10L to desired[0], 11L to desired[1]), plan.updates)
    assertTrue(plan.deletes.isEmpty())
  }

  @Test
  fun `an entry that left continue watching has its row deleted`() {
    val existing = listOf(
      ExistingWatchNextRow(id = 10L, internalProviderId = "movie:1"),
      ExistingWatchNextRow(id = 11L, internalProviderId = "movie:2"),
    )

    val plan = WatchNextDiff.plan(existing, listOf(program("movie:2")))

    assertEquals(listOf(10L), plan.deletes)
    assertEquals(listOf(11L to program("movie:2")), plan.updates)
    assertTrue(plan.inserts.isEmpty())
  }

  @Test
  fun `an empty desired set clears every owned row`() {
    val existing = listOf(
      ExistingWatchNextRow(id = 10L, internalProviderId = "movie:1"),
      ExistingWatchNextRow(id = 11L, internalProviderId = "movie:2"),
    )

    val plan = WatchNextDiff.plan(existing, emptyList())

    assertEquals(setOf(10L, 11L), plan.deletes.toSet())
    assertTrue(plan.inserts.isEmpty())
    assertTrue(plan.updates.isEmpty())
  }

  @Test
  fun `rows with no provider id can never be matched again so they are cleaned up`() {
    val existing = listOf(ExistingWatchNextRow(id = 10L, internalProviderId = null))

    val plan = WatchNextDiff.plan(existing, listOf(program("movie:1")))

    assertEquals(listOf(10L), plan.deletes)
    assertEquals(listOf(program("movie:1")), plan.inserts)
  }

  @Test
  fun `a duplicate row from an interrupted publish is collapsed onto one`() {
    val existing = listOf(
      ExistingWatchNextRow(id = 10L, internalProviderId = "movie:1"),
      ExistingWatchNextRow(id = 11L, internalProviderId = "movie:1"),
    )

    val plan = WatchNextDiff.plan(existing, listOf(program("movie:1")))

    assertEquals(listOf(11L), plan.deletes)
    assertEquals(listOf(10L to program("movie:1")), plan.updates)
    assertTrue(plan.inserts.isEmpty())
  }

  @Test
  fun `planning twice over its own result is a no-op`() {
    val desired = listOf(program("movie:1"), program("movie:2"))
    val first = WatchNextDiff.plan(emptyList(), desired)
    // What the provider would hold after applying [first].
    val settled = first.inserts.mapIndexed { index, program ->
      ExistingWatchNextRow(id = index.toLong(), internalProviderId = program.internalProviderId)
    }

    val second = WatchNextDiff.plan(settled, desired)

    assertTrue(second.inserts.isEmpty())
    assertTrue(second.deletes.isEmpty())
    assertEquals(desired.size, second.updates.size)
  }

  private fun program(providerId: String) = WatchNextProgramData(
    internalProviderId = providerId,
    title = "Title",
    type = WatchNextProgramType.Movie,
    kind = WatchNextKind.Continue,
    posterArtUri = null,
    lastEngagementTimeUtcMillis = 1L,
    lastPlaybackPositionMillis = 10,
    durationMillis = 100,
    seasonNumber = null,
    episodeNumber = null,
    deepLinkUri = "stremio-tv://watch-next?type=movie&tmdb=1",
  )
}
