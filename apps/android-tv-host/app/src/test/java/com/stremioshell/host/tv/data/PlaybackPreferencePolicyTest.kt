package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.player.SubtitleBackground
import com.stremioshell.host.tv.player.SubtitleEdge
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPreferencePolicyTest {
  @Test
  fun `countdown values are constrained to the supported preset ladder`() {
    assertEquals(5, PlaybackPreferencePolicy.countdownSeconds(Int.MIN_VALUE))
    assertEquals(5, PlaybackPreferencePolicy.countdownSeconds(1))
    assertEquals(10, PlaybackPreferencePolicy.countdownSeconds(11))
    assertEquals(15, PlaybackPreferencePolicy.countdownSeconds(15))
    assertEquals(30, PlaybackPreferencePolicy.countdownSeconds(Int.MAX_VALUE))
  }

  @Test
  fun `countdown cycling wraps after the longest preset`() {
    assertEquals(10, PlaybackPreferencePolicy.nextCountdownSeconds(5))
    assertEquals(15, PlaybackPreferencePolicy.nextCountdownSeconds(10))
    assertEquals(30, PlaybackPreferencePolicy.nextCountdownSeconds(15))
    assertEquals(5, PlaybackPreferencePolicy.nextCountdownSeconds(30))
  }

  @Test
  fun `player preferences default to safe binge settings`() {
    val prefs = PlayerPrefs()
    assertEquals(true, prefs.autoPlayNext)
    assertEquals(15, prefs.upNextCountdownSeconds)
  }

  @Test
  fun `an unwritten subtitle style reads back as the look the player already drew`() {
    // What a device that has never opened these rows holds: the blank has to
    // resolve to the shipped appearance rather than to whichever entry happens to
    // be first in the enum.
    val prefs = PlayerPrefs()
    assertEquals("", prefs.subtitleEdge)
    assertEquals("", prefs.subtitleBackground)
    assertEquals(SubtitleEdge.DEFAULT, SubtitleEdge.fromStorage(prefs.subtitleEdge))
    assertEquals(
      SubtitleBackground.DEFAULT,
      SubtitleBackground.fromStorage(prefs.subtitleBackground),
    )
  }

  @Test
  fun `a chosen subtitle style survives the round trip through storage names`() {
    SubtitleEdge.entries.forEach { edge ->
      SubtitleBackground.entries.forEach { background ->
        val stored = PlayerPrefs(
          subtitleEdge = edge.storageName,
          subtitleBackground = background.storageName,
        )
        assertEquals(edge, SubtitleEdge.fromStorage(stored.subtitleEdge))
        assertEquals(background, SubtitleBackground.fromStorage(stored.subtitleBackground))
      }
    }
  }

  @Test
  fun `the subtitle style keys are distinct, so neither row overwrites the other`() {
    val stored = PlayerPrefs(
      subtitleSize = "large",
      subtitleEdge = SubtitleEdge.HighContrast.storageName,
      subtitleBackground = SubtitleBackground.Dim.storageName,
    )
    assertEquals("large", stored.subtitleSize)
    assertEquals("high-contrast", stored.subtitleEdge)
    assertEquals("dim", stored.subtitleBackground)
  }
}
