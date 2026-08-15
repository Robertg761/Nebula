package com.stremioshell.host.update

import android.content.Context
import android.content.Intent

/**
 * "An update is already on disk, offer to install it" flow, kept out of any
 * activity so the WebView shell and the native Compose app run identical
 * checks. Holds no state: the caller owns the session-scoped "Later" choice.
 */
class UpdatePromptCoordinator(
  private val apkUpdateManager: ApkUpdateManager = ApkUpdateManager()
) {
  data class State(
    val prompt: UpdatePromptPolicy.Prompt,
    val update: DownloadedUpdateSnapshot?,
  ) {
    val versionName: String? get() = update?.versionName

    companion object {
      val None = State(UpdatePromptPolicy.Prompt.NONE, null)
    }
  }

  /**
   * Blocking: hits SharedPreferences, DownloadManager and the APK file, so call
   * it off the main thread. Also prunes a stale or truncated download as a side
   * effect of the integrity check.
   */
  fun evaluate(
    context: Context,
    currentVersionName: String,
    dismissedVersionName: String?
  ): State {
    val update = apkUpdateManager.pendingDownloadedUpdate(context, currentVersionName)
      ?: return State.None
    val prompt = UpdatePromptPolicy.decide(
      downloadedVersionName = update.versionName,
      currentVersionName = currentVersionName,
      needsUnknownSourcesPermission = apkUpdateManager.needsUnknownSourcesPermission(context),
      dismissedVersionName = dismissedVersionName
    )
    return if (prompt == UpdatePromptPolicy.Prompt.NONE) {
      State.None
    } else {
      State(prompt = prompt, update = update)
    }
  }

  /** Distinguishes a harmless stale prompt from a genuine final-verification failure. */
  fun buildInstallIntent(
    context: Context,
    expectedUpdate: DownloadedUpdateSnapshot,
  ): InstallIntentResult =
    apkUpdateManager.buildInstallIntentFromDownloadedApk(context, expectedUpdate)

  fun buildUnknownSourcesIntent(context: Context): Intent =
    apkUpdateManager.buildUnknownSourcesSettingsIntent(context)
}
