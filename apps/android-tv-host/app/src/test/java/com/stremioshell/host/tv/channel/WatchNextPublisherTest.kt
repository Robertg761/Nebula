package com.stremioshell.host.tv.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextPublisherTest {
  @Test
  fun `a null query is a failure and never plans inserts from an assumed empty table`() {
    val provider = FakeProvider(rows = null)

    val result = WatchNextPublishExecutor(provider).publish(listOf(program("movie:1")))

    assertEquals(WatchNextPublishResult.Failed, result)
    assertTrue(provider.inserted.isEmpty())
  }

  @Test
  fun `a null insert is reported as failure`() {
    val provider = FakeProvider(rows = emptyList(), insertSucceeds = false)

    assertEquals(
      WatchNextPublishResult.Failed,
      WatchNextPublishExecutor(provider).publish(listOf(program("movie:1"))),
    )
  }

  @Test
  fun `a thrown update is not followed by a duplicate-risk insert`() {
    val provider = FakeProvider(
      rows = listOf(ExistingWatchNextRow(7, "movie:1")),
      updateError = IllegalStateException("provider down"),
    )

    val result = WatchNextPublishExecutor(provider).publish(listOf(program("movie:1")))

    assertEquals(WatchNextPublishResult.Failed, result)
    assertTrue(provider.inserted.isEmpty())
  }

  @Test
  fun `a clean zero-row update reinserts and reports whether that insert landed`() {
    val existing = listOf(ExistingWatchNextRow(7, "movie:1"))

    assertEquals(
      WatchNextPublishResult.Published,
      WatchNextPublishExecutor(FakeProvider(existing, updateCount = 0)).publish(
        listOf(program("movie:1")),
      ),
    )
    assertEquals(
      WatchNextPublishResult.Failed,
      WatchNextPublishExecutor(
        FakeProvider(existing, updateCount = 0, insertSucceeds = false),
      ).publish(listOf(program("movie:1"))),
    )
  }

  @Test
  fun `any failed mutation makes the whole reconciliation retryable`() {
    val provider = FakeProvider(
      rows = listOf(ExistingWatchNextRow(1, "stale")),
      deleteError = IllegalStateException("delete failed"),
    )

    assertEquals(
      WatchNextPublishResult.Failed,
      WatchNextPublishExecutor(provider).publish(emptyList()),
    )
  }

  @Test
  fun `a missing provider is unavailable rather than a retryable mutation failure`() {
    assertEquals(
      WatchNextPublishResult.Unavailable,
      WatchNextPublishExecutor(FakeProvider(available = false)).publish(emptyList()),
    )
  }

  private class FakeProvider(
    private val rows: List<ExistingWatchNextRow>? = emptyList(),
    private val available: Boolean = true,
    private val updateCount: Int = 1,
    private val insertSucceeds: Boolean = true,
    private val updateError: Throwable? = null,
    private val deleteError: Throwable? = null,
  ) : WatchNextProvider {
    val inserted = mutableListOf<WatchNextProgramData>()

    override fun available(): Boolean = available
    override fun queryOwnRows(): List<ExistingWatchNextRow>? = rows
    override fun delete(id: Long) {
      deleteError?.let { throw it }
    }
    override fun update(id: Long, program: WatchNextProgramData): Int {
      updateError?.let { throw it }
      return updateCount
    }
    override fun insert(program: WatchNextProgramData): Boolean {
      inserted += program
      return insertSucceeds
    }
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

class WatchNextRetrySlotTest {
  @Test
  fun `overlapping failures schedule one retry and a released slot can be reused`() {
    val slot = WatchNextRetrySlot()

    assertTrue(slot.claim())
    assertFalse(slot.claim())
    slot.release()
    assertTrue(slot.claim())
  }
}

class WatchNextDismissalPolicyTest {
  @Test
  fun `only the platform dismissal action with a positive row id is accepted`() {
    val action = "android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED"

    assertEquals(42L, WatchNextDismissalPolicy.programId(action, 42L))
    assertNull(WatchNextDismissalPolicy.programId("other", 42L))
    assertNull(WatchNextDismissalPolicy.programId(action, 0L))
    assertNull(WatchNextDismissalPolicy.programId(action, -1L))
  }

  @Test
  fun `provider identity is trimmed and bounded before persistence`() {
    assertEquals("movie:1", WatchNextDismissalPolicy.providerId("  movie:1  "))
    assertNull(WatchNextDismissalPolicy.providerId("   "))
    assertNull(WatchNextDismissalPolicy.providerId("x".repeat(257)))
  }
}
