package com.stremioshell.host.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NebulaButtonFocusPolicyTest {
  @Test
  fun `disabled edge actions are removed from D-pad focus search`() {
    assertTrue(
      NebulaButtonFocusPolicy.canFocus(enabled = true, focusableWhenDisabled = false),
    )
    assertFalse(
      NebulaButtonFocusPolicy.canFocus(enabled = false, focusableWhenDisabled = false),
    )
  }

  @Test
  fun `ordinary buttons can retain focus while a press temporarily disables them`() {
    assertTrue(
      NebulaButtonFocusPolicy.canFocus(enabled = false, focusableWhenDisabled = true),
    )
  }
}
