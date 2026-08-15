package com.stremioshell.host.update

/**
 * Decides what (if anything) to show the user about an update that is already
 * sitting on disk. Pure, so the WebView shell and the native TV app can ask the
 * same question and answer it identically.
 */
object UpdatePromptPolicy {
  enum class Prompt {
    NONE,

    /** Offer to launch the installer for the downloaded APK. */
    INSTALL,

    /** Installer cannot be launched yet: send the user to the unknown-sources screen first. */
    ENABLE_UNKNOWN_SOURCES
  }

  /**
   * @param dismissedVersionName version the user chose "Later" for in this session; the same
   *   version must not be offered again until the app is restarted.
   */
  fun decide(
    downloadedVersionName: String?,
    currentVersionName: String,
    needsUnknownSourcesPermission: Boolean,
    dismissedVersionName: String?
  ): Prompt {
    val downloaded = downloadedVersionName?.trim().orEmpty()
    if (downloaded.isEmpty()) {
      return Prompt.NONE
    }
    if (!ApkUpdateManager.isNewerVersion(downloaded, currentVersionName)) {
      return Prompt.NONE
    }

    val dismissed = dismissedVersionName?.trim().orEmpty()
    if (dismissed.isNotEmpty() &&
      ApkUpdateManager.normalizeVersionName(dismissed) == ApkUpdateManager.normalizeVersionName(downloaded)
    ) {
      return Prompt.NONE
    }

    return if (needsUnknownSourcesPermission) Prompt.ENABLE_UNKNOWN_SOURCES else Prompt.INSTALL
  }

  /**
   * Whether an error message left on screen by the previous evaluation still describes what the
   * next one resolved to.
   *
   * The prompt's error is remembered outside the evaluation, because it has to survive the
   * evaluation that fires when the user comes back from a failed installer. That made it outlive
   * too much: a resolution to [Prompt.NONE] hides the dialog without clearing the text, so weeks
   * later a completely different release raised the dialog still carrying "Install failed" and,
   * worse, still offering "Check download" as its primary button instead of "Install".
   *
   * Attempt identity, not merely version, is the boundary: a corrected same-version asset or a
   * fresh DownloadManager attempt must never inherit the prior prompt's failure text/action.
   */
  fun retainsError(
    previousUpdate: DownloadedUpdateSnapshot?,
    nextPrompt: Prompt,
    nextUpdate: DownloadedUpdateSnapshot?,
  ): Boolean {
    if (nextPrompt == Prompt.NONE || nextUpdate == null) {
      return false
    }
    if (previousUpdate == null) {
      return false
    }
    return ApkUpdateManager.sameDownloadedUpdate(previousUpdate, nextUpdate)
  }
}
