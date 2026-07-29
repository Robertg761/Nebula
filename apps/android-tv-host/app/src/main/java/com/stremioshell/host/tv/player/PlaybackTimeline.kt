package com.stremioshell.host.tv.player

/**
 * The "how much is left" arithmetic behind the OSD, kept out of the composable
 * so the awkward cases — no duration yet, a position past a duration the
 * container understated, a non-1.0 playback speed — are testable.
 */
object PlaybackTimeline {
  /**
   * Wall-clock seconds left at [speed], or null when the duration is unknown
   * (mpv reports 0 until it has one, and never for some live streams).
   */
  fun remainingSec(positionSec: Double, durationSec: Double, speed: Double = 1.0): Double? {
    if (!durationSec.isFinite() || durationSec <= 0 || !positionSec.isFinite()) return null
    // A position past the duration is routine on VBR containers whose estimate
    // runs short; it means "about to end", not a negative countdown.
    val left = (durationSec - positionSec.coerceAtLeast(0.0)).coerceAtLeast(0.0)
    val factor = if (speed.isFinite() && speed > 0) speed else 1.0
    return left / factor
  }

  /** When playback would reach the end, for the "Ends at" clock. */
  fun endsAtEpochMs(nowEpochMs: Long, remainingSec: Double): Long {
    if (remainingSec.isNaN() || remainingSec <= 0.0) return nowEpochMs
    if (remainingSec == Double.POSITIVE_INFINITY) return Long.MAX_VALUE
    val deltaMs = (remainingSec * 1000.0)
      .coerceAtMost(Long.MAX_VALUE.toDouble())
      .toLong()
    return if (deltaMs > 0 && nowEpochMs > Long.MAX_VALUE - deltaMs) {
      Long.MAX_VALUE
    } else {
      nowEpochMs + deltaMs
    }
  }
}
