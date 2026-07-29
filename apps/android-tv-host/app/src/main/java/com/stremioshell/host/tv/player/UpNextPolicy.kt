package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamFetch

/**
 * Whether the end of an episode turns into the next one, and how hard.
 *
 * The countdown is the whole point of a binge loop, but it must never be the
 * thing that decides an evening is over: a viewer who is at the controls when
 * the credits roll - paused on the last frame, or having just pressed something
 * - gets the card without a clock, so nothing starts playing while they are
 * reaching for the remote. Everyone else gets the countdown, which is what turns
 * a finished episode into the next one with no press at all.
 */
object UpNextPolicy {
  /** How long the card counts down before the next episode starts on its own. */
  const val COUNTDOWN_MS = 15_000L

  /**
   * A press this recently means the viewer is watching with the remote in hand,
   * so the next episode waits to be asked for. Deliberately longer than the
   * OSD's own timeout: reaching the end a few seconds after a seek into the
   * credits is exactly the case that must not autoplay.
   */
  const val RECENT_INTERACTION_MS = 20_000L

  sealed interface Offer {
    /** Nothing to move on to; the player exits as it always did. */
    data object None : Offer

    /** Card with a clock: expiry plays the next episode. */
    data class Countdown(val totalMs: Long) : Offer

    /** Card without a clock: the next episode only plays if it is asked for. */
    data object Prompt : Offer
  }

  fun offer(hasNext: Boolean, paused: Boolean, msSinceInteractionMs: Long): Offer = when {
    !hasNext -> Offer.None
    paused || msSinceInteractionMs < RECENT_INTERACTION_MS -> Offer.Prompt
    else -> Offer.Countdown(COUNTDOWN_MS)
  }

  /**
   * Whole seconds the card shows. Rounded up so the countdown opens on the full
   * number rather than one less, and reaches zero only when the time is up.
   *
   * Integer division avoids losing precision for very long durations. A
   * negative elapsed value can happen when a restored monotonic timestamp came
   * from a previous process; treating it as zero is safer than drawing a bar
   * wider than its track.
   */
  fun secondsLeft(elapsedMs: Long, totalMs: Long): Int {
    val remainingMs = remainingMs(elapsedMs, totalMs)
    val roundedUp = remainingMs / 1_000L + if (remainingMs % 1_000L == 0L) 0L else 1L
    return roundedUp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  }

  /**
   * Smooth countdown-bar progress in the closed 0..1 range.
   *
   * Kept beside [secondsLeft] and [isDue] so all three views of the same timer
   * agree at negative, zero-length, exact-end and overrun boundaries.
   */
  fun progressRemaining(elapsedMs: Long, totalMs: Long): Float {
    if (totalMs <= 0L) return 0f
    if (elapsedMs <= 0L) return 1f
    if (elapsedMs >= totalMs) return 0f
    return ((totalMs - elapsedMs).toDouble() / totalMs.toDouble()).toFloat()
  }

  fun isDue(elapsedMs: Long, totalMs: Long): Boolean =
    totalMs <= 0L || elapsedMs >= totalMs

  private fun remainingMs(elapsedMs: Long, totalMs: Long): Long = when {
    totalMs <= 0L -> 0L
    elapsedMs <= 0L -> totalMs
    elapsedMs >= totalMs -> 0L
    else -> totalMs - elapsedMs
  }
}

/**
 * What an automatic next-episode stream lookup means for the player.
 *
 * A healthy lookup with no release belongs in the picker. A lookup where every
 * configured addon failed does not: handing that result to the picker discards
 * the failure and makes the viewer navigate away merely to press Retry.
 */
sealed interface UpNextStreamResolution {
  data class Ready(val stream: AddonStream) : UpNextStreamResolution
  data object NeedsPicker : UpNextStreamResolution
  data class Retry(val message: String) : UpNextStreamResolution
}

object UpNextStreamPolicy {
  const val DEFAULT_RETRY_MESSAGE = "Couldn't reach your stream addons."

  fun classify(fetch: StreamFetch, picked: AddonStream?): UpNextStreamResolution {
    if (fetch.merged.allFailed) {
      return UpNextStreamResolution.Retry(
        fetch.merged.notice?.takeIf { it.isNotBlank() } ?: DEFAULT_RETRY_MESSAGE,
      )
    }
    val playable = picked?.takeIf { !it.url.isNullOrBlank() }
      ?: return UpNextStreamResolution.NeedsPicker
    return UpNextStreamResolution.Ready(playable)
  }
}
