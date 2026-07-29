package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.data.addon.AddonBehaviorHints
import com.stremioshell.host.tv.data.addon.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFilterPolicyTest {
  @Test
  fun `recommended prefers instant non-DV reasonably-sized releases`() {
    val hugeCachedDv = stream("[RD+] 2160p DV", 50, "Comet")
    val friendlyCached = stream("[RD+] 1080p HDR 12 GB", 12, "Torrentio")
    val uncached = stream("1080p SDR 4 GB", 4, "Comet")

    assertEquals(
      listOf(friendlyCached),
      StreamFilterPolicy.apply(
        listOf(hugeCachedDv, friendlyCached, uncached),
        StreamFilters(),
      ),
    )
  }

  @Test
  fun `recommended never removes the only playable class`() {
    val only = stream("2160p DV 55 GB", 55, "Comet")
    assertEquals(listOf(only), StreamFilterPolicy.apply(listOf(only), StreamFilters()))
  }

  @Test
  fun `an explicit DV filter is honored inside recommended view`() {
    val dv = stream("2160p DV 20 GB", 20, "Comet")
    val sdr = stream("[RD+] 1080p 4 GB", 4, "Torrentio")
    assertEquals(
      listOf(dv),
      StreamFilterPolicy.apply(
        listOf(dv, sdr),
        StreamFilters(dynamicRange = StreamDynamicRange.DolbyVision),
      ),
    )
  }

  @Test
  fun `show all returns the raw list in its original order`() {
    val raw = listOf(
      stream("2160p DV 55 GB", 55, "Comet"),
      stream("720p SDR 2 GB", 2, "Torrentio"),
    )
    assertEquals(raw, StreamFilterPolicy.apply(raw, StreamFilters.SHOW_ALL))
  }

  @Test
  fun `availability range resolution source and size filters combine`() {
    val wanted = stream("[RD+] 1080p HDR 4 GB", 4, "Comet")
    val wrongSource = stream("[RD+] 1080p HDR 4 GB", 4, "Torrentio")
    val tooLarge = stream("[RD+] 1080p HDR 8 GB", 8, "Comet")
    val sdr = stream("[RD+] 1080p 4 GB", 4, "Comet")
    val uncached = stream("1080p HDR 4 GB", 4, "Comet")

    val result = StreamFilterPolicy.apply(
      listOf(wanted, wrongSource, tooLarge, sdr, uncached),
      StreamFilters(
        viewMode = StreamViewMode.All,
        availability = StreamAvailability.Instant,
        dynamicRange = StreamDynamicRange.Hdr,
        resolution = StreamResolution.FullHd,
        source = "Comet",
        sizeLimit = StreamSizeLimit.Under5Gb,
      ),
    )
    assertEquals(listOf(wanted), result)
  }

  @Test
  fun `source choices are short labels and never raw links`() {
    val sources = StreamFilterPolicy.sources(
      listOf(stream("1080p", 2, "Comet"), stream("720p", 1, "Torrentio")),
    )
    assertEquals(listOf("Comet", "Torrentio"), sources)
    assertTrue(sources.none { it.contains("://") })
  }

  private fun stream(label: String, sizeGb: Long, source: String) = AddonStream(
    name = label,
    behaviorHints = AddonBehaviorHints(videoSize = sizeGb * 1024L * 1024L * 1024L),
    url = "https://stream/$label",
    source = source,
  )
}
