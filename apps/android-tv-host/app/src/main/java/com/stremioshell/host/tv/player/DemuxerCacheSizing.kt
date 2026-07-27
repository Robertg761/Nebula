package com.stremioshell.host.tv.player

/** Byte caps handed to mpv's demuxer cache. */
data class DemuxerCacheBytes(val forwardBytes: Long, val backBytes: Long)

/**
 * Picks the demuxer cache byte caps for a device.
 *
 * `cache-secs` is meant to decide the working set, but the byte cap is a hard
 * ceiling that wins whenever the bitrate is high enough: a 96 MiB forward cap on
 * a 70 Mbps UHD remux is about eleven seconds of readahead, so a stream that is
 * supposed to be able to ride out two minutes of wobble stalls on any hiccup
 * longer than a breath. Bigger caps are only safe where there is RAM to spare —
 * this is native memory, outside the Java heap, and a low-RAM TV box gets killed
 * for holding too much of it.
 */
object DemuxerCacheSizing {
  private const val MIB = 1024L * 1024

  /**
   * Thresholds read against `ActivityManager.MemoryInfo.totalMem`, which is
   * always somewhat under a device's nominal RAM (the kernel keeps a slice), so
   * they sit below the round numbers they stand for: a nominal 2 GB box reports
   * about 1.85 GiB and must still land in the middle band.
   */
  const val MEDIUM_RAM_BYTES = 1_536L * MIB // 1.5 GiB
  const val LARGE_RAM_BYTES = 3_072L * MIB // 3 GiB

  /**
   * Back cache is a third of the forward cache throughout: it only serves short
   * backward seeks, and every byte it holds is one the readahead cannot use.
   */
  fun forDeviceRam(totalRamBytes: Long): DemuxerCacheBytes = when {
    totalRamBytes >= LARGE_RAM_BYTES -> DemuxerCacheBytes(256 * MIB, 85 * MIB)
    totalRamBytes >= MEDIUM_RAM_BYTES -> DemuxerCacheBytes(192 * MIB, 64 * MIB)
    // Also where an unreadable total lands (0 or negative): the small caps are
    // the ones that are safe everywhere.
    else -> DemuxerCacheBytes(96 * MIB, 32 * MIB)
  }
}
