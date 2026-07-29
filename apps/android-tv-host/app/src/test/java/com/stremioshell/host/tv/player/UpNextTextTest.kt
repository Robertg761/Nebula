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
  fun `the card title falls back to the episode number`() {
    assertEquals("S1E2", UpNextText.titleLine(UpNextTarget(1, 2, "   ")))
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

  @Test
  fun `a failure replaces stale resolving and countdown copy`() {
    val failed = state(
      secondsLeft = 3,
      resolving = true,
      failure = UpNextFailure("Couldn't find a compatible stream."),
    )

    assertEquals("Couldn't find a compatible stream.", UpNextText.statusLine(failed))
    assertEquals("OK retry   |   BACK stop", UpNextText.hintLine(failed))
  }

  @Test
  fun `a blank failure never produces a blank card`() {
    assertEquals(
      "Couldn't start the next episode.",
      UpNextText.statusLine(state(secondsLeft = null, failure = UpNextFailure("  "))),
    )
  }

  @Test
  fun `ready resolving and failed cards expose only valid accessibility actions`() {
    assertEquals(
      listOf(UpNextCardAction.Play, UpNextCardAction.Cancel),
      UpNextText.availableActions(state(secondsLeft = 12)),
    )
    assertEquals(
      listOf(UpNextCardAction.Cancel),
      UpNextText.availableActions(state(secondsLeft = null, resolving = true)),
    )
    assertEquals(
      listOf(UpNextCardAction.Retry, UpNextCardAction.Cancel),
      UpNextText.availableActions(state(secondsLeft = null, failure = UpNextFailure())),
    )
  }

  @Test
  fun `accessibility action labels identify the episode without relying on the card`() {
    val target = UpNextTarget(3, 4, "The Body")

    assertEquals("Play S3E4  The Body now", UpNextText.actionLabel(UpNextCardAction.Play, target))
    assertEquals("Retry S3E4  The Body", UpNextText.actionLabel(UpNextCardAction.Retry, target))
    assertEquals("Cancel up next", UpNextText.actionLabel(UpNextCardAction.Cancel, target))
  }

  @Test
  fun `countdown announcements are bounded to useful milestones`() {
    assertEquals(
      "Up next, S3E4  The Body. Playing in 15s",
      UpNextText.accessibilityAnnouncement(state(secondsLeft = 15)),
    )
    assertEquals(null, UpNextText.accessibilityAnnouncement(state(secondsLeft = 14)))
    assertEquals(
      "Up next, S3E4  The Body. Playing in 5s",
      UpNextText.accessibilityAnnouncement(state(secondsLeft = 5)),
    )
  }

  @Test
  fun `prompt resolving and failure states are announced once per transition`() {
    assertEquals(
      "Up next, S3E4  The Body. Press OK to play",
      UpNextText.accessibilityAnnouncement(state(secondsLeft = null)),
    )
    assertEquals(
      "Up next, S3E4  The Body. Finding a stream... Press BACK to stop.",
      UpNextText.accessibilityAnnouncement(state(secondsLeft = null, resolving = true)),
    )
    assertEquals(
      "Up next, S3E4  The Body. Couldn't start the next episode. " +
        "Press OK to retry, or BACK to stop.",
      UpNextText.accessibilityAnnouncement(
        state(secondsLeft = null, failure = UpNextFailure()),
      ),
    )
  }

  private fun state(
    secondsLeft: Int?,
    resolving: Boolean = false,
    failure: UpNextFailure? = null,
  ) = UpNextCardState(
    seriesTitle = "Buffy",
    target = UpNextTarget(3, 4, "The Body"),
    secondsLeft = secondsLeft,
    resolving = resolving,
    failure = failure,
  )
}
