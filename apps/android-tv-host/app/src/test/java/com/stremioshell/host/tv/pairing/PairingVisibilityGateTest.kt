package com.stremioshell.host.tv.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingVisibilityGateTest {
  @Test
  fun `repeated visible callback starts only one session`() {
    var starts = 0
    var stops = 0
    val gate = PairingVisibilityGate({ starts++ }, { stops++ }, confirmationShowing = { false })

    gate.onVisible()
    gate.onVisible()

    assertEquals(1, starts)
    assertEquals(0, stops)
  }

  @Test
  fun `repeated hidden callback stops the visible session exactly once`() {
    var starts = 0
    var stops = 0
    val gate = PairingVisibilityGate({ starts++ }, { stops++ }, confirmationShowing = { false })

    gate.onVisible()
    gate.onHidden()
    gate.onHidden()

    assertEquals(1, starts)
    assertEquals(1, stops)
  }

  @Test
  fun `resume after stop opens a new paired session`() {
    val events = mutableListOf<String>()
    val gate = PairingVisibilityGate(
      startPairing = { events += "start" },
      stopPairing = { events += "stop" },
      confirmationShowing = { false },
    )

    gate.onVisible()
    gate.onHidden()
    gate.onVisible()

    assertEquals(listOf("start", "stop", "start"), events)
  }

  @Test
  fun `disposing before first resume does not stop a session that never started`() {
    var stops = 0
    val gate = PairingVisibilityGate(
      startPairing = {},
      stopPairing = { stops++ },
      confirmationShowing = { false },
    )

    gate.onHidden()

    assertEquals(0, stops)
  }

  @Test
  fun `a screensaver over a finished pairing stops the server but does not mint a new one`() {
    // The reported failure: HOME or the screensaver over a "Saved to your TV" screen came back as
    // a fresh QR, so the viewer re-entered credentials the TV had already stored.
    val events = mutableListOf<String>()
    var confirmed = false
    val gate = PairingVisibilityGate(
      startPairing = { events += "start" },
      stopPairing = { events += "stop" },
      confirmationShowing = { confirmed },
    )

    gate.onVisible()
    confirmed = true
    gate.onHidden()
    gate.onVisible()

    // The one-shot token still dies with the hidden screen; only the confirmation survives.
    assertEquals(listOf("start", "stop"), events)
  }

  @Test
  fun `a second pause after the confirmation has nothing left to stop`() {
    val events = mutableListOf<String>()
    val gate = PairingVisibilityGate(
      startPairing = { events += "start" },
      stopPairing = { events += "stop" },
      confirmationShowing = { true },
    )

    gate.onVisible()
    gate.onHidden()
    gate.onVisible()
    gate.onHidden()

    assertEquals(emptyList<String>(), events)
  }
}
