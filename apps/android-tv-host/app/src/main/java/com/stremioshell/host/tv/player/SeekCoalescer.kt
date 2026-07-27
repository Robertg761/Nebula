package com.stremioshell.host.tv.player

/**
 * Collapses a burst of D-pad presses into one seek target.
 *
 * Issuing a seek per key event makes mpv flush the demuxer and re-request the
 * byte range every time, which stalls hard on a network stream — and a held key
 * repeats about twenty times a second. Presses accumulate into a single target
 * instead, which the player commits once the remote goes quiet.
 *
 * Main-thread only: called from key handling and from main-thread mpv callbacks.
 */
class SeekCoalescer(
  /**
   * How far short of the end a forward seek stops. Seeking into eof ends the
   * session and marks the video watched, which is not what a seek should do.
   */
  private val endGuardSec: Double = 5.0,
  /** Floor on the interval between accepted repeats, so held keys scrub at a sane rate. */
  private val repeatMinIntervalMs: Long = 120L,
) {
  private var target = NO_TARGET
  private var pending = false
  private var lastAcceptedMs = Long.MIN_VALUE

  /**
   * Whether the current [target] has been handed to mpv and not yet settled.
   * Together with [pending] this identifies which seek a settle event can
   * possibly belong to: only a target that is in flight and has had no press
   * folded into it since can be retired by an incoming restart.
   */
  private var inFlight = false

  /**
   * Where an outstanding seek is heading, or null when the position should come
   * from mpv. Non-null from the first press until [settle], so the OSD tracks
   * the remote while mpv is still reporting the old position.
   *
   * This is also the position a resume entry must record while a seek is
   * outstanding: mpv's `time-pos` still reports the pre-seek position, so saving
   * that would rewind the viewer to where they seeked away from.
   */
  val previewSec: Double?
    get() = target.takeIf { it >= 0 }

  /** True while a press has been folded in but not yet handed to mpv. */
  val hasPendingPress: Boolean
    get() = pending

  /**
   * Folds a press into the target. [positionSec] is mpv's reported position,
   * used only when no seek is already outstanding. Returns the new target, or
   * null if the press was dropped as too fast a repeat.
   *
   * With [durationSec] unknown (0 until mpv reports it, and forever on a stream
   * that never does) the target stays within one step of [positionSec]: presses
   * still seek, but a burst cannot pile up into a jump past an end nobody knows
   * the position of. Each press advances again once mpv reports the position it
   * landed at, so a held key still walks forward, one settled seek at a time.
   */
  fun press(
    deltaSec: Double,
    positionSec: Double,
    durationSec: Double,
    isRepeat: Boolean,
    nowMs: Long,
  ): Double? {
    if (isRepeat && nowMs - lastAcceptedMs < repeatMinIntervalMs) return null
    lastAcceptedMs = nowMs

    val base = previewSec ?: positionSec
    val ceiling = if (durationSec > 0) {
      (durationSec - endGuardSec).coerceAtLeast(0.0)
    } else {
      // One step past mpv's last known position, and never behind an
      // outstanding target: the guard is there to stop a forward seek falling
      // off the end, not to drag a rewind back to where playback happens to be.
      maxOf(positionSec + deltaSec, base).coerceAtLeast(0.0)
    }
    target = (base + deltaSec).coerceIn(0.0, ceiling)
    pending = true
    return target
  }

  /**
   * The target to hand to mpv, or null if nothing is waiting. The preview stays
   * live until [settle]: the seek is in flight, not finished.
   */
  fun consumePending(): Double? {
    if (!pending) return null
    pending = false
    inFlight = true
    return target.takeIf { it >= 0 }
  }

  /**
   * Playback has restarted, so mpv's position is trustworthy again — but only for
   * the seek this restart belongs to. Returns true when the target was retired.
   *
   * A restart cannot be attributed to a target that has had a press folded into
   * it since it was issued, nor to one that was never issued at all: initial load
   * and cache-stall recovery raise restarts too. Clearing in those cases threw
   * the press away and snapped the OSD back to the pre-seek position.
   */
  fun settle(): Boolean {
    if (pending || !inFlight) return false
    target = NO_TARGET
    inFlight = false
    return true
  }

  private companion object {
    const val NO_TARGET = -1.0
  }
}
