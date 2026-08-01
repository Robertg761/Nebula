package com.stremioshell.host.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionGuardTest {
  @Test
  fun `a replacement invalidates the previous session`() {
    val guard = PlaybackSessionGuard()
    val first = guard.begin("https://example.test/first")
    val second = guard.begin("https://example.test/second")

    assertFalse(guard.isCurrent(first))
    assertTrue(guard.isCurrent(second))
    assertNotEquals(first.generation, second.generation)
  }

  @Test
  fun `invalidating a guard rejects delayed callbacks`() {
    val guard = PlaybackSessionGuard()
    val session = guard.begin("https://example.test/stream")

    guard.invalidate()

    assertFalse(guard.isCurrent(session))
    assertFalse(guard.isCurrent(session.generation))
  }

  /**
   * The activity's loadGeneration starts at 0 and mutations queued before the first loadfile carry
   * it - a pause from onStop in that window must run, not be silently dropped.
   */
  @Test
  fun `generation zero is current until the first session begins`() {
    val guard = PlaybackSessionGuard()

    assertTrue(guard.isCurrent(0L))
  }

  @Test
  fun `generation zero is stale once any session has existed`() {
    val guard = PlaybackSessionGuard()
    guard.begin("https://example.test/stream")

    assertFalse(guard.isCurrent(0L))

    guard.invalidate()

    assertFalse(guard.isCurrent(0L))
  }
}
