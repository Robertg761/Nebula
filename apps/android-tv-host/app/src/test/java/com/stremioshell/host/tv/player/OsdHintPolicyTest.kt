package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsdHintPolicyTest {
  @Test
  fun `a fresh install is taught`() {
    assertTrue(OsdHintPolicy.showsHint(0))
  }

  @Test
  fun `the legend retires once it has had its run`() {
    assertTrue(OsdHintPolicy.showsHint(OsdHintPolicy.OPENS_WITH_HINT - 1))
    assertFalse(OsdHintPolicy.showsHint(OsdHintPolicy.OPENS_WITH_HINT))
  }

  @Test
  fun `counting walks up to the limit and stops there`() {
    var opens = 0
    repeat(OsdHintPolicy.OPENS_WITH_HINT) { opens = OsdHintPolicy.advance(opens) }
    assertEquals(OsdHintPolicy.OPENS_WITH_HINT, opens)
    assertFalse(OsdHintPolicy.showsHint(opens))
  }

  /**
   * A viewer who watches a thousand films should not carry a five-digit preference, and a stored
   * value that kept climbing would make the saturation point meaningless.
   */
  @Test
  fun `the stored count saturates rather than growing forever`() {
    assertEquals(OsdHintPolicy.OPENS_WITH_HINT, OsdHintPolicy.advance(OsdHintPolicy.OPENS_WITH_HINT))
    assertEquals(OsdHintPolicy.OPENS_WITH_HINT, OsdHintPolicy.advance(9_999))
  }

  /**
   * A corrupted or hand-edited preference must not resurrect the legend forever. The naive
   * `(n + 1).coerceAtMost(limit)` overflows to Int.MIN_VALUE here, which is below the limit and so
   * would show the hint on every open from then on.
   */
  @Test
  fun `an absurd stored value cannot wrap negative and resurrect the hint`() {
    assertEquals(OsdHintPolicy.OPENS_WITH_HINT, OsdHintPolicy.advance(Int.MAX_VALUE))
    assertFalse(OsdHintPolicy.showsHint(OsdHintPolicy.advance(Int.MAX_VALUE)))
  }

  /** The other end of the same class of corruption. */
  @Test
  fun `a negative stored value is treated as a fresh install, not skipped`() {
    assertTrue(OsdHintPolicy.showsHint(-1))
    assertEquals(0, OsdHintPolicy.advance(-1))
  }
}
