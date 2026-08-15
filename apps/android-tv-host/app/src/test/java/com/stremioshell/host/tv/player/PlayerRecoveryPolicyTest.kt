package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRecoveryPolicyTest {
  @Test
  fun `an invalid descriptor cannot retry the same unsafe launch`() {
    val actions = PlayerRecoveryPolicy.actions(PlayerRecoveryKind.InvalidDescriptor)

    assertEquals(PlayerRecoveryAction.ChooseAnotherRelease, actions.first())
    assertFalse(actions.contains(PlayerRecoveryAction.Retry))
    assertTrue(actions.contains(PlayerRecoveryAction.Back))
    assertTrue(actions.contains(PlayerRecoveryAction.ShareDiagnostics))
  }

  @Test
  fun `transient native startup failures lead with retry`() {
    listOf(
      PlayerRecoveryKind.NativeCoreBusy,
      PlayerRecoveryKind.InitializationFailed,
    ).forEach { kind ->
      val actions = PlayerRecoveryPolicy.actions(kind)

      assertEquals(PlayerRecoveryAction.Retry, actions.first())
      assertTrue(actions.contains(PlayerRecoveryAction.Back))
      assertTrue(actions.contains(PlayerRecoveryAction.ShareDiagnostics))
      assertFalse(actions.contains(PlayerRecoveryAction.ChooseAnotherRelease))
    }
  }

  @Test
  fun `a dead stream offers a visible route back to release selection`() {
    assertEquals(
      listOf(
        PlayerRecoveryAction.Retry,
        PlayerRecoveryAction.ChooseAnotherRelease,
        PlayerRecoveryAction.ShareDiagnostics,
      ),
      PlayerRecoveryPolicy.actions(PlayerRecoveryKind.DeadStream),
    )
  }
}
