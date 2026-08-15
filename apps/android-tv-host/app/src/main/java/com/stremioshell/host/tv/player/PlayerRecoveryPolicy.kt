package com.stremioshell.host.tv.player

/** Viewer-facing failure states that replace an unrecoverable black player surface. */
internal enum class PlayerRecoveryKind {
  InvalidDescriptor,
  NativeCoreBusy,
  InitializationFailed,
  DeadStream,
}

internal enum class PlayerRecoveryAction {
  Retry,
  Back,
  ChooseAnotherRelease,
  ShareDiagnostics,
}

/**
 * Keeps recovery actions honest about what can help.
 *
 * An unsafe descriptor must never be retried unchanged. Core startup failures can be retried
 * without weakening singleton ownership, while a dead stream can be replaced by returning its
 * current title and episode to the release picker.
 */
internal object PlayerRecoveryPolicy {
  fun actions(kind: PlayerRecoveryKind): List<PlayerRecoveryAction> = when (kind) {
    PlayerRecoveryKind.InvalidDescriptor -> listOf(
      PlayerRecoveryAction.ChooseAnotherRelease,
      PlayerRecoveryAction.Back,
      PlayerRecoveryAction.ShareDiagnostics,
    )
    PlayerRecoveryKind.NativeCoreBusy,
    PlayerRecoveryKind.InitializationFailed,
    -> listOf(
      PlayerRecoveryAction.Retry,
      PlayerRecoveryAction.Back,
      PlayerRecoveryAction.ShareDiagnostics,
    )
    PlayerRecoveryKind.DeadStream -> listOf(
      PlayerRecoveryAction.Retry,
      PlayerRecoveryAction.ChooseAnotherRelease,
      PlayerRecoveryAction.ShareDiagnostics,
    )
  }
}
