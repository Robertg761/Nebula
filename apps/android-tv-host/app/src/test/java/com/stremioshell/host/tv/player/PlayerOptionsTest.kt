package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOptionsTest {
  @Test
  fun `speed steps up and down the ladder`() {
    assertEquals(1.25, PlaybackSpeeds.stepped(1.0, 1), 0.0001)
    assertEquals(0.75, PlaybackSpeeds.stepped(1.0, -1), 0.0001)
    assertEquals(2.0, PlaybackSpeeds.stepped(1.0, 3), 0.0001)
  }

  @Test
  fun `speed holds at either end instead of wrapping`() {
    assertEquals(2.0, PlaybackSpeeds.stepped(2.0, 1), 0.0001)
    assertEquals(0.75, PlaybackSpeeds.stepped(0.75, -1), 0.0001)
    assertEquals(0.75, PlaybackSpeeds.stepped(1.0, -9), 0.0001)
  }

  @Test
  fun `a speed off the ladder steps from the nearest step`() {
    // mpv round-trips the value through its own parser, so it can come back a
    // hair off what was set.
    assertEquals(1.5, PlaybackSpeeds.stepped(1.2499999, 1), 0.0001)
    assertEquals(1, PlaybackSpeeds.nearestIndex(0.99))
    assertEquals(1.0, PlaybackSpeeds.nearest(1.05), 0.0001)
  }

  @Test
  fun `speed labels drop trailing zeroes`() {
    assertEquals("1x", PlaybackSpeeds.label(1.0))
    assertEquals("1.25x", PlaybackSpeeds.label(1.25))
    assertEquals("0.75x", PlaybackSpeeds.label(0.75))
    assertEquals("1.5x", PlaybackSpeeds.label(1.5))
  }

  @Test
  fun `medium subtitle size is the size the player used to hardcode`() {
    assertEquals(44, SubtitleSize.Medium.fontSize)
    assertEquals(SubtitleSize.Medium, SubtitleSize.DEFAULT)
  }

  @Test
  fun `subtitle size round-trips through storage`() {
    SubtitleSize.entries.forEach { size ->
      assertEquals(size, SubtitleSize.fromStorage(size.storageName))
    }
  }

  @Test
  fun `an unset or unrecognised stored size falls back to the default`() {
    assertEquals(SubtitleSize.DEFAULT, SubtitleSize.fromStorage(null))
    assertEquals(SubtitleSize.DEFAULT, SubtitleSize.fromStorage(""))
    assertEquals(SubtitleSize.DEFAULT, SubtitleSize.fromStorage("enormous"))
    assertEquals(SubtitleSize.Large, SubtitleSize.fromStorage(" LARGE "))
  }

  @Test
  fun `subtitle size steps and holds at either end`() {
    assertEquals(SubtitleSize.Large, SubtitleSize.stepped(SubtitleSize.Medium, 1))
    assertEquals(SubtitleSize.Small, SubtitleSize.stepped(SubtitleSize.Medium, -1))
    assertEquals(SubtitleSize.Huge, SubtitleSize.stepped(SubtitleSize.Huge, 1))
    assertEquals(SubtitleSize.Small, SubtitleSize.stepped(SubtitleSize.Small, -1))
  }

  @Test
  fun `decode is the default, so nobody gets silence out of the box`() {
    assertEquals(AudioOutputMode.Decode, AudioOutputMode.DEFAULT)
    assertEquals("", AudioOutputMode.Decode.spdifCodecs)
  }

  @Test
  fun `passthrough hands over the formats a receiver decodes`() {
    assertEquals("ac3,eac3,dts,dts-hd,truehd", AudioOutputMode.Passthrough.spdifCodecs)
  }

  @Test
  fun `audio output round-trips through storage`() {
    AudioOutputMode.entries.forEach { mode ->
      assertEquals(mode, AudioOutputMode.fromStorage(mode.storageName))
    }
  }

  @Test
  fun `an unset or unrecognised stored audio output falls back to decode`() {
    assertEquals(AudioOutputMode.DEFAULT, AudioOutputMode.fromStorage(null))
    assertEquals(AudioOutputMode.DEFAULT, AudioOutputMode.fromStorage(""))
    assertEquals(AudioOutputMode.DEFAULT, AudioOutputMode.fromStorage("spdif"))
    assertEquals(AudioOutputMode.Passthrough, AudioOutputMode.fromStorage(" PASSTHROUGH "))
  }

  @Test
  fun `audio output steps and holds at either end`() {
    assertEquals(
      AudioOutputMode.Passthrough,
      AudioOutputMode.stepped(AudioOutputMode.Decode, 1),
    )
    assertEquals(
      AudioOutputMode.Decode,
      AudioOutputMode.stepped(AudioOutputMode.Passthrough, -1),
    )
    assertEquals(
      AudioOutputMode.Passthrough,
      AudioOutputMode.stepped(AudioOutputMode.Passthrough, 3),
    )
    assertEquals(AudioOutputMode.Decode, AudioOutputMode.stepped(AudioOutputMode.Decode, -3))
  }

  @Test
  fun `the passthrough confirmation names the way back out of silence`() {
    assertTrue(AudioOutputMode.Passthrough.osdMessage.contains("Decode"))
  }

  @Test
  fun `delay steps in 25ms increments in both directions`() {
    assertEquals(0.025, DelaySteps.stepped(0.0, 1), 0.0001)
    assertEquals(-0.025, DelaySteps.stepped(0.0, -1), 0.0001)
    assertEquals(0.15, DelaySteps.stepped(0.125, 1), 0.0001)
    assertEquals(0.0, DelaySteps.stepped(0.025, -1), 0.0001)
  }

  @Test
  fun `a long run of steps stays exactly on the grid`() {
    var delay = 0.0
    repeat(40) { delay = DelaySteps.stepped(delay, 1) }

    assertEquals(1.0, delay, 1e-9)
    assertEquals("+1000 ms", DelaySteps.label(delay))
  }

  @Test
  fun `delay is clamped rather than run off to nonsense`() {
    assertEquals(DelaySteps.LIMIT_SEC, DelaySteps.stepped(9.99, 5), 0.0001)
    assertEquals(-DelaySteps.LIMIT_SEC, DelaySteps.stepped(-9.99, -5), 0.0001)
  }

  @Test
  fun `delay labels carry the sign, because the sign is the point`() {
    assertEquals("0 ms", DelaySteps.label(0.0))
    assertEquals("+150 ms", DelaySteps.label(0.15))
    assertEquals("-25 ms", DelaySteps.label(-0.025))
  }
}
