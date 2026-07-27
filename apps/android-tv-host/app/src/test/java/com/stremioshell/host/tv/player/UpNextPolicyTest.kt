package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextPolicyTest {
  @Test
  fun `an untouched ending counts down into the next episode`() {
    val offer = UpNextPolicy.offer(hasNext = true, paused = false, msSinceInteractionMs = 600_000)

    assertEquals(UpNextPolicy.Offer.Countdown(UpNextPolicy.COUNTDOWN_MS), offer)
  }

  @Test
  fun `nothing to play next means nothing is offered`() {
    val offer = UpNextPolicy.offer(hasNext = false, paused = false, msSinceInteractionMs = 600_000)

    assertEquals(UpNextPolicy.Offer.None, offer)
  }

  @Test
  fun `a viewer paused at the credits is asked, not overruled`() {
    val offer = UpNextPolicy.offer(hasNext = true, paused = true, msSinceInteractionMs = 600_000)

    assertEquals(UpNextPolicy.Offer.Prompt, offer)
  }

  @Test
  fun `a recent press means the remote is in hand`() {
    val offer = UpNextPolicy.offer(hasNext = true, paused = false, msSinceInteractionMs = 2_000)

    assertEquals(UpNextPolicy.Offer.Prompt, offer)
  }

  @Test
  fun `the countdown opens on the full number of seconds`() {
    assertEquals(15, UpNextPolicy.secondsLeft(elapsedMs = 0, totalMs = 15_000))
    assertEquals(15, UpNextPolicy.secondsLeft(elapsedMs = 200, totalMs = 15_000))
    assertEquals(14, UpNextPolicy.secondsLeft(elapsedMs = 1_000, totalMs = 15_000))
  }

  @Test
  fun `the countdown reaches zero only when it is up`() {
    assertEquals(1, UpNextPolicy.secondsLeft(elapsedMs = 14_999, totalMs = 15_000))
    assertEquals(0, UpNextPolicy.secondsLeft(elapsedMs = 15_000, totalMs = 15_000))
    assertEquals(0, UpNextPolicy.secondsLeft(elapsedMs = 20_000, totalMs = 15_000))
  }

  @Test
  fun `the next episode is due once the countdown is spent`() {
    assertFalse(UpNextPolicy.isDue(elapsedMs = 14_999, totalMs = 15_000))
    assertTrue(UpNextPolicy.isDue(elapsedMs = 15_000, totalMs = 15_000))
  }
}
