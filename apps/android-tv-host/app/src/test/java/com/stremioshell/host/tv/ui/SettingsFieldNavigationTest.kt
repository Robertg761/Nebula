package com.stremioshell.host.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsFieldNavigationTest {
  @Test
  fun `D-pad up and down escape a Settings field on key down`() {
    assertEquals(
      SettingsFieldKeyAction.NavigateUp,
      settingsFieldKeyAction(Key.DirectionUp, KeyEventType.KeyDown, isEditing = false),
    )
    assertEquals(
      SettingsFieldKeyAction.NavigateDown,
      settingsFieldKeyAction(Key.DirectionDown, KeyEventType.KeyDown, isEditing = true),
    )
  }

  @Test
  fun `center and enter explicitly start text editing from navigation mode`() {
    assertEquals(
      SettingsFieldKeyAction.Edit,
      settingsFieldKeyAction(Key.DirectionCenter, KeyEventType.KeyDown, isEditing = false),
    )
    assertEquals(
      SettingsFieldKeyAction.Edit,
      settingsFieldKeyAction(Key.Enter, KeyEventType.KeyDown, isEditing = false),
    )
    assertEquals(
      SettingsFieldKeyAction.Edit,
      settingsFieldKeyAction(Key.NumPadEnter, KeyEventType.KeyDown, isEditing = false),
    )
  }

  @Test
  fun `hardware enter remains owned by Done or Next while editing`() {
    assertNull(settingsFieldKeyAction(Key.Enter, KeyEventType.KeyDown, isEditing = true))
    assertNull(settingsFieldKeyAction(Key.NumPadEnter, KeyEventType.KeyDown, isEditing = true))
    assertEquals(
      SettingsFieldKeyAction.Edit,
      settingsFieldKeyAction(Key.DirectionCenter, KeyEventType.KeyDown, isEditing = true),
    )
  }

  @Test
  fun `caret keys remain owned by the text field`() {
    assertNull(
      settingsFieldKeyAction(Key.DirectionLeft, KeyEventType.KeyDown, isEditing = false),
    )
    assertNull(
      settingsFieldKeyAction(Key.DirectionRight, KeyEventType.KeyDown, isEditing = true),
    )
  }

  @Test
  fun `key up does not move focus a second time`() {
    assertNull(settingsFieldKeyAction(Key.DirectionUp, KeyEventType.KeyUp, isEditing = false))
    assertNull(settingsFieldKeyAction(Key.DirectionDown, KeyEventType.KeyUp, isEditing = true))
    assertNull(settingsFieldKeyAction(Key.DirectionCenter, KeyEventType.KeyUp, isEditing = false))
  }
}
