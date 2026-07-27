package com.stremioshell.host.tv.player

import kotlin.math.ceil

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
   */
  fun secondsLeft(elapsedMs: Long, totalMs: Long): Int =
    ceil((totalMs - elapsedMs).coerceAtLeast(0L) / 1000.0).toInt()

  fun isDue(elapsedMs: Long, totalMs: Long): Boolean = elapsedMs >= totalMs
}
