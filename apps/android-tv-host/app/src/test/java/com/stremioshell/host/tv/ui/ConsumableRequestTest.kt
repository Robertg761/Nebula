package com.stremioshell.host.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumableRequestTest {
  @Test
  fun `handling the current request consumes it`() {
    assertEquals(0, consumeMatchingRequest(current = 7, handled = 7))
  }

  @Test
  fun `a stale acknowledgement cannot consume a newer request`() {
    assertEquals(8, consumeMatchingRequest(current = 8, handled = 7))
  }
}
