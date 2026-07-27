package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekCoalescerTest {
  private fun coalescer() = SeekCoalescer(endGuardSec = 5.0, repeatMinIntervalMs = 120L)

  @Test
  fun `single press seeks relative to the reported position`() {
    val seeker = coalescer()

    val target = seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)

    assertEquals(110.0, target!!, 0.001)
    assertEquals(110.0, seeker.previewSec!!, 0.001)
  }

  @Test
  fun `presses accumulate onto the outstanding target, not the stale position`() {
    val seeker = coalescer()

    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    // mpv still reports 100.0 because the seek has not been issued yet.
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 200L)
    val target = seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 400L)

    assertEquals(130.0, target!!, 0.001)
    assertEquals(130.0, seeker.consumePending()!!, 0.001)
  }

  @Test
  fun `repeats faster than the floor are dropped`() {
    val seeker = coalescer()

    seeker.press(10.0, positionSec = 0.0, durationSec = 3600.0, isRepeat = false, nowMs = 1_000L)
    val tooSoon = seeker.press(10.0, positionSec = 0.0, durationSec = 3600.0, isRepeat = true, nowMs = 1_050L)
    val accepted = seeker.press(10.0, positionSec = 0.0, durationSec = 3600.0, isRepeat = true, nowMs = 1_200L)

    assertNull(tooSoon)
    assertEquals(20.0, accepted!!, 0.001)
  }

  @Test
  fun `a deliberate press is never dropped, however soon it lands`() {
    val seeker = coalescer()

    seeker.press(10.0, positionSec = 0.0, durationSec = 3600.0, isRepeat = false, nowMs = 1_000L)
    val target = seeker.press(10.0, positionSec = 0.0, durationSec = 3600.0, isRepeat = false, nowMs = 1_001L)

    assertEquals(20.0, target!!, 0.001)
  }

  @Test
  fun `forward seek stops short of the end instead of running into eof`() {
    val seeker = coalescer()

    val target = seeker.press(60.0, positionSec = 1_190.0, durationSec = 1_200.0, isRepeat = false, nowMs = 0L)

    assertEquals(1_195.0, target!!, 0.001)
  }

  @Test
  fun `rewind clamps at the start`() {
    val seeker = coalescer()

    val target = seeker.press(-60.0, positionSec = 12.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)

    assertEquals(0.0, target!!, 0.001)
  }

  @Test
  fun `unknown duration still allows forward seeking`() {
    val seeker = coalescer()

    val target = seeker.press(10.0, positionSec = 30.0, durationSec = 0.0, isRepeat = false, nowMs = 0L)

    assertEquals(40.0, target!!, 0.001)
  }

  @Test
  fun `unknown duration keeps a burst within one step of the known position`() {
    val seeker = coalescer()

    // mpv reports 30.0 throughout: nothing has landed yet, and there is no
    // duration to clamp against, so the burst must not accumulate off the end.
    seeker.press(10.0, positionSec = 30.0, durationSec = 0.0, isRepeat = false, nowMs = 0L)
    seeker.press(10.0, positionSec = 30.0, durationSec = 0.0, isRepeat = true, nowMs = 200L)
    val target = seeker.press(10.0, positionSec = 30.0, durationSec = 0.0, isRepeat = true, nowMs = 400L)

    assertEquals(40.0, target!!, 0.001)
    assertEquals(40.0, seeker.consumePending()!!, 0.001)
  }

  @Test
  fun `unknown duration advances again once mpv reports where the seek landed`() {
    val seeker = coalescer()
    seeker.press(10.0, positionSec = 30.0, durationSec = 0.0, isRepeat = false, nowMs = 0L)
    seeker.consumePending()
    seeker.settle()

    val target = seeker.press(10.0, positionSec = 40.0, durationSec = 0.0, isRepeat = false, nowMs = 500L)

    assertEquals(50.0, target!!, 0.001)
  }

  @Test
  fun `unknown duration rewinds from the outstanding target, not back to the known position`() {
    val seeker = coalescer()
    seeker.press(60.0, positionSec = 30.0, durationSec = 0.0, isRepeat = false, nowMs = 0L)

    val target = seeker.press(-10.0, positionSec = 30.0, durationSec = 0.0, isRepeat = false, nowMs = 500L)

    assertEquals(80.0, target!!, 0.001)
  }

  @Test
  fun `unknown duration rewind clamps at the start`() {
    val seeker = coalescer()

    val target = seeker.press(-60.0, positionSec = 12.0, durationSec = 0.0, isRepeat = false, nowMs = 0L)

    assertEquals(0.0, target!!, 0.001)
  }

  @Test
  fun `unknown duration seeks forward from the very start`() {
    val seeker = coalescer()

    val target = seeker.press(10.0, positionSec = 0.0, durationSec = 0.0, isRepeat = false, nowMs = 0L)

    assertEquals(10.0, target!!, 0.001)
  }

  @Test
  fun `a duration shorter than the end guard clamps to the start`() {
    val seeker = coalescer()

    val target = seeker.press(10.0, positionSec = 1.0, durationSec = 3.0, isRepeat = false, nowMs = 0L)

    assertEquals(0.0, target!!, 0.001)
  }

  @Test
  fun `nothing is pending until a press arrives`() {
    assertNull(coalescer().consumePending())
    assertNull(coalescer().previewSec)
  }

  @Test
  fun `consuming leaves the preview live because the seek is still in flight`() {
    val seeker = coalescer()
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)

    seeker.consumePending()

    assertEquals(110.0, seeker.previewSec!!, 0.001)
    assertNull("a consumed seek must not be issued twice", seeker.consumePending())
  }

  @Test
  fun `settle hands the position back to mpv`() {
    val seeker = coalescer()
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    seeker.consumePending()

    assertTrue(seeker.settle())

    assertNull(seeker.previewSec)
    assertNull(seeker.consumePending())
  }

  @Test
  fun `a settle from the previous seek does not discard a newer press`() {
    val seeker = coalescer()
    // t=0 press, t=350 flush: the first seek is on its way to mpv.
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    assertEquals(110.0, seeker.consumePending()!!, 0.001)
    // t=500 second press; mpv still reports 100.0, the first seek has not landed.
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 500L)

    // t=700 playback restarts from the FIRST seek.
    assertFalse("the restart belongs to the already-issued seek", seeker.settle())

    assertEquals(120.0, seeker.previewSec!!, 0.001)
    // t=850 flush: the second press must still be there to issue.
    assertEquals(120.0, seeker.consumePending()!!, 0.001)
  }

  @Test
  fun `a settle from initial load or a cache stall does not discard a press`() {
    val seeker = coalescer()
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)

    // Load and cache-stall recovery raise the same restart event, with no seek
    // of ours in flight to settle.
    assertFalse(seeker.settle())

    assertEquals(110.0, seeker.previewSec!!, 0.001)
    assertEquals(110.0, seeker.consumePending()!!, 0.001)
  }

  @Test
  fun `the newer press settles normally once it has been issued`() {
    val seeker = coalescer()
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    seeker.consumePending()
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 500L)
    seeker.settle() // stale restart from the first seek
    seeker.consumePending() // second seek issued

    assertTrue(seeker.settle())
    assertNull(seeker.previewSec)
  }

  @Test
  fun `a spurious settle with nothing outstanding changes nothing`() {
    val seeker = coalescer()

    assertFalse(seeker.settle())
    assertNull(seeker.previewSec)

    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    seeker.consumePending()
    assertTrue(seeker.settle())
    assertFalse("a settled seek cannot settle twice", seeker.settle())
  }

  @Test
  fun `an unflushed press is readable as the position to save`() {
    val seeker = coalescer()

    seeker.press(4_200.0, positionSec = 600.0, durationSec = 7_200.0, isRepeat = false, nowMs = 0L)

    // Backing out inside the coalesce window must resume at 1:20:00, not 10:00.
    assertTrue(seeker.hasPendingPress)
    assertEquals(4_800.0, seeker.previewSec!!, 0.001)
  }

  @Test
  fun `an issued but unsettled seek is still the position to save`() {
    val seeker = coalescer()
    seeker.press(4_200.0, positionSec = 600.0, durationSec = 7_200.0, isRepeat = false, nowMs = 0L)

    seeker.consumePending()

    assertFalse("the press has been handed to mpv", seeker.hasPendingPress)
    assertEquals("the seek is in flight, so mpv's position is still stale", 4_800.0, seeker.previewSec!!, 0.001)
  }

  @Test
  fun `once settled there is no target and the save falls back to mpv`() {
    val seeker = coalescer()
    seeker.press(4_200.0, positionSec = 600.0, durationSec = 7_200.0, isRepeat = false, nowMs = 0L)
    seeker.consumePending()

    seeker.settle()

    assertFalse(seeker.hasPendingPress)
    assertNull(seeker.previewSec)
  }

  @Test
  fun `after settling, the next press starts from mpv's position again`() {
    val seeker = coalescer()
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    seeker.consumePending()
    seeker.settle()

    val target = seeker.press(10.0, positionSec = 110.0, durationSec = 3600.0, isRepeat = false, nowMs = 500L)

    assertEquals(120.0, target!!, 0.001)
  }

  @Test
  fun `reset drops an unflushed press so a reload is not seeked afterwards`() {
    val seeker = coalescer()
    seeker.press(4_200.0, positionSec = 600.0, durationSec = 7_200.0, isRepeat = false, nowMs = 0L)

    seeker.reset()

    assertFalse(seeker.hasPendingPress)
    assertNull(seeker.previewSec)
    assertNull(seeker.consumePending())
  }

  @Test
  fun `reset drops an in-flight seek that can never settle`() {
    val seeker = coalescer()
    seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    seeker.consumePending()

    seeker.reset()

    assertNull(seeker.previewSec)
    assertFalse("the restart from the reload belongs to no seek of ours", seeker.settle())
  }

  @Test
  fun `after a reset the next press starts from mpv's position`() {
    val seeker = coalescer()
    seeker.press(600.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 0L)
    seeker.reset()

    val target = seeker.press(10.0, positionSec = 100.0, durationSec = 3600.0, isRepeat = false, nowMs = 500L)

    assertEquals(110.0, target!!, 0.001)
  }
}
