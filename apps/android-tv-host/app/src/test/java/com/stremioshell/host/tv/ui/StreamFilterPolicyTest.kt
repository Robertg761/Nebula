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

  /**
   * The lazy-list keys exist for the debrid case: one resolved URL under several labels. A filter
   * that removes the first duplicate must not renumber the survivors - a renumbered key makes the
   * list reuse a node for a different stream and moves focus by position instead of identity.
   */
  @Test
  fun `duplicate-URL row keys survive filtering unrenumbered`() {
    val sharedUrl = "https://stream/resolved"
    val uncachedDv = stream("2160p DV", 20, "Comet").copy(url = sharedUrl)
    val cachedHdr = stream("[RD+] 1080p HDR 4 GB", 4, "Comet").copy(url = sharedUrl)
    val cachedSdr = stream("[RD+] 1080p 4 GB", 4, "Comet").copy(url = sharedUrl)
    val rated = StreamFilterPolicy.rate(listOf(uncachedDv, cachedHdr, cachedSdr))

    val keysBefore = releaseKeysByName(rated)
    val filtered = StreamFilterPolicy.applyRated(
      rated,
      StreamFilters.SHOW_ALL.copy(availability = StreamAvailability.Instant),
    )
    val keysAfter = releaseKeysByName(filtered)

    assertEquals(listOf(cachedHdr, cachedSdr), filtered.map(RatedStream::stream))
    assertEquals(keysBefore.getValue(cachedHdr.name!!), keysAfter.getValue(cachedHdr.name!!))
    assertEquals(keysBefore.getValue(cachedSdr.name!!), keysAfter.getValue(cachedSdr.name!!))
    assertEquals(keysBefore.size, keysBefore.values.distinct().size)
  }

  @Test
  fun `a URL that ends in a hash suffix cannot forge a duplicate's key`() {
    val duplicated = "https://stream/resolved"
    val forged = stream("1080p", 4, "Comet").copy(url = "$duplicated#1")
    val first = stream("2160p", 20, "Comet").copy(url = duplicated)
    val second = stream("720p", 1, "Comet").copy(url = duplicated)
    val keys = releaseKeysByName(StreamFilterPolicy.rate(listOf(first, second, forged)))

    assertEquals(keys.size, keys.values.distinct().size)
  }

  private fun releaseKeysByName(rated: List<RatedStream>): Map<String, String> =
    StreamPresentation.rows(rated)
      .filterIsInstance<StreamListItem.Release>()
      .associate { it.stream.name!! to it.key }

  private fun stream(label: String, sizeGb: Long, source: String) = AddonStream(
    name = label,
    behaviorHints = AddonBehaviorHints(videoSize = sizeGb * 1024L * 1024L * 1024L),
    url = "https://stream/$label",
    source = source,
  )
}
