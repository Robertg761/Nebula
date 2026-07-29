package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.addon.AddonBehaviorHints
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.BingeGroupMatcher
import com.stremioshell.host.tv.data.addon.StreamAutoPick
import com.stremioshell.host.tv.data.addon.StreamSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPickTest {
  private val nextEpisode = listOf(
    stream("Comet 720p", group = "comet|720p|WEB"),
    stream("Comet 2160p", group = "comet|2160p|WEB"),
    stream("Comet 1080p", group = "comet|1080p|WEB"),
    stream("Other 1080p", group = null),
  )

  @Test
  fun `the release being watched is matched across episodes`() {
    val matched = BingeGroupMatcher.match("comet|1080p|WEB", nextEpisode)

    assertEquals("Comet 1080p", matched?.label)
  }

  @Test
  fun `group matching ignores case and surrounding space`() {
    val matched = BingeGroupMatcher.match("  COMET|1080P|web ", nextEpisode)

    assertEquals("Comet 1080p", matched?.label)
  }

  @Test
  fun `a group the next episode does not have matches nothing`() {
    assertNull(BingeGroupMatcher.match("comet|1080p|BluRay", nextEpisode))
    assertNull(BingeGroupMatcher.match(null, nextEpisode))
    assertNull(BingeGroupMatcher.match("", nextEpisode))
  }

  @Test
  fun `the finished stream's own release wins over the remembered one`() {
    val remembered = StreamSelection(
      seriesId = "tt0903747",
      bingeGroup = "comet|720p|WEB",
      resolutionHeight = 720,
      updatedAtMs = 1,
    )

    val picked = StreamAutoPick.pick(nextEpisode, bingeGroup = "comet|2160p|WEB", remembered = remembered)

    assertEquals("Comet 2160p", picked?.label)
  }

  @Test
  fun `a release that ran out falls back to the remembered one`() {
    val remembered = StreamSelection(
      seriesId = "tt0903747",
      bingeGroup = "comet|1080p|WEB",
      resolutionHeight = 1080,
      updatedAtMs = 1,
    )

    val picked = StreamAutoPick.pick(nextEpisode, bingeGroup = "gone|from|this|season", remembered)

    assertEquals("Comet 1080p", picked?.label)
  }

  @Test
  fun `a remembered tier picks the best row in that tier`() {
    val remembered = StreamSelection(seriesId = "tt0903747", resolutionHeight = 1080, updatedAtMs = 1)

    val picked = StreamAutoPick.pick(nextEpisode, bingeGroup = null, remembered = remembered)

    assertEquals("Comet 1080p", picked?.label)
  }

  @Test
  fun `resolution fallback does not switch SDR playback into HDR or Dolby Vision`() {
    val streams = listOf(
      stream("Comet 2160p DV HDR", group = null),
      stream("Comet 2160p HDR", group = null),
      stream("Comet 2160p SDR", group = null),
    )
    val remembered = StreamSelection(
      seriesId = "tt0903747",
      resolutionHeight = 2160,
      hdr = false,
      dolbyVision = false,
      updatedAtMs = 1,
    )

    assertEquals(
      "Comet 2160p SDR",
      StreamAutoPick.pick(streams, remembered = remembered)?.label,
    )
  }

  @Test
  fun `known playback format with no compatible fallback asks the viewer`() {
    val streams = listOf(stream("Comet 2160p DV HDR", group = null))
    val remembered = StreamSelection(
      seriesId = "tt0903747",
      resolutionHeight = 2160,
      hdr = false,
      dolbyVision = false,
      updatedAtMs = 1,
    )

    assertNull(StreamAutoPick.pick(streams, remembered = remembered))
  }

  @Test
  fun `reused binge group cannot cross a remembered playback format`() {
    val streams = listOf(stream("Comet 2160p DV HDR", group = "comet|season"))
    val remembered = StreamSelection(
      seriesId = "tt0903747",
      bingeGroup = "comet|season",
      resolutionHeight = 2160,
      hdr = false,
      dolbyVision = false,
      updatedAtMs = 1,
    )

    assertNull(
      StreamAutoPick.pick(
        streams,
        bingeGroup = "comet|season",
        remembered = remembered,
      ),
    )
  }

  @Test
  fun `legacy selection without format flags keeps resolution fallback behavior`() {
    val streams = listOf(stream("Comet 2160p DV HDR", group = null))
    val legacy = StreamSelection(
      seriesId = "tt0903747",
      resolutionHeight = 2160,
      updatedAtMs = 1,
    )

    assertEquals("Comet 2160p DV HDR", StreamAutoPick.pick(streams, remembered = legacy)?.label)
  }

  @Test
  fun `nothing remembered means the viewer chooses`() {
    assertNull(StreamAutoPick.pick(nextEpisode, bingeGroup = null, remembered = null))
  }

  @Test
  fun `a remembered tier the next episode cannot offer means the viewer chooses`() {
    val remembered = StreamSelection(seriesId = "tt0903747", resolutionHeight = 1440, updatedAtMs = 1)

    assertNull(StreamAutoPick.pick(nextEpisode, bingeGroup = null, remembered = remembered))
  }

  @Test
  fun `an empty stream list never auto-picks`() {
    val remembered = StreamSelection(
      seriesId = "tt0903747",
      bingeGroup = "comet|1080p|WEB",
      resolutionHeight = 1080,
      updatedAtMs = 1,
    )

    assertNull(StreamAutoPick.pick(emptyList(), bingeGroup = "comet|1080p|WEB", remembered = remembered))
  }

  private fun stream(label: String, group: String?) = AddonStream(
    name = label,
    url = "https://rd.example/${label.hashCode()}.mkv",
    behaviorHints = AddonBehaviorHints(bingeGroup = group),
  )
}
