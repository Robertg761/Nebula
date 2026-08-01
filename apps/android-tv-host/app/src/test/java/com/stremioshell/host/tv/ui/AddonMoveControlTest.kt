package com.stremioshell.host.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where focus goes after a Settings addon row is reordered.
 *
 * The rule is small and the failure it prevents is not: a move that leaves focus on a control the
 * same move disabled ends with a dead D-pad, on a screen whose only other way out is the back
 * button. Kept as a pure function precisely so the edges can be stated here rather than discovered
 * on a device.
 */
class AddonMoveControlTest {
  private companion object {
    const val LAST = 3
  }

  @Test
  fun `focus stays on the control that was pressed`() {
    assertEquals(
      AddonMoveControl.Up,
      addonMoveControl(recovering = true, direction = -1, index = 1, lastIndex = LAST),
    )
    assertEquals(
      AddonMoveControl.Down,
      addonMoveControl(recovering = true, direction = 1, index = 2, lastIndex = LAST),
    )
  }

  @Test
  fun `an addon moved to the top hands focus to the control it has left`() {
    // Up is disabled on the first row, so aiming the recovery at it would strand the remote.
    assertEquals(
      AddonMoveControl.Down,
      addonMoveControl(recovering = true, direction = -1, index = 0, lastIndex = LAST),
    )
  }

  @Test
  fun `an addon moved to the bottom hands focus to the control it has left`() {
    assertEquals(
      AddonMoveControl.Up,
      addonMoveControl(recovering = true, direction = 1, index = LAST, lastIndex = LAST),
    )
  }

  @Test
  fun `every row but the one that moved is left alone`() {
    assertNull(addonMoveControl(recovering = false, direction = -1, index = 0, lastIndex = LAST))
    assertNull(addonMoveControl(recovering = false, direction = 1, index = LAST, lastIndex = LAST))
  }

  @Test
  fun `a list of one has no control to offer`() {
    // Both of its buttons are disabled, so there is nothing here to keep focus on. This is
    // unreachable through the UI - neither control can be pressed - and is asserted so that a
    // future caller cannot be handed a target that would throw on request.
    assertNull(addonMoveControl(recovering = true, direction = -1, index = 0, lastIndex = 0))
    assertNull(addonMoveControl(recovering = true, direction = 1, index = 0, lastIndex = 0))
  }
}
