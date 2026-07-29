package com.stremioshell.host.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedThresholdTest {
  @Test
  fun `credits count as watched`() {
    // A 45 minute episode left at the start of its end credits.
    assertTrue(WatchedThreshold.isFinished(positionSec = 2_460.0, durationSec = 2_700.0))
  }

  @Test
  fun `stopping before the last tenth stays resumable`() {
    assertFalse(WatchedThreshold.isFinished(positionSec = 2_300.0, durationSec = 2_700.0))
  }

  @Test
  fun `a duration understated by a couple of seconds still finishes`() {
    // The fraction alone can never be reached when time-pos overruns the estimate.
    assertTrue(WatchedThreshold.isFinished(positionSec = 3_601.0, durationSec = 3_600.0))
  }

  @Test
  fun `a stream with no duration is never finished`() {
    assertFalse(WatchedThreshold.isFinished(positionSec = 5_000.0, durationSec = 0.0))
    assertFalse(WatchedThreshold.isFinished(positionSec = 5_000.0, durationSec = -1.0))
  }

  @Test
  fun `invalid playback values are never finished`() {
    assertFalse(WatchedThreshold.isFinished(positionSec = -1.0, durationSec = 100.0))
    assertFalse(WatchedThreshold.isFinished(positionSec = Double.NaN, durationSec = 100.0))
    assertFalse(WatchedThreshold.isFinished(positionSec = 100.0, durationSec = Double.NaN))
    assertFalse(
      WatchedThreshold.isFinished(
        positionSec = Double.POSITIVE_INFINITY,
        durationSec = 100.0,
      ),
    )
  }

  @Test
  fun `a truncated stream that ran out early stays resumable`() {
    assertFalse(WatchedThreshold.isFinished(positionSec = 3_000.0, durationSec = 6_000.0))
  }

  @Test
  fun `the millisecond form agrees with the seconds form`() {
    assertTrue(WatchedThreshold.isFinishedMs(positionMs = 2_460_000, durationMs = 2_700_000))
    assertFalse(WatchedThreshold.isFinishedMs(positionMs = 2_300_000, durationMs = 2_700_000))
  }

  @Test
  fun `a short video is carried by the absolute guard, not the fraction`() {
    // 90% of two minutes is twelve seconds short of the end; five is not.
    assertTrue(WatchedThreshold.isFinished(positionSec = 116.0, durationSec = 120.0))
  }

  @Test
  fun `tiny clips are not finished before playback starts`() {
    assertFalse(WatchedThreshold.isFinished(positionSec = 0.0, durationSec = 1.0))
    assertFalse(
      WatchedThreshold.isFinished(
        positionSec = 0.0,
        durationSec = WatchedThreshold.END_GUARD_SEC,
      ),
    )
  }

  @Test
  fun `tiny clips still finish by watched fraction`() {
    assertFalse(WatchedThreshold.isFinished(positionSec = 3.59, durationSec = 4.0))
    assertTrue(WatchedThreshold.isFinished(positionSec = 3.6, durationSec = 4.0))
    assertFalse(WatchedThreshold.isFinished(positionSec = 1.0, durationSec = 6.0))
    assertTrue(WatchedThreshold.isFinished(positionSec = 5.4, durationSec = 6.0))
  }
}
