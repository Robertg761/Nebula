package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTimelineTest {
  @Test
  fun `remaining is what is left of the duration`() {
    val remaining = PlaybackTimeline.remainingSec(positionSec = 600.0, durationSec = 5_400.0)

    assertEquals(4_800.0, remaining!!, 0.001)
  }

  @Test
  fun `an unknown duration has no remaining time`() {
    assertNull(PlaybackTimeline.remainingSec(positionSec = 600.0, durationSec = 0.0))
    assertNull(PlaybackTimeline.remainingSec(positionSec = 600.0, durationSec = -1.0))
  }

  @Test
  fun `a position past an understated duration counts down to zero, not below`() {
    val remaining = PlaybackTimeline.remainingSec(positionSec = 5_402.0, durationSec = 5_400.0)

    assertEquals(0.0, remaining!!, 0.001)
  }

  @Test
  fun `speed shortens the wall-clock time left`() {
    val remaining = PlaybackTimeline.remainingSec(
      positionSec = 0.0,
      durationSec = 3_600.0,
      speed = 2.0,
    )

    assertEquals(1_800.0, remaining!!, 0.001)
  }

  @Test
  fun `a nonsensical speed is treated as normal playback`() {
    val remaining = PlaybackTimeline.remainingSec(
      positionSec = 0.0,
      durationSec = 3_600.0,
      speed = 0.0,
    )

    assertEquals(3_600.0, remaining!!, 0.001)
  }

  @Test
  fun `the end time is now plus what is left`() {
    val endsAt = PlaybackTimeline.endsAtEpochMs(nowEpochMs = 1_000_000L, remainingSec = 90.5)

    assertEquals(1_090_500L, endsAt)
  }

  @Test
  fun `the end time never runs backwards`() {
    val endsAt = PlaybackTimeline.endsAtEpochMs(nowEpochMs = 1_000_000L, remainingSec = -30.0)

    assertEquals(1_000_000L, endsAt)
  }
}
