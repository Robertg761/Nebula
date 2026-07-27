package com.stremioshell.host.tv.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextThrottleTest {
  @Test
  fun `the first publish of a process is always allowed`() {
    assertTrue(WatchNextThrottle.shouldPublish(lastPublishAtMs = 0L, nowMs = 1_000L))
  }

  @Test
  fun `a progress tick inside the window is dropped`() {
    val last = 1_000_000L
    assertFalse(WatchNextThrottle.shouldPublish(last, last + 30_000L))
  }

  @Test
  fun `the window reopens exactly on the interval`() {
    val last = 1_000_000L
    assertTrue(WatchNextThrottle.shouldPublish(last, last + WatchNextThrottle.MIN_INTERVAL_MS))
  }

  @Test
  fun `a clock that jumped backwards does not lock publishing out`() {
    assertTrue(WatchNextThrottle.shouldPublish(lastPublishAtMs = 9_000_000_000L, nowMs = 1_000L))
  }
}
