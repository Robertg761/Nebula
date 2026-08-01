package com.stremioshell.host.tv.player

import kotlin.math.abs

/**
 * How mpv should land a coalesced seek.
 *
 * [Exact] is right for a short step: the target shown in the OSD is then the
 * position playback actually resumes from, even in a file with a long GOP, and
 * over ten seconds the difference between the two is exactly what a viewer
 * correcting a missed line of dialogue is looking for.
 *
 * [Keyframe] is right for a long jump, and for a transient scrub preview. An
 * exact seek makes mpv decode and discard every frame between the preceding
 * keyframe and the target; on a 4K HEVC release with a five-to-ten-second GOP,
 * pulled over a debrid link onto four A55 cores, that is seconds of work spent
 * landing on a frame nobody chose to that precision. See
 * [SeekCoalescer.pendingPrecision] for where the line is drawn.
 */
enum class SeekPrecision(val mpvMode: String) {
  Keyframe("absolute+keyframes"),
  Exact("absolute+exact"),
}

/**
 * One seek handed to mpv.
 *
 * Playback-restart events carry no user data, so callers that can associate a
 * completion with the command should retain this identity and pass it to
 * [SeekCoalescer.settle]. [generation] makes a completion from a file replaced
 * by retry/next unable to retire a seek in the new file; [sequence] separates
 * overlapping seeks within one file.
 */
data class SeekRequest(
  val generation: Long,
  val sequence: Long,
  val targetSec: Double,
  val precision: SeekPrecision,
)

/** What an identity-aware settle did to the current preview. */
enum class SeekSettleResult {
  /** The request was already retired, belonged to an old generation, or was never issued. */
  Ignored,

  /** The request settled, but a newer target still owns the preview. */
  Superseded,

  /** The newest target settled and mpv's reported position can replace the preview. */
  Complete,
}

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
  /**
   * How far a coalesced target must be from the reported position before
   * [pendingPrecision] gives up frame accuracy for a keyframe. Thirty seconds is
   * past every correction (a repeated line, a re-read subtitle) and inside every
   * navigation (skipping a recap or an ad break): a viewer crossing it is looking
   * for a place in the film rather than for a frame, and cannot tell that mpv
   * landed a GOP early — but can very much tell how long the seek took.
   */
  private val keyframeSeekThresholdSec: Double = 30.0,
) {
  private var target = NO_TARGET
  private var pending = false
  private var lastAcceptedMs = Long.MIN_VALUE
  private var generation = 0L
  private var nextSequence = 1L
  private val inFlight = mutableListOf<SeekRequest>()

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
    if (!deltaSec.isFinite() || deltaSec == 0.0) return null
    if (isRepeat && nowMs - lastAcceptedMs < repeatMinIntervalMs) return null
    lastAcceptedMs = nowMs

    val reportedPosition = positionSec.takeIf(Double::isFinite)?.coerceAtLeast(0.0)
      ?: previewSec
      ?: 0.0
    val knownDuration = durationSec.takeIf { it.isFinite() && it > 0.0 }
    val guard = endGuardSec.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    val base = previewSec ?: reportedPosition
    val ceiling = if (knownDuration != null) {
      (knownDuration - guard).coerceAtLeast(0.0)
    } else {
      // One step past mpv's last known position, and never behind an
      // outstanding target: the guard is there to stop a forward seek falling
      // off the end, not to drag a rewind back to where playback happens to be.
      maxOf(reportedPosition + deltaSec, base).coerceAtLeast(0.0)
    }
    target = (base + deltaSec).coerceIn(0.0, ceiling)
    pending = true
    return target
  }

  /**
   * How far the pending target is from [positionSec], and therefore which
   * precision it should be committed with. Read before [consumePendingRequest],
   * whose argument it is meant to be.
   *
   * [positionSec] is mpv's own reported position, so while an earlier seek is
   * still in flight this measures from where mpv last said it was rather than
   * from where it is heading — which is the right distance anyway, because that
   * is the ground the decoder would have to cover.
   *
   * Falls back to [SeekPrecision.Exact] whenever the distance cannot be
   * established: an unaccelerated exact seek is a slow correct answer, and a
   * keyframe seek nobody asked for is a wrong one.
   */
  fun pendingPrecision(positionSec: Double): SeekPrecision {
    val destination = previewSec ?: return SeekPrecision.Exact
    val origin = positionSec.takeIf(Double::isFinite) ?: return SeekPrecision.Exact
    val threshold = keyframeSeekThresholdSec.takeIf { it.isFinite() && it > 0.0 }
      ?: return SeekPrecision.Exact
    return if (abs(destination - origin) > threshold) {
      SeekPrecision.Keyframe
    } else {
      SeekPrecision.Exact
    }
  }

  /**
   * Builds the command to hand to mpv, or null if nothing is waiting. The
   * preview stays live until [settle]: the seek is in flight, not finished.
   *
   * The default is [SeekPrecision.Exact], which is the safe answer for a caller
   * that has not decided. The player passes [pendingPrecision] instead, and a
   * caller intentionally rendering intermediate scrub previews can pass
   * [SeekPrecision.Keyframe] outright.
   */
  fun consumePendingRequest(
    precision: SeekPrecision = SeekPrecision.Exact,
  ): SeekRequest? {
    if (!pending) return null
    pending = false
    val targetSec = target.takeIf { it >= 0 } ?: return null
    val request = SeekRequest(
      generation = generation,
      sequence = nextSequence++,
      targetSec = targetSec,
      precision = precision,
    )
    inFlight += request
    return request
  }

  /**
   * Compatibility form that discards the request's identity and always asks for keyframes.
   *
   * No longer on the player's path: it issues [consumePendingRequest] with
   * [pendingPrecision] and settles by identity. Kept for callers that only want a
   * target, and for the tests that exercise the untagged [settle] against it.
   */
  fun consumePending(): Double? =
    consumePendingRequest(SeekPrecision.Keyframe)?.targetSec

  /**
   * Settles an explicitly identified request.
   *
   * If a newer request has already resumed, it also makes every older request
   * obsolete. This matters when native callbacks arrive out of order: an old
   * completion must not later clear or resurrect state after the newest target
   * has become authoritative.
   */
  fun settle(request: SeekRequest): SeekSettleResult {
    if (request.generation != generation) return SeekSettleResult.Ignored
    val requestIndex = inFlight.indexOfFirst {
      it.generation == request.generation && it.sequence == request.sequence
    }
    if (requestIndex < 0) return SeekSettleResult.Ignored

    // Reaching request N proves every older command in this generation has
    // either completed or been superseded by N.
    inFlight.removeAll {
      it.generation == request.generation && it.sequence <= request.sequence
    }

    val newerIssued = inFlight.any {
      it.generation == generation && it.sequence > request.sequence
    }
    if (pending || newerIssued) return SeekSettleResult.Superseded

    target = NO_TARGET
    return SeekSettleResult.Complete
  }

  /**
   * Compatibility form for an untagged playback-restart callback.
   *
   * Requests are retired in issue order. Crucially, a restart for A no longer
   * clears B merely because B was consumed before A's callback arrived. New
   * integrations should prefer [settle] with the exact [SeekRequest].
   *
   * Returns true only when the newest target was retired and the preview was
   * handed back to mpv, preserving the method's original contract.
   */
  fun settle(): Boolean {
    val oldest = inFlight.firstOrNull() ?: return false
    return settle(oldest) == SeekSettleResult.Complete
  }

  /**
   * Drops everything outstanding, for a reload that starts the stream again from
   * a known position: the target of a seek issued against the file that just
   * died means nothing to the one replacing it, and left in place it would pin
   * the OSD and every saved resume position to a seek that can never settle.
   */
  fun reset() {
    target = NO_TARGET
    pending = false
    inFlight.clear()
    generation++
  }

  private companion object {
    const val NO_TARGET = -1.0
  }
}
