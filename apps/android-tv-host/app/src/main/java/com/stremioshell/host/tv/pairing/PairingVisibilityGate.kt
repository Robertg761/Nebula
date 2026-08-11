package com.stremioshell.host.tv.pairing

/**
 * Idempotently pairs a visible Pair screen with one live pairing session.
 *
 * Lifecycle callbacks can be repeated during rapid pause/resume or composition disposal. Keeping
 * duplicate callbacks out of the ViewModel calls gives every visible transition exactly one start
 * and every matching hidden transition exactly one stop.
 *
 * The one asymmetry is deliberate. A pause always stops: the one-shot token was published in a QR
 * code on a screen that is no longer being watched, so it dies with the screen and is never
 * revived. But a resume does not always start, because the screensaver coming on over a finished
 * pairing used to throw the confirmation away and mint a fresh QR - so the viewer, who had already
 * typed their keys into their phone and seen "Saved to your TV", came back to a screen that showed
 * no evidence of it and asked them to do it again. [confirmationShowing] is that state: while it
 * holds, a resume leaves the confirmation exactly where it was, and the screen only moves on when
 * the viewer leaves it or explicitly starts a new attempt.
 */
internal class PairingVisibilityGate(
  private val startPairing: () -> Unit,
  private val stopPairing: () -> Unit,
  private val confirmationShowing: () -> Boolean,
) {
  private var visible = false
  private var sessionOpen = false

  fun onVisible() {
    if (visible) return
    visible = true
    // Nothing to bind: the phone has already been answered and the receipt is on screen.
    if (confirmationShowing()) return
    sessionOpen = true
    startPairing()
  }

  fun onHidden() {
    if (!visible) return
    visible = false
    if (!sessionOpen) return
    sessionOpen = false
    stopPairing()
  }
}
