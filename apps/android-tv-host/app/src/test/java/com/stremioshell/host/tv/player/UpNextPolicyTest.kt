package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.MergedStreams
import com.stremioshell.host.tv.data.addon.StreamFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextPolicyTest {
  @Test
  fun `an untouched ending counts down into the next episode`() {
    val offer = UpNextPolicy.offer(hasNext = true, paused = false, msSinceInteractionMs = 600_000)

    assertEquals(UpNextPolicy.Offer.Countdown(UpNextPolicy.COUNTDOWN_MS), offer)
  }

  @Test
  fun `nothing to play next means nothing is offered`() {
    val offer = UpNextPolicy.offer(hasNext = false, paused = false, msSinceInteractionMs = 600_000)

    assertEquals(UpNextPolicy.Offer.None, offer)
  }

  @Test
  fun `a viewer paused at the credits is asked, not overruled`() {
    val offer = UpNextPolicy.offer(hasNext = true, paused = true, msSinceInteractionMs = 600_000)

    assertEquals(UpNextPolicy.Offer.Prompt, offer)
  }

  @Test
  fun `a recent press means the remote is in hand`() {
    val offer = UpNextPolicy.offer(hasNext = true, paused = false, msSinceInteractionMs = 2_000)

    assertEquals(UpNextPolicy.Offer.Prompt, offer)
  }

  @Test
  fun `the countdown opens on the full number of seconds`() {
    assertEquals(15, UpNextPolicy.secondsLeft(elapsedMs = 0, totalMs = 15_000))
    assertEquals(15, UpNextPolicy.secondsLeft(elapsedMs = 200, totalMs = 15_000))
    assertEquals(14, UpNextPolicy.secondsLeft(elapsedMs = 1_000, totalMs = 15_000))
  }

  @Test
  fun `the countdown reaches zero only when it is up`() {
    assertEquals(1, UpNextPolicy.secondsLeft(elapsedMs = 14_999, totalMs = 15_000))
    assertEquals(0, UpNextPolicy.secondsLeft(elapsedMs = 15_000, totalMs = 15_000))
    assertEquals(0, UpNextPolicy.secondsLeft(elapsedMs = 20_000, totalMs = 15_000))
  }

  @Test
  fun `the next episode is due once the countdown is spent`() {
    assertFalse(UpNextPolicy.isDue(elapsedMs = 14_999, totalMs = 15_000))
    assertTrue(UpNextPolicy.isDue(elapsedMs = 15_000, totalMs = 15_000))
  }

  @Test
  fun `countdown progress is smooth and clamped at both ends`() {
    assertEquals(1f, UpNextPolicy.progressRemaining(elapsedMs = -1, totalMs = 15_000), 0f)
    assertEquals(1f, UpNextPolicy.progressRemaining(elapsedMs = 0, totalMs = 15_000), 0f)
    assertEquals(.5f, UpNextPolicy.progressRemaining(elapsedMs = 7_500, totalMs = 15_000), 0f)
    assertEquals(0f, UpNextPolicy.progressRemaining(elapsedMs = 15_000, totalMs = 15_000), 0f)
    assertEquals(0f, UpNextPolicy.progressRemaining(elapsedMs = 20_000, totalMs = 15_000), 0f)
  }

  @Test
  fun `zero or negative countdown duration is already due`() {
    assertEquals(0, UpNextPolicy.secondsLeft(elapsedMs = 0, totalMs = 0))
    assertEquals(0f, UpNextPolicy.progressRemaining(elapsedMs = 0, totalMs = 0), 0f)
    assertTrue(UpNextPolicy.isDue(elapsedMs = 0, totalMs = 0))

    assertEquals(0, UpNextPolicy.secondsLeft(elapsedMs = 0, totalMs = -1))
    assertEquals(0f, UpNextPolicy.progressRemaining(elapsedMs = 0, totalMs = -1), 0f)
    assertTrue(UpNextPolicy.isDue(elapsedMs = 0, totalMs = -1))
  }

  @Test
  fun `negative elapsed time restarts at the full countdown`() {
    assertEquals(15, UpNextPolicy.secondsLeft(elapsedMs = -5_000, totalMs = 15_000))
    assertFalse(UpNextPolicy.isDue(elapsedMs = -5_000, totalMs = 15_000))
  }

  @Test
  fun `very long durations do not overflow the displayed seconds`() {
    assertEquals(Int.MAX_VALUE, UpNextPolicy.secondsLeft(elapsedMs = 0, totalMs = Long.MAX_VALUE))
    assertEquals(
      1f,
      UpNextPolicy.progressRemaining(elapsedMs = 0, totalMs = Long.MAX_VALUE),
      0f,
    )
  }

  @Test
  fun `all addon failures stay on the card for retry`() {
    val fetch = streamFetch(
      streams = emptyList(),
      allFailed = true,
      notice = "Couldn't reach Comet and Torrentio.",
    )

    assertEquals(
      UpNextStreamResolution.Retry("Couldn't reach Comet and Torrentio."),
      UpNextStreamPolicy.classify(fetch, picked = null),
    )
  }

  @Test
  fun `a healthy lookup without a match opens the picker`() {
    val fetch = streamFetch(streams = emptyList(), allFailed = false)

    assertEquals(
      UpNextStreamResolution.NeedsPicker,
      UpNextStreamPolicy.classify(fetch, picked = null),
    )
  }

  @Test
  fun `a picked stream is ready only when its url is usable`() {
    val ready = AddonStream(name = "Match", url = "https://example.test/episode.mkv")
    val fetch = streamFetch(streams = listOf(ready), allFailed = false)

    assertEquals(
      UpNextStreamResolution.Ready(ready),
      UpNextStreamPolicy.classify(fetch, picked = ready),
    )
    assertEquals(
      UpNextStreamResolution.NeedsPicker,
      UpNextStreamPolicy.classify(fetch, picked = AddonStream(url = "  ")),
    )
  }

  private fun streamFetch(
    streams: List<AddonStream>,
    allFailed: Boolean,
    notice: String? = null,
  ) = StreamFetch(
    merged = MergedStreams(streams = streams, notice = notice, allFailed = allFailed),
    failures = emptyList(),
  )
}
