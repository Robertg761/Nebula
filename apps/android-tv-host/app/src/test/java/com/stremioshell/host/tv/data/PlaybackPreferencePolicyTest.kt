package com.stremioshell.host.tv.data

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
}
