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
}
