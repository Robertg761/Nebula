package com.stremioshell.host.tv.player

/**
 * Where "watched" starts.
 *
 * A viewer who sits through the closing credits is rare, so a threshold set at
 * the very end of the runtime marks almost nothing as watched: end credits,
 * post-credit stings and a next-episode teaser routinely account for the last
 * 5-10% of an episode, and every competitor counts that as finished. Below the
 * threshold a position survives as a resume point; at or above it the video
 * counts as watched and the resume point goes.
 *
 * The looser fraction has one cost worth naming: a truncated debrid/torrent file
 * whose container still claims the full runtime hits eof early, and one that
 * runs out inside the last tenth is now called finished rather than broken. That
 * is the trade for not asking viewers to sit through credits, and a stream that
 * stops well short of its claimed length is still resumable.
 */
object WatchedThreshold {
  /**
   * Absolute guard for a duration a container understates by a second or two
   * (VBR estimates), where the fraction alone would never be reached.
   */
  const val END_GUARD_SEC = 5.0

  /** Fraction of the runtime that counts as having watched the whole thing. */
  const val FINISHED_FRACTION = 0.90

  fun isFinished(positionSec: Double, durationSec: Double): Boolean {
    // Nothing can be established without a duration, so such a stream is always
    // treated as stopped short and stays resumable.
    if (!durationSec.isFinite() || !positionSec.isFinite() || durationSec <= 0 || positionSec < 0) {
      return false
    }
    // Never let the absolute guard move the threshold earlier than the watched
    // fraction. Without this, not only a one-second clip but also a six-second
    // clip (threshold 1s) can be marked watched almost immediately.
    val fractionThresholdSec = durationSec * FINISHED_FRACTION
    val guardThresholdSec = durationSec - END_GUARD_SEC
    if (
      guardThresholdSec >= fractionThresholdSec &&
      positionSec >= guardThresholdSec
    ) {
      return true
    }
    return positionSec / durationSec >= FINISHED_FRACTION
  }

  fun isFinishedMs(positionMs: Long, durationMs: Long): Boolean =
    isFinished(positionMs / 1000.0, durationMs / 1000.0)
}
