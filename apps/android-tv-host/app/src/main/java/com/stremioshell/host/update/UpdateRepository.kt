package com.stremioshell.host.update

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
      // The asset name carries the numeric version only. A prerelease is tagged `v0.6.2-beta.1`
      // but still ships `StremioShell-tv-0.6.2.apk`, because the workflow names the file from
      // versionName rather than from the tag.
      versionName = SemVer.coreLabel(latestTag),
    ) ?: return null
    return UpdateInfo(
      latestVersionName = latestTag.removePrefix("v").removePrefix("V"),
      apkName = selectedApk.name,
      apkUrl = selectedApk.browserDownloadUrl,
      releaseNotes = latest.body.orEmpty().trim(),
      releaseUrl = latest.htmlUrl.orEmpty().trim(),
      apkSizeBytes = selectedApk.size,
      apkSha256 = selectedApk.sha256,
      publishedAt = latest.publishedAt?.trim()
    )
  }

  /**
   * Full semver ordering, pre-release included: the stable `v0.6.2` is newer than `0.6.2-beta.1`
   * and has to be offered to whoever is running the beta. The string fallback below only runs for
   * a tag no version parser can read, and it compares the normalized labels rather than the bare
   * cores for the same reason.
   */
  private fun isNewerVersion(latestTag: String, currentVersionName: String): Boolean {
    val latestSemVer = SemVer.parseOrNull(latestTag)
    val currentSemVer = SemVer.parseOrNull(currentVersionName)
    if (latestSemVer != null && currentSemVer != null) {
      return latestSemVer > currentSemVer
    }

    return SemVer.normalizeLabel(latestTag) != SemVer.normalizeLabel(currentVersionName)
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
