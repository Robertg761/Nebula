package com.stremioshell.host.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UpdateRepository(
  private val api: GitHubReleaseApi = GitHubReleaseApi()
) {
  fun checkForUpdate(
    owner: String,
    repo: String,
    currentVersionName: String
  ): UpdateInfo? {
    val latest = selectRelease(api.fetchReleases(owner, repo), currentVersionName) ?: return null
    val latestTag = latest.tagName.trim()

    val selectedApk = selectApkAsset(
      assets = latest.assets,
      // The asset name carries the numeric version only. A prerelease is tagged `v0.6.2-beta.1`
      // but still ships `StremioShell-tv-0.6.2.apk`, because the workflow names the file from
      // versionName rather than from the tag.
      versionName = SemVer.coreLabel(latestTag),
    ) ?: return null
    if (!isTrustedApkUrl(selectedApk.browserDownloadUrl, owner, repo, selectedApk.name)) {
      return null
    }
    return UpdateInfo(
      latestVersionName = latestTag.removePrefix("v").removePrefix("V"),
      apkName = selectedApk.name,
      apkUrl = selectedApk.browserDownloadUrl,
      releaseNotes = latest.body.orEmpty().trim(),
      releaseUrl = latest.htmlUrl.orEmpty().trim(),
      apkSizeBytes = selectedApk.size,
      apkSha256 = selectedApk.sha256,
      publishedAt = latest.publishedAt?.trim(),
      apkAssetId = selectedApk.id,
    )
  }

  companion object {
    /** Stable installs stay on stable releases; prerelease installs may advance to either channel. */
    internal fun selectRelease(
      releases: List<GitHubLatestReleaseDto>,
      currentVersionName: String,
    ): GitHubLatestReleaseDto? {
      val current = SemVer.parseOrNull(currentVersionName) ?: return null
      val acceptsPrereleases = current.preRelease.isNotEmpty()
      return releases.asSequence()
        .filterNot { it.draft }
        .filter { acceptsPrereleases || !it.prerelease }
        .mapNotNull { release ->
          SemVer.parseOrNull(release.tagName)?.let { version -> release to version }
        }
        .filter { (_, version) -> version > current }
        .maxWithOrNull(compareBy<Pair<GitHubLatestReleaseDto, SemVer>> { it.second })
        ?.first
    }

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

    /** Accept only the canonical GitHub release-download route for this exact repository/asset. */
    internal fun isTrustedApkUrl(
      rawUrl: String,
      owner: String,
      repo: String,
      assetName: String,
    ): Boolean {
      val url = rawUrl.toHttpUrlOrNull() ?: return false
      if (!url.isHttps || !url.host.equals("github.com", ignoreCase = true)) return false
      val segments = url.pathSegments
      return segments.size == 6 &&
        segments[0].equals(owner, ignoreCase = true) &&
        segments[1].equals(repo, ignoreCase = true) &&
        segments[2] == "releases" &&
        segments[3] == "download" &&
        segments[4].isNotBlank() &&
        segments[5] == assetName
    }
  }
}
