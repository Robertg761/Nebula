package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemuxerCacheSizingTest {
  private val mib = 1024L * 1024

  @Test
  fun `low-RAM boxes keep the conservative caps`() {
    // A nominal 1 GB TV stick reports well under its badge.
    val cache = DemuxerCacheSizing.forDeviceRam(900 * mib)

    assertEquals(96 * mib, cache.forwardBytes)
    assertEquals(32 * mib, cache.backBytes)
  }

  @Test
  fun `a nominal 2 GB device lands in the middle band despite kernel reservation`() {
    val cache = DemuxerCacheSizing.forDeviceRam(1_890 * mib)

    assertEquals(192 * mib, cache.forwardBytes)
  }

  @Test
  fun `a nominal 4 GB device gets the largest caps`() {
    val cache = DemuxerCacheSizing.forDeviceRam(3_700 * mib)

    assertEquals(256 * mib, cache.forwardBytes)
  }

  @Test
  fun `the thresholds are inclusive at their lower edge`() {
    assertEquals(
      192 * mib,
      DemuxerCacheSizing.forDeviceRam(DemuxerCacheSizing.MEDIUM_RAM_BYTES).forwardBytes,
    )
    assertEquals(
      96 * mib,
      DemuxerCacheSizing.forDeviceRam(DemuxerCacheSizing.MEDIUM_RAM_BYTES - 1).forwardBytes,
    )
    assertEquals(
      256 * mib,
      DemuxerCacheSizing.forDeviceRam(DemuxerCacheSizing.LARGE_RAM_BYTES).forwardBytes,
    )
    assertEquals(
      192 * mib,
      DemuxerCacheSizing.forDeviceRam(DemuxerCacheSizing.LARGE_RAM_BYTES - 1).forwardBytes,
    )
  }

  @Test
  fun `an unreadable total falls back to the caps that are safe everywhere`() {
    assertEquals(96 * mib, DemuxerCacheSizing.forDeviceRam(0L).forwardBytes)
    assertEquals(96 * mib, DemuxerCacheSizing.forDeviceRam(-1L).forwardBytes)
  }

  @Test
  fun `every band keeps the back cache well under the forward cache`() {
    val totals = listOf(0L, 900 * mib, 1_890 * mib, 3_700 * mib, 8_000 * mib)

    for (total in totals) {
      val cache = DemuxerCacheSizing.forDeviceRam(total)
      assertTrue("$total", cache.backBytes < cache.forwardBytes / 2)
      assertTrue("$total", cache.backBytes > 0)
    }
  }

  @Test
  fun `caps never shrink as RAM grows`() {
    var previous = 0L
    for (gib in 1..8) {
      val forward = DemuxerCacheSizing.forDeviceRam(gib * 1024L * mib).forwardBytes
      assertTrue("$gib GiB", forward >= previous)
      previous = forward
    }
  }

  /**
   * The whole point of the change: at UHD remux bitrates the byte cap, not
   * `cache-secs`, is what decides how much readahead there is.
   */
  @Test
  fun `a mid-range box buffers meaningfully more of a 70 Mbps remux`() {
    val bytesPerSec = 70_000_000L / 8

    val small = DemuxerCacheSizing.forDeviceRam(900 * mib).forwardBytes / bytesPerSec
    val large = DemuxerCacheSizing.forDeviceRam(3_700 * mib).forwardBytes / bytesPerSec

    assertEquals(11L, small)
    assertTrue("$large", large >= 30L)
  }
}
