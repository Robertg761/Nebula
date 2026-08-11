package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun `invalid native speed values fall back to normal playback`() {
    assertEquals(1.0, PlaybackSpeeds.nearest(Double.NaN), 0.0001)
    assertEquals(1.0, PlaybackSpeeds.nearest(Double.POSITIVE_INFINITY), 0.0001)
    assertEquals(1.0, PlaybackSpeeds.nearest(-1.0), 0.0001)
    assertEquals("1x", PlaybackSpeeds.label(Double.NaN))
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
  fun `the default edge is the one mpv would have drawn unasked`() {
    // mpv's own defaults, which is what the player shipped before this row
    // existed: an existing viewer's subtitles must not change shape under them.
    assertEquals(SubtitleEdge.Outline, SubtitleEdge.DEFAULT)
    assertEquals(3, SubtitleEdge.Outline.borderSize)
    assertEquals(0, SubtitleEdge.Outline.shadowOffset)
  }

  @Test
  fun `each edge step is the mpv properties it claims to be`() {
    assertEquals(
      listOf(
        "sub-border-size" to "0",
        "sub-shadow-offset" to "0",
        "sub-shadow-color" to "#000000",
      ),
      SubtitleEdge.None.mpvOptions,
    )
    assertEquals(
      listOf(
        "sub-border-size" to "1",
        "sub-shadow-offset" to "3",
        "sub-shadow-color" to "#000000",
      ),
      SubtitleEdge.Shadow.mpvOptions,
    )
    assertEquals(
      listOf(
        "sub-border-size" to "5",
        "sub-shadow-offset" to "0",
        "sub-shadow-color" to "#000000",
      ),
      SubtitleEdge.HighContrast.mpvOptions,
    )
  }

  @Test
  fun `every edge step names the same properties, so none of them leaves one behind`() {
    // A step that wrote a subset would inherit the rest from the step before it,
    // and the row would stop being a ladder after the first press.
    val names = SubtitleEdge.entries.map { edge -> edge.mpvOptions.map { it.first } }.toSet()
    assertEquals(1, names.size)
  }

  @Test
  fun `edge round-trips through storage`() {
    SubtitleEdge.entries.forEach { edge ->
      assertEquals(edge, SubtitleEdge.fromStorage(edge.storageName))
    }
  }

  @Test
  fun `an unset or unrecognised stored edge falls back to the default`() {
    assertEquals(SubtitleEdge.DEFAULT, SubtitleEdge.fromStorage(null))
    assertEquals(SubtitleEdge.DEFAULT, SubtitleEdge.fromStorage(""))
    assertEquals(SubtitleEdge.DEFAULT, SubtitleEdge.fromStorage("glow"))
    assertEquals(SubtitleEdge.HighContrast, SubtitleEdge.fromStorage(" HIGH-CONTRAST "))
  }

  @Test
  fun `edge steps and holds at either end`() {
    assertEquals(SubtitleEdge.Shadow, SubtitleEdge.stepped(SubtitleEdge.Outline, 1))
    assertEquals(SubtitleEdge.None, SubtitleEdge.stepped(SubtitleEdge.Outline, -1))
    assertEquals(SubtitleEdge.HighContrast, SubtitleEdge.stepped(SubtitleEdge.HighContrast, 1))
    assertEquals(SubtitleEdge.None, SubtitleEdge.stepped(SubtitleEdge.None, -1))
    assertEquals(SubtitleEdge.None, SubtitleEdge.stepped(SubtitleEdge.HighContrast, -9))
  }

  @Test
  fun `no background is the default, so the picture is not boxed over uninvited`() {
    assertEquals(SubtitleBackground.Off, SubtitleBackground.DEFAULT)
    // mpv's transparent default, in mpv's own AARRGGBB spelling.
    assertEquals("#00000000", SubtitleBackground.Off.backColor)
    // Off names the border style rather than leaving it alone: stepping back to
    // Off has to take the box away, and the player applies these as plain writes.
    assertEquals(
      listOf(
        "sub-border-style" to "outline-and-shadow",
        "sub-back-color" to "#00000000",
      ),
      SubtitleBackground.Off.mpvOptions,
    )
  }

  @Test
  fun `a background step asks for the box style, not only the colour`() {
    // Since mpv 0.38 sub-back-color is ignored under the default border style, so
    // the colour on its own drew nothing at all - which is what this row used to
    // send.
    assertEquals(
      listOf(
        "sub-border-style" to "background-box",
        "sub-back-color" to "#80000000",
      ),
      SubtitleBackground.Dim.mpvOptions,
    )
    assertEquals(
      listOf(
        "sub-border-style" to "background-box",
        "sub-back-color" to "#FF000000",
      ),
      SubtitleBackground.Solid.mpvOptions,
    )
  }

  @Test
  fun `the box is sized by the text, so the edge row stays a separate setting`() {
    // opaque-box would size the box from sub-border-size and quietly turn the edge
    // ladder into a padding control.
    SubtitleBackground.entries.filter { it != SubtitleBackground.Off }.forEach { background ->
      assertEquals("background-box", background.borderStyle)
    }
  }

  @Test
  fun `every background step names the same properties, so none of them leaves one behind`() {
    val names = SubtitleBackground.entries
      .map { background -> background.mpvOptions.map { it.first } }
      .toSet()
    assertEquals(1, names.size)
  }

  @Test
  fun `the background box gets darker along the ladder and ends opaque`() {
    val alphas = SubtitleBackground.entries.map { it.backColor.substring(1, 3).toInt(16) }
    assertEquals(listOf(0x00, 0x80, 0xFF), alphas)
    // Black, whatever the alpha: a tinted box would recolour the picture it covers.
    SubtitleBackground.entries.forEach { background ->
      assertEquals("000000", background.backColor.substring(3))
    }
  }

  @Test
  fun `background round-trips through storage`() {
    SubtitleBackground.entries.forEach { background ->
      assertEquals(background, SubtitleBackground.fromStorage(background.storageName))
    }
  }

  @Test
  fun `an unset or unrecognised stored background falls back to off`() {
    assertEquals(SubtitleBackground.DEFAULT, SubtitleBackground.fromStorage(null))
    assertEquals(SubtitleBackground.DEFAULT, SubtitleBackground.fromStorage(""))
    assertEquals(SubtitleBackground.DEFAULT, SubtitleBackground.fromStorage("opaque"))
    assertEquals(SubtitleBackground.Solid, SubtitleBackground.fromStorage(" SOLID "))
  }

  @Test
  fun `background steps and holds at either end`() {
    assertEquals(SubtitleBackground.Dim, SubtitleBackground.stepped(SubtitleBackground.Off, 1))
    assertEquals(SubtitleBackground.Off, SubtitleBackground.stepped(SubtitleBackground.Dim, -1))
    assertEquals(
      SubtitleBackground.Solid,
      SubtitleBackground.stepped(SubtitleBackground.Solid, 1),
    )
    assertEquals(SubtitleBackground.Off, SubtitleBackground.stepped(SubtitleBackground.Off, -1))
    assertEquals(
      SubtitleBackground.Off,
      SubtitleBackground.stepped(SubtitleBackground.Solid, -9),
    )
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
  fun `passthrough remains unavailable when sink support is unknown`() {
    assertEquals("", AudioOutputMode.Decode.spdifCodecsFor(null))
    assertEquals(null, AudioOutputMode.Passthrough.spdifCodecsFor(null))
    assertEquals(null, AudioOutputMode.Passthrough.spdifCodecsFor(emptySet()))
  }

  @Test
  fun `passthrough requests only codecs the active sink reports`() {
    val supported = setOf(" TRUEHD ", "AC3", "aac")

    assertEquals(
      "ac3,truehd",
      AudioOutputMode.Passthrough.spdifCodecsFor(supported),
    )
  }

  @Test
  fun `passthrough codec resolution keeps mpv canonical order`() {
    // The set arrives from an Android device query and its iteration order means
    // nothing; mpv's list is a preference order and has to survive intact.
    val supported = setOf("truehd", "dts-hd", "dts", "eac3", "ac3")

    assertEquals(
      AudioOutputMode.Passthrough.spdifCodecs,
      AudioOutputMode.Passthrough.spdifCodecsFor(supported),
    )
  }

  @Test
  fun `codec resolution is the value written to audio-spdif`() {
    // The production path: the player resolves the active route's codecs through
    // here and writes the result, so `?: ""` is a route with nothing to hand over
    // and means decode. Nothing may write the candidate list directly.
    fun spdifValue(mode: AudioOutputMode, sinkCodecs: Set<String>?): String =
      mode.spdifCodecsFor(sinkCodecs) ?: ""

    // An HDMI receiver taking Dolby but no DTS, as the caller spells it: mpv's
    // names, with E_AC3_JOC already folded in with E_AC3.
    assertEquals("ac3,eac3", spdifValue(AudioOutputMode.Passthrough, setOf("ac3", "eac3")))
    // Route lost, or a route that decodes nothing this player offers.
    assertEquals("", spdifValue(AudioOutputMode.Passthrough, null))
    assertEquals("", spdifValue(AudioOutputMode.Passthrough, setOf("aac", "pcm")))
    // Decode hands mpv the empty list however capable the sink is.
    assertEquals("", spdifValue(AudioOutputMode.Decode, setOf("ac3", "truehd")))
  }

  @Test
  fun `blank sink entries are not codecs`() {
    // An encoding the caller could not name must not resolve to an empty request
    // that reads as "passthrough is available".
    assertEquals(null, AudioOutputMode.Passthrough.spdifCodecsFor(setOf("", "   ")))
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
  fun `the passthrough confirmation says support is unverified and names the safe way back`() {
    assertTrue(AudioOutputMode.Passthrough.osdMessage.contains("not verified"))
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

  @Test
  fun `invalid or extreme native delay values stay on the safe grid`() {
    assertEquals(0.025, DelaySteps.stepped(Double.NaN, 1), 0.0001)
    assertEquals(DelaySteps.LIMIT_SEC, DelaySteps.stepped(Double.MAX_VALUE, 1), 0.0001)
    assertEquals("0 ms", DelaySteps.label(Double.NaN))
    assertEquals("+10000 ms", DelaySteps.label(Double.MAX_VALUE))
  }

  @Test
  fun `nobody gets a timer they did not set`() {
    assertEquals(SleepTimer.Off, SleepTimer.DEFAULT)
    assertFalse(SleepTimer.Off.isTimed)
  }

  @Test
  fun `each timed option is worth the minutes on its label`() {
    assertEquals(15L * 60_000L, SleepTimer.Minutes15.durationMs)
    assertEquals(30L * 60_000L, SleepTimer.Minutes30.durationMs)
    assertEquals(60L * 60_000L, SleepTimer.Minutes60.durationMs)
    assertEquals(90L * 60_000L, SleepTimer.Minutes90.durationMs)
    listOf(15, 30, 60, 90).forEachIndexed { index, minutes ->
      assertEquals(minutes, SleepTimer.entries[index + 1].minutes)
    }
  }

  @Test
  fun `after this episode has no duration to schedule`() {
    assertFalse(SleepTimer.AfterEpisode.isTimed)
    assertEquals(0L, SleepTimer.AfterEpisode.durationMs)
  }

  @Test
  fun `the sleep ladder runs from off to the ending and holds at either end`() {
    assertEquals(SleepTimer.Minutes15, SleepTimer.stepped(SleepTimer.Off, 1))
    assertEquals(SleepTimer.Off, SleepTimer.stepped(SleepTimer.Minutes15, -1))
    assertEquals(SleepTimer.AfterEpisode, SleepTimer.stepped(SleepTimer.Minutes90, 1))
    assertEquals(SleepTimer.AfterEpisode, SleepTimer.stepped(SleepTimer.AfterEpisode, 1))
    assertEquals(SleepTimer.Off, SleepTimer.stepped(SleepTimer.Off, -1))
    assertEquals(SleepTimer.Off, SleepTimer.stepped(SleepTimer.AfterEpisode, -9))
  }

  @Test
  fun `the remaining label rounds up, so a part minute is never reported as none`() {
    assertEquals(30, SleepTimer.minutesLeft(30L * 60_000L))
    assertEquals(30, SleepTimer.minutesLeft(29L * 60_000L + 30_000L))
    assertEquals(1, SleepTimer.minutesLeft(1L))
    assertEquals(0, SleepTimer.minutesLeft(0L))
    // A deadline the handler has not delivered yet; the row must not read "-1 min".
    assertEquals(0, SleepTimer.minutesLeft(-5_000L))
  }

  @Test
  fun `only after this episode takes the clock off the up-next card`() {
    assertTrue(SleepTimer.autoPlaysNext(SleepTimer.Off))
    assertTrue(SleepTimer.autoPlaysNext(SleepTimer.Minutes30))
    assertFalse(SleepTimer.autoPlaysNext(SleepTimer.AfterEpisode))
  }

  @Test
  fun `after this episode is spent on the ending it was armed for`() {
    assertEquals(SleepTimer.Off, SleepTimer.afterEnding(SleepTimer.AfterEpisode))
    // Two endings in a row: the second one autoplays like any other, because the
    // request was about one episode.
    assertTrue(SleepTimer.autoPlaysNext(SleepTimer.afterEnding(SleepTimer.AfterEpisode)))
  }

  @Test
  fun `a timed option is untouched by an ending, so it is never restarted by one`() {
    SleepTimer.entries.filter { it.isTimed }.forEach { timer ->
      assertEquals(timer, SleepTimer.afterEnding(timer))
    }
    assertEquals(SleepTimer.Off, SleepTimer.afterEnding(SleepTimer.Off))
  }
}
