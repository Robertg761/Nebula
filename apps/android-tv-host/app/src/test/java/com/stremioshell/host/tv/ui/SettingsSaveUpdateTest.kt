package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.SettingsSaveUpdate
import com.stremioshell.host.tv.SettingsSaveOperation
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

  @Test
  fun partialCrossStoreSaveKeepsTheViewerOnSettingsForRecovery() {
    val update = SettingsSaveUpdate.Partial("configuration saved; languages unconfirmed")

    assertEquals("configuration saved; languages unconfirmed", update.message)
    assertFalse(update.completionSuccess()!!)
    assertFalse(SettingsSaveOperation(requestId = 1, update = update).running)
  }

  @Test
  fun operationRemainsRunningAfterPersistenceUntilProbeFinishes() {
    assertTrue(SettingsSaveOperation(requestId = 1).running)
    assertTrue(
      SettingsSaveOperation(
        requestId = 1,
        update = SettingsSaveUpdate.Persisted("saved"),
      ).running,
    )
    assertFalse(
      SettingsSaveOperation(
        requestId = 1,
        update = SettingsSaveUpdate.Complete("connected"),
      ).running,
    )
    assertFalse(
      SettingsSaveOperation(
        requestId = 1,
        update = SettingsSaveUpdate.Failed("failed"),
      ).running,
    )
  }
}
