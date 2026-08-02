package com.stremioshell.host.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StalenessPolicyTest {
  private val hour = 60L * 60 * 1000
  private val policy = StalenessPolicy(maxAgeMillis = 4 * hour)

  @Test
  fun `never loaded is stale`() {
    assertTrue(policy.isStale(loadedAtMillis = null, nowMillis = 10_000))
  }

  @Test
  fun `just loaded is fresh`() {
    assertFalse(policy.isStale(loadedAtMillis = 1_000_000, nowMillis = 1_000_000))
  }

  @Test
  fun `fresh right up to the boundary, stale at it`() {
    val loadedAt = 1_000_000L
    assertFalse(policy.isStale(loadedAt, loadedAt + 4 * hour - 1))
    assertTrue(policy.isStale(loadedAt, loadedAt + 4 * hour))
    assertTrue(policy.isStale(loadedAt, loadedAt + 9 * hour))
  }

  @Test
  fun `a clock that moved backwards counts as stale`() {
    // A TV can boot with a bogus date and have NTP correct it minutes later; treating the future
    // timestamp as fresh would freeze the rails until the real deadline caught up.
    assertTrue(policy.isStale(loadedAtMillis = 5_000_000, nowMillis = 1_000))
  }

  @Test
  fun `default window is four hours`() {
    val default = StalenessPolicy()
    val loadedAt = 1_000_000L
    assertFalse(default.isStale(loadedAt, loadedAt + 4 * hour - 1))
    assertTrue(default.isStale(loadedAt, loadedAt + 4 * hour))
  }

  @Test
  fun `a non-positive window is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { StalenessPolicy(maxAgeMillis = 0) }
  }

  @Test
  fun `only a complete non-stale refresh advances the freshness clock`() {
    assertTrue(
      RefreshCompletionPolicy.loadedAtMillis(
        nowMillis = 10_000,
        hasFailures = false,
        usedStaleFallback = false,
      ) == 10_000L,
    )
    assertTrue(
      RefreshCompletionPolicy.loadedAtMillis(
        nowMillis = 10_000,
        hasFailures = true,
        usedStaleFallback = false,
      ) == null,
    )
    assertTrue(
      RefreshCompletionPolicy.loadedAtMillis(
        nowMillis = 10_000,
        hasFailures = false,
        usedStaleFallback = true,
      ) == null,
    )
  }
}
