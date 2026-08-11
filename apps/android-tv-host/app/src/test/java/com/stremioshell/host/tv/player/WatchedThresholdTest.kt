package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
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
    // A position that overruns a VBR estimate is past 100% of it, which is past
    // the fraction by a wide margin - this is the case the departed absolute
    // guard was written for, and the fraction had always been covering it.
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
  fun `the watched fraction is the whole rule, at its exact boundary`() {
    // Nothing else decides this any more: the absolute guard that used to sit
    // beside the fraction could not fire at any duration, and is gone.
    assertFalse(WatchedThreshold.isFinished(positionSec = 107.999, durationSec = 120.0))
    assertTrue(WatchedThreshold.isFinished(positionSec = 108.0, durationSec = 120.0))
    assertTrue(WatchedThreshold.isFinished(positionSec = 116.0, durationSec = 120.0))
  }

  @Test
  fun `five seconds from the end is not special, short or long`() {
    // Where the old guard claimed to act. Under fifty seconds of runtime the last
    // five are still inside the first 90% and stay resumable; at fifty they land
    // exactly on the fraction; above it they are well past it.
    assertFalse(WatchedThreshold.isFinished(positionSec = 35.0, durationSec = 40.0))
    assertTrue(WatchedThreshold.isFinished(positionSec = 45.0, durationSec = 50.0))
    assertTrue(WatchedThreshold.isFinished(positionSec = 3_595.0, durationSec = 3_600.0))
  }

  @Test
  fun `a forward seek clamped to the end guard still counts as finished`() {
    val seeker = SeekCoalescer()
    // Holding FFWD into the end of a twenty-minute episode. The clamp keeps mpv
    // out of eof; it does not, and is not able to, keep the episode unwatched -
    // the SeekCoalescer KDoc used to claim otherwise. Backing out here marks it
    // watched, which is the honest reading of seeking to five seconds from the
    // end of something.
    val target = seeker.press(
      600.0,
      positionSec = 1_100.0,
      durationSec = 1_200.0,
      isRepeat = false,
      nowMs = 0L,
    )!!

    assertEquals(1_200.0 - SeekCoalescer.END_GUARD_SEC, target, 0.001)
    assertTrue(WatchedThreshold.isFinished(positionSec = target, durationSec = 1_200.0))
  }

  @Test
  fun `a clamped seek in a very short clip stays resumable`() {
    val seeker = SeekCoalescer()
    // The other side of the same clamp: five seconds from the end of a
    // forty-second clip is only 87.5% of it, so this one is not finished.
    val target = seeker.press(
      60.0,
      positionSec = 10.0,
      durationSec = 40.0,
      isRepeat = false,
      nowMs = 0L,
    )!!

    assertEquals(35.0, target, 0.001)
    assertFalse(WatchedThreshold.isFinished(positionSec = target, durationSec = 40.0))
  }

  @Test
  fun `tiny clips are not finished before playback starts`() {
    assertFalse(WatchedThreshold.isFinished(positionSec = 0.0, durationSec = 1.0))
    assertFalse(WatchedThreshold.isFinished(positionSec = 0.0, durationSec = 5.0))
    // The case that ruled out treating the seek guard as a watched threshold: at
    // five seconds of runtime it would have called this finished on the first frame.
    assertFalse(
      WatchedThreshold.isFinished(
        positionSec = 0.0,
        durationSec = SeekCoalescer.END_GUARD_SEC,
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
