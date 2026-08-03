package com.stremioshell.host.tv.pairing

/**
 * Idempotently pairs a visible Pair screen with one live pairing session.
 *
 * Lifecycle callbacks can be repeated during rapid pause/resume or composition disposal. Keeping
 * duplicate callbacks out of the ViewModel calls gives every visible transition exactly one start
 * and every matching hidden transition exactly one stop.
 */
internal class PairingVisibilityGate(
  private val startPairing: () -> Unit,
  private val stopPairing: () -> Unit,
) {
  private var visible = false

  fun onVisible() {
    if (visible) return
    visible = true
    startPairing()
  }

  fun onHidden() {
    if (!visible) return
    visible = false
    stopPairing()
  }
}
