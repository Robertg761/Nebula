package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UpNextTextTest {
  @Test
  fun `the episode line pairs number and name`() {
    assertEquals("S3E4  The Body", UpNextText.episodeLine(UpNextTarget(3, 4, "The Body")))
  }

  @Test
  fun `a missing episode name leaves no trailing separator`() {
    assertEquals("S1E2", UpNextText.episodeLine(UpNextTarget(1, 2, "   ")))
  }

  @Test
  fun `the countdown is what the status line says while it runs`() {
    assertEquals("Playing in 9s", UpNextText.statusLine(state(secondsLeft = 9)))
  }

  @Test
  fun `with no countdown the card asks instead of telling`() {
    assertEquals("Press OK to play", UpNextText.statusLine(state(secondsLeft = null)))
  }

  @Test
  fun `resolving outranks the countdown in both lines`() {
    val resolving = state(secondsLeft = 3, resolving = true)

    assertEquals("Finding a stream...", UpNextText.statusLine(resolving))
    assertEquals("BACK to stop", UpNextText.hintLine(resolving))
  }

  @Test
  fun `an idle card offers both keys`() {
    assertEquals("OK play now   |   BACK stop", UpNextText.hintLine(state(secondsLeft = 12)))
  }

  private fun state(secondsLeft: Int?, resolving: Boolean = false) = UpNextCardState(
    seriesTitle = "Buffy",
    target = UpNextTarget(3, 4, "The Body"),
    secondsLeft = secondsLeft,
    resolving = resolving,
  )
}
