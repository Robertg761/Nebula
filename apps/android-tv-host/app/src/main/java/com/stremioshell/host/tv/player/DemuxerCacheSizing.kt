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
   * Back cache is roughly a third of the forward cache: it only serves short
   * backward seeks, and every byte it holds is one the readahead cannot use.
   *
   * The top band is deliberately not the largest number the arithmetic allows.
   * The devices that reach it are 3–4 GiB TV boxes — a Google TV Streamer reports
   * about 3.7 GiB — which are also running a launcher, a recommendations service
   * and the system UI in the same memory. 341 MiB of native demuxer state on top
   * of a 4K decoder's own buffers was the largest single allocation in the
   * process, and the cost of overshooting here is not a stall but an LMK kill
   * mid-film. 192 MiB is still about 22 seconds of a 70 Mbps remux, well past any
   * hiccup the reconnect options cannot ride out; the back cache gives up
   * proportionally more because a backward seek that misses it costs one range
   * request, while a forward stall costs the viewer a frozen picture.
   *
   * The middle band scales the same LMK argument down: a nominal 2 GB box
   * reports ~1.85 GiB and shares it with the same launcher and system UI, so it
   * gets proportionally less than the top band, never more — the bands must
   * stay monotonic in RAM or the boxes with the least headroom hold the most
   * native cache. 128 MiB is still ~15 seconds of a 70 Mbps remux, and the
   * streams such boxes actually play are far below that bitrate.
   */
  fun forDeviceRam(totalRamBytes: Long): DemuxerCacheBytes = when {
    totalRamBytes >= LARGE_RAM_BYTES -> DemuxerCacheBytes(192 * MIB, 48 * MIB)
    totalRamBytes >= MEDIUM_RAM_BYTES -> DemuxerCacheBytes(128 * MIB, 40 * MIB)
    // Also where an unreadable total lands (0 or negative): the small caps are
    // the ones that are safe everywhere.
    else -> DemuxerCacheBytes(96 * MIB, 32 * MIB)
  }
}
