package com.stremioshell.host.tv.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PairScreenPolicyTest {
  @Test
  fun `540dp viewport reserves one full panel height for scrollable instructions`() {
    assertEquals(308.dp, pairInstructionPaneMaxHeight(540.dp))
  }

  @Test
  fun `larger viewport does not let result rows stretch the card beyond the qr panel`() {
    assertEquals(308.dp, pairInstructionPaneMaxHeight(1_080.dp))
  }

  @Test
  fun `smaller viewport shrinks the instruction pane instead of growing past the screen`() {
    assertEquals(248.dp, pairInstructionPaneMaxHeight(480.dp))
  }

  @Test
  fun `dpad up pages a long result pane toward its first checks`() {
    assertEquals(
      369,
      pairDpadScrollTarget(
        current = 600,
        max = 600,
        viewport = 308,
        direction = PairScrollDirection.Up,
      ),
    )
    assertEquals(
      0,
      pairDpadScrollTarget(
        current = 100,
        max = 600,
        viewport = 308,
        direction = PairScrollDirection.Up,
      ),
    )
  }

  @Test
  fun `dpad down returns from earlier checks to the focused action end`() {
    assertEquals(
      600,
      pairDpadScrollTarget(
        current = 500,
        max = 600,
        viewport = 308,
        direction = PairScrollDirection.Down,
      ),
    )
  }

  @Test
  fun `dpad scroll is not swallowed when the pane cannot move`() {
    assertEquals(
      null,
      pairDpadScrollTarget(
        current = 0,
        max = 0,
        viewport = 308,
        direction = PairScrollDirection.Up,
      ),
    )
    assertEquals(
      null,
      pairDpadScrollTarget(
        current = 0,
        max = 600,
        viewport = 308,
        direction = PairScrollDirection.Up,
      ),
    )
  }
}
