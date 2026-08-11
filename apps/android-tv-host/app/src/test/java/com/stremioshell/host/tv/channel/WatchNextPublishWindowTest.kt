package com.stremioshell.host.tv.channel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextPublishWindowTest {
  private val window = WatchNextPublishWindow()

  @Test
  fun `the first publish of a process takes the window`() {
    assertNotNull(window.claim(force = false, nowMs = 1_000L))
  }

  @Test
  fun `a progress tick inside a held window is dropped`() {
    window.claim(force = false, nowMs = 1_000L)

    assertNull(window.claim(force = false, nowMs = 1_000L + 30_000L))
  }

  @Test
  fun `a publish that came to nothing hands the window straight back`() {
    // The whole point: a publish the provider never accepted must not buy silence for the next
    // minute of updates, because the next update may be the one that ends the session.
    val claim = window.claim(force = false, nowMs = 1_000L)!!
    window.release(claim)

    assertNotNull(window.claim(force = false, nowMs = 1_000L + 1L))
  }

  @Test
  fun `releasing a superseded claim does not re-open the newer window`() {
    val stale = window.claim(force = false, nowMs = 1_000L)!!
    val fresh = window.claim(force = true, nowMs = 2_000L)!!

    window.release(stale)

    assertNull(window.claim(force = false, nowMs = 2_000L + 30_000L))
    assertEquals(1_000L, fresh.previousMs)
  }

  @Test
  fun `a forced publish always takes the window and can be handed back`() {
    window.claim(force = false, nowMs = 1_000L)

    val forced = window.claim(force = true, nowMs = 1_100L)

    assertNotNull(forced)
    assertEquals(1_000L, forced!!.previousMs)
    window.release(forced)
    // Back to the earlier claim's window, which is still shut.
    assertNull(window.claim(force = false, nowMs = 1_200L))
  }

  @Test
  fun `only one of two simultaneous progress ticks gets through`() {
    // Read-then-write let both callers see the same stale stamp and both publish. The claim is one
    // compare-and-set, so exactly one of them can win it.
    val threads = 8
    val start = CountDownLatch(1)
    val claims = AtomicInteger()
    val workers = (1..threads).map {
      Thread {
        start.await(5, TimeUnit.SECONDS)
        if (window.claim(force = false, nowMs = 100_000L) != null) claims.incrementAndGet()
      }.apply { start() }
    }

    start.countDown()
    workers.forEach { it.join(5_000L) }

    assertEquals(1, claims.get())
  }
}
