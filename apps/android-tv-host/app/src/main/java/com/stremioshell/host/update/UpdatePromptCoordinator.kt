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
    val versionName: String?
  ) {
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
    if (!apkUpdateManager.hasPendingDownloadedUpdate(context, currentVersionName)) {
      return State.None
    }

    val downloadedVersion = apkUpdateManager.getDownloadedVersionName(context) ?: return State.None
    val prompt = UpdatePromptPolicy.decide(
      downloadedVersionName = downloadedVersion,
      currentVersionName = currentVersionName,
      needsUnknownSourcesPermission = apkUpdateManager.needsUnknownSourcesPermission(context),
      dismissedVersionName = dismissedVersionName
    )
    return if (prompt == UpdatePromptPolicy.Prompt.NONE) {
      State.None
    } else {
      State(prompt = prompt, versionName = downloadedVersion)
    }
  }

  /** Null when the APK vanished, failed verification, or installs are still blocked. */
  fun buildInstallIntent(context: Context): Intent? =
    apkUpdateManager.buildInstallIntentFromDownloadedApk(context)

  fun buildUnknownSourcesIntent(context: Context): Intent =
    apkUpdateManager.buildUnknownSourcesSettingsIntent(context)
}
