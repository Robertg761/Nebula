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
 *
 * The fraction is the whole rule. There used to be an absolute "within five
 * seconds of the end" guard beside it, described as covering a container that
 * understates its duration by a second or two — but a position that overruns an
 * understated duration is past 100% of it, which the fraction already catches,
 * and the guard was additionally held back from ever firing earlier than the
 * fraction (so that a six-second clip could not be called watched one second in).
 * Those two together made it unreachable for every duration: at 50 seconds and
 * above `duration - 5` is inside the last tenth, and below 50 seconds the guard
 * was skipped. It is gone rather than repaired; the five-second stand-off that
 * remains real is [SeekCoalescer.END_GUARD_SEC], which is about not seeking into
 * eof and does not (and cannot) keep a video below this threshold.
 */
object WatchedThreshold {
  /** Fraction of the runtime that counts as having watched the whole thing. */
  const val FINISHED_FRACTION = 0.90

  fun isFinished(positionSec: Double, durationSec: Double): Boolean {
    // Nothing can be established without a duration, so such a stream is always
    // treated as stopped short and stays resumable. This is also what keeps a
    // save taken before mpv has reported a duration - the first seconds of every
    // file - out of the watched state.
    if (!durationSec.isFinite() || !positionSec.isFinite() || durationSec <= 0 || positionSec < 0) {
      return false
    }
    return positionSec / durationSec >= FINISHED_FRACTION
  }

  fun isFinishedMs(positionMs: Long, durationMs: Long): Boolean =
    isFinished(positionMs / 1000.0, durationMs / 1000.0)
}
