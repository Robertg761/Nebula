package com.stremioshell.host.tv.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingVisibilityGateTest {
  @Test
  fun `repeated visible callback starts only one session`() {
    var starts = 0
    var stops = 0
    val gate = PairingVisibilityGate({ starts++ }, { stops++ })

    gate.onVisible()
    gate.onVisible()

    assertEquals(1, starts)
    assertEquals(0, stops)
  }

  @Test
  fun `repeated hidden callback stops the visible session exactly once`() {
    var starts = 0
    var stops = 0
    val gate = PairingVisibilityGate({ starts++ }, { stops++ })

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
    )

    gate.onVisible()
    gate.onHidden()
    gate.onVisible()

    assertEquals(listOf("start", "stop", "start"), events)
  }

  @Test
  fun `disposing before first resume does not stop a session that never started`() {
    var stops = 0
    val gate = PairingVisibilityGate(startPairing = {}, stopPairing = { stops++ })

    gate.onHidden()

    assertEquals(0, stops)
  }
}
