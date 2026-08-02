package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.SettingsSaveUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSaveUpdateTest {
  @Test
  fun persistenceFailureIsTerminalAndUnsuccessful() {
    val update = SettingsSaveUpdate.Failed("write failed")

    assertEquals("write failed", update.message)
    assertFalse(update.completionSuccess()!!)
  }

  @Test
  fun persistedUpdateDoesNotCompletePendingNavigation() {
    assertNull(SettingsSaveUpdate.Persisted("saved").completionSuccess())
  }

  @Test
  fun completedProbeFinishesPendingNavigationSuccessfully() {
    assertTrue(SettingsSaveUpdate.Complete("connected").completionSuccess()!!)
  }
}
