package com.stremioshell.host.update

import java.util.Locale

class UpdateRepository(
  private val api: GitHubReleaseApi = GitHubReleaseApi()
) {
  fun checkForUpdate(
    owner: String,
    repo: String,
    currentVersionName: String
  ): UpdateInfo? {
    val latest = api.fetchLatestRelease(owner, repo)
    val latestTag = latest.tagName.trim()
    if (latestTag.isBlank()) {
      return null
    }

    if (!isNewerVersion(latestTag, currentVersionName)) {
      return null
    }

    val selectedApk = selectApkAsset(
      assets = latest.assets,
      versionName = normalizeVersionLabel(latestTag),
    ) ?: return null
    return UpdateInfo(
      latestVersionName = latestTag.removePrefix("v").removePrefix("V"),
      apkName = selectedApk.name,
      apkUrl = selectedApk.browserDownloadUrl,
      releaseNotes = latest.body.orEmpty().trim(),
      releaseUrl = latest.htmlUrl.orEmpty().trim(),
      apkSizeBytes = selectedApk.size,
      publishedAt = latest.publishedAt?.trim()
    )
  }

  private fun isNewerVersion(latestTag: String, currentVersionName: String): Boolean {
    val latestSemVer = SemVer.parseOrNull(latestTag)
    val currentSemVer = SemVer.parseOrNull(currentVersionName)
    if (latestSemVer != null && currentSemVer != null) {
      return latestSemVer > currentSemVer
    }

    val normalizedLatest = normalizeVersionLabel(latestTag)
    val normalizedCurrent = normalizeVersionLabel(currentVersionName)
    return normalizedLatest != normalizedCurrent
  }

  private fun normalizeVersionLabel(value: String): String {
    return value
      .trim()
      .removePrefix("v")
      .removePrefix("V")
      .substringBefore('-')
      .lowercase(Locale.ROOT)
  }

  companion object {
    /**
     * The release workflow publishes one canonical TV artifact. Do not fall
     * back to a debug, mobile, stale-version, or ambiguously duplicated APK if
     * a release is malformed.
     */
    internal fun selectApkAsset(
      assets: List<GitHubAssetDto>,
      versionName: String,
    ): GitHubAssetDto? {
      val expectedName = "StremioShell-tv-$versionName.apk"
      return assets.filter { it.name == expectedName }.singleOrNull()
    }
  }
}
